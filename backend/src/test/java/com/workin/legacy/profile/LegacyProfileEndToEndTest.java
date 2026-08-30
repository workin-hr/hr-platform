package com.workin.legacy.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyRuntimeOffset;
import com.workin.legacy.auth.LegacyPhpJwtService;

/**
 * Wave 13.2's seven ported {@code profile/*.php} routes.
 *
 * <p>{@code request_phone_change.php} and {@code confirm_phone_change.php} are
 * not here: both are OTP flows that share their entire helper set with Wave
 * 13.1's auth endpoints, and are ported with it.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyProfileEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String EMPLOYEE = "/apis/api/profile/employee.php";
	private static final String COMPANY = "/apis/api/profile/company.php";
	private static final String CHANGE_PASSWORD = "/apis/api/profile/change_password.php";
	private static final String LOGOUT = "/apis/api/profile/logout.php";
	private static final String PUSH_TOKEN = "/apis/api/profile/register_push_token.php";
	private static final String DELETE_PREVIEW = "/apis/api/profile/delete_account_preview.php";
	private static final String DELETE_ACCOUNT = "/apis/api/profile/delete_account.php";

	private static final long COMPANY_ID = 29001L;
	private static final long DOOMED_COMPANY = 29002L;
	private static final long ADMIN = 290011L;
	private static final long HR = 290012L;
	private static final long STAFF = 290013L;
	private static final long LEAVER = 290014L;
	private static final long DOOMED_STAFF = 290021L;
	private static final long BRANCH = 29011L;
	private static final long DEPARTMENT = 29021L;

	/** The password every seeded account starts with. */
	private static final String PASSWORD = "secret123";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyPhpJwtService legacyPhpJwtService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the profile fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ---------------- profile/employee.php ----------------

	/**
	 * {@code profile/employee.php} authenticates <b>before</b> it looks at the
	 * method, unlike every sibling in the module. So an anonymous request with
	 * an unsupported method is 401 here and 405 next door.
	 */
	@Test
	@Order(1)
	void employeeProfileAuthenticatesBeforeItChecksTheMethod() {
		assertThat(send(EMPLOYEE, HttpMethod.DELETE, null, null).getStatusCode().value())
				.as("auth first: no token wins over the wrong method")
				.isEqualTo(401);
		assertThat(send(LOGOUT, HttpMethod.GET, null, null).getStatusCode().value())
				.as("method first everywhere else")
				.isEqualTo(405);
		assertThat(send(EMPLOYEE, HttpMethod.DELETE, employeeToken(STAFF), null).getStatusCode().value())
				.as("authenticated, then the method is rejected")
				.isEqualTo(405);
	}

	/** A company-type session passes the role list and then fails on the employee id. */
	@Test
	@Order(2)
	void aCompanySessionIsRejectedWith401NotForbidden() {
		assertThat(send(EMPLOYEE, HttpMethod.GET, companyToken(), null).getStatusCode().value())
				.isEqualTo(401);
	}

	/** The GET projection carries the company and manager columns, and no secrets. */
	@Test
	@Order(3)
	@SuppressWarnings("unchecked")
	void theGetCarriesTheCompanyAndManagerColumns() {
		Map<String, Object> row = (Map<String, Object>) data(send(EMPLOYEE, HttpMethod.GET, employeeToken(STAFF), null));
		assertThat(row)
				.containsEntry("company_name", "Profile Co")
				.containsEntry("branch_name", "Main")
				.containsEntry("department_name", "Ops")
				.containsEntry("job_title_name", "Engineer")
				.containsEntry("manager_name", "Ada Admin");
		assertThat(row).as("public_row() strips both").doesNotContainKeys("password_hash", "token_version");
		assertThat(row).as("no contract seeded, so the key is absent entirely")
				.doesNotContainKey("basic_salary");
	}

	/**
	 * {@code employee_row_attach_hr_permissions()} takes its <em>query</em>
	 * branch here, because this projection joins no permission columns. An
	 * EMPLOYEE row is left alone; an HR row gains the object.
	 */
	@Test
	@Order(4)
	@SuppressWarnings("unchecked")
	void permissionsAreAttachedForHrAndNotForAnEmployee() {
		Map<String, Object> staff = (Map<String, Object>) data(send(EMPLOYEE, HttpMethod.GET, employeeToken(STAFF), null));
		assertThat(staff).as("EMPLOYEE is not a granular role").doesNotContainKey("permissions");

		Map<String, Object> hr = (Map<String, Object>) data(send(EMPLOYEE, HttpMethod.GET, employeeToken(HR), null));
		Map<String, Object> permissions = (Map<String, Object>) hr.get("permissions");
		assertThat(permissions).containsEntry("can_employees", 1).containsEntry("can_payroll", 0);
		assertThat(permissions).as("all seventeen, defaulted to 0").hasSize(17);
	}

	/** An empty body is rejected before anything is read or written. */
	@Test
	@Order(5)
	void anEmptyBodyIsNothingToUpdate() {
		assertThat(send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF), "{}").getStatusCode().value())
				.isEqualTo(400);
	}

	/**
	 * A body of keys that are not on the allow-list reaches the <em>second</em>
	 * {@code nothing_to_update}: it is not empty, so the first test passes, but
	 * it produces no assignments.
	 */
	@Test
	@Order(6)
	void aBodyOfUnwritableKeysIsAlsoNothingToUpdate() {
		ResponseEntity<Map<String, Object>> response = send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF),
				"{\"role\":\"company_admin\",\"is_active\":1,\"basic_salary\":9999}");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(role(STAFF)).as("and nothing was written").isEqualTo("employee");
	}

	/** The five self-service columns are written; the re-read drops manager_name. */
	@Test
	@Order(7)
	@SuppressWarnings("unchecked")
	void theSelfServiceColumnsAreWritable() {
		Map<String, Object> row = (Map<String, Object>) data(send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF),
				"{\"first_name\":\"Samir\",\"address\":\"12 Nile St\"}"));
		assertThat(row).containsEntry("first_name", "Samir").containsEntry("address", "12 Nile St");
		assertThat(row).as("the PUT re-read joins no manager").doesNotContainKey("manager_name");
		assertThat(row).as("but keeps the company columns").containsEntry("company_name", "Profile Co");
	}

	/**
	 * {@code normalize_employee_phone()} keeps digits only, so a phone with no
	 * digits normalises to null -- which <b>clears</b> the number and nulls
	 * {@code country_code} with it, even though the body never mentioned the
	 * country code.
	 */
	@Test
	@Order(8)
	@SuppressWarnings("unchecked")
	void aPhoneWithNoDigitsClearsBothColumns() {
		Map<String, Object> row = (Map<String, Object>) data(
				send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF), "{\"phone\":\"abc\"}"));
		assertThat(row).containsEntry("phone", null).containsEntry("country_code", null);
	}

	/** A supplied-but-blank country code alongside a real phone is field_required. */
	@Test
	@Order(9)
	void aBlankCountryCodeBesideAPhoneIsFieldRequired() {
		ResponseEntity<Map<String, Object>> response = send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF),
				"{\"phone\":\"01000000099\",\"country_code\":\"  \"}");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody()).containsEntry("message", "Field 'country_code' is required");
	}

	/** Taking another employee's phone is 409; the caller's own is not a conflict. */
	@Test
	@Order(10)
	@SuppressWarnings("unchecked")
	void aPhoneAlreadyHeldByAnotherEmployeeIs409() {
		assertThat(send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF),
				"{\"phone\":\"" + phoneOf(ADMIN) + "\",\"country_code\":\"+20\"}")
				.getStatusCode().value()).isEqualTo(409);

		Map<String, Object> row = (Map<String, Object>) data(send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF),
				"{\"phone\":\"01000000099\",\"country_code\":\" +20 \"}"));
		assertThat(row).containsEntry("phone", "01000000099");
		assertThat(row).as("the country code is trimmed").containsEntry("country_code", "+20");
	}

	/**
	 * {@code is_string($body[PASSWORD])} is the middle of three tests, so a
	 * numeric password is silently ignored -- and when it is the only field,
	 * the request ends as {@code nothing_to_update} rather than changing
	 * anything.
	 */
	@Test
	@Order(11)
	void aNonStringPasswordIsIgnoredEntirely() {
		String before = passwordHash(STAFF);
		assertThat(send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF), "{\"password\":12345678}")
				.getStatusCode().value()).isEqualTo(400);
		assertThat(passwordHash(STAFF)).isEqualTo(before);
	}

	/** A string password is trimmed and hashed. */
	@Test
	@Order(12)
	void aStringPasswordIsHashed() {
		assertThat(send(EMPLOYEE, HttpMethod.PUT, employeeToken(STAFF), "{\"password\":\"  newpass1  \"}")
				.getStatusCode().value()).isEqualTo(200);
		assertThat(passwordEncoder.matches("newpass1", passwordHash(STAFF)))
				.as("trimmed before hashing").isTrue();
	}

	// ---------------- profile/company.php ----------------

	/** An employee session gets both halves; a company session gets only the company. */
	@Test
	@Order(13)
	@SuppressWarnings("unchecked")
	void theCompanyProfileAttachesTheEmployeeOnlyForAnEmployeeSession() {
		Map<String, Object> fromHr = (Map<String, Object>) data(send(COMPANY, HttpMethod.GET, employeeToken(HR), null));
		assertThat(fromHr).containsKeys("company", "employee");
		Map<String, Object> employee = (Map<String, Object>) fromHr.get("employee");
		assertThat(employee).containsEntry("branch_name", "Main");
		assertThat(((Map<String, Object>) employee.get("permissions")))
				.as("this projection joins the columns, so the row branch is taken")
				.containsEntry("can_employees", 1);
		assertThat(employee).as("the joined columns are removed once lifted")
				.doesNotContainKey("can_employees");
		assertThat((Map<String, Object>) fromHr.get("company"))
				.containsEntry("company_name", "Profile Co")
				.doesNotContainKey("password_hash");

		Map<String, Object> fromCompany = (Map<String, Object>) data(send(COMPANY, HttpMethod.GET, companyToken(), null));
		assertThat(fromCompany).containsKey("company").doesNotContainKey("employee");
	}

	/** EMPLOYEE and MANAGER are not on this route's role list. */
	@Test
	@Order(14)
	void theCompanyProfileAdmitsOnlyAdminAndHr() {
		assertThat(send(COMPANY, HttpMethod.GET, employeeToken(STAFF), null).getStatusCode().value())
				.isEqualTo(403);
	}

	// ---------------- profile/change_password.php ----------------

	/** A wrong old password is 401, and a short new one is 400 before anything is read. */
	@Test
	@Order(15)
	void changePasswordChecksLengthThenTheOldPassword() {
		assertThat(send(CHANGE_PASSWORD, HttpMethod.POST, employeeToken(HR),
				"{\"old_password\":\"" + PASSWORD + "\",\"new_password\":\"short\"}")
				.getStatusCode().value())
				.as("five bytes")
				.isEqualTo(400);
		assertThat(send(CHANGE_PASSWORD, HttpMethod.POST, employeeToken(HR),
				"{\"old_password\":\"wrong\",\"new_password\":\"longenough\"}")
				.getStatusCode().value())
				.as("401, not 403")
				.isEqualTo(401);
	}

	/** The company session changes the <em>company</em> row, not any employee's. */
	@Test
	@Order(16)
	void aCompanySessionChangesTheCompanyPassword() {
		String employeeHashBefore = passwordHash(ADMIN);
		assertThat(send(CHANGE_PASSWORD, HttpMethod.POST, companyToken(),
				"{\"old_password\":\"" + PASSWORD + "\",\"new_password\":\"companypass\"}")
				.getStatusCode().value()).isEqualTo(200);
		assertThat(passwordEncoder.matches("companypass", companyPasswordHash(COMPANY_ID))).isTrue();
		assertThat(passwordHash(ADMIN)).as("no employee row was touched").isEqualTo(employeeHashBefore);
	}

	/** An employee session changes its own row. */
	@Test
	@Order(17)
	void anEmployeeSessionChangesItsOwnPassword() {
		assertThat(send(CHANGE_PASSWORD, HttpMethod.POST, employeeToken(HR),
				"{\"old_password\":\"" + PASSWORD + "\",\"new_password\":\"hrpass12\"}")
				.getStatusCode().value()).isEqualTo(200);
		assertThat(passwordEncoder.matches("hrpass12", passwordHash(HR))).isTrue();
	}

	// ---------------- profile/register_push_token.php ----------------

	/**
	 * <b>This endpoint cannot succeed against the frozen schema.</b>
	 * {@code register_push_token.php} inserts into {@code push_tokens
	 * (employee_id, company_id, token, platform)}, and
	 * {@code push_tokens} has no {@code company_id} column in
	 * {@code hr-legacy@d113204}'s dump -- nor any unique key for its
	 * {@code ON DUPLICATE KEY UPDATE} to fire on. So every call is a database
	 * error in legacy today, for a company session and an employee session
	 * alike, and this port reproduces that rather than quietly repairing the
	 * statement.
	 *
	 * <p>Consistent with F-08 (push never worked end-to-end), the mobile client
	 * having its {@code register_push_token} call commented out, and the ETL
	 * decision to drop the table. Recorded as R-013 and an open question rather
	 * than resolved here.
	 */
	@Test
	@Order(18)
	void registerPushTokenFailsAgainstTheFrozenSchema() {
		assertThat(send(PUSH_TOKEN, HttpMethod.POST, employeeToken(STAFF),
				"{\"token\":\"abc\",\"platform\":\"android\"}")
				.getStatusCode().value())
				.as("the column named by the INSERT does not exist")
				.isEqualTo(500);
		assertThat(pushTokenCount()).as("and nothing was written").isZero();
	}

	/** Its argument validation still runs first, so a missing field is a 400. */
	@Test
	@Order(19)
	void registerPushTokenStillValidatesItsArgumentsFirst() {
		assertThat(send(PUSH_TOKEN, HttpMethod.POST, employeeToken(STAFF), "{\"token\":\"abc\"}")
				.getStatusCode().value()).isEqualTo(400);
	}

	// ---------------- profile/logout.php ----------------

	/**
	 * Logging out of an employee session deactivates the account, notifies the
	 * company once, and drops the employee's push tokens.
	 */
	@Test
	@Order(20)
	void anEmployeeLogoutDeactivatesTheAccountAndNotifiesTheCompany() throws Exception {
		seedPushToken(LEAVER, "leaver-token");
		assertThat(send(LOGOUT, HttpMethod.POST, employeeToken(LEAVER), "{\"token\":\"leaver-token\"}")
				.getStatusCode().value()).isEqualTo(200);

		assertThat(isActive(LEAVER)).isZero();
		assertThat(pushTokenCount()).isZero();

		List<Map<String, Object>> notifications = companyNotifications();
		assertThat(notifications).hasSize(1);
		assertThat(notifications.get(0))
				.containsEntry("notification_type", "employee_left_company")
				.containsEntry("recipient_kind", "company")
				.containsEntry("reference_type", "employee");
		assertThat(String.valueOf(notifications.get(0).get("body")))
				.as("the display name, not the id")
				.contains("Lee Leaver");
	}

	/** A second logout notifies nothing: the row was already inactive. */
	@Test
	@Order(21)
	void aSecondLogoutDoesNotNotifyAgain() throws Exception {
		assertThat(send(LOGOUT, HttpMethod.POST, employeeToken(LEAVER), "{}")
				.getStatusCode().value()).isEqualTo(200);
		assertThat(companyNotifications()).hasSize(1);
	}

	/** A company session logs out without deactivating anything. */
	@Test
	@Order(22)
	void aCompanyLogoutDeactivatesNobody() throws Exception {
		assertThat(send(LOGOUT, HttpMethod.POST, companyToken(), "{}").getStatusCode().value()).isEqualTo(200);
		assertThat(isActive(ADMIN)).isEqualTo(1);
		assertThat(companyNotifications()).hasSize(1);
	}

	// ---------------- profile/delete_account*.php ----------------

	/** The preview is company-sessions-only, and 401 rather than 403 otherwise. */
	@Test
	@Order(23)
	@SuppressWarnings("unchecked")
	void theDeletePreviewIsCompanySessionsOnly() {
		assertThat(send(DELETE_PREVIEW, HttpMethod.GET, employeeToken(ADMIN), null).getStatusCode().value())
				.isEqualTo(401);

		Map<String, Object> payload = (Map<String, Object>) data(
				send(DELETE_PREVIEW, HttpMethod.GET, companyToken(), null));
		assertThat(payload).containsEntry("has_related_records", true);
		List<Map<String, Object>> related = (List<Map<String, Object>>) payload.get("related_records");
		assertThat(related.stream().map(row -> row.get("key")))
				.as("zero counts are omitted entirely, so this is not a fixed-length list")
				.contains("employees", "branches", "departments")
				.doesNotContain("payslips");
		assertThat(related.get(0)).containsEntry("label", "Employees");
	}

	/** An employee deleting their account is a deactivation, not a delete. */
	@Test
	@Order(24)
	void anEmployeeDeleteAccountDeactivates() {
		assertThat(send(DELETE_ACCOUNT, HttpMethod.DELETE, employeeToken(STAFF),
				"{\"password\":\"wrong\"}").getStatusCode().value())
				.as("invalid_phone_password, 401")
				.isEqualTo(401);

		assertThat(send(DELETE_ACCOUNT, HttpMethod.DELETE, employeeToken(STAFF),
				"{\"password\":\"newpass1\"}").getStatusCode().value()).isEqualTo(200);
		assertThat(isActive(STAFF)).isZero();
		assertThat(exists("employees", STAFF)).as("the row survives").isTrue();
	}

	/** A company deleting its account is a hard cascade. */
	@Test
	@Order(25)
	@SuppressWarnings("unchecked")
	void aCompanyDeleteAccountCascades() {
		ResponseEntity<Map<String, Object>> response = send(DELETE_ACCOUNT, HttpMethod.DELETE,
				doomedCompanyToken(), "{\"password\":\"" + PASSWORD + "\"}");
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);

		List<Map<String, Object>> deleted =
				(List<Map<String, Object>>) ((Map<String, Object>) response.getBody().get("data"))
						.get("deleted_related_records");
		assertThat(deleted.stream().map(row -> row.get("key")))
				.as("the pre-delete summary, not what survived")
				.contains("employees", "branches");

		assertThat(exists("companies", DOOMED_COMPANY)).isFalse();
		assertThat(exists("employees", DOOMED_STAFF)).isFalse();
		assertThat(rowCount("branches", "company_id = " + DOOMED_COMPANY)).isZero();
		assertThat(rowCount("notifications", "company_id = " + DOOMED_COMPANY)).isZero();
		assertThat(exists("companies", COMPANY_ID)).as("the other tenant is untouched").isTrue();
		assertThat(exists("employees", ADMIN)).isTrue();
	}

	// ---------------- fixture ----------------

	private static Object data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return response.getBody().get("data");
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, String token, String body) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String employeeToken(long employeeId) {
		String role = employeeId == ADMIN ? "company_admin" : employeeId == HR ? "hr" : "employee";
		long company = employeeId == DOOMED_STAFF ? DOOMED_COMPANY : COMPANY_ID;
		return legacyPhpJwtService.issueEmployeeToken(employeeId, company, role, 1L);
	}

	private String companyToken() {
		return legacyPhpJwtService.issueCompanyToken(COMPANY_ID, "company_admin");
	}

	private String doomedCompanyToken() {
		return legacyPhpJwtService.issueCompanyToken(DOOMED_COMPANY, "company_admin");
	}

	private static String scalar(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getString(1) : null;
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static String passwordHash(long employeeId) {
		return scalar("SELECT password_hash FROM employees WHERE id = " + employeeId);
	}

	private static String companyPasswordHash(long companyId) {
		return scalar("SELECT password_hash FROM companies WHERE id = " + companyId);
	}

	private static String role(long employeeId) {
		return scalar("SELECT role FROM employees WHERE id = " + employeeId);
	}

	private static String phoneOf(long employeeId) {
		return scalar("SELECT phone FROM employees WHERE id = " + employeeId);
	}

	private static long isActive(long employeeId) {
		return Long.parseLong(scalar("SELECT is_active FROM employees WHERE id = " + employeeId));
	}

	private static long pushTokenCount() {
		return Long.parseLong(scalar("SELECT COUNT(*) FROM push_tokens"));
	}

	private static long rowCount(String table, String where) {
		return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table + " WHERE " + where));
	}

	private static boolean exists(String table, long id) {
		return rowCount(table, "id = " + id) == 1;
	}

	private static List<Map<String, Object>> companyNotifications() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT notification_type, recipient_kind, reference_type, body FROM notifications"
								+ " WHERE company_id = " + COMPANY_ID + " ORDER BY id")) {
			List<Map<String, Object>> rows = new java.util.ArrayList<>();
			while (rs.next()) {
				rows.add(Map.of(
						"notification_type", rs.getString(1),
						"recipient_kind", rs.getString(2),
						"reference_type", String.valueOf(rs.getString(3)),
						"body", String.valueOf(rs.getString(4))));
			}
			return rows;
		}
	}

	private static void seedPushToken(long employeeId, String token) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("INSERT INTO push_tokens (employee_id, token, platform) VALUES ("
					+ employeeId + ", '" + token + "', 'android')");
		}
	}

	private static void seed() throws Exception {
		String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("SET time_zone = '" + LegacyRuntimeOffset.DEFAULT + "'");
			st.execute("INSERT INTO company_titles (id, name) VALUES (29031, 'LLC')");
			st.execute("INSERT INTO companies (id, company_name, phone, password_hash, status,"
					+ " company_title_id, created_at) VALUES"
					+ " (" + COMPANY_ID + ", 'Profile Co', '+201000029001', '" + hash + "', 'active',"
					+ " 29031, '2019-01-15 09:00:00'),"
					+ " (" + DOOMED_COMPANY + ", 'Doomed Co', '+201000029002', '" + hash + "', 'active',"
					+ " NULL, '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY_ID + ", 'Main', 1, '2019-03-01 10:00:00'),"
					+ " (" + (BRANCH + 1) + ", " + DOOMED_COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO job_titles (id, company_id, name) VALUES"
					+ " (29041, " + COMPANY_ID + ", 'Engineer')");

			employee(st, ADMIN, COMPANY_ID, BRANCH, "company_admin", "Ada", "Admin", hash);
			// The department's manager is the admin, which is where manager_name
			// on the GET comes from.
			st.execute("INSERT INTO departments (id, company_id, name, manager_id) VALUES"
					+ " (" + DEPARTMENT + ", " + COMPANY_ID + ", 'Ops', " + ADMIN + ")");
			employee(st, HR, COMPANY_ID, BRANCH, "hr", "Hana", "Hr", hash);
			employee(st, STAFF, COMPANY_ID, BRANCH, "employee", "Sam", "Staff", hash);
			employee(st, LEAVER, COMPANY_ID, BRANCH, "employee", "Lee", "Leaver", hash);
			employee(st, DOOMED_STAFF, DOOMED_COMPANY, BRANCH + 1, "employee", "Dan", "Doomed", hash);

			st.execute("UPDATE employees SET department_id = " + DEPARTMENT + ", job_title_id = 29041"
					+ " WHERE id = " + STAFF);
			st.execute("INSERT INTO hr_permissions (employee_id, can_employees) VALUES (" + HR + ", 1)");
			st.execute("INSERT INTO notifications (company_id, recipient_kind, to_employee_id, title,"
					+ " notification_type, created_at) VALUES (" + DOOMED_COMPANY + ", 'employee', "
					+ DOOMED_STAFF + ", 'Doomed', 'manual', '2026-02-01 09:00:00')");
		}
	}

	private static void employee(
			Statement st, long id, long companyId, long branchId, String role,
			String firstName, String lastName, String hash) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, country_code, password_hash, role, is_active, token_version,"
				+ " created_at) VALUES (" + id + ", " + companyId + ", " + branchId + ", '" + id + "', '"
				+ firstName + "', '" + lastName + "', '+2010000" + id + "', '+20', '" + hash + "', '"
				+ role + "', 1, 1, '2019-04-01 08:00:00')");
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
		try (InputStream in = LegacyProfileEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
