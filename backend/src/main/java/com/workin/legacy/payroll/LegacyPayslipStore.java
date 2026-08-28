package com.workin.legacy.payroll;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPagination;

/** {@code payslips/*.php}'s row access (Wave 12.9). */
@Repository
public class LegacyPayslipStore {

	/** The base columns {@code sql_payslip_select_with_computed_totals()} names, plus batch/employee join columns. */
	private static final String LIST_SELECT = """
			SELECT p.id, p.batch_id, p.employee_id, p.days_present, p.days_absent, p.days_leave,
			  p.overtime_hours, p.basic_salary, p.allowances, p.overtime_pay, p.penalties_total,
			  p.advance_deduction, p.other_deductions, p.food_allowance, p.risk_allowance,
			  p.transport_allowance, p.incentives, p.insurance_deduction, p.tax_deduction,
			  p.advances_deduction, p.fund_deduction, p.gross_salary,
			  (SELECT ROUND(COALESCE(CASE WHEN sc.salary_mode = 'daily' THEN sc.daily_wage ELSE sc.basic_salary END, 0), 2)
			   FROM salary_contracts sc WHERE sc.employee_id = p.employee_id
			     AND sc.effective_from <= COALESCE(NULLIF(b.period_to, ''),
			         LAST_DAY(CONCAT(b.year, '-', LPAD(b.month, 2, '0'), '-01')))
			   ORDER BY sc.effective_from DESC LIMIT 1) AS contract_basic_salary,
			  TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,''))) AS employee_name,
			  e.photo_url AS photo_url, e.employee_code AS employee_code,
			  br.name AS branch_name, dep.name AS department_name, jt.name AS job_title_name,
			  b.period_from AS period_from, b.period_to AS period_to
			FROM payslips p
			JOIN payroll_batches b ON p.batch_id = b.id
			JOIN employees e ON p.employee_id = e.id
			LEFT JOIN branches br ON e.branch_id = br.id
			LEFT JOIN departments dep ON dep.id = e.department_id
			LEFT JOIN job_titles jt ON jt.id = e.job_title_id""";

	/**
	 * {@code payslips/export.php}'s filter set
	 * ({@code data_export_helper.php:462-525}).
	 *
	 * <p>Not {@link Filter}: the export has no pagination, no
	 * {@code new_employees_this_month}, and adds a {@code from}/{@code to}
	 * <b>period-overlap</b> pair the listing does not have. Its ordering is the
	 * listing's, not the overall report's.
	 */
	public record ExportFilter(
			long companyId, Long selfEmployeeId, Long employeeId, Long branchId, Long departmentId,
			Long batchId, Integer month, Integer year, String from, String to, String search) {
	}

	/**
	 * {@code data_export_payslips_csv()}'s query: {@link #LIST_SELECT} with the
	 * export's own filters, its own ordering, and no {@code LIMIT}.
	 *
	 * <p>The {@code from}/{@code to} pair is an <b>overlap</b> test, not
	 * containment -- {@code period_to >= from AND period_from <= to} -- so a
	 * batch straddling either bound is included. Supplying only one of the two
	 * is rejected by the caller before this runs.
	 */
	public List<Map<String, Object>> exportRows(ExportFilter filter) {
		List<Object> binds = new ArrayList<>();
		StringBuilder where = new StringBuilder("b.company_id = ?");
		binds.add(filter.companyId());

		if (filter.selfEmployeeId() != null) {
			where.append(" AND p.employee_id = ?");
			binds.add(filter.selfEmployeeId());
		} else {
			if (filter.employeeId() != null) {
				where.append(" AND p.employee_id = ?");
				binds.add(filter.employeeId());
			}
			if (filter.branchId() != null) {
				where.append(" AND e.branch_id = ?");
				binds.add(filter.branchId());
			}
			if (filter.departmentId() != null) {
				where.append(" AND e.department_id = ?");
				binds.add(filter.departmentId());
			}
		}
		if (filter.batchId() != null) {
			where.append(" AND p.batch_id = ?");
			binds.add(filter.batchId());
		}
		if (filter.month() != null) {
			where.append(" AND b.month = ?");
			binds.add(filter.month());
		}
		if (filter.year() != null) {
			where.append(" AND b.year = ?");
			binds.add(filter.year());
		}
		if (filter.from() != null && filter.to() != null) {
			where.append(" AND b.period_to >= ? AND b.period_from <= ?");
			binds.add(filter.from());
			binds.add(filter.to());
		}
		if (filter.search() != null) {
			where.append(" AND (TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))"
					+ " LIKE ? OR e.employee_code LIKE ?)");
			String like = "%" + filter.search() + "%";
			binds.add(like);
			binds.add(like);
		}

		String sql = LIST_SELECT + """

				WHERE %s
				ORDER BY
				  CASE WHEN e.employee_code REGEXP '^[0-9]+$'
				    THEN CAST(e.employee_code AS UNSIGNED) ELSE NULL END ASC,
				  e.employee_code ASC,
				  p.id ASC
				""".formatted(where);

		// LegacyJdbcValues.rowMapper(), not queryForList: every other read here
		// goes through it so DATE and TIMESTAMP columns arrive as the lexical
		// strings PHP hands its callers, rather than as java.sql.Date.
		return jdbc.query(sql, LegacyJdbcValues.rowMapper(), binds.toArray());
	}

