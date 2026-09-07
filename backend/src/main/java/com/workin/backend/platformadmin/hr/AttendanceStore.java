package com.workin.backend.platformadmin.hr;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.backend.platformadmin.web.DashboardPage;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.calendar.LegacyAttendanceWorkedMinutes;
import com.workin.legacy.attendance.calendar.LegacyWeeklyRestCredit;
import com.workin.legacy.payroll.LegacyPayrollAttendanceFigures;

/**
 * {@code payroll_paginate_attendance()},
 * {@code payroll_paginate_attendance_aggregate()}, and the writes
 * {@code attendance.php} makes.
 *
 * <p><b>The two tables on this page do not agree, and must not be made to.</b>
 * The detail list runs every row through
 * {@code attendance_row_worked_minutes()}; the aggregate sums raw
 * {@code TIMESTAMPDIFF(MINUTE, check_in, check_out)} in SQL and never consults
 * that engine. For an employee whose punches the engine adjusts -- a capped
 * shift, an exception day -- the two columns report different totals for the
 * same range. That is what the page shows today.
 *
 * <p><b>And this page is not the payroll page.</b> The obvious shortcut is to
 * route the aggregate through
 * {@link LegacyPayrollAttendanceFigures#attendanceDisplay}, which returns
 * exactly these field names. It computes different numbers:
 *
 * <ul>
 *   <li>absence there is capped by an {@code as-of} notion for a period still
 *   in progress and adds void weekly-rest days; here it is a plain
 *   {@code expected - present - leave - holidays}, floored at zero;</li>
 *   <li>the holiday credit there is
 *   {@code official_holidays_working_credit_for_employee()} from
 *   {@code apis/helpers/}, which weighs working days; here it is
 *   {@code official_holidays_credit_for_employee()} from
 *   {@code dashboard/includes/} -- a plain count of holidays in range the
 *   employee did not attend. Same idea, different answer;</li>
 *   <li>that credit is suppressed there below a minimum coverage; here it
 *   never is.</li>
 * </ul>
 *
 * <p>Only three helpers are genuinely shared and are reused rather than
 * re-implemented: {@code approvedLeaveDays}, {@code employeeWorkHoursPerDay}
 * and {@code expectedWorkDays}. Everything else in the aggregate is this
 * page's own arithmetic. The three parity fixtures at the end of
 * {@code AdminAttendanceEndToEndTest} pin the divergence so a later
 * tidy-up cannot quietly merge the two.
 */
@Repository
@Profile("phase1-mysql")
public class AttendanceStore {

	private static final String DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')))";

	private static final String EMP_CODE =
			"COALESCE(NULLIF(TRIM(e.employee_code), ''), CAST(e.id AS CHAR))";

	/**
	 * {@code $shiftExpectedSql}: the assigned shift's length in minutes, rolled
	 * forward a day when it ends before it starts (a night shift).
	 */
	private static final String SHIFT_EXPECTED_MINUTES = """
			CASE WHEN TIMESTAMPDIFF(MINUTE,
			          STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%Y-%m-%d %H:%i:%s'),
			          STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%Y-%m-%d %H:%i:%s')) < 0
			     THEN TIMESTAMPDIFF(MINUTE,
			          STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%Y-%m-%d %H:%i:%s'),
			          STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%Y-%m-%d %H:%i:%s')) + 1440
			     ELSE TIMESTAMPDIFF(MINUTE,
			          STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%Y-%m-%d %H:%i:%s'),
			          STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%Y-%m-%d %H:%i:%s'))
			END""";

	private final JdbcTemplate jdbcTemplate;

	private final LegacyAttendanceWorkedMinutes workedMinutes;

	private final LegacyPayrollAttendanceFigures attendanceFigures;

	private final LegacyWeeklyRestCredit weeklyRestCredit;

	private final LegacyAttendanceCalendar calendar;

