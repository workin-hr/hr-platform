package com.workin.legacy.payroll;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPagination;

/** {@code payroll_batches/*.php}'s row access (Wave 12.9). */
@Repository
public class LegacyPayrollBatchStore {

	/**
	 * {@code sql_payroll_batch_select_with_stats()}/{@code get_payroll_batch_with_stats()}
	 * ({@code payroll_calculation.php:11-33}): every batch column plus two
	 * payslip aggregates over a {@code LEFT JOIN}, so a batch with no
	 * payslips yet still returns a row -- {@code employees_count = 0},
	 * {@code total_net_salary = 0} via {@code COALESCE}, never {@code NULL}.
	 */
	private static final String WITH_STATS_SELECT = """
			SELECT b.*,
			  COUNT(p.employee_id) AS employees_count,
			  COALESCE(SUM(p.net_salary), 0) AS total_net_salary
			FROM payroll_batches b
			LEFT JOIN payslips p ON p.batch_id = b.id""";

	private final JdbcTemplate jdbc;

	public LegacyPayrollBatchStore(DataSource legacyDataSource) {
		this.jdbc = new JdbcTemplate(legacyDataSource);
	}

	public Map<String, Object> withStats(long batchId, long companyId) {
		return single(jdbc.query(
				WITH_STATS_SELECT + " WHERE b.id=? AND b.company_id=? GROUP BY b.id",
				this::row, batchId, companyId));
	}

	/** {@code byId} without the company scope -- side-effect helpers already hold the batch row. */
	public Map<String, Object> byId(long batchId) {
		return single(jdbc.query("SELECT * FROM payroll_batches WHERE id=?", this::row, batchId));
	}

	public Map<String, Object> scoped(long batchId, long companyId) {
		return single(jdbc.query(
				"SELECT * FROM payroll_batches WHERE id=? AND company_id=?", this::row, batchId, companyId));
	}

	/**
	 * {@code scoped}, but taking an InnoDB row lock for the caller's transaction -- only
	 * meaningful called through a scoped, manual-commit {@code Store} instance (see {@code
	 * LegacyPayrollBatchService#inTransaction}), never through the pooled autocommit instance.
	 * {@code calculate.php} (PR #120 review) previously read and acted on this row with no
	 * lock at all, racing a concurrent {@code finalize.php}/{@code reopen.php} call for the
	 * same batch: {@code finalizeBatchIfNotAlready}/{@code updateStatusIfCurrently}'s own
	 * {@code UPDATE} already takes this same row's lock when it runs, so having {@code
	 * calculate.php} hold it for its own read-then-recompute-then-write is enough to
	 * serialize the two against each other without changing finalize/reopen at all.
	 */
	public Map<String, Object> scopedForUpdate(long batchId, long companyId) {
		return single(jdbc.query(
				"SELECT * FROM payroll_batches WHERE id=? AND company_id=? FOR UPDATE",
				this::row, batchId, companyId));
	}

