package com.workin.backend.platformadmin.home;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.workin.legacy.dashboard.LegacyTurnover;

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
	/**
	 * Today's attendance as a percentage of each department's active headcount.
	 *
	 * <p>{@code NULLIF(..., 0)} makes an empty department NULL rather than a
	 * division by zero, and legacy then drops those with {@code HAVING v > 0}
	 * on the administrator's branch only. Applied on both branches here: a
	 * department at 0% and a department with nobody in it are indistinguishable
	 * on the chart, and one of them is not a fact about attendance.
	 */
	public HomeChart attendanceByDepartment(long companyId) {
		return chart("SELECT d.name AS label,"
				+ " ROUND(100 * COUNT(DISTINCT a.employee_id) / NULLIF("
				+ "  (SELECT COUNT(*) FROM employees e2 WHERE e2.department_id=d.id AND e2.is_active=1),0),"
				+ " 1) AS value"
				+ " FROM departments d"
				+ " LEFT JOIN employees e ON e.department_id=d.id"
				+ " LEFT JOIN attendance a ON a.employee_id=e.id AND DATE(a.check_in)=CURDATE()"
				+ " WHERE " + scope("d", companyId)
				+ " GROUP BY d.id HAVING value > 0 ORDER BY value DESC LIMIT 12", companyId);
	}

	public HomeChart penaltiesByDepartment(long companyId) {
		return chart("SELECT d.name AS label, COUNT(pen.id) AS value"
				+ " FROM departments d"
				+ " JOIN employees e ON e.department_id=d.id"
				+ " JOIN penalties pen ON pen.employee_id=e.id"
				// On the employee, as legacy scopes it: penalties has no
				// company of its own and the department is reached through
				// the employee anyway.
				+ " WHERE " + scope("e", companyId)
				+ " GROUP BY d.id ORDER BY value DESC LIMIT 12", companyId);
	}

	/**
	 * Planned headcount against actual, per department.
	 *
	 * <p>Two series over one label set, which is the only chart on this page
	 * shaped that way -- so it is returned as a pair of {@link HomeChart}s
	 * sharing their labels rather than by widening the record for one caller.
	 *
	 * <p>Legacy guards this with a {@code SHOW COLUMNS} check on
	 * {@code workforce_planning.planned_count} and renders nothing when the
	 * column is absent. Not reproduced: the column is in the frozen schema this
	 * port is built against, and {@code Phase1SchemaCheck} already refuses to
	 * start against a database that disagrees with it.
	 */
	public List<HomeChart> workforcePlanning(long companyId) {
		List<Map<String, Object>> rows = this.jdbcTemplate.queryForList(
				"SELECT d.name AS label, wt.planned_count AS planned,"
						+ " (SELECT COUNT(*) FROM employees e"
						+ "   WHERE e.department_id=d.id AND e.is_active=1) AS actual"
						+ " FROM workforce_planning wt"
						+ " JOIN departments d ON d.id=wt.department_id"
						+ " WHERE " + scope("wt", companyId)
						+ " LIMIT 12",
				scopeArgs(companyId));
		List<String> labels = new ArrayList<>(rows.size());
		List<Double> planned = new ArrayList<>(rows.size());
		List<Double> actual = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			labels.add(row.get("label") == null ? "" : String.valueOf(row.get("label")));
			planned.add(row.get("planned") instanceof Number number ? number.doubleValue() : 0d);
			actual.add(row.get("actual") instanceof Number number ? number.doubleValue() : 0d);
		}
		return List.of(new HomeChart(labels, planned), new HomeChart(labels, actual));
	}

	/**
	 * {@code home_get_turnover_rates()}: three percentages.
	 *
	 * <p>The arithmetic is {@link LegacyTurnover}'s, not a second copy of it --
	 * the rate formula, the two-place rounding and the {@code strtotime}
	 * three-month step all come from there. What is local is the SQL, because
	 * every query in that class requires a company and this page's
	 * administrator view has none; legacy splits it the same way and for the
	 * same reason.
	 *
	 * @param monthly  departures this calendar month over the average headcount
	 * @param annual   the same over the calendar year
	 * @param newHires the 90-day rate for the cohort hired in the last three
	 *     months -- a different denominator, and the one that says whether new
	 *     people are staying
	 */
	public record Turnover(double monthly, double annual, double newHires) {

		/**
		 * {@code number_format($v, 2)}: always two decimals, so the row reads as
		 * three rates rather than as "0" beside "2.01".
		 *
		 * <p>Not {@code PhpMath.numberFormat}, which is the whole-EGP money
		 * formatter measured against a corpus and deliberately not generalised.
		 * The values here are already rounded to two places by {@code round2},
		 * so this only prints them.
		 */
		public String monthlyDisplay() {
			return format(this.monthly);
		}

		public String annualDisplay() {
			return format(this.annual);
		}

		public String newHiresDisplay() {
			return format(this.newHires);
		}

		private static String format(double rate) {
			return String.format(java.util.Locale.ROOT, "%,.2f", rate);
		}
	}

	/** {@code COALESCE(e.hire_date, DATE(e.created_at))}: the date a tenure starts. */
	private static final String HIRE = "COALESCE(e.hire_date, DATE(e.created_at))";

	public Turnover turnover(long companyId, LocalDate today) {
		return new Turnover(
				rateForPeriod(companyId, today.withDayOfMonth(1), today),
				rateForPeriod(companyId, today.withDayOfYear(1), today),
				// strtotime('-3 months'), not minusMonths: PHP keeps the day of
				// the month and lets it roll into the next one when the target
				// month is shorter -- 31 May goes to 3 March, where Java clamps
				// to 28 February and quietly widens the cohort by three days.
				newHireRate(companyId, LegacyTurnover.minusThreeMonthsPhpStyle(today), today));
	}

	private double rateForPeriod(long companyId, LocalDate from, LocalDate to) {
		return LegacyTurnover.rateFromCounts(
				(int) countAtStart(companyId, from),
				(int) countNewHires(companyId, from, to),
				(int) countDepartures(companyId, from, to, false, null));
	}

	/**
	 * The share of the last three months' hires who left within 90 days.
	 *
	 * <p>Its denominator is the cohort, not the workforce, which is why it is
	 * not {@link #rateForPeriod}.
	 */
	private double newHireRate(long companyId, LocalDate from, LocalDate to) {
		long cohort = countNewHires(companyId, from, to);
		if (cohort <= 0) {
			return 0d;
		}
		long stillActive = scalar("SELECT COUNT(*) FROM employees e WHERE " + scope("e", companyId)
				+ " AND " + HIRE + " >= ? AND " + HIRE + " <= ? AND e.is_active = 1",
				companyId, from, to);
		long departures = countDepartures(companyId, from, to, true, 90);
		double average = (cohort + stillActive) / 2.0;
		return average <= 0 ? 0d : LegacyTurnover.round2(departures / average * 100);
	}

	/**
	 * Everyone hired before the period who was still on the books at its start
	 * -- active now, or deactivated at some point during or after it.
	 */
	private long countAtStart(long companyId, LocalDate from) {
		return scalar("SELECT COUNT(*) FROM employees e WHERE " + scope("e", companyId)
				+ " AND " + HIRE + " < ?"
				+ " AND (e.is_active = 1 OR (e.is_active = 0 AND DATE(e.updated_at) >= ?))",
				companyId, from, from);
	}

	private long countNewHires(long companyId, LocalDate from, LocalDate to) {
		return scalar("SELECT COUNT(*) FROM employees e WHERE " + scope("e", companyId)
				+ " AND " + HIRE + " >= ? AND " + HIRE + " <= ?", companyId, from, to);
	}

	/**
	 * A departure is a row deactivated inside the window. {@code updated_at} is
	 * the only date the schema has for it, so a row edited after it was
	 * deactivated moves -- legacy's approximation, and reproduced rather than
	 * improved, because the number on the page has to be the same one.
	 */
	private long countDepartures(long companyId, LocalDate from, LocalDate to,
			boolean hiredInPeriod, Integer withinDaysOfHire) {
		StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM employees e WHERE ")
				.append(scope("e", companyId))
				.append(" AND e.is_active = 0")
				.append(" AND DATE(e.updated_at) >= ? AND DATE(e.updated_at) <= ?");
		List<Object> args = new ArrayList<>();
		if (companyId > 0) {
			args.add(companyId);
		}
		args.add(from);
		args.add(to);
		if (hiredInPeriod) {
			sql.append(" AND ").append(HIRE).append(" >= ? AND ").append(HIRE).append(" <= ?");
			args.add(from);
			args.add(to);
		}
		if (withinDaysOfHire != null) {
			// Interpolated, and safe: the caller's only value is a literal 90.
			sql.append(" AND DATEDIFF(DATE(e.updated_at), ").append(HIRE).append(") <= ")
					.append(withinDaysOfHire.intValue());
		}
		Long value = this.jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
		return value == null ? 0L : value;
	}

	private long scalar(String sql, long companyId, LocalDate... dates) {
		List<Object> args = new ArrayList<>();
		if (companyId > 0) {
			args.add(companyId);
		}
		java.util.Collections.addAll(args, (Object[]) dates);
		Long value = this.jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
		return value == null ? 0L : value;
	}

	/**
	 * {@code home_get_banners()}: the active banners the desktop surface shows.
	 *
	 * <p>Not company-scoped. Banners are platform content -- the same rows the
	 * dial-code and FAQ pages administer -- and every audience sees the same
	 * ones, which is why this takes no company at all rather than a zero.
	 *
	 * <p>Legacy guards the {@code app_platform} predicate with a
	 * {@code SHOW COLUMNS} check; the column is in the frozen schema, and
	 * {@code Phase1SchemaCheck} refuses to start without it.
	 */
	public List<com.workin.backend.platformadmin.content.Banner> banners() {
		return this.jdbcTemplate.query(
				"SELECT id, image_url, title_ar, title_en, description_ar, description_en,"
						+ " button_label_ar, button_label_en, button_action_type,"
						+ " button_action_value, sort_order, is_active, app_platform, created_at"
						+ " FROM banners WHERE is_active = 1"
						+ " AND app_platform IN ('desktop','both')"
						+ " ORDER BY sort_order ASC, id ASC",
				(rs, index) -> new com.workin.backend.platformadmin.content.Banner(
						rs.getLong("id"),
						rs.getString("image_url"),
						rs.getBoolean("is_active"),
						rs.getInt("sort_order"),
						com.workin.backend.platformadmin.content.Faq.Platform.of(rs.getString("app_platform")),
						rs.getString("title_ar"),
						rs.getString("title_en"),
						rs.getString("description_ar"),
						rs.getString("description_en"),
						rs.getString("button_label_ar"),
						rs.getString("button_label_en"),
						com.workin.backend.platformadmin.content.Banner.Action.of(
								rs.getString("button_action_type")),
						rs.getString("button_action_value"),
						rs.getString("created_at")));
	}

	public HomeChart dailyAttendance(long companyId) {
		return chart("SELECT DATE(a.check_in) AS label, COUNT(DISTINCT a.employee_id) AS value"
				+ " FROM attendance a JOIN employees e ON e.id=a.employee_id"
				+ " WHERE " + scope("e", companyId)
				+ " AND a.check_in >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)"
				+ " AND a.check_in < DATE_ADD(CURDATE(), INTERVAL 1 DAY)"
				+ " GROUP BY label ORDER BY label", companyId);
	}

}
