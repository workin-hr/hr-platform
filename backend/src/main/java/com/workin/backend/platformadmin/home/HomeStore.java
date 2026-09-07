package com.workin.backend.platformadmin.home;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code pages/home/home_service.php}'s queries.
 *
 * <p>Every method takes the company to act on, where {@code 0} means every
 * company -- {@code home_company_where()}'s two branches, and the reason the
 * predicate is built here rather than pasted into a dozen strings. The service
 * above is what decides which value that is, and it is the layer the tenant
 * guard checks.
 *
 * <p>Read-only. Nothing on the home page writes.
 */
@Repository
@Profile("phase1-mysql")
public class HomeStore {

	private final JdbcTemplate jdbcTemplate;

	public HomeStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * {@code home_company_where()} for the administrator's two cases.
	 *
	 * @param alias the table alias carrying {@code company_id}
	 * @return a predicate that is always safe to concatenate: it is either the
	 *     literal {@code 1=1} or a bound placeholder, never interpolated input
	 */
	private static String scope(String alias, long companyId) {
		return companyId > 0 ? alias + ".company_id=?" : "1=1";
	}

	private Object[] scopeArgs(long companyId) {
		return companyId > 0 ? new Object[] { companyId } : new Object[0];
	}

	private long count(String sql, long companyId) {
		Long value = this.jdbcTemplate.queryForObject(sql, Long.class, scopeArgs(companyId));
		return value == null ? 0L : value;
	}

	private BigDecimal sum(String sql, long companyId) {
		BigDecimal value = this.jdbcTemplate.queryForObject(sql, BigDecimal.class, scopeArgs(companyId));
		return value == null ? BigDecimal.ZERO : value;
	}

	public HomeSummary summary(long companyId) {
		String employees = scope("e", companyId);
		return new HomeSummary(
				companyId > 0 ? 1 : count("SELECT COUNT(*) FROM companies", 0),
				companyId > 0
						? count("SELECT COUNT(*) FROM companies WHERE status='active' AND id=?", companyId)
						: count("SELECT COUNT(*) FROM companies WHERE status='active'", 0),
				companyId > 0
						? count("SELECT COUNT(*) FROM companies WHERE status='pending' AND id=?", companyId)
						: count("SELECT COUNT(*) FROM companies WHERE status='pending'", 0),
				count("SELECT COUNT(*) FROM employees e WHERE " + employees + " AND e.is_active=1", companyId),
				count("SELECT COUNT(*) FROM branches e WHERE " + employees, companyId),
				count("SELECT COUNT(DISTINCT a.employee_id) FROM attendance a"
						+ " JOIN employees e ON e.id=a.employee_id"
						+ " WHERE " + employees + " AND DATE(a.check_in)=CURDATE()", companyId),
				count("SELECT COUNT(*) FROM requests r JOIN employees e ON e.id=r.employee_id"
						+ " WHERE " + employees + " AND r.status='pending'", companyId),
				// The administrator's queue is the one addressed to the
				// platform; a company's own HR sees `source='employee'`.
				companyId > 0
						? count("SELECT COUNT(*) FROM complaints WHERE status='pending'"
								+ " AND source='employee' AND company_id=?", companyId)
						: count("SELECT COUNT(*) FROM complaints WHERE status='pending'"
								+ " AND source='company_support'", 0),
				count("SELECT COUNT(*) FROM advances a JOIN employees e ON e.id=a.employee_id"
						+ " WHERE " + employees + " AND a.status='pending'", companyId),
				count("SELECT COUNT(*) FROM penalties pen JOIN employees e ON e.id=pen.employee_id"
						+ " WHERE " + employees, companyId),
				count("SELECT COUNT(*) FROM penalties pen JOIN employees e ON e.id=pen.employee_id"
						+ " WHERE " + employees + " AND pen.applied_to_payroll=0", companyId),
				sum(latestContracts("SUM(sc.basic_salary + sc.transport_allowance + sc.food_allowance"
						+ " + sc.risk_allowance + sc.incentives)", employees), companyId),
				sum(latestContracts("SUM(sc.basic_salary)", employees), companyId),
				count("SELECT COUNT(*) FROM payroll_batches e WHERE " + employees
						+ " AND e.status='draft'", companyId),
				sum("SELECT COALESCE(SUM(ps.net_salary),0) FROM payslips ps"
						+ " JOIN payroll_batches e ON e.id=ps.batch_id"
						+ " WHERE " + employees
						+ " AND e.month=MONTH(CURDATE()) AND e.year=YEAR(CURDATE())", companyId),
				count("SELECT COUNT(*) FROM employees e WHERE " + employees
						+ " AND e.is_active=0 AND YEAR(e.updated_at)=YEAR(CURDATE())", companyId));
	}

	/**
	 * The latest salary contract per employee, which is a self-join on
	 * {@code MAX(effective_from)} rather than an {@code ORDER BY ... LIMIT 1} --
	 * legacy's own shape, and the only one that aggregates over every employee
	 * in one pass.
	 */
	private static String latestContracts(String aggregate, String employees) {
		return "SELECT COALESCE(" + aggregate + ", 0) FROM salary_contracts sc"
				+ " INNER JOIN (SELECT employee_id, MAX(effective_from) AS mx"
				+ "   FROM salary_contracts GROUP BY employee_id) latest"
				+ "   ON latest.employee_id = sc.employee_id AND latest.mx = sc.effective_from"
				+ " JOIN employees e ON e.id = sc.employee_id"
				+ " WHERE " + employees + " AND e.is_active=1";
	}