	private static final String ONE_SELECT = """
			SELECT p.id, p.batch_id, p.employee_id, p.days_present, p.days_absent, p.days_leave,
			  p.overtime_hours, p.basic_salary, p.allowances, p.overtime_pay, p.penalties_total,
			  p.advance_deduction, p.other_deductions, p.food_allowance, p.risk_allowance,
			  p.transport_allowance, p.incentives, p.insurance_deduction, p.tax_deduction,
			  p.advances_deduction, p.fund_deduction, p.gross_salary,
			  (SELECT ROUND(COALESCE(CASE WHEN sc.salary_mode = 'daily' THEN sc.daily_wage ELSE sc.basic_salary END, 0), 2)
			   FROM salary_contracts sc WHERE sc.employee_id = p.employee_id
			     AND sc.effective_from <= COALESCE(NULLIF(b.period_to, ''),
			         LAST_DAY(CONCAT(b.year, '-', LPAD(b.month, 2, '0'), '-01')))
			   ORDER BY sc.effective_from DESC LIMIT 1) AS contract_basic_salary,
			  TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,''))) AS employee_name,
			  e.employee_code AS employee_code, b.month AS month, b.year AS year,
			  b.period_from AS period_from, b.period_to AS period_to
			FROM payslips p
			JOIN payroll_batches b ON p.batch_id = b.id
			JOIN employees e ON p.employee_id = e.id""";

	private final JdbcTemplate jdbc;

	public LegacyPayslipStore(DataSource legacyDataSource) {
		this.jdbc = new JdbcTemplate(legacyDataSource);
	}

	/** {@code one.php}'s fetch, scoped to the company through its batch. */
	public Map<String, Object> one(long payslipId, long companyId) {
		return single(jdbc.query(
				ONE_SELECT + " WHERE p.id=? AND b.company_id=?", LegacyJdbcValues.rowMapper(), payslipId, companyId));
	}

	/** {@code delete.php}/{@code update.php}'s fetch: the raw row plus the batch's status, for the finalized guard. */
	public Map<String, Object> withBatchStatus(long payslipId, long companyId) {
		return single(jdbc.query("""
				SELECT p.*, b.status AS batch_status, b.period_from AS period_from, b.period_to AS period_to
				FROM payslips p JOIN payroll_batches b ON p.batch_id = b.id
				WHERE p.id=? AND b.company_id=?""", LegacyJdbcValues.rowMapper(), payslipId, companyId));
	}

	public Map<String, Object> byId(long payslipId) {
		return single(jdbc.query("SELECT * FROM payslips WHERE id=?", LegacyJdbcValues.rowMapper(), payslipId));
	}

	public boolean batchBelongsToCompany(long batchId, long companyId) {
		Long count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM payroll_batches WHERE id=? AND company_id=?", Long.class, batchId, companyId);
		return count != null && count > 0;
	}

	public Map<String, Object> batch(long batchId, long companyId) {
		return single(jdbc.query(
				"SELECT * FROM payroll_batches WHERE id=? AND company_id=?",
				LegacyJdbcValues.rowMapper(), batchId, companyId));
	}

	public Map<String, Object> employee(long employeeId, long companyId) {
		return single(jdbc.query(
				"SELECT * FROM employees WHERE id=? AND company_id=?",
				LegacyJdbcValues.rowMapper(), employeeId, companyId));
	}

	public boolean existsForBatchEmployee(long batchId, long employeeId) {
		Long count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM payslips WHERE batch_id=? AND employee_id=?", Long.class, batchId, employeeId);
		return count != null && count > 0;
	}

