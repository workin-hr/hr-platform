package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Wave 12.6 slice 1a-ii: {@code attendance/create.php} and
 * {@code attendance/update.php}.
 *
 * <p>Separate from the closed 1a-i class on purpose. The behaviours here are
 * the ones a clean-room port normalises away: a parser used only for
 * control flow while raw strings reach the database, an update endpoint that
 * can delete its own row, and D-095's late fail-closed preflight.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyAttendanceMutationEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String CREATE = "/apis/api/attendance/create.php";
	private static final String UPDATE = "/apis/api/attendance/update.php";

	/** The exact literal from {@code create.php:96}, as codepoints. */
	private static final String ARABIC_DUPLICATE_REASON =
			"لا يمكن تسجيل "
					+ "بصمتين متتاليتين "
					+ "خلال اقل من ساعتين";

	private static final long COMPANY_1 = 20901L;
	private static final long COMPANY_2 = 20902L;

	private static final long ADMIN_1 = 209011L;
	private static final long HR_1 = 209012L;
	private static final long MANAGER_1 = 209013L;
	private static final long EMPLOYEE_1 = 209014L;
	private static final long EMPLOYEE_FRESH = 209015L;
	private static final long EMPLOYEE_ODD = 209016L;
	private static final long EMPLOYEE_GAP = 209017L;
	private static final long ADMIN_2 = 209021L;
	private static final long EMPLOYEE_OTHER_CO = 209022L;

	private static final long EXC_ACTIVE = 209201L;
	private static final long EXC_INACTIVE = 209202L;
	private static final long EXC_OTHER_COMPANY = 209203L;

	private static final long ATT_INHERITED_FOREIGN = 209301L;
	private static final long ATT_OTHER_COMPANY = 209302L;

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
			throw new IllegalStateException("could not prepare the attendance mutation fixture", ex);
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
	// create.php
	// ------------------------------------------------------------------

	@Test
	void createStoresAnOrdinaryRealPunch() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_FRESH, "\"check_in\":\"2026-01-15 09:30:00\","
						+ "\"check_out\":\"2026-01-15 17:30:00\""));
		assertThat(response.getStatusCode().value()).isEqualTo(201);

		Map<String, Object> data = dataOf(response.getBody());
		assertThat(data.get("check_in")).isEqualTo("2026-01-15 09:30:00");
		assertThat(data.get("check_out")).isEqualTo("2026-01-15 17:30:00");
		assertThat(number(data.get("duration_minutes"))).isEqualTo(480);
		assertThat(data.get("method")).isEqualTo("app");
	}

	@Test
	void aDateOnlyCheckInIsNotARealPunchAndNeedsAnExceptionOrCheckOut() {
		// Midnight => not a real punch. With no exception and no check_out the
		// else-branch runs and stores it raw anyway.
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_FRESH2, "\"check_in\":\"2027-02-01\""));
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		// MariaDB widens a date-only string to midnight.
		assertThat(dataOf(response.getBody()).get("check_in")).isEqualTo("2027-02-01 00:00:00");
	}

	@Test
	void anExceptionOnlyRowIsNormalisedToMidnight() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_FRESH3, "\"check_in\":\"2027-03-05\",\"exception_type_id\":" + EXC_ACTIVE));
		assertThat(response.getStatusCode().value()).isEqualTo(201);

		Map<String, Object> data = dataOf(response.getBody());
		// The one deliberate normalization: PHP formats the anchor day itself.
		assertThat(data.get("check_in")).isEqualTo("2027-03-05 00:00:00");
		assertThat(data.get("check_out")).isNull();
		assertThat(data.get("exception_type_name")).isEqualTo("Late Arrival");
	}

	/**
	 * The rule the measurements exist for: {@code strtotime()} classifies, and
	 * MariaDB stores the raw string. They disagree, and both behaviours are
	 * legacy's.
	 *
	 * <p>Measured under {@code sql_mode=''}: {@code '1990'} and {@code '0830'}
	 * store {@code 0000-00-00 00:00:00} while the parser reads each as a
	 * non-midnight instant -- so they take the real-punch branch and then
	 * persist as the zero date. Substituting the parsed value would repair a
	 * row legacy does not repair.
	 */
	@Test
	void anOddRawCheckInIsClassifiedByTheParserAndPersistedByMariaDb() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_ODD, "\"check_in\":\"1990\""));
		assertThat(response.getStatusCode().value()).isEqualTo(201);

		// Not 1990-<today> <time>: the raw string does not coerce.
		assertThat(dataOf(response.getBody()).get("check_in")).isEqualTo("0000-00-00 00:00:00");
	}

	/**
	 * {@code $has_real_punch = $is_real_punch_in || $has_check_out} -- a
	 * nonblank {@code check_out} is a real-punch signal without ever being
	 * parsed. Measured: {@code 'oops'} then stores as the zero date.
	 */
	@Test
	void aNonblankOddCheckOutSignalsARealPunchAndStoresRaw() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_ODD2, "\"check_in\":\"2028-04-01\",\"check_out\":\"oops\","
						+ "\"exception_type_id\":" + EXC_ACTIVE));
		assertThat(response.getStatusCode().value()).isEqualTo(201);

		Map<String, Object> data = dataOf(response.getBody());
		// has_check_out made it a real punch, so the exception-only branch was
		// NOT taken: check_in stays the raw date-only value...
		assertThat(data.get("check_in")).isEqualTo("2028-04-01 00:00:00");
		// ...and the garbage check_out is stored, not validated away.
		assertThat(data.get("check_out")).isEqualTo("0000-00-00 00:00:00");
	}

	@Test
	void onlyACheckOutStillRequiresACheckIn() {
		// has_check_out alone makes has_real_punch true, so the else-branch
		// runs and its first act is the check_in guard.
		Map<String, Object> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_FRESH4, "\"check_out\":\"2026-01-15 17:00:00\"")).getBody();
		assertThat(response.get("success")).isEqualTo(false);
		assertThat(response).doesNotContainKey("data");
	}

	@Test
	void noPunchAndNoExceptionIsFieldRequired() {
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1, body(EMPLOYEE_FRESH4, "")))).isEqualTo(400);
	}

	@Test
	void theExceptionTypeMustBeSameCompanyAndActiveOnCreate() {
		// create.php uses exception_type_validate_id_for_company: same company
		// AND active. A null result is a 422.
		for (long bad : new long[] { EXC_INACTIVE, EXC_OTHER_COMPANY, 999_999L }) {
			assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
					body(EMPLOYEE_FRESH4, "\"check_in\":\"2029-01-01\",\"exception_type_id\":" + bad))))
					.describedAs("exception %d", bad).isEqualTo(422);
		}
	}

	@Test
	void createRejectsAForeignEmployee() {
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_OTHER_CO, "\"check_in\":\"2026-01-15 09:00:00\"")))).isEqualTo(400);
	}

	@Test
	void onlyCompanyAdminAndHrMayCreate() {
		assertThat(status(send(CREATE, HttpMethod.POST, MANAGER_1,
				body(EMPLOYEE_FRESH4, "\"check_in\":\"2026-01-15 09:00:00\"")))).isEqualTo(403);
		assertThat(status(send(CREATE, HttpMethod.POST, EMPLOYEE_1,
				body(EMPLOYEE_FRESH4, "\"check_in\":\"2026-01-15 09:00:00\"")))).isEqualTo(403);
	}

	/**
	 * {@code TIMESTAMPDIFF(MINUTE, last, candidate)} and the exact predicate
	 * {@code >= 0 && < 120}: a negative gap passes, 0 and 119 are refused, 120
	 * passes.
	 */
	@Test
	void theDuplicateWindowIsHalfOpenAtBothEnds() {
		// Seeded prior punch for EMPLOYEE_GAP: 2026-06-01 09:00:00.
		// -60 minutes: negative difference passes.
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_GAP, "\"check_in\":\"2026-06-01 08:00:00\"")))).isEqualTo(201);

		// exactly 0 -- refused
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_GAP2, "\"check_in\":\"2026-06-01 09:00:00\"")))).isEqualTo(422);
		// 119 -- refused
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_GAP2, "\"check_in\":\"2026-06-01 10:59:00\"")))).isEqualTo(422);
		// exactly 120 -- allowed
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_GAP2, "\"check_in\":\"2026-06-01 11:00:00\"")))).isEqualTo(201);
	}

	/**
	 * The Arabic duplicate reason is preserved in the port -- and is
	 * <b>unobservable on the wire</b>, which is why this asserts the message
	 * rather than the string.
	 *
	 * <p>{@code fail(INVALID_INPUT, 422, null, [Response::REASON => '...'])}
	 * passes the text as {@code $replace}, a placeholder map. The catalog entry
	 * for {@code invalid_input} is plain -- {@code 'Invalid input'} in
	 * {@code en.php:303}, {@code 'إدخال غير صالح'} in {@code ar.php:306} -- with
	 * no {@code {reason}} token, so there is nothing for the substitution to
	 * fill. Other keys do carry {@code {reason}} ({@code company_rejected},
	 * {@code employees_excel_invalid_template}); this one does not.
	 *
	 * <p>So legacy answers a bare {@code Invalid input} at 422 and the Arabic
	 * sentence never reaches the client. Reproduced exactly: the constant is
	 * kept byte-for-byte so the port matches the source, and the assertion
	 * measures what a caller actually sees.
	 */
	@Test
	void theDuplicateFailureIsABareInvalidInputAt422() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_GAP3, "\"check_in\":\"2026-06-01 09:30:00\""));

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		assertThat(response.getBody().get("message")).isEqualTo("Invalid input");
		// The reason is a $replace entry with no matching placeholder, so it is
		// neither substituted into the message nor returned as data.
		assertThat(response.getBody()).doesNotContainKey("data");
		assertThat(String.valueOf(response.getBody())).doesNotContain(ARABIC_DUPLICATE_REASON);
	}

	/**
	 * Measured: {@code TIMESTAMPDIFF} against an uncoercible candidate returns
	 * SQL NULL, and PHP's {@code (int) null} is <b>0</b> -- which is inside
	 * {@code [0, 120)}. So an odd raw check_in is refused as a duplicate when
	 * the employee already has a punch, rather than slipping through.
	 */
	@Test
	void anUncoercibleCandidateCountsAsZeroMinutesAndIsRefused() {
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_GAP4, "\"check_in\":\"oops\"")))).isEqualTo(422);
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				body(EMPLOYEE_GAP4, "\"check_in\":\"1990\"")))).isEqualTo(422);
	}

	// ------------------------------------------------------------------
	// update.php
	// ------------------------------------------------------------------

	@Test
	void anEmptyBodyRewritesTheStoredValuesAndSucceeds() {
		long id = insertAttendance(EMPLOYEE_1, "2026-07-01 09:00:00", "2026-07-01 17:00:00", null);
		Map<String, Object> before = attendanceRow(id);

		assertThat(status(send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1, "{}"))).isEqualTo(200);
		// No nothing_to_update branch exists here.
		assertThat(attendanceRow(id)).isEqualTo(before);
	}

	@Test
	void clearingBothPunchesWithoutAnExceptionDeletesTheRow() {
		long id = insertAttendance(EMPLOYEE_1, "2026-07-02 09:00:00", "2026-07-02 17:00:00", null);

		Map<String, Object> body = send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1,
				"{\"clear_check_in\":1,\"clear_check_out\":1}").getBody();

		assertThat(body.get("success")).isEqualTo(true);
		// ok(ATTENDANCE_RECORD_UPDATED, null) -- the data key is omitted.
		assertThat(body.keySet()).containsExactly("success", "message");
		assertThat(attendanceRow(id)).isNull();
	}

	@Test
	void clearingThePunchButKeepingAnExceptionConvertsToTheExistingDayAtMidnight() {
		long id = insertAttendance(EMPLOYEE_1, "2026-07-03 14:25:00", "2026-07-03 18:00:00", EXC_ACTIVE);

		assertThat(status(send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1,
				"{\"clear_check_in\":1,\"clear_check_out\":1}"))).isEqualTo(200);

		Map<String, Object> row = attendanceRow(id);
		assertThat(String.valueOf(row.get("check_in"))).isEqualTo("2026-07-03 00:00:00");
		assertThat(row.get("check_out")).isNull();
	}

	@Test
	void theClearFlagsFollowPhpEmptySemantics() {
		long id = insertAttendance(EMPLOYEE_1, "2026-07-04 09:00:00", "2026-07-04 17:00:00", null);

		// !empty(): 0, "0", "" and false do NOT clear.
		for (String falsy : new String[] { "0", "\"0\"", "\"\"", "false", "null" }) {
			send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1, "{\"clear_check_out\":" + falsy + "}");
			assertThat(attendanceRow(id).get("check_out"))
					.describedAs("clear_check_out=%s", falsy).isNotNull();
		}
		// ...while a truthy value does.
		send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1, "{\"clear_check_out\":1}");
		assertThat(attendanceRow(id).get("check_out")).isNull();
	}

	@Test
	void anEmptyStringCheckOutAlsoClearsIt() {
		long id = insertAttendance(EMPLOYEE_1, "2026-07-05 09:00:00", "2026-07-05 17:00:00", null);
		send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1, "{\"check_out\":\"\"}");
		assertThat(attendanceRow(id).get("check_out")).isNull();
	}

	@Test
	void aFailedFinalStrtotimeIsNotAValidationError() {
		// new_check_in = "oops": the final is_midnight test simply reads false,
		// no normalization happens, and the raw value is written.
		long id = insertAttendance(EMPLOYEE_1, "2026-07-06 09:00:00", null, EXC_ACTIVE);

		assertThat(status(send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1,
				"{\"check_in\":\"oops\"}"))).isEqualTo(200);
		assertThat(String.valueOf(attendanceRow(id).get("check_in"))).isEqualTo("0000-00-00 00:00:00");
	}

	// ---- D-095 --------------------------------------------------------

	@Test
	void aSameCompanyInactiveExceptionTypeIsAcceptedOnUpdate() {
		// update.php has no active check -- and D-095 deliberately does not
		// add one, unlike create.php.
		long id = insertAttendance(EMPLOYEE_1, "2026-08-01 09:00:00", "2026-08-01 17:00:00", null);

		assertThat(status(send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1,
				"{\"exception_type_id\":" + EXC_INACTIVE + "}"))).isEqualTo(200);
		assertThat(number(attendanceRow(id).get("exception_type_id"))).isEqualTo(EXC_INACTIVE);
	}

	@Test
	void aForeignAndAMissingExceptionTypeAreIndistinguishableAndWriteNothing() {
		long foreignTarget = insertAttendance(EMPLOYEE_1, "2026-08-02 09:00:00", "2026-08-02 17:00:00", null);
		long missingTarget = insertAttendance(EMPLOYEE_1, "2026-08-03 09:00:00", "2026-08-03 17:00:00", null);
		Map<String, Object> beforeForeign = attendanceRow(foreignTarget);
		Map<String, Object> beforeMissing = attendanceRow(missingTarget);

		ResponseEntity<Map<String, Object>> foreign = send(
				UPDATE + "?id=" + foreignTarget, HttpMethod.PUT, ADMIN_1,
				"{\"exception_type_id\":" + EXC_OTHER_COMPANY + "}");
		ResponseEntity<Map<String, Object>> missing = send(
				UPDATE + "?id=" + missingTarget, HttpMethod.PUT, ADMIN_1,
				"{\"exception_type_id\":999999}");

		// D-084's exact envelope, identical for both -- the caller cannot tell
		// "someone else's" from "does not exist".
		assertThat(foreign.getStatusCode().value()).isEqualTo(500);
		assertThat(missing.getStatusCode().value()).isEqualTo(500);
		assertThat(foreign.getBody()).isEqualTo(missing.getBody());
		assertThat(foreign.getBody().keySet()).containsExactly("success", "message");
		assertThat(foreign.getBody().get("message")).isEqualTo("Internal server error");

		// No exception-type name, company or id detail leaked.
		assertThat(String.valueOf(foreign.getBody())).doesNotContain("Other Co Exception");
		assertThat(String.valueOf(foreign.getBody())).doesNotContain(String.valueOf(EXC_OTHER_COMPANY));

		// Both rows unchanged.
		assertThat(attendanceRow(foreignTarget)).isEqualTo(beforeForeign);
		assertThat(attendanceRow(missingTarget)).isEqualTo(beforeMissing);
	}

	/**
	 * D-095 is scoped to a reference this request supplies. A row that already
	 * carries a foreign id -- dirty legacy data -- must not make an unrelated
	 * field change fail.
	 */
	@Test
	void anInheritedForeignExceptionIdDoesNotTriggerDzeroNinetyFive() {
		assertThat(number(attendanceRow(ATT_INHERITED_FOREIGN).get("exception_type_id")))
				.isEqualTo(EXC_OTHER_COMPANY);

		// The request changes only check_out, and never mentions the exception.
		assertThat(status(send(UPDATE + "?id=" + ATT_INHERITED_FOREIGN, HttpMethod.PUT, ADMIN_1,
				"{\"check_out\":\"2026-09-01 18:00:00\""
						+ "}"))).isEqualTo(200);

		Map<String, Object> row = attendanceRow(ATT_INHERITED_FOREIGN);
		assertThat(String.valueOf(row.get("check_out"))).isEqualTo("2026-09-01 18:00:00");
		// The inherited foreign reference is carried through untouched.
		assertThat(number(row.get("exception_type_id"))).isEqualTo(EXC_OTHER_COMPANY);
	}

	@Test
	void clearingAnInheritedForeignExceptionIsAllowed() {
		// clear wins, so new_exception is null and D-095 never runs.
		long id = insertAttendance(EMPLOYEE_1, "2026-09-05 09:00:00", "2026-09-05 17:00:00",
				EXC_OTHER_COMPANY);

		assertThat(status(send(UPDATE + "?id=" + id, HttpMethod.PUT, ADMIN_1,
				"{\"clear_exception_type\":1}"))).isEqualTo(200);
		assertThat(attendanceRow(id).get("exception_type_id")).isNull();
	}

	// ---- update guards ------------------------------------------------

	@Test
	void updateOfAForeignOrMissingRowIsNotFound() {
		assertThat(status(send(UPDATE + "?id=" + ATT_OTHER_COMPANY, HttpMethod.PUT, ADMIN_1,
				"{\"check_out\":\"2026-01-15 18:00:00\"}"))).isEqualTo(404);
		assertThat(status(send(UPDATE + "?id=999999", HttpMethod.PUT, ADMIN_1, "{}"))).isEqualTo(404);
		assertThat(status(send(UPDATE + "?id=0", HttpMethod.PUT, ADMIN_1, "{}"))).isEqualTo(400);
	}

	@Test
	void onlyCompanyAdminAndHrMayUpdate() {
		long id = insertAttendance(EMPLOYEE_1, "2026-10-01 09:00:00", null, null);
		assertThat(status(send(UPDATE + "?id=" + id, HttpMethod.PUT, MANAGER_1, "{}"))).isEqualTo(403);
		assertThat(status(send(UPDATE + "?id=" + id, HttpMethod.PUT, EMPLOYEE_1, "{}"))).isEqualTo(403);
		assertThat(status(send(UPDATE + "?id=" + id, HttpMethod.PUT, HR_1, "{}"))).isEqualTo(200);
	}

	@Test
	void theMethodGuardRunsBeforeAuthentication() {
		assertThat(anonymous(CREATE, HttpMethod.GET)).isEqualTo(405);
		assertThat(anonymous(UPDATE, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(CREATE, HttpMethod.POST)).isEqualTo(401);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static final long EMPLOYEE_FRESH2 = 209018L;
	private static final long EMPLOYEE_FRESH3 = 209019L;
	private static final long EMPLOYEE_FRESH4 = 209020L;
	private static final long EMPLOYEE_ODD2 = 209023L;
	private static final long EMPLOYEE_GAP2 = 209024L;
	private static final long EMPLOYEE_GAP3 = 209025L;
	private static final long EMPLOYEE_GAP4 = 209026L;

	private static String body(long employeeId, String extra) {
		return "{\"employee_id\":" + employeeId + (extra.isEmpty() ? "" : "," + extra) + "}";
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, long employeeId, String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		headers.set("Accept-Language", "en");
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(body, headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private int anonymous(String path, HttpMethod method) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>("{}", headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
				.getStatusCode().value();
	}

	private static int status(ResponseEntity<Map<String, Object>> response) {
		return response.getStatusCode().value();
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
				: employeeId == ADMIN_1 || employeeId == ADMIN_2 ? "company_admin" : "employee";
		long companyId = employeeId == ADMIN_2 || employeeId == EMPLOYEE_OTHER_CO ? COMPANY_2 : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	/**
	 * Read directly, and CAST the temporal columns so the assertions compare
	 * the stored lexical value. Without the CAST, getObject() returns a
	 * java.sql.Timestamp whose toString() appends ".0" -- a test-helper
	 * artifact that says nothing about the wire contract, which
	 * LegacyJdbcValues owns and LegacyJdbcValuesTest proves.
	 */
	private static Map<String, Object> attendanceRow(long id) {
		return queryOne("SELECT id, employee_id, CAST(check_in AS CHAR) AS check_in,"
				+ " CAST(check_out AS CHAR) AS check_out, exception_type_id, method"
				+ " FROM attendance WHERE id = " + id);
	}

	private static long insertAttendance(long employeeId, String checkIn, String checkOut, Long exception) {
		String out = checkOut == null ? "NULL" : "'" + checkOut + "'";
		String exc = exception == null ? "NULL" : String.valueOf(exception);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.executeUpdate("INSERT INTO attendance (employee_id, check_in, check_out, method,"
					+ " exception_type_id) VALUES (" + employeeId + ", '" + checkIn + "', " + out
					+ ", 'app', " + exc + ")", Statement.RETURN_GENERATED_KEYS);
			try (ResultSet keys = st.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Map<String, Object> queryOne(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			if (!rs.next()) {
				return null;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
				row.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
			}
			return row;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20901, 'Mutation Co', '+201000020901', 'active', '2025-01-15 09:00:00'),
					  (20902, 'Mutation Other Co', '+201000020902', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20911, 20901, 'Main', 1, '2025-03-01 10:00:00'),
					  (20912, 20902, 'Other', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO exception_types (id, company_id, name, is_active, created_at) VALUES
					  (209201, 20901, 'Late Arrival', 1, '2025-02-01 09:00:00'),
					  (209202, 20901, 'Retired Reason', 0, '2025-02-01 09:00:00'),
					  (209203, 20902, 'Other Co Exception', 1, '2025-02-01 09:00:00')
					""");

			for (long id : new long[] { ADMIN_1, HR_1, MANAGER_1, EMPLOYEE_1, EMPLOYEE_FRESH, EMPLOYEE_ODD,
					EMPLOYEE_GAP, EMPLOYEE_FRESH2, EMPLOYEE_FRESH3, EMPLOYEE_FRESH4, EMPLOYEE_ODD2,
					EMPLOYEE_GAP2, EMPLOYEE_GAP3, EMPLOYEE_GAP4 }) {
				employee(st, id, COMPANY_1, 20911L);
			}
			employee(st, ADMIN_2, COMPANY_2, 20912L);
			employee(st, EMPLOYEE_OTHER_CO, COMPANY_2, 20912L);

			// Prior punches for the duplicate-window employees.
			for (long id : new long[] { EMPLOYEE_GAP, EMPLOYEE_GAP2, EMPLOYEE_GAP3, EMPLOYEE_GAP4 }) {
				st.execute("INSERT INTO attendance (employee_id, check_in, method)"
						+ " VALUES (" + id + ", '2026-06-01 09:00:00', 'app')");
			}

			// Dirty legacy data: a company 1 row already pointing at company 2's
			// exception type. The FK permits it; only the application ever cared.
			st.execute("INSERT INTO attendance (id, employee_id, check_in, check_out, method,"
					+ " exception_type_id) VALUES (" + ATT_INHERITED_FOREIGN + ", " + EMPLOYEE_1
					+ ", '2026-09-01 09:00:00', NULL, 'app', " + EXC_OTHER_COMPANY + ")");
			st.execute("INSERT INTO attendance (id, employee_id, check_in, method)"
					+ " VALUES (" + ATT_OTHER_COMPANY + ", " + EMPLOYEE_OTHER_CO
					+ ", '2026-01-15 09:00:00', 'app')");
		}
	}

	private static void employee(Statement st, long id, long companyId, long branchId) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, role, is_active,"
				+ " join_request_status, phone, first_name, last_name, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", '" + id + "', 'employee', 1,"
				+ " 'accepted', '+2010" + id + "', 'Emp', '" + id + "', '2025-01-20 09:00:00')");
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
		try (InputStream stream = LegacyAttendanceMutationEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