	private HomeChart chart(String sql, long companyId) {
		List<Map<String, Object>> rows = this.jdbcTemplate.queryForList(sql, scopeArgs(companyId));
		List<String> labels = new ArrayList<>(rows.size());
		List<Double> values = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			Object label = row.get("label");
			Object value = row.get("value");
			labels.add(label == null ? "" : String.valueOf(label));
			values.add(value instanceof Number number ? number.doubleValue() : 0d);
		}
		return new HomeChart(labels, values);
	}

	public HomeChart employeesByGender(long companyId) {
		return chart("SELECT CASE WHEN TRIM(COALESCE(e.gender,''))='' THEN 'unknown'"
				+ " ELSE LOWER(TRIM(e.gender)) END AS label, COUNT(*) AS value"
				+ " FROM employees e WHERE " + scope("e", companyId)
				+ " AND e.is_active=1 GROUP BY label", companyId);
	}

	public HomeChart employeesByAge(long companyId) {
		return chart("SELECT CASE"
				+ " WHEN e.birth_date IS NULL THEN 'unknown'"
				+ " WHEN TIMESTAMPDIFF(YEAR, e.birth_date, CURDATE()) < 20 THEN 'under_20'"
				+ " WHEN TIMESTAMPDIFF(YEAR, e.birth_date, CURDATE()) BETWEEN 20 AND 29 THEN 'twenties'"
				+ " WHEN TIMESTAMPDIFF(YEAR, e.birth_date, CURDATE()) BETWEEN 30 AND 39 THEN 'thirties'"
				+ " WHEN TIMESTAMPDIFF(YEAR, e.birth_date, CURDATE()) BETWEEN 40 AND 49 THEN 'forties'"
				+ " ELSE 'fifty_plus' END AS label, COUNT(*) AS value"
				+ " FROM employees e WHERE " + scope("e", companyId)
				+ " AND e.is_active=1 GROUP BY label", companyId);
	}

	/** Twelve months back, by the month a row was created. */
	public HomeChart newEmployeesByMonth(long companyId) {
		return chart("SELECT DATE_FORMAT(e.created_at,'%Y-%m') AS label, COUNT(*) AS value"
				+ " FROM employees e WHERE " + scope("e", companyId)
				+ " AND e.created_at >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 11 MONTH), '%Y-%m-01')"
				+ " GROUP BY label ORDER BY label", companyId);
	}

	public HomeChart employeesByDepartment(long companyId) {
		return chart("SELECT d.name AS label, COUNT(e.id) AS value FROM departments d"
				+ " LEFT JOIN employees e ON e.department_id=d.id AND e.is_active=1"
				+ " WHERE " + scope("d", companyId)
				+ " GROUP BY d.id ORDER BY value DESC LIMIT 12", companyId);
	}

	public HomeChart employeesByBranch(long companyId) {
		return chart("SELECT b.name AS label, COUNT(e.id) AS value FROM branches b"
				+ " LEFT JOIN employees e ON e.branch_id=b.id AND e.is_active=1"
				+ " WHERE " + scope("b", companyId)
				+ " GROUP BY b.id ORDER BY value DESC LIMIT 12", companyId);
	}

	public HomeChart salaryByDepartment(long companyId) {
		return chart("SELECT d.name AS label, COALESCE(SUM(sc.basic_salary + sc.transport_allowance"
				+ " + sc.food_allowance + sc.risk_allowance + sc.incentives),0) AS value"
				+ " FROM departments d"
				+ " JOIN employees e ON e.department_id=d.id AND e.is_active=1"
				+ " JOIN salary_contracts sc ON sc.employee_id=e.id"
				+ " INNER JOIN (SELECT employee_id, MAX(effective_from) mx FROM salary_contracts"
				+ "   GROUP BY employee_id) lat"
				+ "   ON lat.employee_id=sc.employee_id AND lat.mx=sc.effective_from"
				+ " WHERE " + scope("d", companyId)
				+ " GROUP BY d.id ORDER BY value DESC LIMIT 12", companyId);
	}

	/**
	 * The last seven days, by punch date.
	 *
	 * <p>Bounded at both ends, where legacy bounds only the lower one. A punch
	 * cannot be in the future, so on production data the two queries return the
	 * same seven rows -- but the development seed carries 3,381 future-dated
	 * rows, and unbounded the chart drew eighteen months under a title that
	 * says "daily". A chart whose axis disagrees with its label is worse than
	 * no chart, and no real attendance is excluded by saying so.
	 */
	public HomeChart dailyAttendance(long companyId) {
		return chart("SELECT DATE(a.check_in) AS label, COUNT(DISTINCT a.employee_id) AS value"
				+ " FROM attendance a JOIN employees e ON e.id=a.employee_id"
				+ " WHERE " + scope("e", companyId)
				+ " AND a.check_in >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)"
				+ " AND a.check_in < DATE_ADD(CURDATE(), INTERVAL 1 DAY)"
				+ " GROUP BY label ORDER BY label", companyId);
	}

}
