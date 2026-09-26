package com.workin.legacy.attendance.records;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A deterministic, deliberately uneven attendance dataset for the report
 * endpoints (D-292).
 *
 * <p>Every shape the per-day rules branch on is here at least once: weekly rest
 * from the company setting and from each of the shift's own grammars (English,
 * abbreviated, Arabic, and a numeric value the shift grammar does not honour),
 * a shift change part-way through the range, two assignments on one date, an
 * employee hired mid-range, one with no shift at all whose expected hours come
 * from the fallback chain (a stored zero included), an overnight shift,
 * holidays on work days, on rest days, in the look-back window and with a blank
 * name, approved, pending and rejected leave, paid and unpaid, timed requests
 * (overlapping, multi-day, and one with no to-time), missing punches, open
 * punches, exception-only markers, two punches on one day with an identical
 * check-in, inactive and pending employees, and employee codes that sort
 * differently under the report's and the listing's orderings.
 *
 * <p>The punches are drawn from a seeded {@link Random}, so the same seed is the
 * same database, and the differential test can run several seeds.
 */
public final class AttendanceReportFixture {

	/** Company with WEEKLY_OFF_DAYS = friday, saturday. */
	public static final long COMPANY_A = 356001L;

	/** Company with no rest-day setting: rest comes only from the shifts' own days_off. */
	public static final long COMPANY_B = 356002L;

	public static final long BRANCH_A1 = 356011L;
	public static final long BRANCH_A2 = 356012L;
	public static final long BRANCH_B1 = 356013L;
	public static final long DEPARTMENT_A = 356021L;

	public static final long ADMIN_A = 356100L;
	public static final long ADMIN_B = 356200L;

	/** A manager in branch A2, so a manager-scoped report has a real scope. */
	public static final long MANAGER_A2 = 356101L;

	/** The first employee the varied fixture creates in company A. */
	public static final long FIRST_A = 356_300L;

	private static final long SHIFT_A_DAY = 356031L;
	private static final long SHIFT_A_LATE = 356032L;
	private static final long SHIFT_A_NIGHT = 356033L;
	private static final long SHIFT_B_DAY = 356034L;
	private static final long SHIFT_B_ARABIC = 356035L;
	private static final long SHIFT_B_NUMERIC = 356036L;

	private static final long JOB_HOURS = 356041L;
	private static final long JOB_ZERO = 356042L;

	private static final long EXCEPTION_SICK = 356051L;
	private static final long EXCEPTION_MISSION = 356052L;
	private static final long EXCEPTION_BLANK = 356053L;

	private static final long TYPE_A_PAID = 356061L;
	private static final long TYPE_A_UNPAID = 356062L;
	private static final long TYPE_B_PAID = 356063L;

	/** First and last day any punch is drawn for. */
	public static final LocalDate DATA_FROM = LocalDate.parse("2025-12-01");
	public static final LocalDate DATA_TO = LocalDate.parse("2026-04-20");

	private final List<String> statements = new ArrayList<>();
	private final List<Long> employeesA = new ArrayList<>();
	private final List<Long> employeesB = new ArrayList<>();
	private final Random random;

	private long nextAttendanceId = 35_600_000L;
	private long nextRequestId = 356_000L;
	private long nextAssignmentId = 356_000L;

	private AttendanceReportFixture(long seed) {
		this.random = new Random(seed);
	}

	/**
	 * The varied dataset: {@code perCompany} employees in each company, every
	 * seventh one of a different kind.
	 */
	public static AttendanceReportFixture varied(long seed, int perCompany) {
		AttendanceReportFixture fixture = new AttendanceReportFixture(seed);
		fixture.reference();
		for (int i = 0; i < perCompany; i++) {
			fixture.employee(COMPANY_A, 356_300L + i, i, i % 2 == 0 ? BRANCH_A1 : BRANCH_A2);
			fixture.employee(COMPANY_B, 356_600L + i, i, BRANCH_B1);
		}
		fixture.edgeCases();
		return fixture;
	}