	/**
	 * Legacy's clock, for the fallback below. {@code time()} in PHP is the
	 * configured timezone's, and the row this dates is compared against
	 * {@code CURDATE()} on a connection set to the same offset.
	 */
	private final com.workin.legacy.LegacyClock clock;

	public AttendanceStore(
			JdbcTemplate jdbcTemplate,
			LegacyAttendanceWorkedMinutes workedMinutes,
			LegacyPayrollAttendanceFigures attendanceFigures,
			LegacyWeeklyRestCredit weeklyRestCredit,
			LegacyAttendanceCalendar calendar,
			com.workin.legacy.LegacyClock clock) {
		this.clock = clock;
		this.jdbcTemplate = jdbcTemplate;
		this.workedMinutes = workedMinutes;
		this.attendanceFigures = attendanceFigures;
		this.weeklyRestCredit = weeklyRestCredit;
		this.calendar = calendar;
	}

	private static RowMapper<AttendanceRecord.Row> detailMapper() {
		return (rs, rowNum) -> {
			Object exceptionTypeId = rs.getObject("exception_type_id");
			return new AttendanceRecord.Row(
					rs.getLong("id"),
					rs.getLong("employee_id"),
					rs.getLong("company_id"),
					rs.getString("check_in"),
					rs.getString("check_out"),
					exceptionTypeId == null ? null : rs.getLong("exception_type_id"),
					rs.getString("employee_name"),
					rs.getString("emp_code"),
					rs.getString("exception_name"),
					rs.getObject("min_total") == null ? null : rs.getInt("min_total"));
		};
	}

	/** The date-range and employee predicates both attendance lists share. */
	private String detailWhere(DashboardListFilters filters, String from, String to, List<Object> params) {
		StringBuilder where = new StringBuilder("DATE(a.check_in) BETWEEN ? AND ?");
		params.add(from);
		params.add(to);
		if (filters.companyId() > 0) {
			where.append(" AND e.company_id = ?");
			params.add(filters.companyId());
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR ")
					.append(EMP_CODE).append(" LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}
		if (filters.filterBranch() > 0) {
			where.append(" AND e.branch_id = ?");
			params.add(filters.filterBranch());
		}
		if (filters.filterDepartment() > 0) {
			where.append(" AND e.department_id = ?");
			params.add(filters.filterDepartment());
		}
		return where.toString();
	}

	/**
	 * {@code payroll_paginate_attendance()}.
	 *
	 * <p>Each row's minutes come from the worked-minutes engine, not from the
	 * {@code min_total} the query computes -- that value is only the engine's
	 * raw input. A non-positive answer becomes null, which the page renders as
	 * an em dash.
	 */
	public DashboardPage<AttendanceRecord.Row> paginateDetail(
			DashboardListFilters filters, String from, String to, String weeklyRestLabel) {
		List<Object> params = new ArrayList<>();
		String where = detailWhere(filters, from, to, params);

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM attendance a JOIN employees e ON e.id = a.employee_id"
						+ " WHERE " + where,
				Integer.class, params.toArray());

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(filters.page(), filters.perPage()));

		List<AttendanceRecord.Row> rows = this.jdbcTemplate.query(
				"SELECT a.id, a.employee_id, e.company_id, a.check_in, a.check_out,"
						+ " a.exception_type_id, " + DISPLAY_NAME + " AS employee_name, "
						+ EMP_CODE + " AS emp_code, et.name AS exception_name,"
						+ " TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS min_total"
						+ " FROM attendance a JOIN employees e ON e.id = a.employee_id"
						+ " LEFT JOIN exception_types et ON et.id = a.exception_type_id"
						+ " WHERE " + where
						+ " ORDER BY a.check_in DESC, a.id DESC LIMIT ? OFFSET ?",
				detailMapper(), pageParams.toArray());

		List<AttendanceRecord.Row> resolved = new ArrayList<>(rows.size());
		for (AttendanceRecord.Row row : rows) {
			resolved.add(withWorkedMinutes(row, filters.companyId(), weeklyRestLabel));
		}
		return DashboardPage.of(resolved, total == null ? 0 : total, filters.page(), filters.perPage());
	}

