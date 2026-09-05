package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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
import com.workin.legacy.LegacyRuntimeOffset;

/**
 * Wave 12.6.4b: `attendance/list.php`, `attendance/stats.php` and
 * `attendance/employee_monthly_attendance.php`.
 *
 * <p>The calendar-day fixture (2020-01-06 through 2020-01-12, a fixed
 * historical Monday-through-Sunday week) is chosen so every branch of
 * {@code attendance_build_employee_range_calendar()}'s per-day classification
 * is deterministic regardless of when this test actually runs: a complete
 * punch, a missing workday, an exception-only row, an official holiday and a
 * weekly-rest day whose credit is void (too little covered coverage before
 * it). Anything that depends on "today" itself -- {@code fill_days}' default
 * range, month/year defaulting, the open-session auto-close -- is instead
 * anchored to {@link LocalDate#now()} at fixture time.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyAttendanceReportEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/attendance/list.php";
	private static final String STATS = "/apis/api/attendance/stats.php";
	private static final String MONTHLY = "/apis/api/attendance/employee_monthly_attendance.php";

	private static final long COMPANY_1 = 20900L;
	private static final long COMPANY_2 = 20901L;

	private static final long ADMIN_1 = 209011L;
	private static final long MANAGER_1 = 209013L;
	private static final long EMPLOYEE_1 = 209014L;
	private static final long EMPLOYEE_2 = 209015L;
	private static final long EMPLOYEE_OTHER_CO = 209022L;
	private static final long EMPLOYEE_OPEN_STALE = 209016L;
	private static final long EMPLOYEE_OPEN_FRESH = 209017L;

	private static final long BRANCH_1 = 209031L;
	private static final long BRANCH_2 = 209033L;
	private static final long DEPARTMENT_1 = 209041L;
	private static final long EXCEPTION_TYPE_1 = 209201L;

	private static final long ATT_MON_COMPLETE = 209101L;
	private static final long ATT_WED_EXCEPTION = 209102L;
	private static final long ATT_E2_FEB = 209104L;
	private static final long ATT_OTHER_CO = 209105L;

	/**
	 * <b>The application's notion of today, not the JVM's.</b>
	 * {@link com.workin.legacy.LegacyClock} resolves "today" against the
	 * <em>application</em> offset — deliberately, as its own javadoc explains:
	 * a wrong zone silently dates records a day early or late. With no
	 * {@code is_daylight_saving} row seeded here, that offset is
	 * {@link LegacyRuntimeOffset#DEFAULT} (UTC+2).
	 *
	 * <p>{@code LocalDate.now()} resolves against the JVM default instead, so
	 * the two disagree for however many hours separate the zones. Not
	 * theoretical: with the JVM on UTC+3 this test failed at 00:34 local on
	 * 1 September, asserting 30 days against the endpoint's 31, because the
	 * application was still on 31 August. It would fail in that window at every
	 * month boundary, and CI runs at all hours.
	 *
	 * <p>{@code LegacyClock} itself is request-scoped and cannot be injected
	 * here, so the offset it would resolve is named directly.
	 */
	private static LocalDate applicationToday() {
		return LocalDate.now(LegacyRuntimeOffset.DEFAULT);
	}

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
			throw new IllegalStateException("could not prepare the attendance report fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ------------------------------------------------------------------
	// list.php -- normal branch
	// ------------------------------------------------------------------

	@Test
	void anEmployeeSeesOnlyTheirOwnRowsInTheNormalBranch() {
		Map<String, Object> body = get(LIST + "?date_from=2020-01-01&date_to=2020-01-31", EMPLOYEE_1, 200);
		List<Map<String, Object>> rows = dataRows(body);
		assertThat(rows).hasSize(2);
		assertThat(rows).allSatisfy(row -> assertThat(number(row.get("employee_id"))).isEqualTo(EMPLOYEE_1));
	}

	@Test
	void anAdminSeesEveryCompanyRowAndDurationIsRecomputed() {
		Map<String, Object> body = get(LIST + "?date_from=2020-01-01&date_to=2020-01-31", ADMIN_1, 200);
		List<Map<String, Object>> rows = dataRows(body);
		assertThat(rows).hasSize(2);

		Map<String, Object> monday = rows.stream()
				.filter(row -> number(row.get("id")) == ATT_MON_COMPLETE).findFirst().orElseThrow();
		// A complete in/out punch against an exactly-matching 8h shift.
		assertThat(number(monday.get("duration_minutes"))).isEqualTo(480);

		Map<String, Object> wednesday = rows.stream()
				.filter(row -> number(row.get("id")) == ATT_WED_EXCEPTION).findFirst().orElseThrow();
		// Exception-only, no timed request covering it.
		assertThat(number(wednesday.get("duration_minutes"))).isEqualTo(0);

		// Never another company's row, even unfiltered.
		assertThat(rows).noneSatisfy(row -> assertThat(number(row.get("id"))).isEqualTo(ATT_OTHER_CO));

		Map<?, ?> meta = (Map<?, ?>) body.get("meta");
		assertThat(number(meta.get("total"))).isEqualTo(2);
	}

	@Test
	void theBranchFilterNarrowsToOneEmployee() {
		Map<String, Object> body = get(LIST + "?branch_id=" + BRANCH_1, ADMIN_1, 200);
		List<Map<String, Object>> rows = dataRows(body);
		assertThat(rows).hasSize(2);
		assertThat(rows).allSatisfy(row -> assertThat(number(row.get("employee_id"))).isEqualTo(EMPLOYEE_1));
	}

	// ------------------------------------------------------------------
	// list.php -- fill_days branch
	// ------------------------------------------------------------------

	@Test
	void fillDaysExpandsOneRowPerCalendarDay() {
		Map<String, Object> body = get(
				LIST + "?fill_days=1&employee_id=" + EMPLOYEE_1 + "&date_from=2020-01-06&date_to=2020-01-12",
				ADMIN_1, 200);
		List<Map<String, Object>> rows = dataRows(body);
		assertThat(rows).hasSize(7);
		assertThat(rows).allSatisfy(row -> {
			assertThat(number(row.get("employee_id"))).isEqualTo(EMPLOYEE_1);
			assertThat(row.get("employee_name")).isEqualTo("Ellie One");
		});

		// page=1, limit=max(1,total) -- the fill_days branch's own meta shape.
		Map<?, ?> meta = (Map<?, ?>) body.get("meta");
		assertThat(number(meta.get("page"))).isEqualTo(1);
		assertThat(number(meta.get("limit"))).isEqualTo(7);
		assertThat(number(meta.get("total"))).isEqualTo(7);
	}

	@Test
	void thePresentDayShowsTheShiftMatchedDuration() {
		Map<String, Object> monday = dayFor(EMPLOYEE_1, "2020-01-06", "2020-01-06", "2020-01-12");
		assertThat(monday.get("is_missing")).isEqualTo(false);
		assertThat(number(monday.get("duration_minutes"))).isEqualTo(480);
		assertThat(number(monday.get("expected_duration_minutes"))).isEqualTo(480);
	}

	@Test
	void aScheduledWorkdayWithNoRowIsMissing() {
		Map<String, Object> tuesday = dayFor(EMPLOYEE_1, "2020-01-07", "2020-01-06", "2020-01-12");
		assertThat(tuesday.get("is_missing")).isEqualTo(true);
		assertThat(number(tuesday.get("duration_minutes"))).isEqualTo(0);
		assertThat(tuesday.get("check_in")).isNull();
	}

	@Test
	void anExceptionOnlyRowIsNeverMissingAndCarriesItsLabel() {
		Map<String, Object> wednesday = dayFor(EMPLOYEE_1, "2020-01-08", "2020-01-06", "2020-01-12");
		assertThat(wednesday.get("is_missing")).isEqualTo(false);
		assertThat(wednesday.get("exception_type_name")).isEqualTo("Sick Leave");
		assertThat(number(wednesday.get("duration_minutes"))).isEqualTo(0);
	}

	@Test
	void anOfficialHolidayWithNoAttendanceRowIsNotMissingEither() {
		Map<String, Object> thursday = dayFor(EMPLOYEE_1, "2020-01-09", "2020-01-06", "2020-01-12");
		assertThat(thursday.get("is_missing")).isEqualTo(false);
		assertThat(thursday.get("is_official_holiday")).isEqualTo(true);
		assertThat(thursday.get("exception_type_name")).isEqualTo("Founders Day");
	}

	@Test
	void aWeeklyRestDayWithTooLittleCoverageIsVoid() {
		Map<String, Object> saturday = dayFor(EMPLOYEE_1, "2020-01-11", "2020-01-06", "2020-01-12");
		assertThat(saturday.get("is_weekly_rest")).isEqualTo(true);
		assertThat(saturday.get("is_weekly_rest_void")).isEqualTo(true);
		assertThat(saturday.get("weekly_rest_credit")).isEqualTo("void");
	}

	@Test
	void anInvertedFillDaysRangeIsInvalidInput() {
		get(LIST + "?fill_days=1&date_from=2020-01-12&date_to=2020-01-06", ADMIN_1, 400);
	}

	// ------------------------------------------------------------------
	// stats.php
	// ------------------------------------------------------------------

	@Test
	void theAggregateBranchWithNoDateBoundCountsEveryRow() {
		// Neither date_from nor date_to given: the SQL bind is entirely absent,
		// so both fixture rows count regardless of their actual month.
		Map<String, Object> data = dataOf(get(STATS, ADMIN_1, 200));
		assertThat(number(data.get("present_days"))).isEqualTo(2);
		assertThat(number(data.get("total_duration_minutes"))).isEqualTo(960);
		assertThat(number(data.get("total_days_in_month"))).isEqualTo(applicationToday().lengthOfMonth());
		assertThat(number(data.get("official_holiday_days"))).isEqualTo(0);
	}

	@Test
	void theAggregateBranchWithAnExplicitRangeUsesItForBothTheSqlAndTheHolidayCredit() {
		Map<String, Object> data = dataOf(
				get(STATS + "?date_from=2020-01-01&date_to=2020-01-31", ADMIN_1, 200));
		assertThat(number(data.get("present_days"))).isEqualTo(1);
		assertThat(number(data.get("total_duration_minutes"))).isEqualTo(480);
		assertThat(number(data.get("total_days_in_month"))).isEqualTo(31);
		assertThat(number(data.get("official_holiday_days"))).isEqualTo(1);
		assertThat(number(data.get("leave_days"))).isEqualTo(1);
		assertThat(number(data.get("absent_days"))).isEqualTo(29);
	}

	/**
	 * Codex/D-101: a parseable-but-non-ISO {@code date_from} (no dashes at
	 * all) must still derive {@code total_days_in_month} from the parsed
	 * date, not from re-splitting the raw string on {@code '-'} -- which
	 * would find no second part and silently fall back to today's month.
	 * February 2024 is a leap year (29 days), a value today's real month
	 * could not produce by accident.
	 */
	@Test
	void theAggregateBranchDerivesTheMonthFromTheParsedDateNotARawStringSplit() {
		Map<String, Object> data = dataOf(
				get(STATS + "?date_from=1%20February%202024", ADMIN_1, 200));
		assertThat(number(data.get("total_days_in_month"))).isEqualTo(29);
	}

	@Test
	void thePerEmployeeBranchWalksTheWholeWeekDayByDay() {
		Map<String, Object> data = dataOf(get(
				STATS + "?employee_id=" + EMPLOYEE_1 + "&date_from=2020-01-06&date_to=2020-01-12", ADMIN_1, 200));
		assertThat(number(data.get("total_days_in_month"))).isEqualTo(31);
		assertThat(number(data.get("present_days"))).isEqualTo(1);
		assertThat(number(data.get("leave_days"))).isEqualTo(2);
		assertThat(number(data.get("official_holiday_days"))).isEqualTo(1);
		assertThat(number(data.get("absent_days"))).isEqualTo(2);
		assertThat(number(data.get("total_duration_minutes"))).isEqualTo(480);
		assertThat(number(data.get("overtime_minutes"))).isEqualTo(0);
	}

	@Test
	void anEmployeeAlwaysGetsTheirOwnStatsRegardlessOfAnyEmployeeIdFilter() {
		Map<String, Object> asSelf = dataOf(get(
				STATS + "?date_from=2020-01-06&date_to=2020-01-12", EMPLOYEE_1, 200));
		Map<String, Object> ignoringOthersId = dataOf(get(
				STATS + "?employee_id=" + EMPLOYEE_2 + "&date_from=2020-01-06&date_to=2020-01-12", EMPLOYEE_1, 200));
		assertThat(asSelf).isEqualTo(ignoringOthersId);
		assertThat(number(asSelf.get("present_days"))).isEqualTo(1);
	}

	@Test
	void aMissingEmployeeIdInTheStatsBranchIs404() {
		get(STATS + "?employee_id=999999", ADMIN_1, 404);
	}

	// ------------------------------------------------------------------
	// employee_monthly_attendance.php
	// ------------------------------------------------------------------

	@Test
	void fullMonthReturnsEveryCalendarDayOfTheMonth() {
		Map<String, Object> body = get(
				MONTHLY + "?id=" + EMPLOYEE_1 + "&month=1&year=2020&full_month=1", ADMIN_1, 200);
		List<Map<String, Object>> rows = dataRows(body);
		assertThat(rows).hasSize(31);

		Map<?, ?> meta = (Map<?, ?>) body.get("meta");
		assertThat(meta.get("full_month")).isEqualTo(true);
		assertThat(meta.get("has_open_check_in")).isEqualTo(false);
		assertThat(meta.get("employee_name")).isEqualTo("Ellie One");
		assertThat(number(meta.get("month"))).isEqualTo(1);
		assertThat(number(meta.get("year"))).isEqualTo(2020);

		Map<String, Object> monday = rows.stream()
				.filter(row -> "2020-01-06".equals(row.get("date"))).findFirst().orElseThrow();
		assertThat(number(monday.get("duration_minutes"))).isEqualTo(480);
		Map<String, Object> saturday = rows.stream()
				.filter(row -> "2020-01-11".equals(row.get("date"))).findFirst().orElseThrow();
		assertThat(saturday.get("is_weekly_rest")).isEqualTo(true);
	}

	@Test
	void theRawBranchRecomputesDurationForEveryRowInTheMonth() {
		Map<String, Object> body = get(MONTHLY + "?id=" + EMPLOYEE_1 + "&month=1&year=2020", ADMIN_1, 200);
		List<Map<String, Object>> rows = dataRows(body);
		assertThat(rows).hasSize(2);

		Map<String, Object> monday = rows.stream()
				.filter(row -> number(row.get("id")) == ATT_MON_COMPLETE).findFirst().orElseThrow();
		assertThat(number(monday.get("duration_minutes"))).isEqualTo(480);
		Map<String, Object> wednesday = rows.stream()
				.filter(row -> number(row.get("id")) == ATT_WED_EXCEPTION).findFirst().orElseThrow();
		assertThat(number(wednesday.get("duration_minutes"))).isEqualTo(0);

		Map<?, ?> meta = (Map<?, ?>) body.get("meta");
		assertThat(meta.get("full_month")).isEqualTo(false);
		assertThat(meta.get("has_open_check_in")).isEqualTo(false);
	}

	@Test
	void monthAndYearDefaultToTodayAndAFreshOpenSessionIsReported() {
		Map<String, Object> body = get(MONTHLY + "?id=" + EMPLOYEE_OPEN_FRESH, EMPLOYEE_OPEN_FRESH, 200);
		Map<?, ?> meta = (Map<?, ?>) body.get("meta");
		assertThat(meta.get("has_open_check_in")).isEqualTo(true);
	}

	@Test
	void aStaleOpenSessionIsAutoClosedBeforeTheRowsAreRead() {
		Map<String, Object> body = get(
				MONTHLY + "?id=" + EMPLOYEE_OPEN_STALE + "&month=1&year=2019", EMPLOYEE_OPEN_STALE, 200);
		List<Map<String, Object>> rows = dataRows(body);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("check_out")).isNotNull();
		// No shift assigned: expected falls back to 8h, closed at expected - 2h.
		assertThat(number(rows.get(0).get("duration_minutes"))).isEqualTo(360);

		Map<?, ?> meta = (Map<?, ?>) body.get("meta");
		assertThat(meta.get("has_open_check_in")).isEqualTo(false);
	}

	@Test
	void anEmployeeMayOnlyReadTheirOwnMonthlySummary() {
		get(MONTHLY + "?id=" + EMPLOYEE_2, EMPLOYEE_1, 403);
		get(MONTHLY + "?id=" + EMPLOYEE_1, EMPLOYEE_1, 200);
	}

	@Test
	void anExplicitZeroIdIsEmployeeIdRequired() {
		get(MONTHLY + "?id=0", ADMIN_1, 400);
	}

	@Test
	void aForeignOrMissingEmployeeIdIsNotFound() {
		get(MONTHLY + "?id=999999", ADMIN_1, 404);
		get(MONTHLY + "?id=" + EMPLOYEE_OTHER_CO, ADMIN_1, 404);
	}

	// ------------------------------------------------------------------
	// Guards
	// ------------------------------------------------------------------

	@Test
	void theMethodGuardRunsBeforeAuthenticationOnAllThreeEndpoints() {
		assertThat(anonymous(LIST, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(STATS, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(MONTHLY, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(LIST, HttpMethod.GET)).isEqualTo(401);
		assertThat(anonymous(STATS, HttpMethod.GET)).isEqualTo(401);
		assertThat(anonymous(MONTHLY, HttpMethod.GET)).isEqualTo(401);
	}

	@Test
	void everyRoleReachesAllThreeEndpoints() {
		for (long actor : new long[] { ADMIN_1, MANAGER_1, EMPLOYEE_1 }) {
			get(LIST, actor, 200);
			get(STATS, actor, 200);
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> dayFor(long employeeId, String date, String from, String to) {
		Map<String, Object> body = get(
				LIST + "?fill_days=1&employee_id=" + employeeId + "&date_from=" + from + "&date_to=" + to,
				ADMIN_1, 200);
		return dataRows(body).stream().filter(row -> date.equals(row.get("date"))).findFirst().orElseThrow();
	}

	private Map<String, Object> get(String path, long employeeId, int expectedStatus) {
		ResponseEntity<Map<String, Object>> response = send(path, HttpMethod.GET, employeeId);
		assertThat(response.getStatusCode().value()).as("%s: %s", path, response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private ResponseEntity<Map<String, Object>> send(String path, HttpMethod method, long employeeId) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		headers.set("Accept-Language", "en");
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(null, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private int anonymous(String path, HttpMethod method) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>("{}", headers),
				new ParameterizedTypeReference<Map<String, Object>>() { })
				.getStatusCode().value();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> dataRows(Map<String, Object> body) {
		return (List<Map<String, Object>>) body.get("data");
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == ADMIN_1 ? "company_admin"
				: "employee";
		long companyId = employeeId == EMPLOYEE_OTHER_CO
				|| employeeId == EMPLOYEE_OPEN_STALE || employeeId == EMPLOYEE_OPEN_FRESH
				? COMPANY_2 : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20900, 'Report Co', '+201000020900', 'active', '2025-01-15 09:00:00'),
					  (20901, 'Report Other Co', '+201000020901', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (209031, 20900, 'Main', 1, '2025-03-01 10:00:00'),
					  (209032, 20901, 'Other', 1, '2025-03-01 10:00:00'),
					  (209033, 20900, 'Secondary', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (209041, 20900, 'Ops', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO exception_types (id, company_id, name, is_active, created_at) VALUES
					  (209201, 20900, 'Sick Leave', 1, '2025-02-01 09:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active, created_at)
					VALUES (209301, 20900, 'Day Shift', '09:00:00', '17:00:00', 'saturday,sunday', 1,
					  '2025-01-01 09:00:00')
					""");
			st.execute("""
					INSERT INTO company_official_holidays (id, company_id, name, holiday_date, created_at) VALUES
					  (209401, 20900, 'Founders Day', '2020-01-09', '2019-12-01 09:00:00')
					""");

			employee(st, ADMIN_1, COMPANY_1, BRANCH_1, null, "company_admin", "+201000209011", "Adam", "Admin");
			employee(st, MANAGER_1, COMPANY_1, BRANCH_1, null, "manager", "+201000209013", "Maged", "Manager");
			employee(st, EMPLOYEE_1, COMPANY_1, BRANCH_1, DEPARTMENT_1, "employee", "+201000209014", "Ellie", "One");
			employee(st, EMPLOYEE_2, COMPANY_1, BRANCH_2, null, "employee", "+201000209015", "Emad", "Two");
			employee(
					st, EMPLOYEE_OPEN_STALE, COMPANY_2, 209032L, null, "employee", "+201000209016", "Sam", "Stale");
			employee(
					st, EMPLOYEE_OPEN_FRESH, COMPANY_2, 209032L, null, "employee", "+201000209017", "Fay", "Fresh");
			employee(st, EMPLOYEE_OTHER_CO, COMPANY_2, 209032L, null, "employee", "+201000209022", "Other", "Staff");

			st.execute("""
					INSERT INTO employee_shift_assignments (id, employee_id, shift_id, effective_from, created_at)
					VALUES (209311, 209014, 209301, '2019-01-01', '2019-01-01 09:00:00')
					""");

			attendance(st, ATT_MON_COMPLETE, EMPLOYEE_1,
					"'2020-01-06 09:00:00'", "'2020-01-06 17:00:00'", "NULL");
			attendance(st, ATT_WED_EXCEPTION, EMPLOYEE_1,
					"'2020-01-08 00:00:00'", "NULL", String.valueOf(EXCEPTION_TYPE_1));
			attendance(st, ATT_E2_FEB, EMPLOYEE_2,
					"'2020-02-01 09:00:00'", "'2020-02-01 17:00:00'", "NULL");
			attendance(st, ATT_OTHER_CO, EMPLOYEE_OTHER_CO,
					"'2020-01-06 09:00:00'", "'2020-01-06 17:00:00'", "NULL");

			DateTimeFormatter sql = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
			attendance(st, 209106L, EMPLOYEE_OPEN_STALE, "'2019-01-01 09:00:00'", "NULL", "NULL");
			attendance(st, 209107L, EMPLOYEE_OPEN_FRESH,
					"'" + LocalDateTime.now().minusMinutes(10).format(sql) + "'", "NULL", "NULL");
		}
	}

	private static void employee(
			Statement st, long id, long companyId, long branchId, Long departmentId, String role, String phone,
			String first, String last) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, department_id, employee_code, role,"
				+ " is_active, join_request_status, phone, first_name, last_name, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", " + (departmentId == null ? "NULL" : departmentId)
				+ ", '" + id + "', '" + role + "', 1, 'accepted', '" + phone + "', '" + first + "', '" + last
				+ "', '2025-01-20 09:00:00')");
	}

	private static void attendance(
			Statement st, long id, long employeeId, String checkIn, String checkOut, String exceptionTypeId)
			throws Exception {
		st.execute("INSERT INTO attendance (id, employee_id, check_in, check_out, method, exception_type_id)"
				+ " VALUES (" + id + ", " + employeeId + ", " + checkIn + ", " + checkOut + ", 'app', "
				+ exceptionTypeId + ")");
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
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream = LegacyAttendanceReportEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