	/**
	 * Company A only, with {@code small} employees in branch A1 and {@code large}
	 * in branch A2, so one report can be asked for either roster size by its
	 * branch filter. The same mix of kinds as {@link #varied}.
	 */
	public static AttendanceReportFixture sized(long seed, int small, int large) {
		AttendanceReportFixture fixture = new AttendanceReportFixture(seed);
		fixture.reference();
		for (int i = 0; i < small + large; i++) {
			fixture.employee(COMPANY_A, 356_300L + i, i, i < small ? BRANCH_A1 : BRANCH_A2);
		}
		fixture.edgeCases();
		return fixture;
	}

	/** Every statement, in the order they must run. */
	public List<String> statements() {
		return List.copyOf(statements);
	}

	public List<Long> employeesA() {
		return List.copyOf(employeesA);
	}

	public List<Long> employeesB() {
		return List.copyOf(employeesB);
	}

	private void reference() {
		for (long company : new long[] { COMPANY_A, COMPANY_B }) {
			statements.add("INSERT INTO companies (id, company_name, phone, password_hash, status, created_at)"
					+ " VALUES (" + company + ", 'Report Co " + company + "', '+20100" + company + "', 'x',"
					+ " 'active', '2019-01-01 00:00:00')");
		}
		statements.add("INSERT INTO branches (id, company_id, name, is_active) VALUES"
				+ " (" + BRANCH_A1 + ", " + COMPANY_A + ", 'A One', 1),"
				+ " (" + BRANCH_A2 + ", " + COMPANY_A + ", 'A Two', 1),"
				+ " (" + BRANCH_B1 + ", " + COMPANY_B + ", '', 1)");
		statements.add("INSERT INTO departments (id, company_id, name) VALUES"
				+ " (" + DEPARTMENT_A + ", " + COMPANY_A + ", 'Ops')");
		statements.add("INSERT INTO job_titles (id, company_id, name, work_hours) VALUES"
				+ " (" + JOB_HOURS + ", " + COMPANY_A + ", 'Clerk', 7.50),"
				+ " (" + JOB_ZERO + ", " + COMPANY_B + ", 'Zero', 0.00)");
		statements.add("INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active) VALUES"
				+ " (" + SHIFT_A_DAY + ", " + COMPANY_A + ", 'Day', '09:00:00', '17:00:00', '', 1),"
				+ " (" + SHIFT_A_LATE + ", " + COMPANY_A + ", 'Late', '13:00:00', '21:30:00', 'Thu', 1),"
				+ " (" + SHIFT_A_NIGHT + ", " + COMPANY_A + ", 'Night', '22:00:00', '06:00:00', 'friday', 1),"
				+ " (" + SHIFT_B_DAY + ", " + COMPANY_B + ", 'B Day', '08:00:00', '16:00:00', 'friday', 1),"
				+ " (" + SHIFT_B_ARABIC + ", " + COMPANY_B + ", '', '10:00:00', '18:00:00', 'الجمعة،السبت', 1),"
				+ " (" + SHIFT_B_NUMERIC + ", " + COMPANY_B + ", 'Numeric', '09:00:00', '15:00:00', '5', 1)");
		statements.add("INSERT INTO exception_types (id, company_id, name) VALUES"
				+ " (" + EXCEPTION_SICK + ", " + COMPANY_A + ", 'Sick 356'),"
				+ " (" + EXCEPTION_MISSION + ", " + COMPANY_A + ", ' Mission 356 '),"
				+ " (" + EXCEPTION_BLANK + ", " + COMPANY_B + ", '   ')");
		statements.add("INSERT INTO request_types (id, company_id, name, counts_as_paid_leave) VALUES"
				+ " (" + TYPE_A_PAID + ", " + COMPANY_A + ", 'Annual', 1),"
				+ " (" + TYPE_A_UNPAID + ", " + COMPANY_A + ", 'Unpaid', 0),"
				+ " (" + TYPE_B_PAID + ", " + COMPANY_B + ", 'Annual B', 1)");
		statements.add("INSERT INTO company_official_holidays (company_id, holiday_date, name) VALUES"
				+ " (" + COMPANY_A + ", '2025-12-28', 'Look-back'),"
				+ " (" + COMPANY_A + ", '2026-01-07', 'Winter'),"
				+ " (" + COMPANY_A + ", '2026-01-25', 'Revolution'),"
				+ " (" + COMPANY_A + ", '2026-02-06', 'On a Friday'),"
				+ " (" + COMPANY_A + ", '2026-03-12', '  '),"
				+ " (" + COMPANY_A + ", '2026-03-19', 'Tomorrow'),"
				+ " (" + COMPANY_B + ", '2026-01-19', 'B Day Off'),"
				+ " (" + COMPANY_B + ", '2026-03-06', 'B Friday')");
		// WEEKLY_OFF_DAYS for company A: friday, then saturday, by sort order.
		statements.add("INSERT INTO setting_definitions (id, setting_key) VALUES (356071, 'weekly_off_days')");
		statements.add("INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order) VALUES"
				+ " (356072, 356071, 'friday', 1), (356073, 356071, 'saturday', 2)");
		statements.add("INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES"
				+ " (356074, " + COMPANY_A + ", 356071)");
		statements.add("INSERT INTO company_setting_values (id, company_setting_id, setting_allowed_value_id) VALUES"
				+ " (356075, 356074, 356072), (356076, 356074, 356073)");

		employeeRow(ADMIN_A, COMPANY_A, BRANCH_A1, "1", "Admin", "A", "company_admin", 1, "accepted", "NULL",
				"NULL");
		employeeRow(ADMIN_B, COMPANY_B, BRANCH_B1, "1", "Admin", "B", "company_admin", 1, "accepted", "NULL",
				"NULL");
		employeeRow(MANAGER_A2, COMPANY_A, BRANCH_A2, "2", "Manager", "Two", "manager", 1, "accepted", "NULL",
				"NULL");
		assign(ADMIN_A, SHIFT_A_DAY, "2020-01-01");
		assign(ADMIN_B, SHIFT_B_DAY, "2020-01-01");
		assign(MANAGER_A2, SHIFT_A_DAY, "2020-01-01");
	}