	private AttendanceRecord.Row withWorkedMinutes(
			AttendanceRecord.Row row, long filterCompanyId, String weeklyRestLabel) {
		String checkIn = row.checkIn() == null ? "" : row.checkIn();
		String checkOut = row.checkOut() != null && !row.checkOut().trim().isEmpty()
				? row.checkOut() : null;
		// date('Y-m-d', strtotime($checkIn) ?: time()) -- an unreadable
		// check-in falls back to today rather than failing the row.
		String dateKey = checkIn.length() >= 10
				? checkIn.substring(0, 10) : this.clock.todayAsString();
		int raw = !checkIn.isEmpty() && checkOut != null
				? Math.max(0, row.workedMinutes() == null ? 0 : row.workedMinutes()) : 0;
		long companyId = row.companyId() > 0 ? row.companyId() : filterCompanyId;
		int worked = this.workedMinutes.rowWorkedMinutes(
				companyId, row.employeeId(), dateKey,
				checkIn.isEmpty() ? null : checkIn, checkOut, raw,
				row.exceptionTypeId(), weeklyRestLabel);
		return new AttendanceRecord.Row(
				row.id(), row.employeeId(), row.companyId(), row.checkIn(), row.checkOut(),
				row.exceptionTypeId(), row.employeeName(), row.empCode(), row.exceptionName(),
				worked > 0 ? worked : null);
	}

	/** {@code payroll_period_days_inclusive()}. */
	public static int periodDaysInclusive(String from, String to) {
		try {
			LocalDate start = LocalDate.parse(from);
			LocalDate end = LocalDate.parse(to);
			if (end.isBefore(start)) {
				return 1;
			}
			return (int) (end.toEpochDay() - start.toEpochDay()) + 1;
		} catch (RuntimeException unparseable) {
			return 1;
		}
	}

