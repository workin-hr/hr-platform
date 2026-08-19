package com.workin.legacy.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.i18n.ApiErrorBody;
import com.workin.backend.identity.JwtService;

/**
 * PR 12.3a's full HTTP-to-real-MariaDB proof, mirroring {@code
 * LegacyExceptionTypeEndToEndTest}'s structure: real JWTs, {@code
 * phase1-mysql} active, every request through the real {@link
 * LegacyBranchController}. Covers D-056 (delete pre-check), D-057
 * (verified no-permission-gate negative, and the role gate applying to
 * reads too, unlike Wave 12.1), and the approved D-060 update-ownership
 * divergence documented on {@link LegacyBranchService#update}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyBranchEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String BASE_PATH = "/api/legacy/branches";

	private static final long COMPANY_1 = 8801L;
	private static final long COMPANY_2 = 8802L;
	private static final long COMPANY_SUSPENDED = 8803L;

	private static final long ADMIN_1 = 88011L; // COMPANY_1, company_admin, no hr_permissions row at all (D-057)
	private static final long HR_1 = 88012L; // COMPANY_1, hr
	private static final long MANAGER_1 = 88013L; // COMPANY_1, manager -- must succeed (D-057: role gate includes MANAGER)
	private static final long PLAIN_EMPLOYEE_1 = 88014L; // COMPANY_1, employee -- must be denied everywhere
	private static final long ADMIN_2 = 88021L; // COMPANY_2, company_admin
	private static final long ADMIN_SUSPENDED = 88031L; // COMPANY_SUSPENDED, company_admin
	private static final long STALE_TOKEN_ADMIN = 88041L; // COMPANY_1, dedicated to the token_version test

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
			throw new IllegalStateException("could not prepare the branches e2e fixture", ex);
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
					  (8801, 'Branch E2E Co 1', '+201000008801', 'active', '2025-01-15 09:00:00'),
					  (8802, 'Branch E2E Co 2', '+201000008802', 'active', '2025-01-15 09:00:00'),
					  (8803, 'Branch E2E Co Suspended', '+201000008803', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, address, is_active, created_at) VALUES
					  (8901, 8801, 'HQ One', 'Head Office St', 1, '2025-03-01 10:00:00'),
					  (8902, 8801, 'Search Target Alpha', 'Downtown Ave', 1, '2025-03-01 10:00:00'),
					  (8903, 8801, 'Inactive Branch', NULL, 0, '2025-03-01 10:00:00'),
					  (8904, 8802, 'Other Co Branch', NULL, 1, '2025-03-01 10:00:00'),
					  (8905, 8801, 'Delete Target No Employees', NULL, 1, '2025-03-01 10:00:00'),
					  (8906, 8801, 'Delete Target With Employees', NULL, 1, '2025-03-01 10:00:00'),
					  (8907, 8801, 'Update Target', 'Original Address', 1, '2025-03-01 10:00:00'),
					  (8908, 8803, 'Suspended Co Branch', NULL, 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (8941, 8801, 'E2E Dept', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO department_branches (department_id, branch_id) VALUES (8941, 8905)
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, first_name, last_name, phone, role,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (88011, 8801, 8901, 'Admin', 'One', '+201100088011', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (88012, 8801, 8901, 'Hr', 'One', '+201100088012', 'hr', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (88013, 8801, 8901, 'Manager', 'One', '+201100088013', 'manager', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (88014, 8801, 8901, 'Plain', 'One', '+201100088014', 'employee', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (88021, 8802, 8904, 'Admin', 'Two', '+201100088021', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (88031, 8803, 8908, 'Admin', 'Suspended', '+201100088031', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (88041, 8801, 8901, 'Stale', 'Token', '+201100088041', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (88051, 8801, 8906, 'Blocks', 'Delete', '+201100088051', 'employee', 1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00')
					""");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyBranchEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static boolean branchExists(long id) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM branches WHERE id = " + id)) {
			rs.next();
			return rs.getLong(1) > 0;
		}
	}

	private static long departmentBranchLinkCount(long branchId) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM department_branches WHERE branch_id = " + branchId)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	// ---------- guard stack ----------

	@Test
	void plainEmployeeIsDeniedOnListDespiteAuthenticAuthentication() {
		ResponseEntity<ApiErrorBody> response = get(BASE_PATH, PLAIN_EMPLOYEE_1, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().code()).isEqualTo("forbidden_insufficient_role");
	}

	@Test
	void managerCanListBranches() {
		ResponseEntity<LegacyBranchPage> response = get(BASE_PATH, MANAGER_1, LegacyBranchPage.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void inactiveCompanyReturns403OnList() {
		ResponseEntity<ApiErrorBody> response = get(BASE_PATH, ADMIN_SUSPENDED, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().code()).isEqualTo("company_account_not_active");
	}

	@Test
	void aStaleTokenVersionIsRejectedAsSessionReplaced() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("UPDATE employees SET token_version = token_version + 1 WHERE id = " + STALE_TOKEN_ADMIN);
		}
		String token = jwtService.issueAccessToken(
				STALE_TOKEN_ADMIN, STALE_TOKEN_ADMIN, COMPANY_1, "test-session",
				Map.of("role", "company_admin", "token_version", 1L));
		ResponseEntity<ApiErrorBody> response = restTemplate.exchange(
				BASE_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(token)), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().code()).isEqualTo("session_replaced");
	}

	/** D-057: no hr_permissions row exists for ADMIN_1 at all -- every write must still succeed. */
	@Test
	void adminWithNoHrPermissionsRowSucceedsOnWrites() {
		ResponseEntity<LegacyBranchView> response = post(
				BASE_PATH, ADMIN_1, Map.of("name", "No Permission Row Needed"), LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
	}

	// ---------- list / one ----------

	@Test
	void listOnlyShowsActiveBranches() {
		ResponseEntity<LegacyBranchPage> response = get(BASE_PATH, ADMIN_1, LegacyBranchPage.class);

		assertThat(response.getBody().data()).extracting(LegacyBranchListItem::name).doesNotContain("Inactive Branch");
	}

	@Test
	void searchFiltersByNameOrAddress() {
		ResponseEntity<LegacyBranchPage> response = get(BASE_PATH + "?search=Downtown", ADMIN_1, LegacyBranchPage.class);

		assertThat(response.getBody().data()).extracting(LegacyBranchListItem::name).containsExactly("Search Target Alpha");
	}

	@Test
	void oneReturnsTheBranchForItsOwnCompany() {
		ResponseEntity<LegacyBranchView> response = get(BASE_PATH + "/8901", ADMIN_1, LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().name()).isEqualTo("HQ One");
	}

	/** Legacy's own (unusual) pairing: message key "forbidden", HTTP 404. */
	@Test
	void oneReturns404WithForbiddenKeyForAnotherCompanysRow() {
		ResponseEntity<ApiErrorBody> response = get(BASE_PATH + "/8904", ADMIN_1, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("forbidden");
	}

	@Test
	void oneReturns404ForAnInactiveBranchEvenWithinTheSameCompany() {
		ResponseEntity<ApiErrorBody> response = get(BASE_PATH + "/8903", ADMIN_1, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("forbidden");
	}

	// ---------- create ----------

	@Test
	void createWithoutNameReturns400() {
		ResponseEntity<ApiErrorBody> response = post(BASE_PATH, ADMIN_1, Map.of(), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().code()).isEqualTo("field_required");
	}

	@Test
	void createWithValidCoordinatesSucceeds() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Valid Coords Branch");
		body.put("latitude", 30.0444);
		body.put("longitude", 31.2357);
		ResponseEntity<LegacyBranchView> response = post(BASE_PATH, ADMIN_1, body, LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().latitude()).isNotNull();
	}

	@Test
	void createWithNearZeroZeroCoordinatesReturns422() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Bad Coords Branch");
		body.put("latitude", 0.0000001);
		body.put("longitude", 0.0000001);
		ResponseEntity<ApiErrorBody> response = post(BASE_PATH, ADMIN_1, body, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(response.getBody().code()).isEqualTo("invalid_branch_location");
	}

	/** The zoom-level/latitude mixup: lng looks like an integer zoom level (1-21), lat looks like a real latitude (20-70 abs). */
	@Test
	void createWithZoomLevelMixupCoordinatesReturns422() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Zoom Mixup Branch");
		body.put("latitude", 30.5);
		body.put("longitude", 15.0);
		ResponseEntity<ApiErrorBody> response = post(BASE_PATH, ADMIN_1, body, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(response.getBody().code()).isEqualTo("invalid_branch_location");
	}

	@Test
	void createWithAGoogleMapsAtCoordinateLinkParsesTheLocation() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Link Parsed Branch");
		body.put("locationLink", "https://www.google.com/maps/@30.0444200,31.2357100,15z");
		ResponseEntity<LegacyBranchView> response = post(BASE_PATH, ADMIN_1, body, LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().latitude()).isEqualByComparingTo(java.math.BigDecimal.valueOf(30.04442));
	}

	@Test
	void createWithNoLocationAtAllSucceedsWithNullCoordinates() {
		ResponseEntity<LegacyBranchView> response = post(BASE_PATH, ADMIN_1, Map.of("name", "No Location Branch"), LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().latitude()).isNull();
		assertThat(response.getBody().longitude()).isNull();
		assertThat(response.getBody().radiusMeters()).isEqualTo(200);
	}

	@Test
	void createWithRadiusMetersZeroPreservesZeroNotTheDefault() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Zero Radius Create");
		body.put("radiusMeters", 0);
		ResponseEntity<LegacyBranchView> response = post(BASE_PATH, ADMIN_1, body, LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().radiusMeters()).isEqualTo(0);
	}

	// ---------- update ----------

	@Test
	void updateOnlyTouchesFieldsSentAndNonNull() {
		Map<String, Object> body = new HashMap<>();
		body.put("radiusMeters", 500);
		ResponseEntity<LegacyBranchView> response = put(BASE_PATH + "/8907", ADMIN_1, body, LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().radiusMeters()).isEqualTo(500);
		assertThat(response.getBody().address()).isEqualTo("Original Address");
	}

	/**
	 * The isset()-semantics quirk: an explicit {@code null} is
	 * indistinguishable from an omitted field in legacy's own {@code
	 * update.php} -- sending {@code address: null} must leave the
	 * existing value untouched, not clear it.
	 */
	@Test
	void updateWithAnExplicitNullFieldDoesNotClearTheExistingValue() {
		Map<String, Object> body = new HashMap<>();
		body.put("address", null);
		ResponseEntity<LegacyBranchView> response = put(BASE_PATH + "/8907", ADMIN_1, body, LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().address()).isEqualTo("Original Address");
	}

	@Test
	void updateWithNoFieldsAtAllIsASilentNoOpNot400() {
		ResponseEntity<LegacyBranchView> response = put(BASE_PATH + "/8907", ADMIN_1, Map.of(), LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().address()).isEqualTo("Original Address");
	}

	/**
	 * The D-060 approved divergence from legacy (see {@code
	 * LegacyBranchService#update}'s javadoc): legacy's own update has no
	 * ownership check at all and would leak another company's branch
	 * data back in the response. This proves the safe behaviour actually
	 * implemented instead: 404, not a cross-tenant read.
	 */
	@Test
	void updateOfAnotherCompanysBranchReturns404NotTheOtherCompanysData() {
		ResponseEntity<ApiErrorBody> response = put(BASE_PATH + "/8904", ADMIN_1, Map.of("name", "Hijacked"), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("branch_not_found");
	}

	/** D-060: missing and cross-tenant ids deliberately share one non-enumerating 404 contract. */
	@Test
	void updateOfMissingBranchReturnsTheSame404AsAnotherTenantsBranch() {
		ResponseEntity<ApiErrorBody> response = put(
				BASE_PATH + "/899999", ADMIN_1, Map.of("name", "Missing"), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("branch_not_found");
	}

	// ---------- delete (D-056) ----------

	@Test
	void deleteWithAssignedEmployeesReturns409BeforeAttemptingTheDelete() throws Exception {
		ResponseEntity<ApiErrorBody> response = delete(BASE_PATH + "/8906", ADMIN_1, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().code()).isEqualTo("branch_has_employees_cannot_delete");
		assertThat(branchExists(8906L)).describedAs("the pre-check must short-circuit before any delete").isTrue();
	}

	@Test
	void deleteWithNoAssignedEmployeesSucceedsAndClearsDepartmentBranchesAtomically() throws Exception {
		assertThat(departmentBranchLinkCount(8905L)).isEqualTo(1);

		ResponseEntity<Void> response = delete(BASE_PATH + "/8905", ADMIN_1, Void.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(branchExists(8905L)).isFalse();
		assertThat(departmentBranchLinkCount(8905L)).isEqualTo(0);
	}

	@Test
	void deleteOfAnotherCompanysBranchReturns404AndLeavesItIntact() throws Exception {
		ResponseEntity<ApiErrorBody> response = delete(BASE_PATH + "/8904", ADMIN_1, ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(branchExists(8904L)).isTrue();
	}

	// ---------- generate_qr ----------

	@Test
	void generateQrSucceedsAndSetsQrCodeAndExpiresAt() {
		ResponseEntity<LegacyBranchView> response = post(
				BASE_PATH + "/8901/qr", ADMIN_1, Map.of("expiresAt", "2026-12-31T00:00:00Z"), LegacyBranchView.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().qrCode()).isNotBlank();
		assertThat(response.getBody().expiresAt()).isNotNull();
	}

	@Test
	void generateQrWithoutExpiresAtReturns400() {
		ResponseEntity<ApiErrorBody> response = post(BASE_PATH + "/8901/qr", ADMIN_1, Map.of(), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().code()).isEqualTo("field_required");
	}

	/** Legacy's own {@code fail(LangKey::FORBIDDEN)} omits the status arg -- default 400, not 403/404. */
	@Test
	void generateQrForAnotherCompanysBranchReturns400WithForbiddenKey() {
		ResponseEntity<ApiErrorBody> response = post(
				BASE_PATH + "/8904/qr", ADMIN_1, Map.of("expiresAt", "2026-12-31T00:00:00Z"), ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().code()).isEqualTo("forbidden");
	}

	// ---------- helpers ----------

	private String tokenFor(long employeeId) {
		String role = switch ((int) employeeId) {
			case 88011, 88021, 88031, 88041 -> "company_admin";
			case 88012 -> "hr";
			case 88013 -> "manager";
			default -> "employee";
		};
		long companyId = employeeId == ADMIN_2 ? COMPANY_2 : employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
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

	private <T> ResponseEntity<T> delete(String path, long employeeId, Class<T> type) {
		return restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headersFor(tokenFor(employeeId))), type);
	}

	private HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

}