	private static final String[] CODES = { "10", "9", "A12", "12abc", "", "0007", "100" };

	private void employee(long company, long id, int index, long branch) {
		int kind = index % 7;
		boolean a = company == COMPANY_A;
		String code = index < CODES.length ? CODES[index] : String.valueOf(1000 + index);
		String expectedHours = switch (index % 3) {
			case 0 -> "NULL";
			case 1 -> "0";
			default -> "6.5";
		};
		String jobTitle = a ? String.valueOf(JOB_HOURS) : (index % 2 == 0 ? String.valueOf(JOB_ZERO) : "NULL");
		int active = kind == 5 ? 0 : 1;
		String join = kind == 6 ? "pending" : "accepted";
		employeeRow(id, company, branch, code, "Emp" + index, a ? "Alpha" : "Beta", "employee", active, join,
				expectedHours, jobTitle);
		(a ? employeesA : employeesB).add(id);

		long first = a ? SHIFT_A_DAY : SHIFT_B_DAY;
		long second = a ? SHIFT_A_LATE : SHIFT_B_ARABIC;
		switch (kind) {
			case 0 -> {
				// No shift at all: expected minutes come from the fallback chain.
			}
			case 1 -> assign(id, first, "2024-06-01");
			case 2 -> {
				assign(id, first, "2024-06-01");
				// A shift change mid-range, and a second assignment on the same
				// date whose higher id must win.
				assign(id, second, "2026-02-10");
				assign(id, a ? SHIFT_A_NIGHT : SHIFT_B_NUMERIC, "2026-02-10");
				assign(id, first, "2026-03-15");
			}
			case 3 -> assign(id, second, "2026-02-15");
			case 4 -> assign(id, a ? SHIFT_A_NIGHT : SHIFT_B_NUMERIC, "2025-01-01");
			default -> assign(id, first, "2025-01-01");
		}
		boolean night = kind == 4 && a;

		for (LocalDate day = DATA_FROM; !day.isAfter(DATA_TO); day = day.plusDays(1)) {
			punches(id, day, night, a);
		}
		requests(id, a);
	}

