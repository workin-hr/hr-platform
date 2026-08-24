package com.workin.legacy.schedules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Wave 12.6.5: {@code schedules/employee_monthly_schedule.php} and
 * {@code generate_employee_schedule.php}.
 *
 * <p>Both are computed views over the shift assignment rather than stored data,
 * which is what makes the manual-row precedence and the rest-day/holiday
 * ordering the load-bearing contract. Fixtures use fixed 2026 dates so nothing
 * depends on when the suite runs.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyScheduleMonthEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String MONTH = "/apis/api/schedules/employee_monthly_schedule.php";
	private static final String GENERATE = "/apis/api/schedules/generate_employee_schedule.php";

	private static final long COMPANY_1 = 21301L;
	private static final long COMPANY_2 = 21302L;
	private static final long ADMIN_1 = 213011L;
	private static final long EMPLOYEE_1 = 213012L;
	private static final long EMPLOYEE_2 = 213013L;
	private static final long EMPLOYEE_OTHER_CO = 213021L;
	private static final long BRANCH_1 = 21311L;
	private static final long BRANCH_2 = 21312L;
	private static final long SHIFT_DAY = 21331L;
	private static final long SHIFT_NIGHT = 21332L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the schedule-month fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@BeforeEach
	void reset() {
		execute("DELETE FROM employee_schedules");
		execute("DELETE FROM employee_shift_assignments");
		execute("DELETE FROM company_official_holidays");
		execute("DELETE FROM company_setting_values");
		execute("DELETE FROM company_settings");
		execute("DELETE FROM setting_allowed_values");
		execute("DELETE FROM setting_definitions");
		execute("DELETE FROM notifications");
	}

	// ------------------------------------------------------------------
	// Method, authority and tenancy
	// ------------------------------------------------------------------

	@Test
	void theMonthViewIsGetOnlyAndTheGeneratorIsPostOnly() {
		assertThat(send(MONTH, ADMIN_1, HttpMethod.POST, null, 405).get("message"))
				.isEqualTo("Invalid method");
		assertThat(send(GENERATE, ADMIN_1, HttpMethod.GET, null, 405).get("message"))
				.isEqualTo("Invalid method");
	}

	/** The month view admits EMPLOYEE; the generator's role list does not. */
	@Test
	void anEmployeeMayReadTheirOwnMonthButMayNotGenerate() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		get(MONTH, EMPLOYEE_1, "?month=4&year=2026", 200);
		send(GENERATE, EMPLOYEE_1, HttpMethod.POST,
				"{\"employee_id\":" + EMPLOYEE_1 + ",\"from_date\":\"2026-04-01\","
						+ "\"to_date\":\"2026-04-03\"}", 403);
	}

	/** And only their own: another id in the same company is 403, before the row is read. */
	@Test
	void anEmployeeMayNotReadAColleaguesMonth() {
		Map<String, Object> body = get(MONTH, EMPLOYEE_1, "?employee_id=" + EMPLOYEE_2, 403);

		assertThat(body.get("message")).isEqualTo("Forbidden");
	}

	@Test
	void aForeignCompanysEmployeeIsNotFound() {
		assertThat(get(MONTH, ADMIN_1, "?employee_id=" + EMPLOYEE_OTHER_CO, 404).get("message"))
				.isEqualTo("Employee not found");
		assertThat(send(GENERATE, ADMIN_1, HttpMethod.POST,
				"{\"employee_id\":" + EMPLOYEE_OTHER_CO + ",\"from_date\":\"2026-04-01\","
						+ "\"to_date\":\"2026-04-03\"}", 404).get("message"))
				.isEqualTo("Employee not found");
	}

	// ------------------------------------------------------------------
	// The month view
	// ------------------------------------------------------------------

	/** The envelope's four keys, in PHP's order. */
	@Test
	void theMonthViewHasItsFourKeysInOrder() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		Map<String, Object> data = dataOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=4&year=2026", 200));

		assertThat(data.keySet())
				.containsExactly("shift", "weekly_rest_days", "official_holidays", "days");
	}

	/**
	 * A month with an assignment reports one computed day per calendar day,
	 * with {@code id = 0}.
	 *
	 * <p>April 2026 has 30 days and the assignment covers all of them, so the
	 * whole month appears -- that is what distinguishes a computed view from a
	 * stored one.
	 */
	@Test
	void everyDayOfAnAssignedMonthIsComputed() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		List<Map<String, Object>> days = daysOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=4&year=2026", 200));

		assertThat(days).hasSize(30);
		assertThat(days.get(0).get("schedule_date")).isEqualTo("2026-04-01");
		assertThat(number(days.get(0).get("id"))).isZero();
		assertThat(days.get(0).get("name")).isEqualTo("Day");
		assertThat(days.get(0).get("start_time")).isEqualTo("09:00:00");
		assertThat(days.get(29).get("schedule_date")).isEqualTo("2026-04-30");
	}

	/**
	 * A day with <b>no</b> assignment is skipped entirely, so the array is not
	 * one entry per calendar day.
	 *
	 * <p>The assignment starts on the 10th, so the first nine days of April are
	 * absent rather than present-and-empty. A client indexing by day-of-month
	 * would be wrong.
	 */
	@Test
	void daysBeforeTheAssignmentAreOmittedRatherThanEmpty() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-04-10");

		List<Map<String, Object>> days = daysOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=4&year=2026", 200));

		assertThat(days).hasSize(21);
		assertThat(days.get(0).get("schedule_date")).isEqualTo("2026-04-10");
	}

	/**
	 * A manual row wins over the computed one -- <b>including on a holiday</b>.
	 *
	 * <p>{@code compute_days_for_range()} checks {@code employee_schedules}
	 * first and never evaluates the exception for a stored day. So a day that
	 * would have been a holiday reports the manual shift with no exception at
	 * all, and its real row id.
	 */
	@Test
	void aManualRowOverridesTheComputedDayEvenOnAHoliday() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		holiday("2026-04-15", "Eid");
		execute("INSERT INTO employee_schedules (employee_id, schedule_date, name, start_time,"
				+ " end_time, exception_note) VALUES (" + EMPLOYEE_1
				+ ", '2026-04-15', 'Special', '11:00:00', '19:00:00', NULL)");

		List<Map<String, Object>> days = daysOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=4&year=2026", 200));
		Map<String, Object> day = days.stream()
				.filter(row -> "2026-04-15".equals(row.get("schedule_date"))).findFirst().orElseThrow();

		assertThat(day.get("name")).isEqualTo("Special");
		assertThat(day.get("exception")).isNull();
		assertThat(number(day.get("id"))).isPositive();
	}

	/**
	 * A holiday with no manual row becomes an exception day with <b>no times at
	 * all</b>.
	 *
	 * <p>{@code schedule_row_from_shift()} replaces the entire row when there is
	 * an exception note, which is how a rest day is distinguishable from a
	 * working day in the payload.
	 */
	@Test
	void aHolidayClearsTheNameAndTimesAndCarriesItsOwnLabel() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		holiday("2026-04-15", "Eid");

		Map<String, Object> day = dayOn(ADMIN_1, EMPLOYEE_1, 4, 2026, "2026-04-15");

		assertThat(day.get("exception")).isEqualTo("Eid");
		assertThat(day.get("name")).isNull();
		assertThat(day.get("start_time")).isNull();
		assertThat(day.get("end_time")).isNull();
	}

	/** A nameless holiday falls back to the weekly-rest label, not an empty string. */
	@Test
	void aNamelessHolidayReportsTheWeeklyRestLabel() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		holiday("2026-04-15", "");

		assertThat(dayOn(ADMIN_1, EMPLOYEE_1, 4, 2026, "2026-04-15").get("exception"))
				.isEqualTo("Weekly rest");
	}

	/** The shift's own {@code days_off} marks a weekly rest day. */
	@Test
	void theShiftsOwnDaysOffMarkARestDay() {
		// 2026-04-17 is a Friday.
		execute("UPDATE shifts SET days_off = 'friday' WHERE id = " + SHIFT_DAY);
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		assertThat(dayOn(ADMIN_1, EMPLOYEE_1, 4, 2026, "2026-04-17").get("exception"))
				.isEqualTo("Weekly rest");
		execute("UPDATE shifts SET days_off = NULL WHERE id = " + SHIFT_DAY);
	}

	/**
	 * The company setting marks one too, and {@code weekly_rest_days} merges
	 * both sources.
	 *
	 * <p>The setting accepts a numeric value where the shift text does not --
	 * two grammars for the same concept, and the merged list is de-duplicated
	 * and sorted ascending.
	 */
	@Test
	void theCompanySettingMergesWithTheShiftDaysOff() {
		execute("UPDATE shifts SET days_off = 'saturday' WHERE id = " + SHIFT_DAY);
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		weeklyOffDays("5");

		Map<String, Object> data = dataOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=4&year=2026", 200));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rest = (List<Map<String, Object>>) data.get("weekly_rest_days");
		assertThat(rest).hasSize(2);
		assertThat(number(rest.get(0).get("day_of_week"))).isEqualTo(5L);
		assertThat(rest.get(0).get("name")).isEqualTo("Friday");
		assertThat(number(rest.get(1).get("day_of_week"))).isEqualTo(6L);
		assertThat(rest.get(1).get("name")).isEqualTo("Saturday");
		execute("UPDATE shifts SET days_off = NULL WHERE id = " + SHIFT_DAY);
	}

	/**
	 * {@code effective_to} is the day before the next assignment, and null for
	 * the latest one.
	 */
	@Test
	void theShiftSummaryReportsTheAssignmentWindow() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		assign(EMPLOYEE_1, SHIFT_NIGHT, "2026-04-16");

		@SuppressWarnings("unchecked")
		Map<String, Object> early = (Map<String, Object>) dataOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=3&year=2026", 200)).get("shift");
		assertThat(early.get("name")).isEqualTo("Day");
		assertThat(early.get("effective_to")).isEqualTo("2026-04-15");

		@SuppressWarnings("unchecked")
		Map<String, Object> late = (Map<String, Object>) dataOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=4&year=2026", 200)).get("shift");
		assertThat(late.get("name")).isEqualTo("Night");
		assertThat(late.get("effective_to")).isNull();
	}

	/** No assignment at all: a null shift, and no days. */
	@Test
	void anEmployeeWithNoAssignmentHasANullShiftAndNoDays() {
		Map<String, Object> data = dataOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=4&year=2026", 200));

		assertThat(data.get("shift")).isNull();
		assertThat((List<?>) data.get("days")).isEmpty();
		assertThat((List<?>) data.get("weekly_rest_days")).isEmpty();
	}

	/** The month is clamped to 1-12; the year is not clamped at all. */
	@Test
	void theMonthIsClampedAndTheYearIsNot() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2020-01-01");

		// month=99 clamps to December.
		List<Map<String, Object>> december = daysOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=99&year=2026", 200));
		assertThat(december.get(0).get("schedule_date")).isEqualTo("2026-12-01");
		assertThat(december).hasSize(31);

		// month=0 clamps to January.
		assertThat(daysOf(get(MONTH, ADMIN_1,
				"?employee_id=" + EMPLOYEE_1 + "&month=0&year=2026", 200)).get(0).get("schedule_date"))
				.isEqualTo("2026-01-01");
	}

	// ------------------------------------------------------------------
	// Generation
	// ------------------------------------------------------------------

	@Test
	void generationWritesOneRowPerDayAndReportsTheShift() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		Map<String, Object> data = dataOf(generate(EMPLOYEE_1, "2026-04-01", "2026-04-05", null, 200));

		assertThat(data.keySet()).containsExactly("count", "shift_id", "shift_name");
		assertThat(number(data.get("count"))).isEqualTo(5L);
		assertThat(number(data.get("shift_id"))).isEqualTo(SHIFT_DAY);
		assertThat(data.get("shift_name")).isEqualTo("Day");
		assertThat(query("SELECT id FROM employee_schedules")).hasSize(5);
	}

	/** A generated rest day stores the note and clears the times. */
	@Test
	void aGeneratedHolidayStoresTheNoteAndNoTimes() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		holiday("2026-04-02", "Eid");

		generate(EMPLOYEE_1, "2026-04-01", "2026-04-03", null, 200);

		List<Map<String, Object>> rows = query(
				"SELECT name, start_time, exception_note FROM employee_schedules"
						+ " WHERE schedule_date = '2026-04-02'");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("exception_note")).isEqualTo("Eid");
		assertThat(rows.get(0).get("name")).isNull();
		assertThat(rows.get(0).get("start_time")).isNull();
	}

	/**
	 * No assignment is {@code shift_not_assigned} 400 -- but an <b>inverted</b>
	 * range with an assignment is a 200 reporting zero.
	 *
	 * <p>Two ways to write nothing, two different statuses, because the guard
	 * tests {@code count === 0 && shift_id === null} rather than the count
	 * alone.
	 */
	@Test
	void noAssignmentFailsWhileAnEmptyRangeSucceedsWithZero() {
		Map<String, Object> refused = generate(EMPLOYEE_1, "2026-04-01", "2026-04-03", null, 400);
		assertThat(refused.get("message")).isEqualTo("No shift is assigned to this employee");

		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		Map<String, Object> inverted = generate(EMPLOYEE_1, "2026-04-05", "2026-04-01", null, 400);
		// An inverted range returns before the assignment lookup, so it is also
		// shift_not_assigned -- the early return reports no shift id.
		assertThat(inverted.get("message")).isEqualTo("No shift is assigned to this employee");
	}

	/**
	 * {@code replace} defaults to <b>true</b>, so omitting it wipes the range
	 * first.
	 *
	 * <p>A pre-existing manual row inside the range is replaced by the generated
	 * one; with {@code replace: false} it is instead overwritten in place by the
	 * upsert, which is the same visible outcome here but a different statement
	 * -- so the case that distinguishes them is a manual row on a day the loop
	 * <em>skips</em>.
	 */
	@Test
	void replaceDefaultsToTrueAndDeletesTheRangeFirst() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-04-03");
		execute("INSERT INTO employee_schedules (employee_id, schedule_date, name, start_time,"
				+ " end_time) VALUES (" + EMPLOYEE_1 + ", '2026-04-01', 'Manual', '07:00:00', '15:00:00')");

		// The loop skips 04-01 and 04-02 (no assignment yet), but the delete does not.
		generate(EMPLOYEE_1, "2026-04-01", "2026-04-05", null, 200);

		assertThat(query("SELECT id FROM employee_schedules WHERE schedule_date = '2026-04-01'"))
				.describedAs("the manual row was deleted and never regenerated").isEmpty();
		assertThat(query("SELECT id FROM employee_schedules")).hasSize(3);
	}

	/** With {@code replace: false} the same manual row survives. */
	@Test
	void replaceFalseKeepsRowsTheLoopDoesNotTouch() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-04-03");
		execute("INSERT INTO employee_schedules (employee_id, schedule_date, name, start_time,"
				+ " end_time) VALUES (" + EMPLOYEE_1 + ", '2026-04-01', 'Manual', '07:00:00', '15:00:00')");

		generate(EMPLOYEE_1, "2026-04-01", "2026-04-05", Boolean.FALSE, 200);

		assertThat(query("SELECT name FROM employee_schedules WHERE schedule_date = '2026-04-01'")
				.get(0).get("name")).isEqualTo("Manual");
		assertThat(query("SELECT id FROM employee_schedules")).hasSize(4);
	}

	/** Generation is idempotent: the unique key collapses a repeat run. */
	@Test
	void generatingTwiceLeavesTheSameRows() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		generate(EMPLOYEE_1, "2026-04-01", "2026-04-05", null, 200);
		generate(EMPLOYEE_1, "2026-04-01", "2026-04-05", null, 200);

		assertThat(query("SELECT id FROM employee_schedules")).hasSize(5);
	}

	/**
	 * A range spanning a shift change generates <b>both</b> shifts and reports
	 * only the later one.
	 *
	 * <p>Each day uses the assignment effective that day, while the reported
	 * shift comes from the range's end date.
	 */
	@Test
	void aRangeSpanningAShiftChangeGeneratesBothAndReportsTheLater() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		assign(EMPLOYEE_1, SHIFT_NIGHT, "2026-04-03");

		Map<String, Object> data = dataOf(generate(EMPLOYEE_1, "2026-04-01", "2026-04-04", null, 200));

		assertThat(data.get("shift_name")).isEqualTo("Night");
		List<Map<String, Object>> rows = query(
				"SELECT schedule_date, name FROM employee_schedules ORDER BY schedule_date");
		assertThat(rows.get(0).get("name")).isEqualTo("Day");
		assertThat(rows.get(3).get("name")).isEqualTo("Night");
	}

	// ------------------------------------------------------------------
	// The range bounds are built with new DateTimeImmutable(), not a parser
	// ------------------------------------------------------------------

	/**
	 * A PHP-accepted non-ISO bound works, and the raw string still reaches SQL.
	 *
	 * <p>{@code 2026/04/26} is accepted by {@code new DateTimeImmutable()} --
	 * measured -- so it must not be a parser rejection. The DELETE and the
	 * holiday lookup keep the caller's original strings, which MariaDB then
	 * coerces itself; only the loop bounds are parsed.
	 */
	@Test
	void aSlashSeparatedBoundIsAcceptedAsPhpAcceptsIt() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		Map<String, Object> data = dataOf(generate(EMPLOYEE_1, "2026/04/01", "2026/04/03", null, 200));

		assertThat(number(data.get("count"))).isEqualTo(3L);
		assertThat(query("SELECT schedule_date FROM employee_schedules ORDER BY schedule_date")
				.get(0).get("schedule_date")).hasToString("2026-04-01");
	}

	/** The eight-digit form works end to end: the parser takes it and so does SQL. */
	@Test
	void anEightDigitBoundIsAcceptedByBothTheParserAndTheQuery() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		assertThat(number(dataOf(generate(EMPLOYEE_1, "20260401", "20260403", null, 200))
				.get("count"))).isEqualTo(3L);
	}

	/**
	 * <b>The parser and the SQL disagree, and the SQL decides.</b>
	 *
	 * <p>{@code new DateTimeImmutable('03-04-2026')} is 3 April 2026 -- the
	 * bound parses. But the raw string is what reaches
	 * {@code schedule_shift_for_employee_on_date()}, and MariaDB casts
	 * {@code '03-04-2026'} to <b>NULL</b>, so {@code effective_from <= ?}
	 * matches nothing and the endpoint answers {@code shift_not_assigned}
	 * despite the employee having an assignment since January.
	 *
	 * <p>Both measured. This is exactly why the raw strings are not normalised
	 * before the SQL: doing so would "fix" this into a 200 and diverge. Three
	 * date surfaces in this wave now disagree about {@code 03-04-2026} --
	 * the constructor accepts it, MariaDB nulls it, and the punch parser reads
	 * it as day-first.
	 */
	@Test
	void aDayFirstDashedBoundParsesButFindsNoAssignmentBecauseSqlNullsIt() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		Map<String, Object> body = generate(EMPLOYEE_1, "01-04-2026", "03-04-2026", null, 400);

		assertThat(body.get("message")).isEqualTo("No shift is assigned to this employee");
		assertThat(query("SELECT id FROM employee_schedules")).isEmpty();
	}

	/**
	 * An impossible day <b>rolls</b> rather than failing.
	 *
	 * <p>{@code new DateTimeImmutable('2026-02-30')} is 2 March 2026, so a range
	 * ending there really does extend into March -- four days from 27 February,
	 * not a rejection and not a truncation at the month end.
	 */
	@Test
	void anImpossibleDayRollsForwardAsPhpDoes() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		Map<String, Object> data = dataOf(generate(EMPLOYEE_1, "2026-02-27", "2026-02-30", null, 200));

		// 27, 28 February plus 1, 2 March.
		assertThat(number(data.get("count"))).isEqualTo(4L);
		assertThat(query("SELECT schedule_date FROM employee_schedules ORDER BY schedule_date DESC")
				.get(0).get("schedule_date")).hasToString("2026-03-02");
	}

	/**
	 * <b>A malformed bound is a D-084 500, not {@code shift_not_assigned}.</b>
	 *
	 * <p>PHP's {@code new DateTimeImmutable('oops')} throws
	 * {@code DateMalformedStringException}, and nothing on this path catches it
	 * -- so it is an uncaught throwable, which D-084 answers with its fixed
	 * envelope. Mapping it to a business error would tell the client their
	 * employee has no shift, which is a different and wrong statement.
	 *
	 * <p>The response carries no exception text and no PHP file or line.
	 */
	@Test
	void aMalformedBoundIsAnUnexpectedFailureNotAShiftError() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		Map<String, Object> body = generate(EMPLOYEE_1, "oops", "2026-04-03", null, 500);

		assertThat(body.get("success")).isEqualTo(false);
		assertThat(body.get("message")).isEqualTo("Internal server error");
		assertThat(body).doesNotContainKey("data");
		assertThat(query("SELECT id FROM employee_schedules")).isEmpty();
	}

	/** {@code 26/04/2026} throws in PHP too -- the day-first slash form is not accepted here. */
	@Test
	void aDayFirstSlashBoundIsAlsoAnUnexpectedFailure() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");

		assertThat(generate(EMPLOYEE_1, "2026-04-01", "26/04/2026", null, 500).get("message"))
				.isEqualTo("Internal server error");
	}

	/**
	 * The three outcomes stay distinct.
	 *
	 * <p>A parse failure is a 500, an inverted range is
	 * {@code shift_not_assigned} 400 because the early return reports no shift,
	 * and a valid range with no assignment is the same 400 by a different route.
	 * The first must never collapse into the other two.
	 */
	@Test
	void parseFailureInvertedRangeAndNoAssignmentAreThreeOutcomes() {
		assign(EMPLOYEE_1, SHIFT_DAY, "2026-01-01");
		assertThat(generate(EMPLOYEE_1, "oops", "2026-04-03", null, 500).get("message"))
				.isEqualTo("Internal server error");
		assertThat(generate(EMPLOYEE_1, "2026-04-05", "2026-04-01", null, 400).get("message"))
				.isEqualTo("No shift is assigned to this employee");

		execute("DELETE FROM employee_shift_assignments");
		assertThat(generate(EMPLOYEE_1, "2026-04-01", "2026-04-03", null, 400).get("message"))
				.isEqualTo("No shift is assigned to this employee");
	}

	@Test
	void generationRequiresItsThreeFields() {
		assertThat((String) send(GENERATE, ADMIN_1, HttpMethod.POST,
				"{\"from_date\":\"2026-04-01\",\"to_date\":\"2026-04-03\"}", 400).get("message"))
				.contains("employee_id");
		assertThat((String) send(GENERATE, ADMIN_1, HttpMethod.POST,
				"{\"employee_id\":" + EMPLOYEE_1 + ",\"to_date\":\"2026-04-03\"}", 400).get("message"))
				.contains("from_date");
		assertThat((String) send(GENERATE, ADMIN_1, HttpMethod.POST,
				"{\"employee_id\":" + EMPLOYEE_1 + ",\"from_date\":\"2026-04-01\"}", 400).get("message"))
				.contains("to_date");
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> generate(long employeeId, String from, String to, Boolean replace,
			int expectedStatus) {
		String json = "{\"employee_id\":" + employeeId + ",\"from_date\":\"" + from
				+ "\",\"to_date\":\"" + to + "\""
				+ (replace == null ? "" : ",\"replace\":" + replace) + "}";
		return send(GENERATE, ADMIN_1, HttpMethod.POST, json, expectedStatus);
	}

	private Map<String, Object> dayOn(long actor, long employeeId, int month, int year, String date) {
		return daysOf(get(MONTH, actor, "?employee_id=" + employeeId + "&month=" + month
				+ "&year=" + year, 200)).stream()
				.filter(row -> date.equals(row.get("schedule_date"))).findFirst().orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> daysOf(Map<String, Object> body) {
		return (List<Map<String, Object>>) dataOf(body).get("days");
	}

	private Map<String, Object> get(String path, long actor, String query, int expectedStatus) {
		return send(path + query, actor, HttpMethod.GET, null, expectedStatus);
	}

	private Map<String, Object> send(
			String path, long actor, HttpMethod method, String json, int expectedStatus) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(actor));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(json, headers), mapType());
		assertThat(response.getStatusCode().value()).as("%s", response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private static ParameterizedTypeReference<Map<String, Object>> mapType() {
		return new ParameterizedTypeReference<Map<String, Object>>() { };
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == ADMIN_1 ? "company_admin" : "employee";
		long companyId = employeeId == EMPLOYEE_OTHER_CO ? COMPANY_2 : COMPANY_1;
		return jwtService.issueAccessToken(employeeId, employeeId, companyId, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	private static void assign(long employeeId, long shiftId, String effectiveFrom) {
		execute("INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from)"
				+ " VALUES (" + employeeId + ", " + shiftId + ", '" + effectiveFrom + "')");
	}

	private static void holiday(String date, String name) {
		execute("INSERT INTO company_official_holidays (company_id, name, holiday_date) VALUES ("
				+ COMPANY_1 + ", '" + name + "', '" + date + "')");
	}

	private static void weeklyOffDays(String... values) {
		execute("INSERT INTO setting_definitions (id, setting_key, is_multi) VALUES"
				+ " (930, 'WEEKLY_OFF_DAYS', 1)");
		execute("INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES"
				+ " (930, " + COMPANY_1 + ", 930)");
		int id = 930;
		for (String value : values) {
			id++;
			execute("INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order)"
					+ " VALUES (" + id + ", 930, '" + value + "', 0)");
			execute("INSERT INTO company_setting_values (company_setting_id, setting_allowed_value_id)"
					+ " VALUES (930, " + id + ")");
		}
	}

	private static void execute(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("SET time_zone = '+02:00'");
			st.execute(sql);
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static List<Map<String, Object>> query(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET time_zone = '+02:00'");
			ResultSet rs = st.executeQuery(sql);
			List<Map<String, Object>> rows = new ArrayList<>();
			while (rs.next()) {
				Map<String, Object> row = new LinkedHashMap<>();
				for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
					row.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
				}
				rows.add(row);
			}
			return rows;
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (21301, 'Schedule Month Co', '+201000021301', 'active', '2025-01-15 09:00:00'),
					  (21302, 'Schedule Month Other', '+201000021302', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (21311, 21301, 'Main', 1, '2025-03-01 10:00:00'),
					  (21312, 21302, 'Other', 1, '2025-03-01 10:00:00')
					""");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_DAY + ", " + COMPANY_1
					+ ", 'Day', '09:00:00', '17:00:00', 1, '2025-03-02 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_NIGHT + ", " + COMPANY_1
					+ ", 'Night', '22:00:00', '06:00:00', 1, '2025-03-02 10:00:00')");
			employee(st, ADMIN_1, COMPANY_1, BRANCH_1, "company_admin", "+201000213011");
			employee(st, EMPLOYEE_1, COMPANY_1, BRANCH_1, "employee", "+201000213012");
			employee(st, EMPLOYEE_2, COMPANY_1, BRANCH_1, "employee", "+201000213013");
			employee(st, EMPLOYEE_OTHER_CO, COMPANY_2, BRANCH_2, "employee", "+201000213021");
		}
	}

	private static void employee(Statement st, long id, long companyId, long branchId, String role,
			String phone) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES (" + id + ", " + companyId
				+ ", " + branchId + ", " + id + ", 'First', 'Last', '" + phone + "', '" + role
				+ "', 1, '2025-04-01 08:00:00')");
	}

	private static void applySchema(String resourceName) throws Exception {
		String schema = readResource(resourceName);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			for (String statement : schema.split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream = LegacyScheduleMonthEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
