package com.workin.legacy.attendance.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;

/**
 * The batched pre-warm answers what the per-date query answers, for every date.
 *
 * <p>{@code warmShiftsForEmployees} exists to turn one statement per employee
 * per day into one statement per page, and it earns that only if a warmed cache
 * is indistinguishable from the cache the per-date query would have built. The
 * resolution it does in memory is the inverse of
 * {@code ORDER BY effective_from DESC, esa.id DESC LIMIT 1}, so the cases that
 * decide it are the boundaries: the day an assignment takes effect, the day
 * before, a second assignment later in the range, two assignments effective on
 * the <em>same</em> day (where only {@code id} breaks the tie), and a date
 * before any assignment exists, which the query answers with no row at all.
 *
 * <p>Asserted by comparing the two, date by date, rather than by restating what
 * the answer should be -- a test that hard-coded the expected shift would pass
 * against a pre-warm that was wrong in the same way as its author's reading.
 */
class LegacyAttendanceCalendarWarmTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 99760;
	private static final long EMPLOYEE = 997601;
	private static final long NEVER_ASSIGNED = 997602;
	private static final long EARLY_SHIFT = 997611;
	private static final long LATE_SHIFT = 997612;
	private static final long TIE_SHIFT = 997613;

	private DataSource dataSource;

	@BeforeEach
	void seed() throws Exception {
		seedAsLegacyWould(
				"DELETE FROM employee_shift_assignments WHERE employee_id BETWEEN 997600 AND 997699",
				"DELETE FROM employees WHERE id BETWEEN 997600 AND 997699",
				"DELETE FROM shifts WHERE id BETWEEN 997600 AND 997699",
				"DELETE FROM branches WHERE id BETWEEN 997600 AND 997699",
				"DELETE FROM companies WHERE id = " + COMPANY,
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
						+ " (" + COMPANY + ", 'Warm Co', '+201000997600', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
						+ " (997601, " + COMPANY + ", 'Warm HQ', 1, '2019-03-01 10:00:00')",
				"INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active,"
						+ " created_at) VALUES"
						+ " (" + EARLY_SHIFT + ", " + COMPANY + ", 'Early', '07:00:00', '15:00:00', '', 1,"
						+ " '2019-05-01 08:00:00'),"
						+ " (" + LATE_SHIFT + ", " + COMPANY + ", 'Late', '14:00:00', '22:00:00', '', 1,"
						+ " '2019-05-01 08:00:00'),"
						+ " (" + TIE_SHIFT + ", " + COMPANY + ", 'Tie', '10:00:00', '18:00:00', '', 1,"
						+ " '2019-05-01 08:00:00')",
				employee(EMPLOYEE),
				employee(NEVER_ASSIGNED),
				// Effective mid-range, then a second one later, then a third
				// effective the same day as the second -- only esa.id separates
				// the last two, which is the tie the query breaks with id DESC.
				"INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES"
						+ " (" + EMPLOYEE + ", " + EARLY_SHIFT + ", '2026-03-10'),"
						+ " (" + EMPLOYEE + ", " + LATE_SHIFT + ", '2026-03-20'),"
						+ " (" + EMPLOYEE + ", " + TIE_SHIFT + ", '2026-03-20')");

		this.dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	@Test
	void aWarmedCacheAnswersExactlyWhatThePerDateQueryAnswers() {
		List<String> dates = new ArrayList<>();
		for (java.time.LocalDate d = java.time.LocalDate.parse("2026-03-01");
				!d.isAfter(java.time.LocalDate.parse("2026-03-31")); d = d.plusDays(1)) {
			dates.add(d.toString());
		}

		LegacyAttendanceCalendar warmed = calendar();
		warmed.warmShiftsForEmployees(List.of(EMPLOYEE, NEVER_ASSIGNED), "2026-03-01", "2026-03-31");

		for (long employee : List.of(EMPLOYEE, NEVER_ASSIGNED)) {
			for (String date : dates) {
				// A fresh calendar per lookup, so the comparison is against the
				// query and never against another warmed value.
				Map<String, Object> direct = calendar().shiftForEmployeeOnDate(employee, date);
				assertThat(warmed.shiftForEmployeeOnDate(employee, date))
						.as("employee %s on %s", employee, date)
						.isEqualTo(direct);
			}
		}
	}

	@Test
	void theBoundariesAreTheOnesThatDecideIt() {
		LegacyAttendanceCalendar warmed = calendar();
		warmed.warmShiftsForEmployees(List.of(EMPLOYEE), "2026-03-01", "2026-03-31");

		assertThat(warmed.shiftForEmployeeOnDate(EMPLOYEE, "2026-03-09"))
				.as("the day before the first assignment takes effect has no shift at all")
				.isNull();
		assertThat(warmed.shiftForEmployeeOnDate(EMPLOYEE, "2026-03-10"))
				.as("the day it takes effect does")
				.isNotNull();
		assertThat(name(warmed, "2026-03-10")).isEqualTo("Early");
		assertThat(name(warmed, "2026-03-19")).as("and holds until the next one").isEqualTo("Early");
		assertThat(name(warmed, "2026-03-20"))
				.as("two assignments effective the same day: the later id wins, as id DESC picks")
				.isEqualTo("Tie");
		assertThat(name(warmed, "2026-03-31")).isEqualTo("Tie");
	}

	@Test
	void warmingIsRefusedRatherThanGuessedWhenItCannotBeDone() {
		LegacyAttendanceCalendar calendar = calendar();
		calendar.warmShiftsForEmployees(List.of(), "2026-03-01", "2026-03-31");
		calendar.warmShiftsForEmployees(List.of(EMPLOYEE), "not-a-date", "2026-03-31");
		calendar.warmShiftsForEmployees(List.of(EMPLOYEE), "2026-03-31", "2026-03-01");

		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, "2026-03-15"))
				.as("nothing was cached, so the per-date query still answers correctly")
				.isEqualTo(calendar().shiftForEmployeeOnDate(EMPLOYEE, "2026-03-15"));
	}

	private String name(LegacyAttendanceCalendar calendar, String date) {
		return String.valueOf(calendar.shiftForEmployeeOnDate(EMPLOYEE, date).get("name"));
	}

	private LegacyAttendanceCalendar calendar() {
		return new LegacyAttendanceCalendar(this.dataSource, new LegacyWeeklyOffDays(this.dataSource));
	}

	private static String employee(long id) {
		return "INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES (" + id + ", " + COMPANY
				+ ", 997601, '" + id + "', 'Warm', 'One', '+2010" + id + "', 'employee', 1,"
				+ " '2019-04-01 08:00:00')";
	}
}