	/**
	 * {@code official_holidays_credit_for_employee()} --
	 * {@code dashboard/includes/official_holidays_helper.php}, <b>not</b> the
	 * {@code apis/helpers/} function of the almost-identical name that
	 * {@link LegacyPayrollAttendanceFigures} ports. This one is a plain count
	 * of the company's holidays in range that the employee did not attend; the
	 * API's weighs working days and is suppressed below a coverage floor. They
	 * return different numbers for the same input, and the page that calls
	 * each expects its own.
	 */
	public int officialHolidayCreditForEmployee(long companyId, long employeeId, String from, String to) {
		if (companyId <= 0 || employeeId <= 0 || from == null || from.isEmpty()
				|| to == null || to.isEmpty()) {
			return 0;
		}
		Integer credit = this.jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM company_official_holidays h
				WHERE h.company_id = ? AND h.holiday_date BETWEEN ? AND ?
				  AND NOT EXISTS (
				      SELECT 1 FROM attendance a
				      WHERE a.employee_id = ? AND DATE(a.check_in) = h.holiday_date)""",
				Integer.class, companyId, from, to, employeeId);
		return credit == null ? 0 : credit;
	}

	/** The company an attendance row belongs to, through its employee (R-046). */
	public Long companyOf(long id) {
		if (id <= 0) {
			return null;
		}
		List<Long> owners = this.jdbcTemplate.queryForList(
				"SELECT e.company_id FROM attendance a JOIN employees e ON e.id = a.employee_id"
						+ " WHERE a.id = ? LIMIT 1",
				Long.class, id);
		return owners.isEmpty() ? null : owners.get(0);
	}

	/** The company an employee belongs to, for the add form's ownership check. */
	public Long companyOfEmployee(long employeeId) {
		if (employeeId <= 0) {
			return null;
		}
		List<Long> owners = this.jdbcTemplate.queryForList(
				"SELECT company_id FROM employees WHERE id = ? LIMIT 1", Long.class, employeeId);
		return owners.isEmpty() ? null : owners.get(0);
	}

	/** {@code payroll_exception_type_belongs_to_company()}. */
	public boolean exceptionTypeBelongsToCompany(long exceptionTypeId, long companyId) {
		if (exceptionTypeId <= 0 || companyId <= 0) {
			return false;
		}
		Integer found = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM exception_types WHERE id = ? AND company_id = ?",
				Integer.class, exceptionTypeId, companyId);
		return found != null && found > 0;
	}

	public List<AttendanceRecord.EmployeeOption> employeeOptions(long companyId) {
		StringBuilder sql = new StringBuilder(
				"SELECT e.id, " + DISPLAY_NAME + " AS employee_name, " + EMP_CODE + " AS emp_code"
				+ " FROM employees e WHERE e.is_active = 1");
		List<Object> params = new ArrayList<>();
		if (companyId > 0) {
			sql.append(" AND e.company_id = ?");
			params.add(companyId);
		}
		sql.append(" ORDER BY employee_name ASC, e.id ASC");
		return this.jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
			String name = rs.getString("employee_name");
			String code = rs.getString("emp_code");
			String label = name == null || name.isBlank() ? code : name + " (" + code + ")";
			return new AttendanceRecord.EmployeeOption(rs.getLong("id"), label);
		}, params.toArray());
	}

	public List<AttendanceRecord.ExceptionTypeOption> exceptionTypeOptions(long companyId) {
		if (companyId <= 0) {
			return List.of();
		}
		return this.jdbcTemplate.query(
				"SELECT id, name FROM exception_types WHERE company_id = ? ORDER BY name ASC, id ASC",
				(rs, rowNum) -> new AttendanceRecord.ExceptionTypeOption(
						rs.getLong("id"), rs.getString("name")),
				companyId);
	}

	// ------------------------------------------------------------------
	// Writes
	// ------------------------------------------------------------------

	public void insert(long employeeId, String checkIn, String checkOut, Long exceptionTypeId) {
		this.jdbcTemplate.update(
				"INSERT INTO attendance (employee_id, check_in, check_out, method, exception_type_id)"
						+ " VALUES (?, ?, ?, 'app', ?)",
				employeeId, checkIn, checkOut, exceptionTypeId);
	}

	public int update(long id, String checkIn, String checkOut, Long exceptionTypeId) {
		return this.jdbcTemplate.update(
				"UPDATE attendance SET check_in = ?, check_out = ?, exception_type_id = ? WHERE id = ?",
				checkIn, checkOut, exceptionTypeId, id);
	}

	public int delete(long id) {
		return this.jdbcTemplate.update("DELETE FROM attendance WHERE id = ?", id);
	}

	/** The count legacy reports back, taken before the delete runs. */
	public int countInRange(long companyId, String from, String to) {
		Integer count = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM attendance a JOIN employees e ON e.id = a.employee_id"
						+ " WHERE e.company_id = ? AND DATE(a.check_in) >= ? AND DATE(a.check_in) <= ?",
				Integer.class, companyId, from, to);
		return count == null ? 0 : count;
	}

	public void deleteRange(long companyId, String from, String to) {
		this.jdbcTemplate.update(
				"DELETE a FROM attendance a JOIN employees e ON e.id = a.employee_id"
						+ " WHERE e.company_id = ? AND DATE(a.check_in) >= ? AND DATE(a.check_in) <= ?",
				companyId, from, to);
	}

	// ------------------------------------------------------------------
	// Aggregate
	// ------------------------------------------------------------------

	private record AggregateSql(String where, List<Object> params) {
	}

	/** {@code payroll_attendance_employee_where()}. */
	private AggregateSql aggregateEmployeeScope(DashboardListFilters filters, long scopedCompanyId) {
		StringBuilder where = new StringBuilder("e.is_active = 1");
		List<Object> params = new ArrayList<>();
		if (filters.companyId() > 0) {
			where.append(" AND e.company_id = ?");
			params.add(filters.companyId());
		} else if (scopedCompanyId > 0) {
			where.append(" AND e.company_id = ?");
			params.add(scopedCompanyId);
		}
		if (!filters.search().isEmpty()) {
			where.append(" AND (").append(DISPLAY_NAME).append(" LIKE ? OR ")
					.append(EMP_CODE).append(" LIKE ?)");
			params.add("%" + filters.search() + "%");
			params.add("%" + filters.search() + "%");
		}
		if (filters.filterBranch() > 0) {
			where.append(" AND e.branch_id = ?");
			params.add(filters.filterBranch());
		}
		if (filters.filterDepartment() > 0) {
			where.append(" AND e.department_id = ?");
			params.add(filters.filterDepartment());
		}
		return new AggregateSql(where.toString(), params);
	}

	private record RawAggregate(
			long employeeId, String empCode, String employeeName, String jobTitleName,
			int presentDays, int exceptionDays, long totalMinutes, long expectedMinutes) {
	}

	/**
	 * {@code payroll_paginate_attendance_aggregate()}.
	 *
	 * <p>{@code totalMinutes} is the raw SQL sum and stays that way -- see the
	 * class javadoc. The per-row figures below are this page's own formula and
	 * are deliberately not {@code attendanceDisplay}'s.
	 *
	 * @param aggPage the aggregate table paginates independently of the detail
	 *     table, on its own {@code agg_page} parameter
	 */
	public DashboardPage<AttendanceRecord.AggregateRow> aggregate(
			DashboardListFilters filters, String from, String to, int aggPage, long scopedCompanyId,
			String asOf) {
		int daysInPeriod = periodDaysInclusive(from, to);
		AggregateSql scope = aggregateEmployeeScope(filters, scopedCompanyId);

		Integer total = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM employees e WHERE " + scope.where(),
				Integer.class, scope.params().toArray());

		List<Object> pageParams = new ArrayList<>();
		pageParams.add(from);
		pageParams.add(to);
		pageParams.addAll(scope.params());
		pageParams.add(filters.perPage());
		pageParams.add(DashboardPage.offsetFor(aggPage, filters.perPage()));

		List<RawAggregate> raw = this.jdbcTemplate.query(
				"SELECT e.id AS employee_id, " + EMP_CODE + " AS emp_code, "
						+ DISPLAY_NAME + " AS employee_name, jt.name AS job_title_name,"
						+ " COALESCE(att.present_days, 0) AS present_days,"
						+ " COALESCE(att.exception_days, 0) AS exception_days,"
						+ " COALESCE(att.total_minutes, 0) AS total_minutes,"
						+ " COALESCE(att.expected_minutes, 0) AS expected_minutes"
						+ " FROM employees e"
						+ " LEFT JOIN job_titles jt ON jt.id = e.job_title_id"
						+ " LEFT JOIN ("
						+ "   SELECT a.employee_id,"
						+ "     COUNT(DISTINCT DATE(a.check_in)) AS present_days,"
						+ "     COUNT(DISTINCT CASE WHEN a.exception_type_id IS NOT NULL"
						+ "       THEN DATE(a.check_in) END) AS exception_days,"
						+ "     SUM(CASE WHEN a.check_out IS NOT NULL"
						+ "       THEN TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) ELSE 0 END) AS total_minutes,"
						+ "     SUM(CASE WHEN a.check_out IS NOT NULL THEN COALESCE(("
						+ "         SELECT " + SHIFT_EXPECTED_MINUTES
						+ "         FROM employee_shift_assignments esa"
						+ "         INNER JOIN shifts sh ON sh.id = esa.shift_id"
						+ "         WHERE esa.employee_id = a.employee_id"
						+ "         ORDER BY esa.effective_from DESC, esa.id DESC LIMIT 1"
						+ "       ), COALESCE(NULLIF(e2.expected_daily_hours, 0),"
						+ "            NULLIF(jt2.work_hours, 0), 8) * 60) ELSE 0 END) AS expected_minutes"
						+ "   FROM attendance a"
						+ "   INNER JOIN employees e2 ON e2.id = a.employee_id"
						+ "   LEFT JOIN job_titles jt2 ON jt2.id = e2.job_title_id"
						+ "   WHERE DATE(a.check_in) BETWEEN ? AND ?"
						+ "   GROUP BY a.employee_id"
						+ " ) att ON att.employee_id = e.id"
						+ " WHERE " + scope.where()
						+ " ORDER BY employee_name ASC, e.id ASC LIMIT ? OFFSET ?",
				(rs, rowNum) -> new RawAggregate(
						rs.getLong("employee_id"), rs.getString("emp_code"),
						rs.getString("employee_name"), rs.getString("job_title_name"),
						rs.getInt("present_days"), rs.getInt("exception_days"),
						rs.getLong("total_minutes"), rs.getLong("expected_minutes")),
				pageParams.toArray());

		long aggCompanyId = filters.companyId() > 0 ? filters.companyId() : scopedCompanyId;
		List<AttendanceRecord.AggregateRow> report = new ArrayList<>(raw.size());
		for (RawAggregate row : raw) {
			report.add(summarise(row, aggCompanyId, daysInPeriod, from, to, asOf));
		}
		return DashboardPage.of(report, total == null ? 0 : total, aggPage, filters.perPage());
	}

	/**
	 * One aggregate row's derived figures, in the source's order.
	 *
	 * <p>Absence has two forms and both are kept. Without a company there is
	 * nothing to resolve a work calendar against, so it falls back to the
	 * period's own length; with one it is the expected working days. Neither is
	 * {@code attendanceDisplay}'s: there is no as-of cap and void weekly-rest
	 * days are not counted as absence here.
	 */
	private AttendanceRecord.AggregateRow summarise(
			RawAggregate row, long companyId, int daysInPeriod, String from, String to, String asOf) {
		long employeeId = row.employeeId();
		int holidayCredit = officialHolidayCreditForEmployee(companyId, employeeId, from, to);
		int paidLeaveDays = employeeId > 0
				? this.attendanceFigures.approvedLeaveDays(employeeId, from, to) : 0;
		double workHoursPerDay = employeeId > 0
				? this.attendanceFigures.employeeWorkHoursPerDay(employeeId).doubleValue() : 8.0d;

		int earnedWeeklyRest = 0;
		int absentDays = Math.max(0, daysInPeriod - row.presentDays() - holidayCredit - paidLeaveDays);
		if (companyId > 0 && employeeId > 0) {
			Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attFlags =
					this.weeklyRestCredit.attendanceFlagsInRange(companyId, employeeId, from, to);
			String lookbackFrom = LocalDate.parse(from).minusDays(7).toString();
			Map<String, String> holidayByDate = this.calendar.holidaysByDate(companyId, lookbackFrom, to);
			// The dashboard passes no as-of, and PHP defaults that to today.
			earnedWeeklyRest = this.attendanceFigures.weeklyRestDatesByStatus(
					companyId, employeeId, from, to, LegacyWeeklyRestCredit.EARNED,
					attFlags, holidayByDate, asOf).size();
			int expectedWorkDays = this.attendanceFigures.expectedWorkDays(companyId, employeeId, from, to);
			absentDays = Math.max(0,
					expectedWorkDays - row.presentDays() - paidLeaveDays - holidayCredit);
		}

		int paidRestMinutes = (int) Math.round(
				(holidayCredit + (double) paidLeaveDays + earnedWeeklyRest) * workHoursPerDay * 60);

		return new AttendanceRecord.AggregateRow(
				employeeId,
				row.empCode() == null ? "" : row.empCode(),
				row.employeeName() == null ? "" : row.employeeName(),
				row.jobTitleName() == null ? "" : row.jobTitleName(),
				daysInPeriod,
				row.presentDays(),
				holidayCredit,
				paidLeaveDays,
				absentDays,
				row.exceptionDays(),
				paidRestMinutes,
				row.totalMinutes(),
				row.totalMinutes() - row.expectedMinutes());
	}

}
