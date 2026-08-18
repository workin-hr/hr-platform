package com.workin.legacy.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.i18n.ApiErrorBody;
import com.workin.backend.identity.JwtService;

/**
 * PR 12.1's full HTTP-to-real-MariaDB proof, per the Item 12
 * specification §9 (the guard-stack chain and its negative cases), §10.2
 * (permission/contract/isolation classes), D-047 (company-scoped
 * uniqueness) and D-048 (delete's atomic, tenant-safe FK clearing).
 * {@code phase1-mysql} active, real JWTs from {@link JwtService}, no
 * probe controller anywhere in the path -- every request goes through
 * {@code legacySecurityFilterChain}, {@link
 * com.workin.legacy.auth.LegacyRequestGuard}, {@link
 * com.workin.legacy.authorization.LegacyHrPermissionEnforcer} and the
 * real {@link LegacyExceptionTypeController}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyExceptionTypeEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String BASE_PATH = "/api/legacy/attendance_exception_types";

	private static final long COMPANY_1 = 9101L;
	private static final long COMPANY_2 = 9102L;
	private static final long COMPANY_SUSPENDED = 9103L;

	private static final long ADMIN_1 = 91011L; // COMPANY_1, company_admin, can_company_settings=1
	private static final long HR_NO_PERM = 91012L; // COMPANY_1, hr, no hr_permissions row
	private static final long PLAIN_EMPLOYEE = 91013L; // COMPANY_1, employee
	private static final long ADMIN_2 = 91021L; // COMPANY_2, company_admin, can_company_settings=1
	private static final long ADMIN_SUSPENDED = 91031L; // COMPANY_SUSPENDED, company_admin
	private static final long STALE_TOKEN_ADMIN = 91041L; // COMPANY_1, dedicated to the token_version test

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
			throw new IllegalStateException("could not prepare the attendance_exception_types e2e fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
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

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (9101, 'E2E Co 1', '+201000009101', 'active', '2025-01-15 09:00:00'),
					  (9102, 'E2E Co 2', '+201000009102', 'active', '2025-01-15 09:00:00'),
					  (9103, 'E2E Co Suspended', '+201000009103', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (9111, 9101, 'Co1 HQ', 1, '2025-03-01 10:00:00'),
					  (9112, 9102, 'Co2 HQ', 1, '2025-03-01 10:00:00'),
					  (9113, 9103, 'Suspended HQ', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, first_name, last_name, phone, role,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (91011, 9101, 9111, 'Admin', 'One', '+201100091011', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (91012, 9101, 9111, 'HrNoPerm', 'One', '+201100091012', 'hr', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (91013, 9101, 9111, 'Plain', 'One', '+201100091013', 'employee', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (91021, 9102, 9112, 'Admin', 'Two', '+201100091021', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (91031, 9103, 9113, 'Admin', 'Suspended', '+201100091031', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (91041, 9101, 9111, 'Stale', 'Token', '+201100091041', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00')
					""");
			st.execute("""
					INSERT INTO hr_permissions
					  (id, employee_id, can_dashboard, can_recent_activities, can_branches,
					   can_departments, can_job_titles, can_shifts, can_leave_balances,
					   can_assets, can_advances, can_workforce_planning, can_salary_calculator,
					   can_company_settings, can_employees, can_attendance, can_requests,
					   can_payroll, can_penalties)
					VALUES
					  (9151, 91011, 0,0,0,0,0,0,0,0,0,0,0, 1, 0,0,0,0,0),
					  (9152, 91021, 0,0,0,0,0,0,0,0,0,0,0, 1, 0,0,0,0,0),
					  (9153, 91041, 0,0,0,0,0,0,0,0,0,0,0, 1, 0,0,0,0,0)
					""");
			st.execute("""
					INSERT INTO exception_types (id, company_id, name, is_active, created_at, updated_at) VALUES
					  (9201, 9101, 'Sick Leave', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
					  (9202, 9101, 'Unpaid Leave', 0, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
					  (9203, 9102, 'Other Co Type', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
					  (9204, 9101, 'Delete Target', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
					  (9205, 9102, 'Other Co Delete Target', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
					  (9206, 9101, 'Rename Source', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00'),
					  (9207, 9101, 'Update Self', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00')
					""");
			st.execute("""
					INSERT INTO attendance (id, employee_id, check_in, method, exception_type_id, created_at, updated_at) VALUES
					  (9301, 91013, '2025-05-01 09:00:00', 'app', 9204, '2025-05-01 09:00:00', '2025-05-01 09:00:00'),
					  (9302, 91021, '2025-05-01 09:00:00', 'app', 9205, '2025-05-01 09:00:00', '2025-05-01 09:00:00')
					""");
			st.execute("""
					INSERT INTO request_types (id, company_id, name, is_active, exception_type_id, created_at) VALUES
					  (9401, 9101, 'Co1 Request Type', 1, 9204, '2025-04-01 08:00:00'),
					  (9402, 9102, 'Co2 Request Type', 1, 9205, '2025-04-01 08:00:00')
					""");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyExceptionTypeEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	// ---------- list / one : ungated ----------

	@Test
	void plainEmployeeSeesOnlyActiveTypesByDefault() {
		ResponseEntity<LegacyExceptionTypePage> response = get(BASE_PATH, PLAIN_EMPLOYEE);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().data()).extracting("name").contains("Sick Leave").doesNotContain("Unpaid Leave");
	}

	@Test
	void adminSeesInactiveTypesByDefault() {
		ResponseEntity<LegacyExceptionTypePage> response = get(BASE_PATH, ADMIN_1);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().data()).extracting("name").contains("Sick Leave", "Unpaid Leave");
	}

	@Test
	void searchFiltersByNameSubstring() {
		ResponseEntity<LegacyExceptionTypePage> response = get(BASE_PATH + "?search=Sick", ADMIN_1);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().data()).extracting("name").containsExactly("Sick Leave");
	}

	@Test
	void oneReturnsTheRowForItsOwnCompany() {
		ResponseEntity<LegacyExceptionTypeView> response = get(BASE_PATH + "/9201", ADMIN_1, LegacyExceptionTypeView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().name()).isEqualTo("Sick Leave");
	}

	@Test
	void oneReturns404ForAnotherCompanysRow() {
		ResponseEntity<ApiErrorBody> response = get(BASE_PATH + "/9203", ADMIN_1, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("not_found");
	}

	// ---------- guard stack negatives (spec §9) ----------

	@Test
	void insufficientRoleOnCreateReturns403() {
		ResponseEntity<ApiErrorBody> response = post(BASE_PATH, PLAIN_EMPLOYEE, Map.of("name", "Should Not Create"), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().code()).isEqualTo("forbidden_insufficient_role");
	}

	@Test
	void inactiveCompanyReturns403OnList() {
		ResponseEntity<ApiErrorBody> response = get(BASE_PATH, ADMIN_SUSPENDED, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().code()).isEqualTo("company_account_not_active");
	}

	/** The divergence a module-named gate would have hidden (D-045): denied on write, but list still succeeds. */
	@Test
	void missingCanCompanySettingsDeniesWriteButListStillSucceeds() {
		ResponseEntity<ApiErrorBody> writeResponse =
				post(BASE_PATH, HR_NO_PERM, Map.of("name", "Should Not Create Either"), ApiErrorBody.class);
		assertThat(writeResponse.getStatusCode().value()).isEqualTo(403);

		ResponseEntity<LegacyExceptionTypePage> listResponse = get(BASE_PATH, HR_NO_PERM);
		assertThat(listResponse.getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void aStaleTokenVersionIsRejectedAsSessionReplaced() throws Exception {
		String staleToken = tokenFor(STALE_TOKEN_ADMIN);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("UPDATE employees SET token_version = 2 WHERE id = " + STALE_TOKEN_ADMIN);
		}

		ResponseEntity<ApiErrorBody> response = restTemplate.exchange(
				BASE_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(staleToken)), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().code()).isEqualTo("session_replaced");
	}

	@Test
	void aTokenWithNoTokenVersionClaimAtAllIsRejected() {
		String tokenWithoutVersion = jwtService.issueAccessToken(ADMIN_1, ADMIN_1, COMPANY_1, "test-session");

		ResponseEntity<ApiErrorBody> response = restTemplate.exchange(
				BASE_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(tokenWithoutVersion)), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().code()).isEqualTo("session_replaced");
	}

	// ---------- create / D-047 ----------

	@Test
	void createSucceedsForAnAdminWithPermission() {
		ResponseEntity<LegacyExceptionTypeView> response =
				post(BASE_PATH, ADMIN_1, Map.of("name", "Freshly Created"), LegacyExceptionTypeView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().name()).isEqualTo("Freshly Created");
		assertThat(response.getBody().isActive()).isTrue();
	}

	@Test
	void createDuplicateNameInTheSameCompanyReturns409() {
		ResponseEntity<ApiErrorBody> response = post(BASE_PATH, ADMIN_1, Map.of("name", "Sick Leave"), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().code()).isEqualTo("already_exists");
	}

	/**
	 * D-7/D-051 (final): uniqueness is global, matching legacy's own
	 * {@code exception_type_name_exists()} and the real, table-wide
	 * {@code UNIQUE KEY unique_exception_type_name (name)} in the
	 * vendored schema (`mysql_workin.schema.sql:1158`). Company 1's own
	 * pre-check is correctly company-scoped (it never queries company
	 * 2's rows to decide company 1's conflict), but the database itself
	 * still refuses two companies sharing one name -- and per D-051 that
	 * is now the intended behaviour, not a flagged gap (D-047, the
	 * company-scoped alternative, was superseded).
	 */
	@Test
	void createTheSameNameInADifferentCompanyConflictsMatchingLegacysGlobalUniqueness() {
		ResponseEntity<ApiErrorBody> response =
				post(BASE_PATH, ADMIN_2, Map.of("name", "Sick Leave"), ApiErrorBody.class);

		assertThat(response.getStatusCode().value())
				.describedAs("exception_types.name is unique across every company (D-7/D-051) -- "
						+ "the real database-wide unique key is the final, accepted enforcement mechanism")
				.isEqualTo(409);
		assertThat(response.getBody().code()).isEqualTo("already_exists");
	}

	@Test
	void createWithABlankNameReturns400() {
		ResponseEntity<ApiErrorBody> response = post(BASE_PATH, ADMIN_1, Map.of("name", "   "), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().code()).isEqualTo("field_required");
	}

	// ---------- update ----------

	@Test
	void updatingARowsNameToItsOwnCurrentValueSucceeds() {
		ResponseEntity<LegacyExceptionTypeView> response =
				put(BASE_PATH + "/9207", ADMIN_1, Map.of("name", "Update Self"), LegacyExceptionTypeView.class);

		assertThat(response.getStatusCode().value())
				.describedAs("excluding the row's own id from the uniqueness check must prevent a false-positive 409")
				.isEqualTo(200);
		assertThat(response.getBody().name()).isEqualTo("Update Self");
	}

	@Test
	void updatingToAnotherRowsNameInTheSameCompanyReturns409() {
		ResponseEntity<ApiErrorBody> response =
				put(BASE_PATH + "/9206", ADMIN_1, Map.of("name", "Sick Leave"), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().code()).isEqualTo("already_exists");
	}

	@Test
	void updateWithNoRecognizedFieldsReturns400NothingToUpdate() {
		ResponseEntity<ApiErrorBody> response = put(BASE_PATH + "/9206", ADMIN_1, Map.of(), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().code()).isEqualTo("nothing_to_update");
	}

	// ---------- delete / D-046 / D-048 ----------

	@Test
	void deleteClearsForeignKeysAtomicallyAndHardDeletesWithoutTouchingAnotherTenant() throws Exception {
		ResponseEntity<Void> response = restTemplate.exchange(
				BASE_PATH + "/9204", HttpMethod.DELETE, new HttpEntity<>(headersFor(tokenFor(ADMIN_1))), Void.class);
		assertThat(response.getStatusCode().value()).isEqualTo(200);

		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			ResultSet deletedRow = st.executeQuery("SELECT COUNT(*) FROM exception_types WHERE id = 9204");
			deletedRow.next();
			assertThat(deletedRow.getInt(1)).isZero();

			ResultSet ownAttendance = st.executeQuery("SELECT exception_type_id FROM attendance WHERE id = 9301");
			ownAttendance.next();
			assertThat(ownAttendance.getObject(1)).isNull();

			ResultSet ownRequestType = st.executeQuery("SELECT exception_type_id FROM request_types WHERE id = 9401");
			ownRequestType.next();
			assertThat(ownRequestType.getObject(1)).isNull();

			// D-048: another tenant's rows referencing a same-shaped (same name, different company) exception
			// type must be provably untouched by this delete.
			ResultSet otherCompanyType = st.executeQuery("SELECT COUNT(*) FROM exception_types WHERE id = 9205");
			otherCompanyType.next();
			assertThat(otherCompanyType.getInt(1)).isEqualTo(1);

			ResultSet otherAttendance = st.executeQuery("SELECT exception_type_id FROM attendance WHERE id = 9302");
			otherAttendance.next();
			assertThat(otherAttendance.getLong(1)).isEqualTo(9205L);

			ResultSet otherRequestType = st.executeQuery("SELECT exception_type_id FROM request_types WHERE id = 9402");
			otherRequestType.next();
			assertThat(otherRequestType.getLong(1)).isEqualTo(9205L);
		}
	}

	@Test
	void deleteReturns404ForAnotherCompanysRowAndLeavesItIntact() throws Exception {
		ResponseEntity<ApiErrorBody> response = restTemplate.exchange(
				BASE_PATH + "/9203", HttpMethod.DELETE, new HttpEntity<>(headersFor(tokenFor(ADMIN_1))),
				ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);

		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			ResultSet stillThere = st.executeQuery("SELECT COUNT(*) FROM exception_types WHERE id = 9203");
			stillThere.next();
			assertThat(stillThere.getInt(1)).isEqualTo(1);
		}
	}

	// ---------- helpers ----------

	private String tokenFor(long employeeId) {
		String role = switch ((int) employeeId) {
			case 91011, 91021, 91031, 91041 -> "company_admin";
			case 91012 -> "hr";
			default -> "employee";
		};
		long companyId = employeeId == ADMIN_2 ? COMPANY_2 : employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private ResponseEntity<LegacyExceptionTypePage> get(String path, long employeeId) {
		return restTemplate.exchange(
				path, HttpMethod.GET, new HttpEntity<>(headersFor(tokenFor(employeeId))), LegacyExceptionTypePage.class);
	}

	private <T> ResponseEntity<T> get(String path, long employeeId, Class<T> type) {
		return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headersFor(tokenFor(employeeId))), type);
	}

	private <T> ResponseEntity<T> post(String path, long employeeId, Map<String, Object> body, Class<T> type) {
		return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headersFor(tokenFor(employeeId))), type);
	}

	private <T> ResponseEntity<T> put(String path, long employeeId, Map<String, Object> body, Class<T> type) {
		return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headersFor(tokenFor(employeeId))), type);
	}

	private HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

}