	private void punches(long employee, LocalDate day, boolean night, boolean a) {
		double roll = random.nextDouble();
		boolean weekend = day.getDayOfWeek() == DayOfWeek.FRIDAY
				|| (a && day.getDayOfWeek() == DayOfWeek.SATURDAY);
		if (weekend && roll < 0.8) {
			return;
		}
		int startMinute = (night ? 21 * 60 + 30 : 8 * 60) + random.nextInt(150);
		int length = 7 * 60 + random.nextInt(150);
		String in = at(day, startMinute);
		String out = at(day, startMinute + length);
		long exception = a ? (random.nextBoolean() ? EXCEPTION_SICK : EXCEPTION_MISSION) : EXCEPTION_BLANK;
		if (roll < 0.52) {
			attendance(employee, in, out, null);
		} else if (roll < 0.60) {
			attendance(employee, in, null, null);
		} else if (roll < 0.65) {
			attendance(employee, day + " 00:00:00", null, exception);
		} else if (roll < 0.69) {
			attendance(employee, in, out, exception);
		} else if (roll < 0.72) {
			attendance(employee, in, at(day, startMinute + 200), null);
			// An identical check-in: the tie the per-employee query broke by id.
			attendance(employee, in, out, null);
			attendance(employee, at(day, startMinute + 240), out, null);
		} else if (roll < 0.74) {
			attendance(employee, day + " 00:00:00", null, exception);
			attendance(employee, in, out, null);
		} else if (roll < 0.76) {
			attendance(employee, in, out, exception);
			attendance(employee, at(day, startMinute + 30), null, a ? EXCEPTION_SICK : EXCEPTION_BLANK);
		}
		// else: nothing that day, a missing day.
	}

	private void requests(long employee, boolean a) {
		long paid = a ? TYPE_A_PAID : TYPE_B_PAID;
		long unpaid = a ? TYPE_A_UNPAID : TYPE_B_PAID;
		for (int i = 0; i < 6; i++) {
			LocalDate from = DATA_FROM.plusDays(random.nextInt(130));
			LocalDate to = from.plusDays(random.nextInt(4));
			String status = switch (random.nextInt(4)) {
				case 0 -> "pending";
				case 1 -> "rejected";
				default -> "approved";
			};
			request(employee, i % 3 == 0 ? unpaid : paid, from, to, null, null, status);
		}
		// A leave crossing the start of every range the tests use.
		request(employee, paid, LocalDate.parse("2025-12-29"), LocalDate.parse("2026-01-03"), null, null,
				"approved");
		request(employee, paid, LocalDate.parse("2026-02-26"), LocalDate.parse("2026-03-02"), null, null,
				"approved");
		// Timed requests: a one-day mission, a multi-day one overlapping it with a
		// higher id, one with no to-time, and a pending one.
		LocalDate mission = LocalDate.parse("2026-01-12").plusDays(random.nextInt(10));
		request(employee, paid, mission, mission, "10:00:00", "14:00:00", "approved");
		request(employee, unpaid, mission.minusDays(1), mission.plusDays(2), "09:30:00", "16:45:00", "approved");
		request(employee, paid, mission.plusDays(5), mission.plusDays(5), "11:00:00", null, "approved");
		request(employee, paid, mission.plusDays(6), mission.plusDays(6), "11:00:00", "12:00:00", "pending");
		LocalDate march = LocalDate.parse("2026-03-09").plusDays(random.nextInt(8));
		request(employee, unpaid, march, march, "08:00:00", "13:15:00", "approved");
	}

