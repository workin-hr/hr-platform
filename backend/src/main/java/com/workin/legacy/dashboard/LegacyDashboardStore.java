package com.workin.legacy.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The fourteen queries {@code dashboard/stats.php} issues, in its order.
 *
 * <h2>Several results are keyed by <em>name</em> though the SQL groups by id</h2>
 * <p>{@code $out[$row['department_name']] = ...} after {@code GROUP BY s.id}.
 * Two departments sharing a name therefore collapse into one response key, and
 * the <b>last</b> row wins -- silently, with no indication in the payload that
 * a figure was overwritten. Reproduced with an insertion-ordered map so the key
 * order and the collision behaviour both match; "fixing" it would change the
 * response shape for every company that has duplicate department names.
 */
@Repository
public class LegacyDashboardStore {

	private final JdbcTemplate jdbcTemplate;

	public LegacyDashboardStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	private long count(String sql, Object... binds) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, binds);
		return value == null ? 0L : value;
	}

	public long totalEmployees(long companyId) {
		return count("SELECT COUNT(*) FROM employees WHERE company_id=? AND is_active=1", companyId);
	}

	/** Note: branches are <b>not</b> filtered by {@code is_active}, unlike employees. */
	public long totalBranches(long companyId) {
		return count("SELECT COUNT(*) FROM branches WHERE company_id=?", companyId);
	}

	/** Gross and basic salary totals over active employees' contracts. */
	public record SalaryTotals(double gross, double basic) {
	}

	/**
	 * <b>Every</b> contract row for an active employee is summed, not the
	 * currently-effective one. An employee with three historical contracts
	 * contributes all three, so this total overstates payroll wherever contract
	 * history exists. Legacy's query has no {@code effective_from} filter and no
	 * per-employee pick, and the port keeps it.
	 */
	public SalaryTotals salaryTotals(long companyId) {
		return jdbcTemplate.query(
				"""
				SELECT SUM(basic_salary + transport_allowance + food_allowance + risk_allowance + incentives) AS total_gross,
				       SUM(basic_salary) AS total_basic
				FROM salary_contracts sc
				JOIN employees e ON e.id = sc.employee_id
				WHERE e.company_id=? AND e.is_active=1""",
				rs -> rs.next()
						? new SalaryTotals(rs.getDouble("total_gross"), rs.getDouble("total_basic"))
						: new SalaryTotals(0, 0),
				companyId);
	}

	public Map<String, Object> salariesByDepartment(long companyId) {
		return doubleMap("""
				SELECT s.name AS k,
				       SUM(sc.basic_salary + sc.transport_allowance + sc.food_allowance
				           + sc.risk_allowance + sc.incentives) AS v
				FROM departments s
				JOIN employees e ON e.department_id = s.id
				JOIN salary_contracts sc ON sc.employee_id = e.id
				WHERE s.company_id=? AND e.is_active=1
				GROUP BY s.id""", companyId);
	}

	/** LEFT JOIN, so a branch with no active employees is present with 0. */
	public Map<String, Object> employeesByBranch(long companyId) {
		return longMap("""
				SELECT b.name AS k, COUNT(e.id) AS v
				FROM branches b
				LEFT JOIN employees e ON e.branch_id = b.id AND e.is_active=1
				WHERE b.company_id=?
				GROUP BY b.id""", companyId);
	}

	public Map<String, Object> employeesByDepartment(long companyId) {
		return longMap("""
				SELECT s.name AS k, COUNT(e.id) AS v
				FROM departments s
				LEFT JOIN employees e ON e.department_id = s.id AND e.is_active=1
				WHERE s.company_id=?
				GROUP BY s.id""", companyId);
	}

	/**
	 * Workforce planning rows: a <b>list</b>, not a map, so duplicate department
	 * names survive here where they collide everywhere else in this response.
	 *
	 * <p>The department join is unscoped by company -- only
	 * {@code wt.company_id} is filtered. An earlier revision of this comment
	 * called that safe because {@code workforce_planning} rows are themselves
	 * company-scoped; <b>that reasoning is wrong</b> and is retracted. Scoping
	 * the row does not scope the join: {@code workforce_planning.department_id}
	 * carries no foreign key in {@code hr-legacy@d113204}, so a row owned by
	 * this company may point at another company's department, and then both the
	 * name and the {@code actual} subquery's headcount are read from that
	 * tenant.
	 *
	 * <p>It is reproduced rather than fixed because
	 * {@code apis/api/dashboard/stats.php:91-99} is character-for-character this
	 * query (D-058). The disclosure is legacy's, and it is real.
	 */
	public List<Map<String, Object>> workforcePlanning(long companyId) {
		return jdbcTemplate.query("""
				SELECT s.name AS department_name,
				       wt.planned_count AS planned,
				       (SELECT COUNT(*) FROM employees e
				         WHERE e.department_id = s.id AND e.is_active=1) AS actual
				FROM workforce_planning wt
				JOIN departments s ON s.id = wt.department_id
				WHERE wt.company_id=?""",
				(rs, rowNum) -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("department_name", rs.getString("department_name"));
					row.put("planned", rs.getLong("planned"));
					row.put("actual", rs.getLong("actual"));
					return row;
				}, companyId);
	}

	/**
	 * Attendance per day over a seven-day window.
	 *
	 * <p>The bound is {@code check_in >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)}
	 * with <b>no upper bound</b>, so a check-in dated in the future is counted
	 * too. Days with no attendance are absent from the map rather than zero --
	 * the client sees a sparse series, not seven entries.
	 */
	public Map<String, Object> dailyAttendance(long companyId) {
		return longMap("""
				SELECT DATE(a.check_in) AS k, COUNT(DISTINCT a.employee_id) AS v
				FROM attendance a
				JOIN employees e ON e.id = a.employee_id
				WHERE e.company_id=? AND a.check_in >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)
				GROUP BY DATE(a.check_in)
				ORDER BY k""", companyId);
	}

	/** Present count and active headcount per department, for today. */
	public record AttendanceShare(String departmentName, long present, long total) {
	}

	public List<AttendanceShare> attendanceByDepartment(long companyId) {
		return jdbcTemplate.query("""
				SELECT s.name AS department_name,
				       COUNT(DISTINCT a.employee_id) AS present,
				       (SELECT COUNT(*) FROM employees e2
				         WHERE e2.department_id = s.id AND e2.is_active=1) AS total
				FROM departments s
				LEFT JOIN employees e ON e.department_id = s.id
				LEFT JOIN attendance a ON a.employee_id = e.id AND DATE(a.check_in) = CURDATE()
				WHERE s.company_id=?
				GROUP BY s.id""",
				(rs, rowNum) -> new AttendanceShare(
						rs.getString("department_name"), rs.getLong("present"), rs.getLong("total")),
				companyId);
	}

	/**
	 * Resignations this year, counted as {@code is_active=0} rows whose
	 * {@code updated_at} falls in the given year -- so any edit to a deactivated
	 * employee moves them into the current year's count.
	 */
	public long resignations(long companyId, int year) {
		return count("SELECT COUNT(*) FROM employees WHERE company_id=? AND is_active=0 AND YEAR(updated_at)=?",
				companyId, year);
	}

	public long totalPenalties(long companyId) {
		return count("SELECT COUNT(*) FROM penalties p JOIN employees e ON e.id = p.employee_id"
				+ " WHERE e.company_id=?", companyId);
	}

	public Map<String, Object> penaltiesByDepartment(long companyId) {
		return longMap("""
				SELECT s.name AS k, COUNT(p.id) AS v
				FROM departments s
				JOIN employees e ON e.department_id = s.id
				JOIN penalties p ON p.employee_id = e.id
				WHERE s.company_id=?
				GROUP BY s.id""", companyId);
	}

	/** Blank or NULL gender collapses to the literal key {@code unknown}. */
	public Map<String, Object> employeesByGender(long companyId) {
		return longMap("""
				SELECT CASE WHEN TRIM(COALESCE(e.gender, '')) = '' THEN 'unknown'
				            ELSE LOWER(TRIM(e.gender)) END AS k,
				       COUNT(*) AS v
				FROM employees e
				WHERE e.company_id=? AND e.is_active=1
				GROUP BY CASE WHEN TRIM(COALESCE(e.gender, '')) = '' THEN 'unknown'
				              ELSE LOWER(TRIM(e.gender)) END""", companyId);
	}

	/**
	 * Age brackets. A NULL {@code birth_date} is {@code unknown} and everything
	 * from 50 upwards is {@code fifty_plus}.
	 *
	 * <p><b>A future {@code birth_date} lands in {@code under_20}</b>, not in
	 * {@code fifty_plus}: {@code TIMESTAMPDIFF} returns a negative number and
	 * the first arm tests {@code < 20}, which a negative satisfies. The
	 * {@code BETWEEN} arms below it never see the value.
	 *
	 * <p>That is legacy's, and the predicate is copied rather than bounded to
	 * {@code 0..19}. Bounding it would move every future-dated row from
	 * {@code under_20} to {@code fifty_plus} and change the response for data
	 * that exists in the wild -- a divergence, not a fix (D-058).
	 */
	public Map<String, Object> employeesByAgeBracket(long companyId) {
		String bracket = """
				CASE WHEN e.birth_date IS NULL THEN 'unknown'
				     WHEN TIMESTAMPDIFF(YEAR, e.birth_date, CURDATE()) < 20 THEN 'under_20'
				     WHEN TIMESTAMPDIFF(YEAR, e.birth_date, CURDATE()) BETWEEN 20 AND 29 THEN 'twenties'
				     WHEN TIMESTAMPDIFF(YEAR, e.birth_date, CURDATE()) BETWEEN 30 AND 39 THEN 'thirties'
				     WHEN TIMESTAMPDIFF(YEAR, e.birth_date, CURDATE()) BETWEEN 40 AND 49 THEN 'forties'
				     ELSE 'fifty_plus' END""";
		return longMap("SELECT " + bracket + " AS k, COUNT(*) AS v FROM employees e"
				+ " WHERE e.company_id=? AND e.is_active=1 GROUP BY " + bracket, companyId);
	}

	/**
	 * New employees per calendar month by {@code created_at}, over the window
	 * starting on the first of the month eleven months ago.
	 *
	 * <p>Not filtered by {@code is_active}, unlike almost everything else here.
	 */
	public Map<String, Long> newHiresByMonth(long companyId) {
		Map<String, Long> out = new LinkedHashMap<>();
		jdbcTemplate.query("""
				SELECT DATE_FORMAT(e.created_at, '%Y-%m') AS ym, COUNT(*) AS cnt
				FROM employees e
				WHERE e.company_id=?
				  AND e.created_at >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 11 MONTH), '%Y-%m-01')
				GROUP BY ym
				ORDER BY ym""",
				rs -> { out.put(rs.getString("ym"), rs.getLong("cnt")); }, companyId);
		return out;
	}

	private Map<String, Object> longMap(String sql, Object... binds) {
		Map<String, Object> out = new LinkedHashMap<>();
		jdbcTemplate.query(sql, rs -> { out.put(rs.getString("k"), rs.getLong("v")); }, binds);
		return out;
	}

	private Map<String, Object> doubleMap(String sql, Object... binds) {
		Map<String, Object> out = new LinkedHashMap<>();
		jdbcTemplate.query(sql, rs -> { out.put(rs.getString("k"), rs.getDouble("v")); }, binds);
		return out;
	}
}