	public long insert(
			long batchId, long employeeId, long daysPresent, long daysAbsent, long daysLeave,
			java.math.BigDecimal overtimeHours, java.math.BigDecimal basicSalary, java.math.BigDecimal allowances,
			java.math.BigDecimal overtimePay, java.math.BigDecimal penaltiesTotal,
			java.math.BigDecimal advanceDeduction, java.math.BigDecimal otherDeductions,
			java.math.BigDecimal netSalary) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement("""
					INSERT INTO payslips (
					  batch_id, employee_id, days_present, days_absent, days_leave, overtime_hours,
					  basic_salary, allowances, overtime_pay, penalties_total, advance_deduction,
					  other_deductions, net_salary
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, batchId);
			ps.setLong(2, employeeId);
			ps.setLong(3, daysPresent);
			ps.setLong(4, daysAbsent);
			ps.setLong(5, daysLeave);
			ps.setBigDecimal(6, overtimeHours);
			ps.setBigDecimal(7, basicSalary);
			ps.setBigDecimal(8, allowances);
			ps.setBigDecimal(9, overtimePay);
			ps.setBigDecimal(10, penaltiesTotal);
			ps.setBigDecimal(11, advanceDeduction);
			ps.setBigDecimal(12, otherDeductions);
			ps.setBigDecimal(13, netSalary);
			return ps;
		}, key);
		Number id = key.getKey();
		return id == null ? 0L : id.longValue();
	}

	public void delete(long payslipId) {
		jdbc.update("DELETE FROM payslips WHERE id=?", payslipId);
	}

	public void update(long payslipId, Map<String, Object> columns) {
		if (columns.isEmpty()) {
			return;
		}
		List<String> assignments = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		for (Map.Entry<String, Object> column : columns.entrySet()) {
			assignments.add(column.getKey() + "=?");
			params.add(column.getValue());
		}
		params.add(payslipId);
		jdbc.update("UPDATE payslips SET " + String.join(", ", assignments) + " WHERE id=?", params.toArray());
	}

	public long countForList(Filter filter) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM payslips p "
				+ "JOIN payroll_batches b ON p.batch_id = b.id JOIN employees e ON p.employee_id = e.id WHERE "
				+ filter.whereSql(), Long.class, filter.params());
	}

	public List<Map<String, Object>> list(Filter filter, LegacyPagination.Params page) {
		List<Object> params = new ArrayList<>(List.of(filter.params()));
		params.add(page.limit());
		params.add(page.offset());
		return jdbc.query(LIST_SELECT + " WHERE " + filter.whereSql() + """

				ORDER BY
				  CASE WHEN e.employee_code REGEXP '^[0-9]+$' THEN CAST(e.employee_code AS UNSIGNED) ELSE NULL END ASC,
				  e.employee_code ASC, e.id ASC
				LIMIT ? OFFSET ?""", LegacyJdbcValues.rowMapper(), params.toArray());
	}

	/** {@code list.php}'s filter set, applied in PHP's own order. */
	public record Filter(
			long companyId, Long batchId, Long employeeId, Integer month, Integer year,
			Long branchId, Long departmentId, boolean newEmployeesThisMonth, String search) {

		String whereSql() {
			StringBuilder sql = new StringBuilder("b.company_id=?");
			if (batchId != null && batchId > 0) {
				sql.append(" AND p.batch_id=?");
			}
			if (employeeId != null && employeeId > 0) {
				sql.append(" AND p.employee_id=?");
			}
			if (month != null && month > 0) {
				sql.append(" AND b.month=?");
			}
			if (year != null && year > 0) {
				sql.append(" AND b.year=?");
			}
			if (branchId != null && branchId > 0) {
				sql.append(" AND e.branch_id=?");
			}
			if (departmentId != null && departmentId > 0) {
				sql.append(" AND e.department_id=?");
			}
			if (newEmployeesThisMonth) {
				sql.append(" AND e.hire_date IS NOT NULL AND e.hire_date BETWEEN "
						+ "COALESCE(b.period_from, DATE(CONCAT(b.year, '-', LPAD(b.month, 2, '0'), '-01'))) AND "
						+ "COALESCE(b.period_to, LAST_DAY(DATE(CONCAT(b.year, '-', LPAD(b.month, 2, '0'), '-01'))))");
			}
			if (search != null) {
				sql.append(" AND (TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) LIKE ? "
						+ "OR e.employee_code LIKE ?)");
			}
			return sql.toString();
		}

		Object[] params() {
			List<Object> params = new ArrayList<>();
			params.add(companyId);
			if (batchId != null && batchId > 0) {
				params.add(batchId);
			}
			if (employeeId != null && employeeId > 0) {
				params.add(employeeId);
			}
			if (month != null && month > 0) {
				params.add(month);
			}
			if (year != null && year > 0) {
				params.add(year);
			}
			if (branchId != null && branchId > 0) {
				params.add(branchId);
			}
			if (departmentId != null && departmentId > 0) {
				params.add(departmentId);
			}
			if (search != null) {
				String like = "%" + search + "%";
				params.add(like);
				params.add(like);
			}
			return params.toArray();
		}
	}

	private static Map<String, Object> single(List<Map<String, Object>> rows) {
		return rows.isEmpty() ? null : rows.getFirst();
	}
}
