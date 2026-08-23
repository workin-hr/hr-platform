package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Wave 12.6.3: {@code attendance/check_in.php}, {@code check_in_qr.php} and
 * {@code check_out.php}.
 *
 * <p>The high-risk slice of the wave. Weighted towards authority, the geofence
 * and the write-bearing session lookup rather than towards scalar coercion,
 * which the shared primitives already own.
 *
 * <p>Geography: the fixture branch sits at (30.0, 31.0) with a 200 m radius.
 * (30.0, 31.001) is ~96 m away and inside; (30.0, 31.01) is ~963 m away and
 * outside. Distances are the measured haversine values.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyCheckInEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String CHECK_IN = "/apis/api/attendance/check_in.php";
	private static final String CHECK_IN_QR = "/apis/api/attendance/check_in_qr.php";
	private static final String CHECK_OUT = "/apis/api/attendance/check_out.php";

	private static final long COMPANY_1 = 21201L;
	private static final long COMPANY_2 = 21202L;

	private static final long ADMIN_1 = 212011L;
	private static final long HR_1 = 212012L;
	private static final long MANAGER_1 = 212013L;
	private static final long EMPLOYEE_1 = 212014L;
	private static final long EMPLOYEE_2 = 212015L;
	/** Mobile attendance disabled. */
	private static final long EMPLOYEE_NO_MOBILE = 212016L;
	/** Assigned to the branch with no GPS configured. */
	private static final long EMPLOYEE_UNLOCATED = 212018L;
	/** can_check_in_any_branch = 1. */
	private static final long EMPLOYEE_ANY_BRANCH = 212019L;
	private static final long EMPLOYEE_OTHER_CO = 212021L;

	private static final long BRANCH_1 = 21211L;
	private static final long BRANCH_UNLOCATED = 21212L;
	private static final long BRANCH_OTHER_CO = 21213L;
	private static final long BRANCH_SECOND = 21214L;

	private static final long SHIFT_1 = 21231L;

	private static final String INSIDE = "\"latitude\":30.0,\"longitude\":31.001";
	private static final String OUTSIDE = "\"latitude\":30.0,\"longitude\":31.01";

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
			throw new IllegalStateException("could not prepare the check-in fixture", ex);
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
		execute("DELETE FROM company_setting_values");
		execute("DELETE FROM company_settings");
		execute("DELETE FROM setting_allowed_values");
		execute("DELETE FROM setting_definitions");
		execute("UPDATE branches SET expires_at = NULL, qr_code = NULL");
	}

	// ------------------------------------------------------------------
	// Method and authentication
	// ------------------------------------------------------------------

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {CHECK_IN, CHECK_IN_QR, CHECK_OUT})
	void everyPresenceEndpointIsPostOnly(String path) {
		Map<String, Object> body = send(path, EMPLOYEE_1, HttpMethod.GET, "{}", 405);
		assertThat(body.get("message")).isEqualTo("Invalid method");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {CHECK_IN, CHECK_IN_QR, CHECK_OUT})
	void everyPresenceEndpointRequiresAToken(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.POST,
				new HttpEntity<>("{}", headers), mapType());
		assertThat(response.getStatusCode().value()).isEqualTo(401);
	}

	// ------------------------------------------------------------------
	// D-092: who may be targeted
	// ------------------------------------------------------------------

	/**
	 * D-092: an employee session may target only itself, and the refusal
	 * happens <b>before</b> any employee row is read.
	 *
	 * <p>The target here is a real colleague in the same company, so a
	 * post-lookup check would have answered 200. It is refused, and the refusal
	 * is indistinguishable from the one below for a foreign id -- which is the
	 * point: a 403 cannot be used to learn which ids exist.
	 */
	@Test
	void anEmployeeMayNotCheckInAColleague() {
		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1,
				"{\"employee_id\":" + EMPLOYEE_2 + "," + INSIDE + ",\"method\":\"app\"}", 403);

		assertThat(body.get("message")).isEqualTo("Forbidden");
		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	/** The same 403, for an id in another company -- no tenant existence leaked. */
	@Test
	void anEmployeeTargetingAForeignIdGetsTheSameForbidden() {
		Map<String, Object> foreign = post(CHECK_IN, EMPLOYEE_1,
				"{\"employee_id\":" + EMPLOYEE_OTHER_CO + "," + INSIDE + ",\"method\":\"app\"}", 403);
		Map<String, Object> colleague = post(CHECK_IN, EMPLOYEE_1,
				"{\"employee_id\":" + EMPLOYEE_2 + "," + INSIDE + ",\"method\":\"app\"}", 403);

		assertThat(foreign.get("message")).isEqualTo(colleague.get("message"));
	}

	/** COMPANY_ADMIN, HR and MANAGER may all act for another employee. */
	@ParameterizedTest(name = "actor {0}")
	@CsvSource({"212011", "212012", "212013"})
	void anAdministrativeRoleMayCheckInAnotherEmployee(long actor) {
		post(CHECK_IN, actor,
				"{\"employee_id\":" + EMPLOYEE_1 + "," + INSIDE + ",\"method\":\"app\"}", 200);

		assertThat(query("SELECT employee_id FROM attendance")).hasSize(1);
	}

	/** A target in another company is {@code invalid_employee}, after the role check. */
	@Test
	void anAdministrativeRoleCannotReachAnotherCompanysEmployee() {
		Map<String, Object> body = post(CHECK_IN, ADMIN_1,
				"{\"employee_id\":" + EMPLOYEE_OTHER_CO + "," + INSIDE + ",\"method\":\"app\"}", 404);

		assertThat(body.get("message")).isEqualTo("Invalid employee");
		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	// ------------------------------------------------------------------
	// The mobile-attendance gate, and its delegated bypass
	// ------------------------------------------------------------------

	/**
	 * The flag stops the employee, and only the employee.
	 *
	 * <p>{@code if ($is_self_attendance && empty($employee['is_mobile_attendance_enabled']))}
	 * -- so the same disabled employee is checked in successfully by an admin.
	 * That is the delegated bypass D-092 records, reproduced rather than closed.
	 */
	@Test
	void mobileAttendanceDisabledStopsTheEmployeeButNotAnAdmin() {
		Map<String, Object> refused = post(CHECK_IN, EMPLOYEE_NO_MOBILE,
				"{" + INSIDE + ",\"method\":\"app\"}", 403);
		assertThat(refused.get("message"))
				.isEqualTo("Mobile attendance is not enabled for this employee");
		assertThat(query("SELECT id FROM attendance")).isEmpty();

		post(CHECK_IN, ADMIN_1,
				"{\"employee_id\":" + EMPLOYEE_NO_MOBILE + "," + INSIDE + ",\"method\":\"app\"}", 200);
		assertThat(query("SELECT id FROM attendance")).hasSize(1);
	}

	// ------------------------------------------------------------------
	// required(), and the happy path
	// ------------------------------------------------------------------

	@ParameterizedTest(name = "missing {0}")
	@CsvSource({"latitude", "longitude", "method"})
	void checkInRequiresItsThreeFields(String field) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("latitude", "30.0");
		fields.put("longitude", "31.001");
		fields.put("method", "\"app\"");
		fields.remove(field);

		StringBuilder json = new StringBuilder("{");
		fields.forEach((key, value) -> json.append(json.length() > 1 ? "," : "")
				.append('"').append(key).append("\":").append(value));
		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1, json.append('}').toString(), 400);

		assertThat((String) body.get("message")).contains(field);
	}

	@Test
	void aSelfCheckInInsideTheZoneIsRecorded() {
		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1,
				"{" + INSIDE + ",\"method\":\"app\"}", 200);

		assertThat(body.get("message")).isEqualTo("Check-in recorded");
		Map<String, Object> data = dataOf(body);
		assertThat(data.keySet()).containsExactly("attendance_id", "time");

		List<Map<String, Object>> rows = query(
				"SELECT employee_id, method, latitude, longitude, check_out FROM attendance");
		assertThat(rows).hasSize(1);
		assertThat(number(rows.get(0).get("employee_id"))).isEqualTo(EMPLOYEE_1);
		assertThat(rows.get(0).get("method")).isEqualTo("app");
		assertThat(rows.get(0).get("check_out")).isNull();
		// The coordinates are stored as supplied, through the DECIMAL(10,7) column.
		assertThat(String.valueOf(rows.get(0).get("longitude"))).startsWith("31.0010");
	}

	// ------------------------------------------------------------------
	// The geofence
	// ------------------------------------------------------------------

	/** Outside the radius is a 400 that reports the distance and the radius. */
	@Test
	void aCheckInOutsideTheZoneReportsDistanceAndRadius() {
		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1,
				"{" + OUTSIDE + ",\"method\":\"app\"}", 400);

		assertThat((String) body.get("message"))
				.contains("Distance from branch: 963 m").contains("allowed range: 200 m");
		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	/**
	 * D-093: a coordinate of exactly zero is validated, not skipped.
	 *
	 * <p>Legacy guards the whole geofence with
	 * {@code !empty($lat) && !empty($lng)}, and {@code "0"} is PHP-empty -- so a
	 * client sending zeroes checked in from anywhere. D-093 removes that guard,
	 * so Null Island is now compared against the branch like any other point and
	 * refused. This is the accepted security divergence, and it is narrower than
	 * legacy.
	 */
	@Test
	void aZeroCoordinateIsValidatedRatherThanSkipped() {
		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1,
				"{\"latitude\":0,\"longitude\":0,\"method\":\"app\"}", 400);

		assertThat((String) body.get("message")).contains("outside the allowed attendance zone");
		assertThat(query("SELECT id FROM attendance")).isEmpty();
	}

	/**
	 * {@code require_location_configured} is {@code $is_self_attendance}, and
	 * the difference is visible on the same employee.
	 *
	 * <p>The employee's branch has no coordinates. Checking themselves in is a
	 * 403; the identical check-in performed by an admin succeeds, because the
	 * flag that makes the missing configuration fatal is the same flag that says
	 * "this is the employee acting on their own behalf".
	 */
	@Test
	void anUnconfiguredBranchStopsTheEmployeeAndNotTheAdmin() {
		Map<String, Object> refused = post(CHECK_IN, EMPLOYEE_UNLOCATED,
				"{" + INSIDE + ",\"method\":\"app\"}", 403);
		assertThat((String) refused.get("message")).contains("not set up for mobile attendance");

		post(CHECK_IN, ADMIN_1,
				"{\"employee_id\":" + EMPLOYEE_UNLOCATED + "," + INSIDE + ",\"method\":\"app\"}", 200);
		assertThat(query("SELECT id FROM attendance")).hasSize(1);
	}

	/**
	 * <b>"No assigned branch" is not reachable against the vendored schema</b>,
	 * which makes three helper branches dead code.
	 *
	 * <p>{@code employees.branch_id} is {@code int(10) UNSIGNED NOT NULL} and
	 * carries {@code fk_employee_branch} to {@code branches(id)}. So it can be
	 * neither NULL nor 0, and
	 * {@code normalize_optional_branch_id($employee['branch_id'])} can never
	 * return null for a real employee row. That makes all three of these
	 * unreachable:
	 *
	 * <ul>
	 *   <li>{@code validate_employee_attendance_location()}'s
	 *       {@code employee_branch_required} 403;</li>
	 *   <li>{@code employee_may_check_in_at_branch()}'s "no assigned branch, so
	 *       any active branch will do" arm;</li>
	 *   <li>{@code employee_can_check_in_any_branch()}'s no-column fallback,
	 *       which is defined entirely in terms of an absent branch.</li>
	 * </ul>
	 *
	 * <p>All three are still ported -- the schema is the reason they cannot run,
	 * not the code -- and this test pins the reason rather than leaving them
	 * looking like untested paths. Same shape as the {@code branch_id} NOT NULL
	 * finding Wave 12.6.1b hit on the import's create-mapping branch.
	 */
	@Test
	void anEmployeeWithNoAssignedBranchCannotExistInThisSchema() {
		assertThatThrownBy(() -> execute(
				"INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
						+ " last_name, phone, role, is_active, created_at) VALUES (212099, "
						+ COMPANY_1 + ", 0, 212099, 'No', 'Branch', '+201000212099', 'employee', 1,"
						+ " '2025-04-01 08:00:00')"))
				.hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
	}

	/** The any-branch flag lets an employee check in at a different branch's zone. */
	@Test
	void theAnyBranchFlagAcceptsAnotherBranchesZone() {
		// The second branch is at (31.0, 32.0); the employee's assigned one is not.
		post(CHECK_IN, EMPLOYEE_ANY_BRANCH,
				"{\"latitude\":31.0,\"longitude\":32.0,\"method\":\"app\"}", 200);

		assertThat(query("SELECT id FROM attendance")).hasSize(1);
	}

	// ------------------------------------------------------------------
	// Open sessions and the two-hour rule
	// ------------------------------------------------------------------

	@Test
	void aSecondCheckInWhileOneIsOpenIsRefused() {
		openSession(EMPLOYEE_1, "NOW() - INTERVAL 30 MINUTE");

		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1,
				"{" + INSIDE + ",\"method\":\"app\"}", 400);

		assertThat((String) body.get("message")).contains("Already checked in");
	}

	/**
	 * The two-hour rule, with its hard-coded Arabic reason.
	 *
	 * <p>The previous punch is <b>closed</b>, so this is not the open-session
	 * check -- it is the separate {@code TIMESTAMPDIFF} guard, and its 422
	 * carries a reason string that exists nowhere in the catalog.
	 */
	@Test
	void aSecondCheckInWithinTwoHoursOfAClosedOneIsUnprocessable() {
		execute("INSERT INTO attendance (employee_id, check_in, check_out, method) VALUES ("
				+ EMPLOYEE_1 + ", NOW() - INTERVAL 30 MINUTE, NOW() - INTERVAL 10 MINUTE, 'app')");

		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1,
				"{" + INSIDE + ",\"method\":\"app\"}", 422);

		assertThat(dataOf(body)).isNull();
		assertThat((String) body.get("message")).isNotBlank();
		assertThat(query("SELECT id FROM attendance")).hasSize(1);
	}

	/** More than two hours later is accepted. */
	@Test
	void aSecondCheckInAfterTwoHoursIsAccepted() {
		execute("INSERT INTO attendance (employee_id, check_in, check_out, method) VALUES ("
				+ EMPLOYEE_1 + ", NOW() - INTERVAL 5 HOUR, NOW() - INTERVAL 4 HOUR, 'app')");

		post(CHECK_IN, EMPLOYEE_1, "{" + INSIDE + ",\"method\":\"app\"}", 200);

		assertThat(query("SELECT id FROM attendance")).hasSize(2);
	}

	/**
	 * An exception-only row is not an open session and does not block a
	 * check-in.
	 *
	 * <p>Midnight check-in, an exception type, no check-out: that is how the
	 * import writes a leave marker. It must not read as "already checked in",
	 * and it must not be auto-closed either.
	 */
	@Test
	void anExceptionOnlyRowNeitherBlocksNorIsClosed() {
		long exceptionTypeId = exceptionType();
		execute("INSERT INTO attendance (employee_id, check_in, method, exception_type_id) VALUES ("
				+ EMPLOYEE_1 + ", DATE_SUB(CURDATE(), INTERVAL 3 DAY), 'excel', " + exceptionTypeId + ")");

		post(CHECK_IN, EMPLOYEE_1, "{" + INSIDE + ",\"method\":\"app\"}", 200);

		List<Map<String, Object>> rows = query(
				"SELECT check_out FROM attendance WHERE exception_type_id IS NOT NULL");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("check_out")).describedAs("the marker stays open").isNull();
	}

	// ------------------------------------------------------------------
	// The stale-session auto-close, and its durability
	// ------------------------------------------------------------------

	/**
	 * A stale open session is closed by the <em>next</em> request, at
	 * check-in + (expected - 120) minutes.
	 *
	 * <p>The employee has an 8-hour shift, so the synthetic check-out is six
	 * hours after the check-in -- not "now", and not the shift end.
	 */
	@Test
	void aStaleOpenSessionIsClosedAtExpectedMinusTwoHours() {
		assignShift(EMPLOYEE_1, SHIFT_1, "2020-01-01");
		openSession(EMPLOYEE_1, "NOW() - INTERVAL 10 DAY");

		post(CHECK_IN, EMPLOYEE_1, "{" + INSIDE + ",\"method\":\"app\"}", 200);

		List<Map<String, Object>> rows = query(
				"SELECT TIMESTAMPDIFF(MINUTE, check_in, check_out) AS m FROM attendance"
						+ " WHERE check_out IS NOT NULL");
		assertThat(rows).hasSize(1);
		// 09:00-17:00 is 480 expected minutes; 480 - 120 = 360.
		assertThat(number(rows.get(0).get("m"))).isEqualTo(360L);
	}

	/**
	 * <b>The partial-write proof.</b> The auto-close survives a request that
	 * then fails.
	 *
	 * <p>{@code attendance_find_open_session()} auto-closes before the endpoint
	 * reaches the geofence, and PHP opens no transaction anywhere on the path.
	 * So a stale session is closed, the check-in is then refused for being out
	 * of range, and the close is <b>committed</b> while no new row exists. A
	 * port that wrapped the endpoint in a transaction would roll the close back
	 * and diverge.
	 */
	@Test
	void aFailedCheckInStillLeavesTheAutoCloseCommitted() {
		assignShift(EMPLOYEE_1, SHIFT_1, "2020-01-01");
		openSession(EMPLOYEE_1, "NOW() - INTERVAL 10 DAY");

		post(CHECK_IN, EMPLOYEE_1, "{" + OUTSIDE + ",\"method\":\"app\"}", 400);

		List<Map<String, Object>> rows = query("SELECT check_out FROM attendance");
		assertThat(rows).describedAs("no new row was inserted").hasSize(1);
		assertThat(rows.get(0).get("check_out"))
				.describedAs("but the stale session stayed closed").isNotNull();
	}

	/**
	 * A rest day pushes the deadline past it, so an open session across a
	 * weekly rest day is <b>not</b> stale.
	 *
	 * <p>The deadline is the next working day's shift start. With the company's
	 * {@code WEEKLY_OFF_DAYS} covering every day, no candidate in the eight-day
	 * scan qualifies and the 18-hour fallback applies instead -- which a
	 * two-hour-old session is still inside.
	 */
	@Test
	void anOpenSessionAcrossRestDaysIsNotStale() {
		assignShift(EMPLOYEE_1, SHIFT_1, "2020-01-01");
		weeklyOffDays(COMPANY_1, "sunday", "monday", "tuesday", "wednesday", "thursday",
				"friday", "saturday");
		openSession(EMPLOYEE_1, "NOW() - INTERVAL 2 HOUR");

		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1,
				"{" + INSIDE + ",\"method\":\"app\"}", 400);

		assertThat((String) body.get("message")).contains("Already checked in");
		assertThat(query("SELECT check_out FROM attendance").get(0).get("check_out")).isNull();
	}

	/**
	 * An official holiday is a rest day for the deadline too, and it takes
	 * precedence over the shift's own hours.
	 */
	@Test
	void anOfficialHolidayCountsAsARestDayForTheDeadline() {
		assignShift(EMPLOYEE_1, SHIFT_1, "2020-01-01");
		// Every day of the next fortnight is a holiday, so the scan finds no
		// working day and falls back to 18 hours.
		for (int day = 0; day <= 14; day++) {
			execute("INSERT INTO company_official_holidays (company_id, name, holiday_date)"
					+ " VALUES (" + COMPANY_1 + ", 'Eid', DATE_ADD(CURDATE(), INTERVAL " + day + " DAY))");
		}
		openSession(EMPLOYEE_1, "NOW() - INTERVAL 2 HOUR");

		Map<String, Object> body = post(CHECK_IN, EMPLOYEE_1,
				"{" + INSIDE + ",\"method\":\"app\"}", 400);

		assertThat((String) body.get("message")).contains("Already checked in");
	}

	/**
	 * With no shift assigned, the expected day comes from the coalesce chain,
	 * and the auto-close uses it.
	 *
	 * <p>{@code expected_daily_hours} is 3, so expected is 180 minutes and the
	 * synthetic check-out is 60 minutes after the check-in.
	 */
	@Test
	void theExpectedDailyHoursFallbackDrivesTheAutoClose() {
		execute("UPDATE employees SET expected_daily_hours = 3 WHERE id = " + EMPLOYEE_1);
		openSession(EMPLOYEE_1, "NOW() - INTERVAL 10 DAY");

		post(CHECK_IN, EMPLOYEE_1, "{" + INSIDE + ",\"method\":\"app\"}", 200);

		List<Map<String, Object>> rows = query(
				"SELECT TIMESTAMPDIFF(MINUTE, check_in, check_out) AS m FROM attendance"
						+ " WHERE check_out IS NOT NULL");
		assertThat(number(rows.get(0).get("m"))).isEqualTo(60L);
		execute("UPDATE employees SET expected_daily_hours = NULL WHERE id = " + EMPLOYEE_1);
	}

	// ------------------------------------------------------------------
	// QR check-in
	// ------------------------------------------------------------------

	@Test
	void aValidQrCodeRecordsAQrCheckIn() {
		qrCode(BRANCH_1, "QR-OK", "NOW() + INTERVAL 1 HOUR");

		Map<String, Object> body = post(CHECK_IN_QR, EMPLOYEE_1, "{\"qr_code\":\"QR-OK\"}", 200);

		assertThat(body.get("message")).isEqualTo("QR Check-in recorded");
		List<Map<String, Object>> rows = query("SELECT method, latitude FROM attendance");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("method")).isEqualTo("qr");
		// No GPS is written at all: the QR is the proof of presence.
		assertThat(rows.get(0).get("latitude")).isNull();
	}

	/** An expired code is {@code invalid_qr}; the expiry is compared in the database. */
	@Test
	void anExpiredQrCodeIsRefused() {
		qrCode(BRANCH_1, "QR-OLD", "NOW() - INTERVAL 1 MINUTE");

		Map<String, Object> body = post(CHECK_IN_QR, EMPLOYEE_1, "{\"qr_code\":\"QR-OLD\"}", 400);

		assertThat(body.get("message")).isEqualTo("Invalid or expired QR code");
	}

	/** Another company's QR code is not visible, even with a valid expiry. */
	@Test
	void aForeignCompanysQrCodeIsInvalid() {
		qrCode(BRANCH_OTHER_CO, "QR-FOREIGN", "NOW() + INTERVAL 1 HOUR");

		Map<String, Object> body = post(CHECK_IN_QR, EMPLOYEE_1, "{\"qr_code\":\"QR-FOREIGN\"}", 400);

		assertThat(body.get("message")).isEqualTo("Invalid or expired QR code");
	}

	/**
	 * The QR code is checked <b>before</b> the employee, so a bad code plus a
	 * bad employee is a QR error.
	 *
	 * <p>Ordering, not a coincidence: it means an attacker with a valid session
	 * cannot use this endpoint to enumerate employee ids, because every attempt
	 * without a live code fails identically.
	 */
	@Test
	void theQrCodeIsValidatedBeforeTheEmployee() {
		Map<String, Object> body = post(CHECK_IN_QR, ADMIN_1,
				"{\"qr_code\":\"NOPE\",\"employee_id\":" + EMPLOYEE_OTHER_CO + "}", 400);

		assertThat(body.get("message")).isEqualTo("Invalid or expired QR code");
	}

	/** An employee assigned elsewhere cannot use this branch's code. */
	@Test
	void anEmployeeOfAnotherBranchIsRefused() {
		qrCode(BRANCH_SECOND, "QR-SECOND", "NOW() + INTERVAL 1 HOUR");

		Map<String, Object> body = post(CHECK_IN_QR, EMPLOYEE_1, "{\"qr_code\":\"QR-SECOND\"}", 400);

		assertThat(body.get("message")).isEqualTo("Employee not assigned to this branch");
	}

	/** The any-branch flag makes any active branch's code usable. */
	@Test
	void theAnyBranchFlagAcceptsAnotherBranchesQrCode() {
		qrCode(BRANCH_SECOND, "QR-SECOND", "NOW() + INTERVAL 1 HOUR");

		post(CHECK_IN_QR, EMPLOYEE_ANY_BRANCH, "{\"qr_code\":\"QR-SECOND\"}", 200);

		assertThat(query("SELECT id FROM attendance")).hasSize(1);
	}

	/**
	 * QR has <b>no</b> two-hour rule.
	 *
	 * <p>The same history that makes an ordinary check-in a 422 is accepted
	 * here. Two endpoints, one guard, and it is only on one of them.
	 */
	@Test
	void qrCheckInHasNoTwoHourRule() {
		qrCode(BRANCH_1, "QR-OK", "NOW() + INTERVAL 1 HOUR");
		execute("INSERT INTO attendance (employee_id, check_in, check_out, method) VALUES ("
				+ EMPLOYEE_1 + ", NOW() - INTERVAL 30 MINUTE, NOW() - INTERVAL 10 MINUTE, 'app')");

		post(CHECK_IN_QR, EMPLOYEE_1, "{\"qr_code\":\"QR-OK\"}", 200);

		assertThat(query("SELECT id FROM attendance")).hasSize(2);
	}

	/** QR runs no geofence: an employee anywhere on earth passes with a live code. */
	@Test
	void qrCheckInRunsNoGeofence() {
		qrCode(BRANCH_1, "QR-OK", "NOW() + INTERVAL 1 HOUR");

		post(CHECK_IN_QR, EMPLOYEE_1, "{\"qr_code\":\"QR-OK\"}", 200);

		assertThat(query("SELECT id FROM attendance")).hasSize(1);
	}

	/** And no mobile-attendance gate either. */
	@Test
	void qrCheckInIgnoresTheMobileAttendanceFlag() {
		qrCode(BRANCH_1, "QR-OK", "NOW() + INTERVAL 1 HOUR");

		post(CHECK_IN_QR, EMPLOYEE_NO_MOBILE, "{\"qr_code\":\"QR-OK\"}", 200);

		assertThat(query("SELECT id FROM attendance")).hasSize(1);
	}

	// ------------------------------------------------------------------
	// Check-out
	// ------------------------------------------------------------------

	@Test
	void checkOutWithNoOpenSessionIsRefused() {
		Map<String, Object> body = post(CHECK_OUT, EMPLOYEE_1, "{}", 400);

		assertThat(body.get("message")).isEqualTo("No open check-in found");
	}

	@Test
	void aSelfCheckOutClosesTheOpenSessionAndReportsTheDuration() {
		openSessionWithCoordinates(EMPLOYEE_1, "NOW() - INTERVAL 90 MINUTE", "30.0", "31.001");

		Map<String, Object> body = post(CHECK_OUT, EMPLOYEE_1, "{" + INSIDE + "}", 200);

		assertThat(body.get("message")).isEqualTo("Check-out recorded");
		Map<String, Object> data = dataOf(body);
		assertThat(data.keySet()).containsExactly("duration_minutes", "time");
		assertThat(number(data.get("duration_minutes"))).isBetween(89L, 91L);
		assertThat(query("SELECT check_out FROM attendance").get(0).get("check_out")).isNotNull();
	}

	/**
	 * A check-out with no GPS reuses the <b>check-in's</b> coordinates.
	 *
	 * <p>Which means a check-out from anywhere passes the geofence, as long as
	 * the check-in was inside it. Legacy behaviour, reproduced -- and a real
	 * hole worth seeing in a test rather than discovering in production.
	 */
	@Test
	void aCheckOutWithNoGpsFallsBackToTheCheckInCoordinates() {
		openSessionWithCoordinates(EMPLOYEE_1, "NOW() - INTERVAL 90 MINUTE", "30.0", "31.001");

		post(CHECK_OUT, EMPLOYEE_1, "{}", 200);

		assertThat(query("SELECT check_out FROM attendance").get(0).get("check_out")).isNotNull();
	}

	/** With neither body nor stored coordinates, the GPS requirement bites. */
	@Test
	void aCheckOutWithNoGpsAnywhereIsRefused() {
		openSession(EMPLOYEE_1, "NOW() - INTERVAL 90 MINUTE");

		Map<String, Object> body = post(CHECK_OUT, EMPLOYEE_1, "{}", 400);

		assertThat((String) body.get("message")).contains("Location is required");
		assertThat(query("SELECT check_out FROM attendance").get(0).get("check_out")).isNull();
	}

	/** A supplied out-of-range coordinate refuses the check-out. */
	@Test
	void aSelfCheckOutOutsideTheZoneIsRefused() {
		openSessionWithCoordinates(EMPLOYEE_1, "NOW() - INTERVAL 90 MINUTE", "30.0", "31.001");

		Map<String, Object> body = post(CHECK_OUT, EMPLOYEE_1, "{" + OUTSIDE + "}", 400);

		assertThat((String) body.get("message")).contains("outside the allowed attendance zone");
		assertThat(query("SELECT check_out FROM attendance").get(0).get("check_out")).isNull();
	}

	/**
	 * An admin checking an employee out runs <b>no</b> geofence at all.
	 *
	 * <p>{@code if ($is_self_attendance)} wraps the whole validation, so a
	 * delegated check-out never parses coordinates and never compares them --
	 * which is why it succeeds here with no GPS anywhere, where the employee's
	 * own attempt is refused.
	 */
	@Test
	void aDelegatedCheckOutSkipsTheGeofenceEntirely() {
		openSession(EMPLOYEE_1, "NOW() - INTERVAL 90 MINUTE");

		post(CHECK_OUT, ADMIN_1, "{\"employee_id\":" + EMPLOYEE_1 + "}", 200);

		assertThat(query("SELECT check_out FROM attendance").get(0).get("check_out")).isNotNull();
	}

	/** D-092 applies to check-out too. */
	@Test
	void anEmployeeMayNotCheckOutAColleague() {
		openSession(EMPLOYEE_2, "NOW() - INTERVAL 90 MINUTE");

		post(CHECK_OUT, EMPLOYEE_1, "{\"employee_id\":" + EMPLOYEE_2 + "}", 403);

		assertThat(query("SELECT check_out FROM attendance").get(0).get("check_out")).isNull();
	}

	// ------------------------------------------------------------------
	// Fixtures and helpers
	// ------------------------------------------------------------------

	private static void openSession(long employeeId, String checkInExpression) {
		execute("INSERT INTO attendance (employee_id, check_in, method) VALUES ("
				+ employeeId + ", " + checkInExpression + ", 'app')");
	}

	private static void openSessionWithCoordinates(
			long employeeId, String checkInExpression, String latitude, String longitude) {
		execute("INSERT INTO attendance (employee_id, check_in, method, latitude, longitude) VALUES ("
				+ employeeId + ", " + checkInExpression + ", 'app', " + latitude + ", " + longitude + ")");
	}

	private static void assignShift(long employeeId, long shiftId, String effectiveFrom) {
		execute("INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from)"
				+ " VALUES (" + employeeId + ", " + shiftId + ", '" + effectiveFrom + "')");
	}

	private static void qrCode(long branchId, String code, String expiryExpression) {
		execute("UPDATE branches SET qr_code = '" + code + "', expires_at = " + expiryExpression
				+ " WHERE id = " + branchId);
	}

	private static long exceptionType() {
		execute("INSERT INTO exception_types (company_id, name, is_active) VALUES ("
				+ COMPANY_1 + ", 'Leave', 1)");
		return number(query("SELECT id FROM exception_types ORDER BY id DESC LIMIT 1")
				.get(0).get("id"));
	}

	private static void weeklyOffDays(long companyId, String... days) {
		execute("INSERT INTO setting_definitions (id, setting_key, is_multi) VALUES"
				+ " (900, 'WEEKLY_OFF_DAYS', 1)");
		execute("INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES"
				+ " (900, " + companyId + ", 900)");
		int id = 900;
		for (String day : days) {
			id++;
			execute("INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order)"
					+ " VALUES (" + id + ", 900, '" + day + "', 0)");
			execute("INSERT INTO company_setting_values (company_setting_id, setting_allowed_value_id)"
					+ " VALUES (900, " + id + ")");
		}
	}

	private Map<String, Object> post(String path, long actor, String json, int expectedStatus) {
		return send(path, actor, HttpMethod.POST, json, expectedStatus);
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
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == HR_1 ? "hr"
				: employeeId == ADMIN_1 ? "company_admin"
				: "employee";
		long companyId = employeeId == EMPLOYEE_OTHER_CO ? COMPANY_2 : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	/**
	 * Fixture writes run on the <b>same session zone the application uses</b>.
	 *
	 * <p>D-099 puts the application's connections on +02:00. A fixture writing
	 * {@code NOW() - INTERVAL 30 MINUTE} through a default-zone connection would
	 * be two hours out relative to the application's clock, and every
	 * time-relative expectation here -- the two-hour rule, QR expiry, session
	 * staleness -- would be measuring the mismatch rather than the behaviour.
	 * Production's writer is PHP on the same offset, so this is the faithful
	 * fixture, not a workaround.
	 */
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
					  (21201, 'Presence Co', '+201000021201', 'active', '2025-01-15 09:00:00'),
					  (21202, 'Presence Other Co', '+201000021202', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("INSERT INTO branches (id, company_id, name, latitude, longitude,"
					+ " radius_meters, is_active, created_at) VALUES (" + BRANCH_1 + ", " + COMPANY_1
					+ ", 'Main', 30.0000000, 31.0000000, 200, 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, latitude, longitude,"
					+ " radius_meters, is_active, created_at) VALUES (" + BRANCH_UNLOCATED + ", "
					+ COMPANY_1 + ", 'No GPS', NULL, NULL, 200, 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, latitude, longitude,"
					+ " radius_meters, is_active, created_at) VALUES (" + BRANCH_SECOND + ", "
					+ COMPANY_1 + ", 'Second', 31.0000000, 32.0000000, 200, 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, latitude, longitude,"
					+ " radius_meters, is_active, created_at) VALUES (" + BRANCH_OTHER_CO + ", "
					+ COMPANY_2 + ", 'Other', 30.0000000, 31.0000000, 200, 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_1 + ", " + COMPANY_1
					+ ", 'Day', '09:00:00', '17:00:00', 1, '2025-03-02 10:00:00')");

			employee(st, ADMIN_1, COMPANY_1, BRANCH_1, "company_admin", "+201000212011", 1, 0);
			employee(st, HR_1, COMPANY_1, BRANCH_1, "hr", "+201000212012", 1, 0);
			employee(st, MANAGER_1, COMPANY_1, BRANCH_1, "manager", "+201000212013", 1, 0);
			employee(st, EMPLOYEE_1, COMPANY_1, BRANCH_1, "employee", "+201000212014", 1, 0);
			employee(st, EMPLOYEE_2, COMPANY_1, BRANCH_1, "employee", "+201000212015", 1, 0);
			employee(st, EMPLOYEE_NO_MOBILE, COMPANY_1, BRANCH_1, "employee", "+201000212016", 0, 0);
			employee(st, EMPLOYEE_UNLOCATED, COMPANY_1, BRANCH_UNLOCATED, "employee",
					"+201000212018", 1, 0);
			employee(st, EMPLOYEE_ANY_BRANCH, COMPANY_1, BRANCH_1, "employee", "+201000212019", 1, 1);
			employee(st, EMPLOYEE_OTHER_CO, COMPANY_2, BRANCH_OTHER_CO, "employee",
					"+201000212021", 1, 0);
		}
	}

	private static void employee(Statement st, long id, long companyId, Long branchId, String role,
			String phone, int mobileEnabled, int anyBranch) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, is_mobile_attendance_enabled,"
				+ " can_check_in_any_branch, created_at) VALUES (" + id + ", " + companyId + ", "
				+ branchId + ", " + id + ", 'First', 'Last', '" + phone
				+ "', '" + role + "', 1, " + mobileEnabled + ", " + anyBranch
				+ ", '2025-04-01 08:00:00')");
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
		try (InputStream stream = LegacyCheckInEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
