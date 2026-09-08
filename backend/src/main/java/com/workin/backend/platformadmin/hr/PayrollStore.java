package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code payroll_paginate_batches()}, {@code payroll_paginate_payslips()},
 * {@code payroll_batch_payslip_totals()}, and the ownership lookups the write
 * guards need.
 *
 * <p>The writes themselves are not here. Three of them are bare column writes
 * that {@link com.workin.legacy.payroll.LegacyPayrollBatchStore} already
 * performs, and the fourth — {@code calculate} — goes through
 * {@link com.workin.legacy.payroll.LegacyPayrollBatchService}, because it is
 * literally the same PHP function on both sides. See
 * {@link PayrollAdminService} for why {@code finalize} and {@code reopen} do
 * <b>not</b> go through their same-named service methods.
 */
@Repository
public class PayrollStore {

	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))";

	private static final String EMP_CODE =
			"COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR))";

	/**
	 * {@code payroll_paginate_payslips()}'s ordering: a numeric employee code
	 * sorts numerically, everything else falls to the lexical tail. Kept
	 * verbatim because it decides which rows land on which page.
	 */
	private static final String PAYSLIP_ORDER = """
			ORDER BY CASE WHEN e.employee_code REGEXP '^[0-9]+$'
			              THEN CAST(e.employee_code AS UNSIGNED) ELSE NULL END ASC,
			         e.employee_code ASC, e.id ASC""";

	private final JdbcTemplate jdbcTemplate;

	public PayrollStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// ------------------------------------------------------------------
	// Ownership, for the guards
	// ------------------------------------------------------------------

	/** The company a batch belongs to, or null when no such batch exists. */
	public Long companyOfBatch(long batchId) {
		if (batchId <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM payroll_batches WHERE id = ?", Long.class, batchId);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * The company a payslip belongs to, resolved through its batch.
	 *
	 * <p>{@code payslips} carries no {@code company_id} of its own — the same
	 * shape {@code attendance} has, and the reason {@code hr_row_company_id()}
	 * needs a per-table arm rather than one generic lookup.
	 */
	public Long companyOfPayslip(long payslipId) {
		if (payslipId <= 0) {
			return null;
		}
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT b.company_id FROM payslips p"
						+ " JOIN payroll_batches b ON b.id = p.batch_id WHERE p.id = ?",
				Long.class, payslipId);
		return found.isEmpty() ? null : found.get(0);
	}

	/** The batch a payslip belongs to, for the redirect back to its detail view. */
	public long batchOfPayslip(long payslipId) {
		List<Long> found = this.jdbcTemplate.queryForList(
				"SELECT batch_id FROM payslips WHERE id = ?", Long.class, payslipId);
		return found.isEmpty() ? 0L : found.get(0);
	}

	/** {@code dbFind('companies', $cid)}: create_run writes nothing without it. */
	public boolean companyExists(long companyId) {
		Integer found = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM companies WHERE id = ?", Integer.class, companyId);
		return found != null && found > 0;
	}

	// ------------------------------------------------------------------
	// Reads
	// ------------------------------------------------------------------

	private static final RowMapper<PayrollRecord.BatchRow> BATCH = (rs, index) -> new PayrollRecord.BatchRow(
			rs.getLong("id"),
			rs.getLong("company_id"),
			rs.getString("company_name"),
			rs.getInt("month"),
			rs.getInt("year"),
			rs.getString("period_from"),
			rs.getString("period_to"),
			rs.getString("status"),
			rs.getString("created_at"),
			rs.getInt("emp_count"),
			money(rs.getBigDecimal("total_net")));

	/** {@code payroll_paginate_batches()}. */
	public com.workin.backend.platformadmin.web.DashboardPage<PayrollRecord.BatchRow> paginateBatches(
			long companyId, int month, int year, int page, int perPage) {

		StringBuilder where = new StringBuilder("1=1");
		List<Object> params = new java.util.ArrayList<>();
		if (companyId > 0) {
			where.append(" AND pr.company_id = ?");
			params.add(companyId);
		}
		if (month > 0) {
			where.append(" AND pr.month = ?");
			params.add(month);
		}
		if (year > 0) {
			where.append(" AND pr.year = ?");
			params.add(year);
		}

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM payroll_batches pr WHERE " + where,
				Integer.class, params.toArray());
		int count = total == null ? 0 : total;

		int size = Math.max(1, Math.min(perPage,
				com.workin.backend.platformadmin.web.DashboardPage.SIZE_MAX));
		List<Object> paged = new java.util.ArrayList<>(params);
		paged.add(size);
		paged.add(com.workin.backend.platformadmin.web.DashboardPage.offsetFor(page, size));

		List<PayrollRecord.BatchRow> rows = this.jdbcTemplate.query(
				"SELECT pr.*, c.company_name AS company_name,"
						+ " (SELECT COUNT(*) FROM payslips WHERE batch_id = pr.id) AS emp_count,"
						+ " (SELECT COALESCE(SUM(net_salary), 0) FROM payslips WHERE batch_id = pr.id)"
						+ " AS total_net"
						+ " FROM payroll_batches pr JOIN companies c ON c.id = pr.company_id"
						+ " WHERE " + where
						+ " ORDER BY pr.created_at DESC, pr.id DESC LIMIT ? OFFSET ?",
				BATCH, paged.toArray());

		return com.workin.backend.platformadmin.web.DashboardPage.of(rows, count, page, perPage);
	}

	/**
	 * The batch behind {@code ?run_id=}, scoped.
	 *
	 * <p>PHP scopes this read for a company session only ({@code if ($isComp)}),
	 * which is correct for that audience and leaves a filtered administrator
	 * able to open any batch. That is R-044's deliberate reach and is kept; the
	 * <em>write</em> guards are where this port diverges.
	 */
	public PayrollRecord.CurrentBatch currentBatch(long batchId, long scopedCompanyId) {
		boolean scoped = scopedCompanyId > 0;
		String sql = "SELECT pr.*, c.company_name AS company_name FROM payroll_batches pr"
				+ " JOIN companies c ON c.id = pr.company_id WHERE pr.id = ?"
				+ (scoped ? " AND pr.company_id = ?" : "");
		Object[] params = scoped
				? new Object[] { batchId, scopedCompanyId }
				: new Object[] { batchId };
		List<PayrollRecord.CurrentBatch> found = this.jdbcTemplate.query(sql,
				(rs, index) -> new PayrollRecord.CurrentBatch(
						rs.getLong("id"),
						rs.getLong("company_id"),
						rs.getString("company_name"),
						rs.getInt("month"),
						rs.getInt("year"),
						rs.getString("period_from"),
						rs.getString("period_to"),
						rs.getString("status")),
				params);
		return found.isEmpty() ? null : found.get(0);
	}

	/**
	 * {@code payroll_paginate_payslips()}, as rows rather than a typed record.
	 *
	 * <p>The page does not render these columns as stored. PHP hands them to
	 * {@code payroll_enrich_payslip_rows()} first, which recomputes
	 * {@code days_present}, {@code days_absent}, {@code days_leave},
	 * {@code penalties_total}, {@code total_entitlements},
	 * {@code total_deductions} and {@code net_salary} from live attendance and
	 * contract data and adds the four derived salary figures the table shows.
	 * So the query stops here and {@code AdminPayrollController} runs the same
	 * enrichment the API's {@code list.php} and CSV export use — one function
	 * with two callers on both sides, unlike {@code finalize}.
	 *
	 * @param periodTo the batch's {@code period_to}, which the effective-contract
	 *     subquery is dated against exactly as PHP dates it
	 */
	public com.workin.backend.platformadmin.web.DashboardPage<java.util.Map<String, Object>> paginatePayslips(
			long batchId, String periodTo, int page, int perPage) {

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM payslips pd WHERE pd.batch_id = ?", Integer.class, batchId);
		int count = total == null ? 0 : total;

		int size = Math.max(1, Math.min(perPage,
				com.workin.backend.platformadmin.web.DashboardPage.SIZE_MAX));
		// LegacyJdbcValues.rowMapper(), not queryForList: the enrichment reads
		// period_from/period_to through LegacyValues.toPhpString(), which
		// refuses a java.sql.Date. Every legacy read goes through this mapper so
		// DATE and TIMESTAMP columns arrive as the lexical strings PHP hands its
		// callers (D-096).
		List<java.util.Map<String, Object>> rows = this.jdbcTemplate.query(
				"SELECT pd.*, " + DISPLAY_NAME + " AS employee_name, " + EMP_CODE + " AS emp_code,"
						+ " b.name AS branch_name, d.name AS department_name,"
						+ " jt.name AS job_title_name,"
						+ " pb.period_from, pb.period_to, pb.company_id AS batch_company_id,"
						+ " COALESCE(("
						+ "   SELECT CASE WHEN sc.salary_mode = 'daily' AND sc.daily_wage > 0"
						+ "               THEN sc.daily_wage * 26 ELSE sc.basic_salary END"
						+ "   FROM salary_contracts sc"
						+ "   WHERE sc.employee_id = e.id AND sc.effective_from <= ?"
						+ "   ORDER BY sc.effective_from DESC, sc.id DESC LIMIT 1"
						+ " ), 0) AS contract_basic_salary"
						+ " FROM payslips pd"
						+ " JOIN payroll_batches pb ON pb.id = pd.batch_id"
						+ " JOIN employees e ON e.id = pd.employee_id"
						+ " LEFT JOIN branches b ON b.id = e.branch_id"
						+ " LEFT JOIN departments d ON d.id = e.department_id"
						+ " LEFT JOIN job_titles jt ON jt.id = e.job_title_id"
						+ " WHERE pd.batch_id = ? " + PAYSLIP_ORDER + " LIMIT ? OFFSET ?",
				com.workin.legacy.LegacyJdbcValues.rowMapper(),
				periodTo, batchId, size,
				com.workin.backend.platformadmin.web.DashboardPage.offsetFor(page, size));

		return com.workin.backend.platformadmin.web.DashboardPage.of(rows, count, page, perPage);
	}

	/** {@code payroll_batch_payslip_totals()}. */
	public PayrollRecord.BatchTotals batchTotals(long batchId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) AS emp_count,"
						+ " COALESCE(SUM(total_entitlements), 0) AS total_entitlements,"
						+ " COALESCE(SUM(total_deductions), 0) AS total_deductions,"
						+ " COALESCE(SUM(net_salary), 0) AS net_salary"
						+ " FROM payslips WHERE batch_id = ?",
				(rs, index) -> new PayrollRecord.BatchTotals(
						rs.getInt("emp_count"),
						money(rs.getBigDecimal("total_entitlements")),
						money(rs.getBigDecimal("total_deductions")),
						money(rs.getBigDecimal("net_salary"))),
				batchId);
	}

	/**
	 * One payslip, for the edit form.
	 *
	 * <p>Deliberately <b>not</b> enriched. The form edits the stored columns, so
	 * it must show the stored columns; enriching here would put a recomputed
	 * {@code net_salary} into the field whose whole purpose is to override it.
	 */
	public PayrollRecord.PayslipRow payslipForEdit(long payslipId) {
		List<PayrollRecord.PayslipRow> found = this.jdbcTemplate.query(
				"SELECT pd.*, " + DISPLAY_NAME + " AS employee_name, " + EMP_CODE + " AS emp_code"
						+ " FROM payslips pd JOIN employees e ON e.id = pd.employee_id"
						+ " WHERE pd.id = ?",
				(rs, index) -> new PayrollRecord.PayslipRow(
						rs.getLong("id"),
						rs.getLong("employee_id"),
						rs.getString("emp_code"),
						rs.getString("employee_name"),
						money(rs.getBigDecimal("days_present")),
						money(rs.getBigDecimal("days_absent")),
						money(rs.getBigDecimal("days_leave")),
						money(rs.getBigDecimal("overtime_hours")),
						money(rs.getBigDecimal("basic_salary")),
						money(rs.getBigDecimal("allowances")),
						money(rs.getBigDecimal("overtime_pay")),
						money(rs.getBigDecimal("penalties_total")),
						money(rs.getBigDecimal("advance_deduction")),
						money(rs.getBigDecimal("advances_deduction")),
						money(rs.getBigDecimal("other_deductions")),
						money(rs.getBigDecimal("net_salary"))),
				payslipId);
		return found.isEmpty() ? null : found.get(0);
	}

	/** {@code dbAll('companies', ['status' => STATUS_ACTIVE], 'company_name')}. */
	public List<PayrollRecord.CompanyOption> activeCompanies() {
		return this.jdbcTemplate.query(
				"SELECT id, company_name FROM companies WHERE status = 'active' ORDER BY company_name",
				(rs, index) -> new PayrollRecord.CompanyOption(
						rs.getLong("id"), rs.getString("company_name")));
	}

	// ------------------------------------------------------------------
	// The one write that has no equivalent in the API's own store
	// ------------------------------------------------------------------

	/**
	 * {@code edit_detail}: the twelve columns the dashboard's payslip form
	 * posts.
	 *
	 * <p>{@code payslips} has twenty-five. The thirteen this does not touch —
	 * {@code gross_salary}, {@code total_entitlements}, {@code total_deductions}
	 * and the seven itemised allowance/deduction columns among them — keep
	 * whatever {@code calculate} last wrote, so a hand-edited payslip's net no
	 * longer reconciles with its own stored totals. That is what the page does
	 * today and recomputing them here would diverge from it;
	 * {@code AdminPayrollEndToEndTest} asserts all twenty-five so the partial
	 * write cannot drift into a full one unnoticed.
	 */
	public void updatePayslipDetail(long payslipId, PayslipEdit edit) {
		this.jdbcTemplate.update(
				"UPDATE payslips SET days_present=?, days_absent=?, days_leave=?,"
						+ " overtime_hours=?, basic_salary=?, allowances=?, overtime_pay=?,"
						+ " penalties_total=?, advance_deduction=?, advances_deduction=?,"
						+ " other_deductions=?, net_salary=? WHERE id=?",
				edit.daysPresent(), edit.daysAbsent(), edit.daysLeave(), edit.overtimeHours(),
				edit.basicSalary(), edit.allowances(), edit.overtimePay(), edit.penaltiesTotal(),
				edit.advanceDeduction(), edit.advancesDeduction(), edit.otherDeductions(),
				edit.netSalary(), payslipId);
	}

	/**
	 * The twelve posted values.
	 *
	 * <p>Six of the twelve are read in PHP with {@code ?? 0} and six without.
	 * The six without — {@code basic_salary}, {@code allowances},
	 * {@code overtime_pay}, {@code penalties_total}, {@code advance_deduction}
	 * and {@code net_salary} — write {@code NULL} when the field is absent from
	 * the request rather than defaulting to zero. {@link PayrollAdminService}
	 * reproduces both halves; the distinction is the source's, not a choice.
	 */
	public record PayslipEdit(
			BigDecimal daysPresent,
			BigDecimal daysAbsent,
			BigDecimal daysLeave,
			BigDecimal overtimeHours,
			BigDecimal basicSalary,
			BigDecimal allowances,
			BigDecimal overtimePay,
			BigDecimal penaltiesTotal,
			BigDecimal advanceDeduction,
			BigDecimal advancesDeduction,
			BigDecimal otherDeductions,
			BigDecimal netSalary) {
	}

	private static BigDecimal money(BigDecimal raw) {
		return raw == null ? BigDecimal.ZERO : raw;
	}

	private static String blank(String raw) {
		return raw == null ? "" : raw;
	}

}