	private void edgeCases() {
		// Open punches around the fixed "now" (2026-03-18 12:00): one still inside
		// its deadline, one from the evening before, one from a week ago.
		long open = employeesA.get(Math.min(1, employeesA.size() - 1));
		attendance(open, "2026-03-18 08:05:00", null, null);
		attendance(open, "2026-03-17 20:00:00", null, null);
		attendance(open, "2026-03-11 09:10:00", null, null);
		// An overnight punch that ends the next morning.
		attendance(employeesA.get(4 % employeesA.size()), "2026-01-14 22:10:00", "2026-01-15 06:20:00", null);
		// Zero dates, which a non-strict legacy database can hold: an approved
		// paid leave and an approved mission whose from_date is 0000-00-00 cover
		// every date up to their to_date in SQL's comparison.
		long zero = employeesA.get(0);
		statements.add("INSERT INTO requests (id, employee_id, request_type_id, from_date, to_date, status)"
				+ " VALUES (" + (nextRequestId++) + ", " + zero + ", " + TYPE_A_PAID + ", '0000-00-00',"
				+ " '2026-01-05', 'approved')");
		statements.add("INSERT INTO requests (id, employee_id, request_type_id, from_date, to_date, from_time,"
				+ " to_time, status) VALUES (" + (nextRequestId++) + ", " + zero + ", " + TYPE_A_PAID + ","
				+ " '0000-00-00', '2026-01-08', '07:00:00', '08:00:00', 'approved')");
	}

	private void employeeRow(long id, long company, long branch, String code, String first, String last,
			String role, int active, String join, String expectedHours, String jobTitle) {
		statements.add("INSERT INTO employees (id, company_id, branch_id, department_id, job_title_id,"
				+ " employee_code, expected_daily_hours, first_name, last_name, phone, role, is_active,"
				+ " join_request_status, photo_url, created_at) VALUES (" + id + ", " + company + ", " + branch
				+ ", " + (company == COMPANY_A ? DEPARTMENT_A : "NULL") + ", " + jobTitle + ", "
				+ (code == null ? "NULL" : "'" + code + "'") + ", " + expectedHours + ", '" + first + "', '"
				+ last + "', '+2012" + id + "', '" + role + "', " + active + ", '" + join + "', "
				+ (id % 2 == 0 ? "'/p/" + id + ".png'" : "NULL") + ", '2019-04-01 08:00:00')");
	}

	private void assign(long employee, long shift, String from) {
		statements.add("INSERT INTO employee_shift_assignments (id, employee_id, shift_id, effective_from)"
				+ " VALUES (" + (nextAssignmentId++) + ", " + employee + ", " + shift + ", '" + from + "')");
	}

	private void attendance(long employee, String in, String out, Long exception) {
		statements.add("INSERT INTO attendance (id, employee_id, check_in, check_out, exception_type_id)"
				+ " VALUES (" + (nextAttendanceId++) + ", " + employee + ", '" + in + "', "
				+ (out == null ? "NULL" : "'" + out + "'") + ", " + (exception == null ? "NULL" : exception)
				+ ")");
	}

	private void request(long employee, long type, LocalDate from, LocalDate to, String fromTime, String toTime,
			String status) {
		statements.add("INSERT INTO requests (id, employee_id, request_type_id, from_date, to_date, from_time,"
				+ " to_time, status) VALUES (" + (nextRequestId++) + ", " + employee + ", " + type + ", '" + from
				+ "', '" + to + "', " + (fromTime == null ? "NULL" : "'" + fromTime + "'") + ", "
				+ (toTime == null ? "NULL" : "'" + toTime + "'") + ", '" + status + "')");
	}

	private static String at(LocalDate day, int minuteOfDay) {
		return day.atStartOfDay().plusMinutes(minuteOfDay).toString().replace('T', ' ') + ":00";
	}
}
