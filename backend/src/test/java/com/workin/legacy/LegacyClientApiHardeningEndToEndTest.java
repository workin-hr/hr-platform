package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.BackendApplication;
import com.workin.legacy.auth.LegacyLoginThrottle;
import com.workin.legacy.auth.LegacyPhpJwtService;

/**
 * D-289 over real HTTP against real MariaDB: the app API refuses what its
 * tokens no longer grant, and bounds what one request can cost.
 *
 * <p>One class for the six API items so they share one application context.
 * Every test seeds or resets what it reads, so the order they run in does not
 * matter. Tokens are the frozen PHP shape ({@link LegacyPhpJwtService}),
 * because {@code delete_account.php} only takes the employee branch for a
 * token whose {@code type} says {@code employee}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class LegacyClientApiHardeningEndToEndTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "Right-Horse-9";

	private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

	private static final long COMPANY = 28901L;
	private static final long BRANCH_A = 28911L;
	private static final long BRANCH_B = 28912L;
	private static final long REQUEST_TYPE = 28921L;

	private static final long ADMIN = 289001L;
	private static final long HR_PERMITTED = 289002L;
	private static final long HR_PEER = 289003L;
	private static final long MANAGER_A = 289004L;
	private static final long MANAGER_B = 289005L;

	/** Staff are seeded per test from here up, so none depends on another's leftovers. */
	private static long nextStaff = 289100L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyPhpJwtService jwtService;

	static {
		try {
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the D-289 fixture", ex);
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
	void freshLoginBudgets() throws Exception {
		execute("DELETE FROM platform_admin_login_attempts");
	}

	// ------------------------------------------------------------------
	// Item 1 -- a deactivated employee keeps no session
	// ------------------------------------------------------------------

	@Test
	void deactivatingAnEmployeeEndsTheSessionTheirTokenCarries() throws Exception {
		long staff = staff(BRANCH_A, 1, "accepted");
		String token = token(staff, "employee", 1);
		assertThat(call("/apis/api/profile/employee.php", HttpMethod.GET, token, null).getStatusCode().value())
				.isEqualTo(200);

		assertThat(call("/apis/api/employees/deactivate.php?id=" + staff, HttpMethod.DELETE,
				token(ADMIN, "company_admin", 1), null).getStatusCode().value()).isEqualTo(200);

		assertRefused(call("/apis/api/profile/employee.php", HttpMethod.GET, token, null));
		assertThat(queryLong("SELECT token_version FROM employees WHERE id = " + staff)).isEqualTo(2);
	}

	@Test
	void anInactiveRowIsRefusedEvenWhenNothingBumpedItsTokenVersion() throws Exception {
		// Legacy's own deactivate.php, a dashboard edit or a direct write leaves
		// token_version alone; the guard must not depend on every writer.
		long staff = staff(BRANCH_A, 0, "accepted");

		assertRefused(call("/apis/api/profile/employee.php", HttpMethod.GET, token(staff, "employee", 1), null));
	}

	@Test
	void aPendingJoinerIsInactiveByConstructionAndKeepsItsSession() throws Exception {
		// join_company creates is_active=0 with join_request_status='pending' and
		// login_employee deliberately lets that single pending row in.
		long joiner = staff(BRANCH_A, 0, "pending");

		assertThat(call("/apis/api/profile/employee.php", HttpMethod.GET, token(joiner, "employee", 1), null)
				.getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void anUpdateThatDeactivatesEndsTheSessionAndOneThatDoesNotLeavesIt() throws Exception {
		long staff = staff(BRANCH_A, 1, "accepted");
		String admin = token(ADMIN, "company_admin", 1);

		assertThat(call("/apis/api/employees/update.php?id=" + staff, HttpMethod.PUT, admin,
				Map.of("first_name", "Renamed")).getStatusCode().value()).isEqualTo(200);
		assertThat(queryLong("SELECT token_version FROM employees WHERE id = " + staff)).isEqualTo(1);

		assertThat(call("/apis/api/employees/update.php?id=" + staff, HttpMethod.PUT, admin,
				Map.of("is_active", 0)).getStatusCode().value()).isEqualTo(200);
		assertThat(queryLong("SELECT token_version FROM employees WHERE id = " + staff)).isEqualTo(2);
	}

	@Test
	void deletingYourOwnAccountEndsTheSessionThatDeletedIt() throws Exception {
		long staff = staff(BRANCH_A, 1, "accepted");
		String token = token(staff, "employee", 1);

		assertThat(call("/apis/api/profile/delete_account.php", HttpMethod.DELETE, token,
				Map.of("password", PASSWORD)).getStatusCode().value()).isEqualTo(200);

		assertThat(queryLong("SELECT token_version FROM employees WHERE id = " + staff)).isEqualTo(2);
		assertRefused(call("/apis/api/profile/employee.php", HttpMethod.GET, token, null));
	}

	// ------------------------------------------------------------------
	// Item 3 -- HR cannot take over a peer's credentials
	// ------------------------------------------------------------------

	@Test
	void hrWithCanEmployeesCannotResetAPeersPasswordPhoneOrActiveFlag() throws Exception {
		String hr = token(HR_PERMITTED, "hr", 1);
		for (long target : new long[] {HR_PEER, MANAGER_A, ADMIN}) {
			for (Map<String, Object> body : new Map[] {
					Map.of("password", "taken-over"),
					Map.of("phone", "01009998877", "country_code", "+20"),
					Map.of("is_active", 0)}) {
				ResponseEntity<Map<String, Object>> response =
						call("/apis/api/employees/update.php?id=" + target, HttpMethod.PUT, hr, body);
				assertThat(response.getStatusCode().value()).as("%s on %s", body.keySet(), target).isEqualTo(403);
				assertThat(response.getBody().get("message")).isEqualTo("Forbidden");
			}
			assertThat(queryString("SELECT CONCAT(password_hash, '|', phone, '|', is_active, '|', token_version)"
					+ " FROM employees WHERE id = " + target))
					.as("nothing about %s changed", target)
					.isEqualTo(HASH + "|+20100" + target + "|1|1");
		}
	}

	@Test
	void hrStillEditsAPlainEmployeesCredentialsAndAPeersOtherFields() throws Exception {
		String hr = token(HR_PERMITTED, "hr", 1);
		long staff = staff(BRANCH_A, 1, "accepted");

		assertThat(call("/apis/api/employees/update.php?id=" + staff, HttpMethod.PUT, hr,
				Map.of("password", "reset-by-hr")).getStatusCode().value()).isEqualTo(200);
		assertThat(call("/apis/api/employees/update.php?id=" + HR_PEER, HttpMethod.PUT, hr,
				Map.of("address", "Moved")).getStatusCode().value()).isEqualTo(200);
		assertThat(call("/apis/api/employees/update.php?id=" + HR_PERMITTED, HttpMethod.PUT, hr,
				Map.of("password", PASSWORD)).getStatusCode().value())
				.as("an HR session changing its own password is the self branch, not a peer")
				.isEqualTo(200);
	}

	@Test
	void theCompanyAdminStillResetsAnHrPassword() throws Exception {
		long hrTarget = hrEmployee();
		assertThat(call("/apis/api/employees/update.php?id=" + hrTarget, HttpMethod.PUT,
				token(ADMIN, "company_admin", 1), Map.of("password", "admin-reset")).getStatusCode().value())
				.isEqualTo(200);
	}

	@Test
	void hrCannotReachAPeersCredentialsThroughTheBulkUpdateEither() throws Exception {
		String hr = token(HR_PERMITTED, "hr", 1);
		long peer = hrEmployee();
		long staff = staff(BRANCH_A, 1, "accepted");

		// The review's two steps: employee_code is not a guarded field, so HR
		// can first give the peer a code of its choosing...
		assertThat(call("/apis/api/employees/update.php?id=" + peer, HttpMethod.PUT, hr,
				Map.of("employee_code", "7700" + peer)).getStatusCode().value()).isEqualTo(200);

		// ...and then address the peer by it on update_bulk.php.
		ResponseEntity<Map<String, Object>> bulk = call("/apis/api/employees/update_bulk.php", HttpMethod.POST, hr,
				Map.of("rows", java.util.List.of(
						Map.of("employee_code", "7700" + peer, "password", "owned"),
						Map.of("employee_code", "7700" + peer, "phone", "01009998877"),
						Map.of("employee_code", String.valueOf(ADMIN % 100_000), "password", "owned"),
						Map.of("employee_code", String.valueOf(staff % 100_000), "password", "reset-by-hr"))));
		assertThat(bulk.getStatusCode().value()).isEqualTo(200);
		Map<String, Object> result = data(bulk);
		assertThat(result.get("updated")).as("only the plain employee's row").isEqualTo(1);
		assertThat(result.get("updated_ids")).isEqualTo(java.util.List.of((int) staff));
		assertThat(failedErrors(bulk)).containsExactly(
				java.util.List.of("forbidden"),
				java.util.List.of("employee_code_duplicate_in_file"),
				java.util.List.of("forbidden"));

		// The phone alone, on a row of its own.
		ResponseEntity<Map<String, Object>> phone = call("/apis/api/employees/update_bulk.php", HttpMethod.POST, hr,
				Map.of("rows", java.util.List.of(Map.of("employee_code", "7700" + peer, "phone", "01009998877"))));
		assertThat(failedErrors(phone)).containsExactly(java.util.List.of("forbidden"));

		for (long target : new long[] {peer, ADMIN}) {
			assertThat(queryString("SELECT CONCAT(password_hash, '|', phone, '|', country_code)"
					+ " FROM employees WHERE id = " + target))
					.as("nothing about %s changed", target)
					.isEqualTo(HASH + "|+20100" + target + "|+20");
		}
		assertThat(new BCryptPasswordEncoder().matches("reset-by-hr",
				queryString("SELECT password_hash FROM employees WHERE id = " + staff))).isTrue();
	}

	@Test
	void theBulkUpdateStillLetsHrEditAPeersOtherFieldsAndTheAdminEditAnyone() throws Exception {
		long peer = hrEmployee();
		String code = String.valueOf(peer % 100_000);

		ResponseEntity<Map<String, Object>> hrEdit = call("/apis/api/employees/update_bulk.php", HttpMethod.POST,
				token(HR_PERMITTED, "hr", 1), Map.of("rows", java.util.List.of(
						Map.of("employee_code", code, "address", "Moved", "password", "   "))));
		assertThat(data(hrEdit).get("updated")).as("a blank password cell changes nothing").isEqualTo(1);
		assertThat(queryString("SELECT address FROM employees WHERE id = " + peer)).isEqualTo("Moved");

		ResponseEntity<Map<String, Object>> adminEdit = call("/apis/api/employees/update_bulk.php", HttpMethod.POST,
				token(ADMIN, "company_admin", 1), Map.of("rows", java.util.List.of(
						Map.of("employee_code", code, "password", "admin-reset"))));
		assertThat(data(adminEdit).get("updated")).isEqualTo(1);
		assertThat(new BCryptPasswordEncoder().matches("admin-reset",
				queryString("SELECT password_hash FROM employees WHERE id = " + peer))).isTrue();
	}

	/** Each failed row's {@code errors}, in row order. */
	private static java.util.List<Object> failedErrors(ResponseEntity<Map<String, Object>> response) {
		return ((java.util.List<?>) data(response).get("failed")).stream()
				.map(row -> ((Map<?, ?>) row).get("errors"))
				.collect(java.util.stream.Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}

	// ------------------------------------------------------------------
	// Item 4 -- a manager decides only inside their branch
	// ------------------------------------------------------------------

	@Test
	void aManagerCannotApproveOrRejectARequestFromAnotherBranch() throws Exception {
		long otherBranchStaff = staff(BRANCH_B, 1, "accepted");
		long request = pendingRequest(otherBranchStaff);
		String manager = token(MANAGER_A, "manager", 1);

		for (String action : new String[] {"approve", "reject"}) {
			ResponseEntity<Map<String, Object>> response = call(
					"/apis/api/requests/" + action + ".php?id=" + request, HttpMethod.POST, manager, Map.of());
			assertThat(response.getStatusCode().value()).as(action).isEqualTo(403);
			assertThat(response.getBody().get("message")).isEqualTo("Forbidden — insufficient role");
		}
		assertThat(queryString("SELECT status FROM requests WHERE id = " + request)).isEqualTo("pending");
	}

	@Test
	void aManagerStillDecidesInsideTheirOwnBranch() throws Exception {
		String manager = token(MANAGER_A, "manager", 1);
		long approve = pendingRequest(staff(BRANCH_A, 1, "accepted"));
		long reject = pendingRequest(staff(BRANCH_A, 1, "accepted"));

		assertThat(call("/apis/api/requests/approve.php?id=" + approve, HttpMethod.POST, manager, Map.of())
				.getStatusCode().value()).isEqualTo(200);
		assertThat(call("/apis/api/requests/reject.php?id=" + reject, HttpMethod.POST, manager, Map.of())
				.getStatusCode().value()).isEqualTo(200);
		assertThat(queryString("SELECT status FROM requests WHERE id = " + approve)).isEqualTo("approved");
		assertThat(queryString("SELECT status FROM requests WHERE id = " + reject)).isEqualTo("rejected");
	}

	// ------------------------------------------------------------------
	// Item 2 -- a report range is capped at 62 days
	// ------------------------------------------------------------------

	@Test
	void aReportRangeOverSixtyTwoDaysIsRefusedWithTheInversionsOwnError() throws Exception {
		String admin = token(ADMIN, "company_admin", 1);
		String decade = "2016-01-01&date_to=2026-01-01";

		assertBadRequest(call("/apis/api/attendance/list.php?fill_days=1&date_from=" + decade,
				HttpMethod.GET, admin, null), "Invalid input");
		assertBadRequest(call("/apis/api/attendance/overall_report.php?from=2016-01-01&to=2026-01-01",
				HttpMethod.GET, admin, null), "Invalid date");
		assertThat(rawCall("/apis/api/attendance/export.php?type=fingerprints&from=2016-01-01&to=2026-01-01",
				admin).getStatusCode().value()).isEqualTo(400);

		// Sixty-three days is the first span refused.
		assertBadRequest(call("/apis/api/attendance/overall_report.php?from=2026-01-01&to=2026-03-04",
				HttpMethod.GET, admin, null), "Invalid date");
	}

	@Test
	void aSixtyTwoDayRangeIsStillServed() throws Exception {
		String admin = token(ADMIN, "company_admin", 1);

		assertThat(call("/apis/api/attendance/list.php?fill_days=1&date_from=2026-01-01&date_to=2026-03-03",
				HttpMethod.GET, admin, null).getStatusCode().value()).isEqualTo(200);
		assertThat(call("/apis/api/attendance/overall_report.php?from=2026-01-01&to=2026-03-03",
				HttpMethod.GET, admin, null).getStatusCode().value()).isEqualTo(200);
		assertThat(rawCall("/apis/api/attendance/export.php?type=fingerprints&from=2026-01-01&to=2026-03-03",
				admin).getStatusCode().value()).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// Item 6 -- the app's password logins have a miss budget
	// ------------------------------------------------------------------

	@Test
	void aPhonesMissBudgetRefusesEvenTheRightPasswordAcrossAllThreeRoutes() throws Exception {
		long staff = staff(BRANCH_A, 1, "accepted");
		String phone = "+20100" + staff;

		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			ResponseEntity<Map<String, Object>> wrong = login("login_employee", phone, "wrong", null);
			assertThat(wrong.getStatusCode().value()).isEqualTo(401);
			assertThat(wrong.getBody().get("message")).as("the existing answer is unchanged")
					.isEqualTo("Incorrect password");
		}

		ResponseEntity<Map<String, Object>> refused = login("login_employee", phone, PASSWORD, null);
		assertThat(refused.getStatusCode().value()).isEqualTo(429);
		assertThat(refused.getBody().get("success")).isEqualTo(false);
		assertThat(refused.getBody().get("message")).isEqualTo("Too many login attempts. Please try again later.");

		assertThat(login("login_company", phone, PASSWORD, null).getStatusCode().value())
				.as("one phone is one budget, whichever route asks").isEqualTo(429);
		assertThat(login("login_desktop", phone, PASSWORD, "hr").getStatusCode().value()).isEqualTo(429);
	}

	@Test
	void aSuccessReturnsOnlyItsOwnReservationAndClearsNoEarlierMiss() throws Exception {
		// A success used to clear the phone's whole budget -- and join_company
		// lets a pending row share the admin's phone, so a guesser's own
		// successful login reset the admin's budget at will.
		long staff = staff(BRANCH_A, 1, "accepted");
		String phone = "+20100" + staff;

		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES - 1; miss++) {
			assertThat(login("login_employee", phone, "wrong", null).getStatusCode().value()).isEqualTo(401);
		}
		assertThat(login("login_employee", phone, PASSWORD, null).getStatusCode().value())
				.as("within the budget, the right password still signs in").isEqualTo(200);
		assertThat(login("login_employee", phone, "wrong", null).getStatusCode().value())
				.as("the success returned its own row, so this is the eighth miss").isEqualTo(401);
		assertThat(login("login_employee", phone, PASSWORD, null).getStatusCode().value())
				.as("the seven misses before the success still count").isEqualTo(429);
	}

	@Test
	void aGuesserSpendingThePhonesBudgetFromTheirAddressDoesNotLockTheOwnerOut() throws Exception {
		long staff = staff(BRANCH_A, 1, "accepted");
		String phone = "+20100" + staff;

		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			assertThat(loginFrom("127.0.0.1", "login_employee", phone, "wrong").getStatusCode().value())
					.isEqualTo(401);
		}
		assertThat(loginFrom("127.0.0.1", "login_employee", phone, PASSWORD).getStatusCode().value())
				.as("the guesser's address is refused").isEqualTo(429);

		// The owner, from an address of their own: IPv6 loopback is another
		// client address as far as getRemoteAddr() is concerned.
		assertThat(loginFrom("[::1]", "login_employee", phone, PASSWORD).getStatusCode().value())
				.as("the owner's correct password from elsewhere").isEqualTo(200);
	}

	@Test
	void theSamePhoneWrittenInArabicIndicDigitsSpendsTheSameBudget() throws Exception {
		long staff = staff(BRANCH_A, 1, "accepted");
		String phone = "+20100" + staff;
		String arabicIndic = "+" + toArabicIndic(phone.substring(1));

		assertThat(login("login_employee", arabicIndic, PASSWORD, null).getStatusCode().value())
				.as("MariaDB's collation finds the row by these digits").isEqualTo(200);
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			assertThat(login("login_employee", phone, "wrong", null).getStatusCode().value()).isEqualTo(401);
		}
		assertThat(login("login_employee", arabicIndic, PASSWORD, null).getStatusCode().value())
				.as("another script is not another budget").isEqualTo(429);
		String mixed = phone.substring(0, 6) + toArabicIndic(phone.substring(6));
		assertThat(login("login_employee", mixed, PASSWORD, null).getStatusCode().value())
				.as("nor is a mix of scripts").isEqualTo(429);
	}

	@Test
	void theCompanyLoginIsChargedToo() throws Exception {
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			assertThat(login("login_company", "+201000289000", "wrong", null).getStatusCode().value())
					.isEqualTo(401);
		}
		assertThat(login("login_company", "+201000289000", PASSWORD, null).getStatusCode().value())
				.isEqualTo(429);
	}

	@Test
	void oneAddressSprayingManyPhonesIsRefusedAndAForwardedHeaderDoesNotHelp() throws Exception {
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_ADDRESS_MISSES; miss++) {
			ResponseEntity<Map<String, Object>> unknown = login(
					"login_employee", "+2015550" + (10_000 + miss), "guess", null, "198.51.100." + (miss % 250));
			assertThat(unknown.getStatusCode().value()).as("miss %d", miss).isEqualTo(401);
			assertThat(unknown.getBody().get("message")).isEqualTo("User not found");
		}
		assertThat(login("login_employee", "+2015550" + 99_999, "guess", null, "203.0.113.9")
				.getStatusCode().value())
				.as("X-Forwarded-For is not trusted from an unconfigured proxy, so rotating it changes nothing")
				.isEqualTo(429);
	}

	// ------------------------------------------------------------------

	private static void assertRefused(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().get("message"))
				.isEqualTo("Signed in from another device. Your session here was ended.");
	}

	private static void assertBadRequest(ResponseEntity<Map<String, Object>> response, String message) {
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		assertThat(response.getBody().get("message")).isEqualTo(message);
	}

	private String token(long employeeId, String role, long version) {
		return jwtService.issueEmployeeToken(employeeId, COMPANY, role, version);
	}

	private ResponseEntity<Map<String, Object>> login(String route, String phone, String password, String loginAs) {
		return login(route, phone, password, loginAs, null);
	}

	private ResponseEntity<Map<String, Object>> login(
			String route, String phone, String password, String loginAs, String forwardedFor) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (forwardedFor != null) {
			headers.add("X-Forwarded-For", forwardedFor);
		}
		Map<String, Object> body = new HashMap<>();
		body.put("phone", phone);
		body.put("password", password);
		if (loginAs != null) {
			body.put("login_as", loginAs);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/auth/" + route + ".php"), HttpMethod.POST,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	/** A login from the client address the host names, which is what getRemoteAddr() then reports. */
	private ResponseEntity<Map<String, Object>> loginFrom(String host, String route, String phone, String password) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		int port = URI.create(restTemplate.getRootUri()).getPort();
		return restTemplate.exchange(
				URI.create("http://" + host + ":" + port + "/apis/api/auth/" + route + ".php"), HttpMethod.POST,
				new HttpEntity<>(Map.of("phone", phone, "password", password), headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static String toArabicIndic(String asciiDigits) {
		StringBuilder out = new StringBuilder();
		for (char digit : asciiDigits.toCharArray()) {
			out.append((char) ('\u0660' + (digit - '0')));
		}
		return out.toString();
	}

	private ResponseEntity<Map<String, Object>> call(String path, HttpMethod method, String token, Object body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(body, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private ResponseEntity<byte[]> rawCall(String path, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
	}

	private static synchronized long staff(long branchId, int active, String joinStatus) throws Exception {
		long id = nextStaff++;
		insertEmployee(id, branchId, "employee", active, joinStatus);
		return id;
	}

	private static synchronized long hrEmployee() throws Exception {
		long id = nextStaff++;
		insertEmployee(id, BRANCH_A, "hr", 1, "accepted");
		return id;
	}

	private static long pendingRequest(long employeeId) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("INSERT INTO requests (employee_id, request_type_id, from_date, to_date, status, created_at)"
					+ " VALUES (" + employeeId + ", " + REQUEST_TYPE + ", '2026-01-01', '2026-01-02', 'pending',"
					+ " '2025-06-01 09:00:00')", Statement.RETURN_GENERATED_KEYS);
			try (ResultSet keys = st.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, password_hash, status, otp_verified,"
					+ " profile_completed, created_at) VALUES (" + COMPANY + ", 'Hardening Co', '+201000289000', '"
					+ HASH + "', 'active', 1, 1, '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH_A + ", " + COMPANY + ", 'Branch A', 1, '2025-03-01 10:00:00'),"
					+ " (" + BRANCH_B + ", " + COMPANY + ", 'Branch B', 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO request_types (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + REQUEST_TYPE + ", " + COMPANY + ", 'Vacation', 1, '2025-02-01 08:00:00')");
		}
		insertEmployee(ADMIN, BRANCH_A, "company_admin", 1, "accepted");
		insertEmployee(HR_PERMITTED, BRANCH_A, "hr", 1, "accepted");
		insertEmployee(HR_PEER, BRANCH_A, "hr", 1, "accepted");
		insertEmployee(MANAGER_A, BRANCH_A, "manager", 1, "accepted");
		insertEmployee(MANAGER_B, BRANCH_B, "manager", 1, "accepted");
		execute("INSERT INTO hr_permissions (employee_id, can_employees) VALUES (" + HR_PERMITTED + ", 1)");
	}

	private static void insertEmployee(long id, long branchId, String role, int active, String joinStatus)
			throws Exception {
		execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
				   password_hash, token_version, role, is_active, join_request_status, created_at)
				VALUES (%d, %d, %d, '%d', 'Hardening', 'Subject', '+20100%d', '+20',
				   '%s', 1, '%s', %d, '%s', '2025-05-01 09:00:00')
				""".formatted(id, COMPANY, branchId, id % 100_000, id, HASH, role, active, joinStatus));
	}

	private static void execute(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute(sql);
		}
	}

	private static long queryLong(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static String queryString(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getString(1);
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}
}
