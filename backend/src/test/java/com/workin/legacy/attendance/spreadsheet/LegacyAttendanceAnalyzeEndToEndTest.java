package com.workin.legacy.attendance.spreadsheet;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Wave 12.6.4's clear endpoint: {@code attendance/analyze_excel.php}.
 *
 * <p>An upload/parser boundary, so this goes after the actual spreadsheet and
 * error semantics rather than treating it as a read endpoint: the four
 * {@code import_format} shapes, the classification thresholds, the bounded gap
 * fill, and the fact that nothing is ever written.
 *
 * <p>What the reader does with bytes is already pinned by the import's own
 * suites and by D-097's differential; this does not repeat them.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyAttendanceAnalyzeEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String ANALYZE = "/apis/api/attendance/analyze_excel.php";
	private static final String CONFIG_KEY = "attendance_excel_import_available_from";

	private static final long COMPANY_1 = 21401L;
	private static final long ADMIN_1 = 214011L;
	private static final long MANAGER_1 = 214012L;
	private static final long EMPLOYEE_1 = 214013L;
	private static final long BRANCH_1 = 21411L;
	private static final long SHIFT_1 = 21431L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the analyze fixture", ex);
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
		execute("DELETE FROM attendance");
		execute("DELETE FROM employee_shift_assignments");
		execute("DELETE FROM company_official_holidays");
		availableFrom("2020-01-01");
	}

	// ------------------------------------------------------------------
	// Guards, in PHP's order
	// ------------------------------------------------------------------

	@Test
	void analyzeIsPostOnly() {
		assertThat(sendWithoutFile(ADMIN_1, HttpMethod.GET, 405).get("message"))
				.isEqualTo("Invalid method");
	}

	@Test
	void aManagerIsForbidden() {
		assertThat(post(MANAGER_1, "punch.csv", punchCsv(), 403).get("message"))
				.isEqualTo("Forbidden");
	}

	/**
	 * The availability gate runs <b>before</b> the file is looked at -- the same
	 * ordering the import has, and the same failure for a request with no file
	 * at all.
	 */
	@Test
	void theAvailabilityGateRunsBeforeTheFile() {
		availableFrom("2099-06-05");

		Map<String, Object> body = sendWithoutFile(ADMIN_1, HttpMethod.POST, 403);

		assertThat(body.get("message")).isEqualTo("Will be available on 5/6/2099.");
	}

	@Test
	void aMissingFilePartIsNoFileUploaded() {
		assertThat(sendWithoutFile(ADMIN_1, HttpMethod.POST, 400).get("message"))
				.isEqualTo("No file uploaded");
	}

	// ------------------------------------------------------------------
	// The four import_format shapes
	// ------------------------------------------------------------------

	/**
	 * A zero-byte upload never reaches the analyzer at all.
	 *
	 * <p>{@code attendance_import_load_rows()} throws
	 * {@code 'Empty or unreadable file'} for an <b>upload format</b> of
	 * {@code empty} or {@code unknown}, before returning anything, so the
	 * analyzer's own {@code import_format === 'empty'} branch is not what
	 * answers here -- the {@code catch (RuntimeException)} is. Two different
	 * vocabularies share the word "empty", and only one of them is a 200.
	 */
	@Test
	void aZeroByteUploadIsRefusedBeforeTheAnalyzerRuns() {
		Map<String, Object> body = post(ADMIN_1, "empty.csv", new byte[0], 400);

		assertThat(body.get("message")).isEqualTo("Invalid file type");
		assertThat(body.get("data")).isEqualTo("Empty or unreadable file");
	}

	/**
	 * The {@code empty} shape is "parsed to no rows", not "no bytes".
	 *
	 * <p>A CSV with a header and nothing under it is a readable file whose row
	 * list comes back empty, which is the only way that branch is reached.
	 */
	@Test
	void aHeaderOnlyFileIsTheEmptyShape() {
		Map<String, Object> data = dataOf(post(ADMIN_1, "header.csv", csv("code,datetime"), 200));

		assertThat(data.get("import_format")).isEqualTo("empty");
		assertThat(warnings(data)).containsExactly("File is empty");
		assertThat((List<?>) data.get("employees")).isEmpty();
		assertThat(number(summary(data).get("total_punches"))).isZero();
	}

	/** Three or more unrecognised columns is {@code unknown}, and stops there. */
	@Test
	void anUnrecognisedLayoutIsTheUnknownShape() {
		Map<String, Object> data = dataOf(post(ADMIN_1, "odd.csv",
				csv("alpha,beta,gamma", "1,2,3", "4,5,6"), 200));

		assertThat(data.get("import_format")).isEqualTo("unknown");
		assertThat((String) warnings(data).get(0)).startsWith("Unrecognized column layout.");
		assertThat((List<?>) data.get("employees")).isEmpty();
	}

	/**
	 * PHP writes {@code 'summary' => []} for this branch, and an empty PHP
	 * array reaches the wire as {@code []} -- never <code>{}</code>.
	 *
	 * <p>This reads the bytes because the assertions above cannot see the
	 * difference: the response is deserialised into a {@code Map} first, and by
	 * then both shapes have become something the test would accept. Measured
	 * against the running legacy PHP, which answers
	 * {@code "import_format":"unknown","summary":[]}. See D-156.
	 */
	@Test
	void theUnknownLayoutSummaryIsAnEmptyArrayOnTheWire() {
		JsonNode data = rawJson(ADMIN_1, "odd.csv", csv("alpha,beta,gamma", "1,2,3", "4,5,6"))
				.get("data");

		assertThat(data.get("import_format").asString()).isEqualTo("unknown");
		assertThat(data.get("summary").isArray()).as("summary: %s", data.get("summary")).isTrue();
		assertThat(data.get("summary")).isEmpty();
	}

	/** A template sheet reports its row count and nothing else. */
	@Test
	void aTemplateSheetIsTheTemplateShapeWithARowCount() {
		Map<String, Object> data = dataOf(post(ADMIN_1, "template.csv",
				csv("employee_code,check_in_date,check_in_time",
						EMPLOYEE_1 + ",2026-04-26,08:03",
						EMPLOYEE_1 + ",2026-04-27,08:05"), 200));

		assertThat(data.get("import_format")).isEqualTo("template");
		assertThat(number(summary(data).get("total_rows"))).isEqualTo(2L);
		assertThat((String) warnings(data).get(0)).startsWith("Template format detected");
		assertThat((List<?>) data.get("employees")).isEmpty();
	}

	// ------------------------------------------------------------------
	// The punch-log analysis
	// ------------------------------------------------------------------

	/** A matched employee, one complete day, and the summary that describes it. */
	@Test
	void aMatchedPunchLogIsSummarisedAndNothingIsWritten() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");

		Map<String, Object> data = dataOf(post(ADMIN_1, "punch.csv", csv("code,datetime",
				EMPLOYEE_1 + ",26/04/2026 09:00", EMPLOYEE_1 + ",26/04/2026 17:00"), 200));

		assertThat(data.keySet())
				.containsExactly("import_format", "summary", "employees", "warnings");
		assertThat(data.get("import_format")).isEqualTo("punch_log");

		Map<String, Object> summary = summary(data);
		assertThat(summary.keySet()).containsExactly("total_punches", "total_employees",
				"matched_employees", "unknown_employees", "total_days", "date_from", "date_to",
				"records_to_import", "records_skipped");
		assertThat(number(summary.get("total_punches"))).isEqualTo(2L);
		assertThat(number(summary.get("matched_employees"))).isEqualTo(1L);
		assertThat(number(summary.get("unknown_employees"))).isZero();
		assertThat(number(summary.get("records_to_import"))).isEqualTo(1L);
		assertThat(summary.get("date_from")).isEqualTo("2026-04-26");

		Map<String, Object> employee = employees(data).get(0);
		assertThat(number(employee.get("employee_id"))).isEqualTo(EMPLOYEE_1);
		assertThat(employee.get("status")).isEqualTo("matched");
		assertThat(employee.get("shift_name")).isEqualTo("Day");

		// A dry run: no attendance row exists afterwards.
		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	/** An unmatched code is {@code skipped}, with a null employee id. */
	@Test
	void anUnknownCodeIsSkippedRatherThanFailing() {
		Map<String, Object> data = dataOf(post(ADMIN_1, "punch.csv", csv("code,datetime",
				"999999,26/04/2026 09:00", "999999,26/04/2026 17:00"), 200));

		Map<String, Object> summary = summary(data);
		assertThat(number(summary.get("unknown_employees"))).isEqualTo(1L);
		assertThat(number(summary.get("records_skipped"))).isEqualTo(1L);
		Map<String, Object> employee = employees(data).get(0);
		assertThat(employee.get("employee_id")).isNull();
		assertThat(employee.get("status")).isEqualTo("skipped");
		// No expectation is computed for an unmatched employee, so the day
		// carries zero expected minutes and classifies against nothing.
		assertThat(number(days(employee).get(0).get("expected_minutes"))).isZero();
	}

	/**
	 * The classification's <b>15-minute dead band</b>.
	 *
	 * <p>Against an 8-hour shift, a day 14 minutes short is {@code ok} with no
	 * undertime at all, while 15 minutes short is {@code undertime}. The
	 * threshold is inclusive on the outside, which is easy to get one off.
	 */
	@Test
	void theFifteenMinuteDeadBandIsInclusiveOnTheOutside() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");

		Map<String, Object> justInside = days(employees(dataOf(post(ADMIN_1, "a.csv",
				csv("code,datetime", EMPLOYEE_1 + ",26/04/2026 09:00",
						EMPLOYEE_1 + ",26/04/2026 16:46"), 200))).get(0)).get(0);
		assertThat(justInside.get("status")).isEqualTo("ok");
		assertThat(number(justInside.get("undertime_minutes"))).isZero();

		Map<String, Object> justOutside = days(employees(dataOf(post(ADMIN_1, "b.csv",
				csv("code,datetime", EMPLOYEE_1 + ",26/04/2026 09:00",
						EMPLOYEE_1 + ",26/04/2026 16:45"), 200))).get(0)).get(0);
		assertThat(justOutside.get("status")).isEqualTo("undertime");
		assertThat(number(justOutside.get("undertime_minutes"))).isEqualTo(15L);
	}

	/** A single punch is {@code incomplete} with no over/undertime attributed. */
	@Test
	void aSinglePunchDayIsIncomplete() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");

		Map<String, Object> day = days(employees(dataOf(post(ADMIN_1, "one.csv",
				csv("code,datetime", EMPLOYEE_1 + ",26/04/2026 09:00"), 200))).get(0)).get(0);

		assertThat(day.get("status")).isEqualTo("incomplete");
		assertThat(number(day.get("overtime_minutes"))).isZero();
		assertThat(number(day.get("undertime_minutes"))).isZero();
	}

	/**
	 * A day worked on a holiday is {@code rest_or_holiday}, and <b>every</b>
	 * minute counts as overtime.
	 *
	 * <p>Expected minutes are zero on a rest day, and the classifier's zero-
	 * expectation arm attributes the whole actual duration as overtime rather
	 * than as an ordinary day.
	 */
	@Test
	void aDayWorkedOnAHolidayIsAllOvertime() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		execute("INSERT INTO company_official_holidays (company_id, name, holiday_date) VALUES ("
				+ COMPANY_1 + ", 'Eid', '2026-04-26')");

		Map<String, Object> day = days(employees(dataOf(post(ADMIN_1, "h.csv",
				csv("code,datetime", EMPLOYEE_1 + ",26/04/2026 09:00",
						EMPLOYEE_1 + ",26/04/2026 13:00"), 200))).get(0)).get(0);

		assertThat(day.get("status")).isEqualTo("rest_or_holiday");
		assertThat(day.get("is_rest_day")).isEqualTo(true);
		assertThat(day.get("rest_note")).isEqualTo("Eid");
		assertThat(number(day.get("expected_minutes"))).isZero();
		assertThat(number(day.get("overtime_minutes"))).isEqualTo(240L);
	}

	/**
	 * The gap fill is bounded by the <b>punch range</b>, not the calendar month.
	 *
	 * <p>Punches on the 26th and the 29th produce four days: the two punched
	 * ones plus the 27th and 28th as {@code missing}. Nothing before the 26th or
	 * after the 29th appears -- PHP's own comment says filling the month
	 * "wrongly starts from day 1".
	 */
	@Test
	void missingDaysAreFilledOnlyInsideThePunchRange() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");

		Map<String, Object> employee = employees(dataOf(post(ADMIN_1, "gap.csv",
				csv("code,datetime",
						EMPLOYEE_1 + ",26/04/2026 09:00", EMPLOYEE_1 + ",26/04/2026 17:00",
						EMPLOYEE_1 + ",29/04/2026 09:00", EMPLOYEE_1 + ",29/04/2026 17:00"), 200)))
				.get(0);

		List<Map<String, Object>> days = days(employee);
		assertThat(days).hasSize(4);
		assertThat(days.get(0).get("date")).isEqualTo("2026-04-26");
		assertThat(days.get(1).get("date")).isEqualTo("2026-04-27");
		assertThat(days.get(1).get("status")).isEqualTo("missing");
		// A missing working day is charged its whole expected duration.
		assertThat(number(days.get(1).get("undertime_minutes"))).isEqualTo(480L);
		assertThat(days.get(3).get("date")).isEqualTo("2026-04-29");
	}

	/** A missing day that is a holiday is {@code rest_or_holiday}, and costs nothing. */
	@Test
	void aMissingDayThatIsAHolidayCostsNothing() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		execute("INSERT INTO company_official_holidays (company_id, name, holiday_date) VALUES ("
				+ COMPANY_1 + ", 'Eid', '2026-04-27')");

		Map<String, Object> employee = employees(dataOf(post(ADMIN_1, "gap2.csv",
				csv("code,datetime",
						EMPLOYEE_1 + ",26/04/2026 09:00", EMPLOYEE_1 + ",26/04/2026 17:00",
						EMPLOYEE_1 + ",28/04/2026 09:00", EMPLOYEE_1 + ",28/04/2026 17:00"), 200)))
				.get(0);

		Map<String, Object> filled = days(employee).get(1);
		assertThat(filled.get("date")).isEqualTo("2026-04-27");
		assertThat(filled.get("status")).isEqualTo("rest_or_holiday");
		assertThat(number(filled.get("undertime_minutes"))).isZero();
		assertThat(number(totals(employee).get("rest_days"))).isEqualTo(1L);
	}

	/** The suggested name split, including the empty-name default. */
	@Test
	void theSuggestedNameSplitsOnTheFirstSpace() {
		Map<String, Object> employee = employees(dataOf(post(ADMIN_1, "named.csv",
				csv("code,datetime", "999999,26/04/2026 09:00", "999999,26/04/2026 17:00"), 200)))
				.get(0);

		// A two-column punch log carries no name, so the default applies.
		assertThat(employee.get("suggested_first_name")).isEqualTo("Employee");
		assertThat(employee.get("suggested_last_name")).isEqualTo("");
	}

	/** Employees are ordered numerically when every code is digits. */
	@Test
	void employeesAreOrderedNumericallyByCode() {
		Map<String, Object> data = dataOf(post(ADMIN_1, "many.csv", csv("code,datetime",
				"30,26/04/2026 09:00", "30,26/04/2026 17:00",
				"9,26/04/2026 09:00", "9,26/04/2026 17:00",
				"200,26/04/2026 09:00", "200,26/04/2026 17:00"), 200));

		List<Map<String, Object>> employees = employees(data);
		assertThat(employees).hasSize(3);
		assertThat(employees.get(0).get("sheet_code")).isEqualTo("9");
		assertThat(employees.get(1).get("sheet_code")).isEqualTo("30");
		assertThat(employees.get(2).get("sheet_code")).isEqualTo("200");
	}

	/** A sheet the reader refuses is {@code invalid_file_type} with the message in data. */
	@Test
	void anUnreadableWorkbookIsInvalidFileType() {
		byte[] biff5 = fixture("biff5.xls");

		Map<String, Object> body = post(ADMIN_1, "old.xls", biff5, 400);

		assertThat(body.get("message")).isEqualTo("Invalid file type");
		assertThat(body.get("data")).isEqualTo("Cannot read XLS file. Invalid or corrupted file");
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/**
	 * The four-column daily sheet, which auto-detection reads as
	 * {@code unknown} -- four columns is neither the two-column punch log nor
	 * the template's header set.
	 */
	@Test
	void fourColumnsForcesADailySheetThatDetectionWouldRefuse() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		byte[] sheet = csv("code,date,in,out", EMPLOYEE_1 + ",26/04/2026,09:00,17:00");

		// Without the field it is unrecognised.
		assertThat(dataOf(post(ADMIN_1, "daily.csv", sheet, 200)).get("import_format"))
				.isEqualTo("unknown");

		Map<String, Object> data = dataOf(post(ADMIN_1, "daily.csv", sheet, "four_columns", 200));
		assertThat(data.get("import_format")).isEqualTo("punch_log");

		Map<String, Object> summary = summary(data);
		// Two cells, so two punches -- the count is the record's punch_count,
		// not a punch list that never existed on this path.
		assertThat(number(summary.get("total_punches"))).isEqualTo(2L);
		assertThat(number(summary.get("total_days"))).isEqualTo(1L);
		assertThat(number(summary.get("matched_employees"))).isEqualTo(1L);
		assertThat(summary.get("date_from")).isEqualTo("2026-04-26");
		assertThat(summary.get("date_to")).isEqualTo("2026-04-26");

		Map<String, Object> day = days(employees(data).get(0)).get(0);
		assertThat(day.get("check_in")).isEqualTo("2026-04-26 09:00:00");
		assertThat(day.get("check_out")).isEqualTo("2026-04-26 17:00:00");
		assertThat(number(day.get("actual_minutes"))).isEqualTo(480L);

		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	@Test
	void anOvernightRowRollsTheCheckOutToTheNextDay() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		Map<String, Object> data = dataOf(post(ADMIN_1, "daily.csv",
				csv("code,date,in,out", EMPLOYEE_1 + ",26/04/2026,22:00,06:30"), "four_columns", 200));

		Map<String, Object> day = days(employees(data).get(0)).get(0);
		// The day is still the 26th; only the check-out crosses midnight.
		assertThat(day.get("date")).isEqualTo("2026-04-26");
		assertThat(day.get("check_in")).isEqualTo("2026-04-26 22:00:00");
		assertThat(day.get("check_out")).isEqualTo("2026-04-27 06:30:00");
		assertThat(number(day.get("actual_minutes"))).isEqualTo(510L);
	}

	@Test
	void aSecondRowForTheSameDayReplacesTheFirstRatherThanMerging() {
		// `$byEmployeeDay[$key] = [...]` is an assignment, not an append.
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		Map<String, Object> data = dataOf(post(ADMIN_1, "daily.csv", csv("code,date,in,out",
				EMPLOYEE_1 + ",26/04/2026,09:00,13:00",
				EMPLOYEE_1 + ",26/04/2026,14:00,18:00"), "four_columns", 200));

		assertThat(number(summary(data).get("total_days"))).isEqualTo(1L);
		Map<String, Object> day = days(employees(data).get(0)).get(0);
		assertThat(day.get("check_in")).isEqualTo("2026-04-26 14:00:00");
		assertThat(day.get("check_out")).isEqualTo("2026-04-26 18:00:00");
	}

	@Test
	void aRowWithNoCheckOutIsAnIncompleteDayAndOneOfItsPunchesIsMissing() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		Map<String, Object> data = dataOf(post(ADMIN_1, "daily.csv",
				csv("code,date,in,out", EMPLOYEE_1 + ",26/04/2026,09:00,"), "four_columns", 200));

		assertThat(number(summary(data).get("total_punches"))).isEqualTo(1L);
		Map<String, Object> day = days(employees(data).get(0)).get(0);
		assertThat(day.get("check_in")).isEqualTo("2026-04-26 09:00:00");
		assertThat(day.get("check_out")).isNull();
		assertThat(day.get("status")).isEqualTo("incomplete");
		assertThat(number(day.get("punch_count"))).isEqualTo(1L);
		assertThat(number(day.get("actual_minutes"))).isZero();
	}

	@Test
	void anOutOnlyRowIsSkippedEntirely() {
		// `if ($inParts === null) continue;` -- and it does not count towards
		// the 25-row sample either, so a sheet of them has no valid rows at all.
		assertThat(post(ADMIN_1, "daily.csv",
				csv("code,date,in,out", EMPLOYEE_1 + ",26/04/2026,,17:00"), "four_columns", 400)
				.get("message"))
				.isEqualTo("No valid punch rows were found in the file.");
	}

	@Test
	void twoColumnsForcesThePunchLogReadingOfATemplateLookingSheet() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		// Three columns, one of them a template alias: detection calls this
		// `template` and the analysis would stop at a row count.
		byte[] sheet = csv("employee_code,check_in_date,ignored",
				EMPLOYEE_1 + ",26/04/2026 09:00,x", EMPLOYEE_1 + ",26/04/2026 17:00,x");
		assertThat(dataOf(post(ADMIN_1, "punch.csv", sheet, 200)).get("import_format"))
				.isEqualTo("template");

		// two_columns cannot rescue it: the forced reading still needs exactly
		// two non-blank columns, and this sheet has three.
		assertThat(post(ADMIN_1, "punch.csv", sheet, "two_columns", 400).get("message"))
				.isEqualTo("Only two columns are required: employee code, and punch date/time.");
	}

	@Test
	void everySpellingOfTheTwoLayoutsIsAccepted() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		byte[] daily = csv("code,date,in,out", EMPLOYEE_1 + ",26/04/2026,09:00,17:00");

		for (String spelling : List.of("four_columns", "4", "four", "daily", "in_out",
				"FOUR_COLUMNS", " four_columns ")) {
			assertThat(dataOf(post(ADMIN_1, "daily.csv", daily, spelling, 200)).get("import_format"))
					.as("layout '%s'", spelling)
					.isEqualTo("punch_log");
		}
		// And an unknown value is auto-detect, not an error: a client sending a
		// layout this build does not know keeps working.
		for (String ignored : List.of("", "columns", "five", "0")) {
			assertThat(dataOf(post(ADMIN_1, "daily.csv", daily, ignored, 200)).get("import_format"))
					.as("layout '%s'", ignored)
					.isEqualTo("unknown");
		}
	}

	@Test
	void twoColumnsOnAPunchLogIsTheSameAnswerAutoDetectionGives() {
		assign(EMPLOYEE_1, SHIFT_1, "2026-01-01");
		for (String spelling : List.of("two_columns", "2", "two", "punches", "punch_log")) {
			Map<String, Object> data = dataOf(post(ADMIN_1, "punch.csv", punchCsv(), spelling, 200));
			assertThat(data.get("import_format")).as("layout '%s'", spelling).isEqualTo("punch_log");
			assertThat(number(summary(data).get("total_punches"))).as("layout '%s'", spelling).isEqualTo(2L);
		}
	}

	@Test
	void aDailySheetWithFewerThanFourColumnsSaysSo() {
		assertThat(post(ADMIN_1, "daily.csv", csv("code,date,in", EMPLOYEE_1 + ",26/04/2026,09:00"),
				"four_columns", 400).get("message"))
				.isEqualTo("Four columns are required: employee code, date, check-in time, and check-out time.");
	}

	@Test
	void anUnreadableDateColumnAndAnUnreadableTimeColumnHaveDistinctMessages() {
		assertThat(post(ADMIN_1, "daily.csv",
				csv("code,date,in,out", EMPLOYEE_1 + ",not a date,09:00,17:00"), "four_columns", 400)
				.get("message"))
				.isEqualTo("The date column is invalid. Please use a date such as 26/04/2026.");

		assertThat(post(ADMIN_1, "daily.csv",
				csv("code,date,in,out", EMPLOYEE_1 + ",26/04/2026,not a time,also not"), "four_columns", 400)
				.get("message"))
				.isEqualTo("The check-in or check-out time column is invalid. Please use a time such as 08:15.");

		// A non-numeric code fails on the code before either of those is reached.
		assertThat(post(ADMIN_1, "daily.csv",
				csv("code,date,in,out", "A12,26/04/2026,09:00,17:00"), "four_columns", 400)
				.get("message"))
				.isEqualTo("Employee codes must be digits only (no letters).");
	}

	@Test
	void aDeclaredLayoutDoesNotResurrectAnEmptyFile() {
		// prepare_records decides `empty` before the layout branches, so a
		// header-only sheet is still the empty shape.
		Map<String, Object> data = dataOf(post(ADMIN_1, "daily.csv", csv("code,date,in,out"),
				"four_columns", 200));
		assertThat(data.get("import_format")).isEqualTo("empty");
		assertThat(number(summary(data).get("total_punches"))).isZero();
		assertThat(warnings(data)).containsExactly("File is empty");
	}

	private static byte[] csv(String... lines) {
		return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] punchCsv() {
		return csv("code,datetime", EMPLOYEE_1 + ",26/04/2026 09:00", EMPLOYEE_1 + ",26/04/2026 17:00");
	}

	private static byte[] fixture(String name) {
		try (InputStream stream = LegacyAttendanceAnalyzeEndToEndTest.class
				.getResourceAsStream("/legacy/spreadsheet/xls/" + name)) {
			if (stream == null) {
				throw new IllegalStateException("missing fixture " + name);
			}
			return stream.readAllBytes();
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> summary(Map<String, Object> data) {
		return (Map<String, Object>) data.get("summary");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> employees(Map<String, Object> data) {
		return (List<Map<String, Object>>) data.get("employees");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> days(Map<String, Object> employee) {
		return (List<Map<String, Object>>) employee.get("days");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> totals(Map<String, Object> employee) {
		return (Map<String, Object>) employee.get("totals");
	}

	@SuppressWarnings("unchecked")
	private static List<Object> warnings(Map<String, Object> data) {
		return (List<Object>) data.get("warnings");
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private Map<String, Object> post(long actor, String filename, byte[] content, int expectedStatus) {
		return post(actor, filename, content, null, expectedStatus);
	}

	/** The same upload with a {@code sheet_layout} field beside the file. */
	private Map<String, Object> post(
			long actor, String filename, byte[] content, String sheetLayout, int expectedStatus) {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		if (sheetLayout != null) {
			parts.add("sheet_layout", sheetLayout);
		}
		return send(actor, HttpMethod.POST, parts, expectedStatus);
	}

	private Map<String, Object> sendWithoutFile(long actor, HttpMethod method, int expectedStatus) {
		return send(actor, method, new LinkedMultiValueMap<>(), expectedStatus);
	}

	private Map<String, Object> send(
			long actor, HttpMethod method, MultiValueMap<String, Object> parts, int expectedStatus) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(actor));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE), method,
				new HttpEntity<>(parts, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).as("%s", response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	/** The response as parsed JSON rather than a deserialised Map, for shape assertions. */
	private JsonNode rawJson(long actor, String filename, byte[] content) {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(actor));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		ResponseEntity<String> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE), HttpMethod.POST,
				new HttpEntity<>(parts, headers), String.class);
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		return JsonMapper.builder().build().readTree(response.getBody());
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == EMPLOYEE_1 ? "employee" : "company_admin";
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY_1, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	private static void assign(long employeeId, long shiftId, String effectiveFrom) {
		execute("INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from)"
				+ " VALUES (" + employeeId + ", " + shiftId + ", '" + effectiveFrom + "')");
	}

	private static void availableFrom(String value) {
		execute("DELETE FROM configs WHERE config_key = '" + CONFIG_KEY + "'");
		execute("INSERT INTO configs (config_key, config_value) VALUES ('" + CONFIG_KEY + "', '"
				+ value + "')");
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
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES ("
					+ COMPANY_1 + ", 'Analyze Co', '+201000021401', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
					+ BRANCH_1 + ", " + COMPANY_1 + ", 'Main', 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_1 + ", " + COMPANY_1
					+ ", 'Day', '09:00:00', '17:00:00', 1, '2025-03-02 10:00:00')");
			employee(st, ADMIN_1, "company_admin", "+201000214011");
			employee(st, MANAGER_1, "manager", "+201000214012");
			employee(st, EMPLOYEE_1, "employee", "+201000214013");
		}
	}

	private static void employee(Statement st, long id, String role, String phone) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES (" + id + ", " + COMPANY_1
				+ ", " + BRANCH_1 + ", " + id + ", 'First', 'Last', '" + phone + "', '" + role
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
		try (InputStream stream = LegacyAttendanceAnalyzeEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