	/** {@code create.php}'s pre-insert uniqueness check. */
	public boolean existsForPeriod(long companyId, int month, int year) {
		Long count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM payroll_batches WHERE company_id=? AND month=? AND year=?",
				Long.class, companyId, month, year);
		return count != null && count > 0;
	}

	public long insert(long companyId, int month, int year, String periodFrom, String periodTo, String status) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"INSERT INTO payroll_batches (company_id, month, year, period_from, period_to, status) "
							+ "VALUES (?, ?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, companyId);
			ps.setInt(2, month);
			ps.setInt(3, year);
			ps.setString(4, periodFrom);
			ps.setString(5, periodTo);
			ps.setString(6, status);
			return ps;
		}, key);
		Number id = key.getKey();
		return id == null ? 0L : id.longValue();
	}

	/** {@code update.php}: month/year/period_from/period_to together, never a partial set. */
	public void updatePeriod(long batchId, int month, int year, String periodFrom, String periodTo) {
		jdbc.update("UPDATE payroll_batches SET month=?, year=?, period_from=?, period_to=? WHERE id=?",
				month, year, periodFrom, periodTo, batchId);
	}

	public void updateStatus(long batchId, String status) {
		jdbc.update("UPDATE payroll_batches SET status=? WHERE id=?", status, batchId);
	}

	/**
	 * Atomic compare-and-set counterpart to {@link #updateStatus}: only
	 * transitions when the row's status still equals {@code expectedCurrentStatus}
	 * at write time. Closes the race between {@code reopen.php}'s pre-transaction
	 * status read (on the pooled connection) and this write (on a separately
	 * opened single connection moments later): two concurrent {@code reopen}
	 * calls for the same batch cannot both return 0 rows changed to the loser
	 * and both apply the reversal side effects, because the second UPDATE's
	 * {@code WHERE} clause is evaluated against a row InnoDB has already locked
	 * for the first, and by the time it can proceed the first has already
	 * committed the new status.
	 *
	 * @return the number of rows changed -- 0 means someone else already
	 *         changed the status; the caller must abort rather than apply
	 *         the reversal side effects
	 */
	public int updateStatusIfCurrently(long batchId, String expectedCurrentStatus, String newStatus) {
		return jdbc.update(
				"UPDATE payroll_batches SET status=? WHERE id=? AND status=?", newStatus, batchId, expectedCurrentStatus);
	}

	/** {@code finalize.php} also rewrites the fiscal period at the same time as the status. */
	public void finalizeBatch(long batchId, String status, String periodFrom, String periodTo) {
		jdbc.update("UPDATE payroll_batches SET status=?, period_from=?, period_to=? WHERE id=?",
				status, periodFrom, periodTo, batchId);
	}

	/**
	 * Atomic compare-and-set counterpart to {@link #finalizeBatch}, same
	 * reasoning as {@link #updateStatusIfCurrently} -- see that method's note.
	 * Guards on not-already-{@code finalizedStatus} rather than on a specific
	 * "from" status, matching {@code finalize()}'s own pre-check, which
	 * accepts any non-finalized status.
	 *
	 * @return the number of rows changed -- 0 means someone else already
	 *         finalized this batch; the caller must abort rather than apply
	 *         the finalize side effects
	 */
	public int finalizeBatchIfNotAlready(long batchId, String finalizedStatus, String periodFrom, String periodTo) {
		return jdbc.update(
				"UPDATE payroll_batches SET status=?, period_from=?, period_to=? WHERE id=? AND status<>?",
				finalizedStatus, periodFrom, periodTo, batchId, finalizedStatus);
	}

	/** {@code delete.php}: payslips first, batch second -- no FK cascade in this schema. */
	public void deleteWithPayslips(long batchId) {
		jdbc.update("DELETE FROM payslips WHERE batch_id=?", batchId);
		jdbc.update("DELETE FROM payroll_batches WHERE id=?", batchId);
	}

	// ------------------------------------------------------------------
	// calculate.php / finalize.php / reopen.php
	// ------------------------------------------------------------------

	/** {@code payroll_calculate_batch()}'s employee roster: active employees only. */
	public List<Long> activeEmployeeIds(long companyId) {
		return jdbc.queryForList("SELECT id FROM employees WHERE company_id=? AND is_active=1", Long.class, companyId);
	}

	/** {@code payroll_calculate_batch()}'s contract lookup: the latest contract effective on or before {@code periodTo}. */
	public Map<String, Object> effectiveContract(long employeeId, String periodTo) {
		return single(jdbc.query(
				"SELECT * FROM salary_contracts WHERE employee_id=? AND effective_from<=? "
						+ "ORDER BY effective_from DESC LIMIT 1",
				this::row, employeeId, periodTo));
	}

	/** {@code payroll_compute_employee_payslip()}'s unapplied-penalty-days sum. */
	public java.math.BigDecimal unappliedPenaltyDays(long employeeId, String periodFrom, String periodTo) {
		java.math.BigDecimal sum = jdbc.queryForObject(
				"SELECT COALESCE(SUM(penalty_days), 0) FROM penalties "
						+ "WHERE employee_id=? AND penalty_date BETWEEN ? AND ? AND applied_to_payroll=0",
				java.math.BigDecimal.class, employeeId, periodFrom, periodTo);
		return sum == null ? java.math.BigDecimal.ZERO : sum;
	}

	/** Approved advances for one employee -- read by both the compute step and finalize/reopen side effects. */
	public List<Map<String, Object>> approvedAdvances(long employeeId) {
		return jdbc.query("SELECT * FROM advances WHERE employee_id=? AND status='approved'", this::row, employeeId);
	}

	public void deletePayslipsForBatch(long batchId) {
		jdbc.update("DELETE FROM payslips WHERE batch_id=?", batchId);
	}

	/** {@code payroll_calculate_batch()}'s INSERT -- the delete-then-insert-fresh pattern makes the upsert branch it also carries unreachable, so a plain insert is faithful. */
	public void insertPayslip(long batchId, long employeeId, LegacyPayrollCalculationService.PayslipComputation p) {
		jdbc.update("""
				INSERT INTO payslips (
				  batch_id, employee_id, days_present, days_absent, days_leave, overtime_hours,
				  basic_salary, allowances, overtime_pay, penalties_total, advance_deduction,
				  other_deductions, net_salary, food_allowance, risk_allowance, insurance_deduction,
				  tax_deduction, advances_deduction, fund_deduction, transport_allowance, incentives,
				  gross_salary, total_entitlements, total_deductions
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				batchId, employeeId, p.daysPresent(), p.daysAbsent(), p.daysLeave(), p.overtimeHours(),
				p.basicSalary(), p.allowances(), p.overtimePay(), p.penaltiesTotal(), p.advanceDeduction(),
				p.otherDeductions(), p.netSalary(), p.foodAllowance(), p.riskAllowance(), p.insuranceDeduction(),
				p.taxDeduction(), p.advancesDeduction(), p.fundDeduction(), p.transportAllowance(),
				p.incentives(), p.grossSalary(), p.totalEntitlements(), p.totalDeductions());
	}

	public void updateAdvanceRemaining(long advanceId, java.math.BigDecimal newRemaining) {
		jdbc.update("UPDATE advances SET remaining=? WHERE id=?", newRemaining, advanceId);
	}

	public void addAdvanceRemaining(long advanceId, java.math.BigDecimal delta) {
		jdbc.update("UPDATE advances SET remaining = remaining + ? WHERE id=?", delta, advanceId);
	}

	public java.math.BigDecimal advanceRemaining(long advanceId) {
		return jdbc.queryForObject("SELECT remaining FROM advances WHERE id=?", java.math.BigDecimal.class, advanceId);
	}

	public List<Map<String, Object>> payslipsForBatch(long batchId) {
		return jdbc.query("SELECT * FROM payslips WHERE batch_id=?", this::row, batchId);
	}

	/**
	 * {@code stats.php}'s aggregate query ({@code payroll_calculation.php}'s
	 * {@code sql_payslip_total_entitlements()}/{@code sql_payslip_total_deductions()}
	 * expressions, inlined verbatim).
	 */
	public Map<String, Object> statsForBatch(long batchId) {
		String entitlements = """
				COALESCE(NULLIF(ps.total_entitlements, 0), ROUND(
				  COALESCE(ps.basic_salary,0) + COALESCE(ps.transport_allowance,0) + COALESCE(ps.food_allowance,0)
				  + COALESCE(ps.risk_allowance,0) + COALESCE(ps.incentives,0) + COALESCE(ps.allowances,0)
				  + COALESCE(ps.overtime_pay,0), 2))""";
		String deductions = """
				COALESCE(NULLIF(ps.total_deductions, 0), ROUND(
				  COALESCE(ps.insurance_deduction,0) + COALESCE(ps.tax_deduction,0) + COALESCE(ps.advances_deduction,0)
				  + COALESCE(ps.fund_deduction,0) + COALESCE(ps.penalties_total,0) + COALESCE(ps.advance_deduction,0)
				  + COALESCE(ps.other_deductions,0), 2))""";
		String net = "GREATEST(0, ROUND(" + entitlements + " - (" + deductions + "), 2))";
		String sql = "SELECT COUNT(*) AS total_employees, "
				+ "SUM(ps.basic_salary) AS total_basic_salary, SUM(ps.allowances) AS total_allowances, "
				+ "SUM(ps.overtime_pay) AS total_overtime_pay, SUM(" + entitlements + ") AS total_entitlements, "
				+ "SUM(" + deductions + ") AS total_deductions, SUM(ps.penalties_total) AS total_penalties, "
				+ "SUM(ps.advance_deduction) AS total_advance_deductions, "
				+ "SUM(ps.other_deductions) AS total_other_deductions, SUM(" + net + ") AS total_net_salary, "
				+ "AVG(" + net + ") AS avg_net_salary, MAX(" + net + ") AS max_net_salary, "
				+ "MIN(" + net + ") AS min_net_salary, SUM(ps.days_present) AS total_days_present, "
				+ "SUM(ps.days_absent) AS total_days_absent, SUM(ps.days_leave) AS total_days_leave, "
				+ "SUM(ps.overtime_hours) AS total_overtime_hours "
				+ "FROM payslips ps WHERE ps.batch_id=?";
		return single(jdbc.query(sql, this::row, batchId));
	}

	/** {@code payroll_finalize_batch_side_effects()}'s penalty-marking UPDATE. */
	public void markPenaltiesAppliedForBatch(long batchId, String periodFrom, String periodTo) {
		jdbc.update("""
				UPDATE penalties p
				INNER JOIN payslips ps ON ps.employee_id = p.employee_id
				SET p.applied_to_payroll = 1
				WHERE ps.batch_id = ? AND p.penalty_date BETWEEN ? AND ? AND p.applied_to_payroll = 0
				""", batchId, periodFrom, periodTo);
	}

	/** {@code payroll_reopen_batch_side_effects()}'s inverse. */
	public void unmarkPenaltiesAppliedForBatch(long batchId, String periodFrom, String periodTo) {
		jdbc.update("""
				UPDATE penalties p
				INNER JOIN payslips ps ON ps.employee_id = p.employee_id
				SET p.applied_to_payroll = 0
				WHERE ps.batch_id = ? AND p.penalty_date BETWEEN ? AND ? AND p.applied_to_payroll = 1
				""", batchId, periodFrom, periodTo);
	}

	public long countForList(long companyId, String status, Integer year, String search) {
		Filter filter = new Filter(companyId, status, year, search);
		return jdbc.queryForObject("SELECT COUNT(*) FROM payroll_batches b WHERE " + filter.whereSql(),
				Long.class, filter.params());
	}

	/** {@code list.php}: filtered, joined with stats, newest year/month first. */
	public List<Map<String, Object>> list(
			long companyId, String status, Integer year, String search, LegacyPagination.Params page) {
		Filter filter = new Filter(companyId, status, year, search);
		List<Object> params = new ArrayList<>(List.of(filter.params()));
		params.add(page.limit());
		params.add(page.offset());
		return jdbc.query(
				WITH_STATS_SELECT + " WHERE " + filter.whereSql()
						+ " GROUP BY b.id ORDER BY b.year DESC, b.month DESC LIMIT ? OFFSET ?",
				this::row, params.toArray());
	}

	/** {@code list.php}'s three optional filters, applied in PHP's own order. */
	private record Filter(long companyId, String status, Integer year, String search) {

		String whereSql() {
			StringBuilder sql = new StringBuilder("b.company_id=?");
			if (status != null) {
				sql.append(" AND b.status=?");
			}
			if (year != null && year > 0) {
				sql.append(" AND b.year=?");
			}
			if (search != null) {
				sql.append(" AND (CAST(b.month AS CHAR) LIKE ? OR CAST(b.year AS CHAR) LIKE ?)");
			}
			return sql.toString();
		}

		Object[] params() {
			List<Object> params = new ArrayList<>();
			params.add(companyId);
			if (status != null) {
				params.add(status);
			}
			if (year != null && year > 0) {
				params.add(year);
			}
			if (search != null) {
				String like = "%" + search + "%";
				params.add(like);
				params.add(like);
			}
			return params.toArray();
		}
	}

	private Map<String, Object> row(ResultSet rs, int rowNum) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> row = new LinkedHashMap<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			row.put(meta.getColumnLabel(i), LegacyJdbcValues.read(rs, i, meta.getColumnType(i)));
		}
		return row;
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.getFirst();
	}
}
