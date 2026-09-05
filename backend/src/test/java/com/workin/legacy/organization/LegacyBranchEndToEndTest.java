package com.workin.legacy.organization;

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
 * PR 12.3a's full HTTP-to-real-MariaDB proof, retrofitted for Wave 12.R
 * (D-108): the same guard-stack, D-056 (delete pre-check), D-057
 * (no-permission-gate, role gate applying to reads too) and D-060
 * (update-ownership divergence) coverage, now exercised against the literal
 * {@code /apis/api/branches/*.php} routes and the D-074 PHP envelope instead
 * of the retired {@code /api/legacy/branches} REST surface.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyBranchEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String BASE_PATH = "/apis/api/branches";
	private static final String LIST = BASE_PATH + "/list.php";
	private static final String ONE = BASE_PATH + "/one.php";
	private static final String CREATE = BASE_PATH + "/create.php";
	private static final String UPDATE = BASE_PATH + "/update.php";
	private static final String DELETE = BASE_PATH + "/delete.php";
	private static final String GENERATE_QR = BASE_PATH + "/generate_qr.php";

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
			applySchema("db/phase1-mysql/phase1_extensions.sql");
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
			// branches.created_at is `timestamp` (same D-107 finding as exception_types): match
			// LegacySessionDataSource's LegacyRuntimeOffset.DEFAULT so seeded literals round-trip.
			st.execute("SET time_zone = '+02:00'");
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
		Map<String, Object> body = send(LIST, PLAIN_EMPLOYEE_1, HttpMethod.GET, null, 403, "");
		assertThat(body.get("message")).isEqualTo("Forbidden — insufficient role");
	}

	@Test
	void managerCanListBranches() {
		send(LIST, MANAGER_1, HttpMethod.GET, null, 200, "");
	}

	@Test
	void inactiveCompanyReturns403OnList() {
		Map<String, Object> body = send(LIST, ADMIN_SUSPENDED, HttpMethod.GET, null, 403, "");
		assertThat(body.get("message")).isEqualTo("Company account is not active");
	}

	@Test
	void aStaleTokenVersionIsRejectedAsSessionReplaced() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("UPDATE employees SET token_version = token_version + 1 WHERE id = " + STALE_TOKEN_ADMIN);
		}
		String token = jwtService.issueAccessToken(
				STALE_TOKEN_ADMIN, STALE_TOKEN_ADMIN, COMPANY_1, "test-session",
				Map.of("role", "company_admin", "token_version", 1L));
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + LIST), HttpMethod.GET,
				new HttpEntity<>(headersFor(token)), mapType());
		assertThat(response.getStatusCode().value()).isEqualTo(401);
	}

	/** D-057: no hr_permissions row exists for ADMIN_1 at all -- every write must still succeed. */
	@Test
	void adminWithNoHrPermissionsRowSucceedsOnWrites() {
		send(CREATE, ADMIN_1, HttpMethod.POST, "{\"name\":\"No Permission Row Needed\"}", 201, "");
	}

	// ---------- list / one ----------

	@Test
	void listOnlyShowsActiveBranches() {
		List<Map<String, Object>> rows = rowsOf(send(LIST, ADMIN_1, HttpMethod.GET, null, 200, ""));
		assertThat(rows).extracting(row -> row.get("name")).doesNotContain("Inactive Branch");
	}

	@Test
	void searchFiltersByNameOrAddress() {
		List<Map<String, Object>> rows = rowsOf(send(LIST, ADMIN_1, HttpMethod.GET, null, 200, "?search=Downtown"));
		assertThat(rows).extracting(row -> row.get("name")).containsExactly("Search Target Alpha");
	}

	@Test
	void oneReturnsTheWireFaithfulBranchForItsOwnCompany() {
		Map<String, Object> row = dataOf(send(ONE, ADMIN_1, HttpMethod.GET, null, 200, "?id=8901"));
		assertThat(row.get("name")).isEqualTo("HQ One");
		assertThat(row.get("created_at")).isEqualTo("2025-03-01 10:00:00");
	}

	/** Legacy's own (unusual) pairing: message key "forbidden", HTTP 404. */
	@Test
	void oneReturns404WithForbiddenMessageForAnotherCompanysRow() {
		Map<String, Object> body = send(ONE, ADMIN_1, HttpMethod.GET, null, 404, "?id=8904");
		assertThat(body.get("message")).isEqualTo("Forbidden");
	}

	@Test
	void oneReturns404ForAnInactiveBranchEvenWithinTheSameCompany() {
		Map<String, Object> body = send(ONE, ADMIN_1, HttpMethod.GET, null, 404, "?id=8903");
		assertThat(body.get("message")).isEqualTo("Forbidden");
	}

	@Test
	void oneWithNoIdReturns400IdRequired() {
		Map<String, Object> body = send(ONE, ADMIN_1, HttpMethod.GET, null, 400, "");
		assertThat(body.get("message")).isEqualTo("id required");
	}

	// ---------- create ----------

	@Test
	void createWithoutNameReturns400() {
		Map<String, Object> body = send(CREATE, ADMIN_1, HttpMethod.POST, "{}", 400, "");
		assertThat(body.get("message")).isEqualTo("Field 'name' is required");
	}

	@Test
	void createWithValidCoordinatesSucceeds() {
		Map<String, Object> row = dataOf(send(
				CREATE, ADMIN_1, HttpMethod.POST,
				"{\"name\":\"Valid Coords Branch\",\"latitude\":30.0444,\"longitude\":31.2357}", 201, ""));
		assertThat(row.get("latitude")).isNotNull();
	}

	@Test
	void createWithNearZeroZeroCoordinatesReturns422() {
		Map<String, Object> body = send(
				CREATE, ADMIN_1, HttpMethod.POST,
				"{\"name\":\"Bad Coords Branch\",\"latitude\":0.0000001,\"longitude\":0.0000001}", 422, "");
		assertThat(body.get("message")).isEqualTo(
				"Invalid location link or coordinates. Use a full Google Maps link or valid latitude and longitude.");
	}

	/** The zoom-level/latitude mixup: lng looks like an integer zoom level (1-21), lat looks like a real latitude (20-70 abs). */
	@Test
	void createWithZoomLevelMixupCoordinatesReturns422() {
		Map<String, Object> body = send(
				CREATE, ADMIN_1, HttpMethod.POST,
				"{\"name\":\"Zoom Mixup Branch\",\"latitude\":30.5,\"longitude\":15.0}", 422, "");
		assertThat(String.valueOf(body.get("message"))).contains("Invalid location link");
	}

	@Test
	void createWithAGoogleMapsAtCoordinateLinkParsesTheLocation() {
		Map<String, Object> row = dataOf(send(
				CREATE, ADMIN_1, HttpMethod.POST,
				"{\"name\":\"Link Parsed Branch\","
						+ "\"location_link\":\"https://www.google.com/maps/@30.0444200,31.2357100,15z\"}",
				201, ""));
		assertThat(new java.math.BigDecimal(row.get("latitude").toString()))
				.isEqualByComparingTo(java.math.BigDecimal.valueOf(30.04442));
	}

	@Test
	void createWithNoLocationAtAllSucceedsWithNullCoordinates() {
		Map<String, Object> row = dataOf(send(CREATE, ADMIN_1, HttpMethod.POST, "{\"name\":\"No Location Branch\"}", 201, ""));
		assertThat(row.get("latitude")).isNull();
		assertThat(row.get("longitude")).isNull();
		assertThat(row.get("radius_meters")).isEqualTo(200);
	}

	@Test
	void createWithRadiusMetersZeroPreservesZeroNotTheDefault() {
		Map<String, Object> row = dataOf(send(
				CREATE, ADMIN_1, HttpMethod.POST, "{\"name\":\"Zero Radius Create\",\"radius_meters\":0}", 201, ""));
		assertThat(row.get("radius_meters")).isEqualTo(0);
	}

	// ---------- update ----------

	@Test
	void updateOnlyTouchesFieldsSentAndNonNull() {
		Map<String, Object> row = dataOf(send(UPDATE, ADMIN_1, HttpMethod.PUT, "{\"radius_meters\":500}", 200, "?id=8907"));
		assertThat(row.get("radius_meters")).isEqualTo(500);
		assertThat(row.get("address")).isEqualTo("Original Address");
	}

	/**
	 * The isset()-semantics quirk: an explicit {@code null} is
	 * indistinguishable from an omitted field in legacy's own {@code
	 * update.php} -- sending {@code address: null} must leave the
	 * existing value untouched, not clear it.
	 */
	@Test
	void updateWithAnExplicitNullFieldDoesNotClearTheExistingValue() {
		Map<String, Object> row = dataOf(send(UPDATE, ADMIN_1, HttpMethod.PUT, "{\"address\":null}", 200, "?id=8907"));
		assertThat(row.get("address")).isEqualTo("Original Address");
	}

	@Test
	void updateWithNoFieldsAtAllIsASilentNoOpNot400() {
		Map<String, Object> row = dataOf(send(UPDATE, ADMIN_1, HttpMethod.PUT, "{}", 200, "?id=8907"));
		assertThat(row.get("address")).isEqualTo("Original Address");
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
		Map<String, Object> body = send(UPDATE, ADMIN_1, HttpMethod.PUT, "{\"name\":\"Hijacked\"}", 404, "?id=8904");
		assertThat(body.get("message")).isEqualTo("Branch not found");
	}

	/** D-060: missing and cross-tenant ids deliberately share one non-enumerating 404 contract. */
	@Test
	void updateOfMissingBranchReturnsTheSame404AsAnotherTenantsBranch() {
		Map<String, Object> body = send(UPDATE, ADMIN_1, HttpMethod.PUT, "{\"name\":\"Missing\"}", 404, "?id=899999");
		assertThat(body.get("message")).isEqualTo("Branch not found");
	}

	// ---------- delete (D-056) ----------

	@Test
	void deleteWithAssignedEmployeesReturns409BeforeAttemptingTheDelete() throws Exception {
		Map<String, Object> body = send(DELETE, ADMIN_1, HttpMethod.DELETE, null, 409, "?id=8906");
		assertThat(body.get("message")).isEqualTo(
				"Cannot delete this branch because it has employees assigned. Remove or reassign employees first.");
		assertThat(branchExists(8906L)).describedAs("the pre-check must short-circuit before any delete").isTrue();
	}

	@Test
	void deleteWithNoAssignedEmployeesSucceedsAndClearsDepartmentBranchesAtomically() throws Exception {
		assertThat(departmentBranchLinkCount(8905L)).isEqualTo(1);

		send(DELETE, ADMIN_1, HttpMethod.DELETE, null, 200, "?id=8905");

		assertThat(branchExists(8905L)).isFalse();
		assertThat(departmentBranchLinkCount(8905L)).isEqualTo(0);
	}

	@Test
	void deleteOfAnotherCompanysBranchReturns404AndLeavesItIntact() throws Exception {
		send(DELETE, ADMIN_1, HttpMethod.DELETE, null, 404, "?id=8904");
		assertThat(branchExists(8904L)).isTrue();
	}

	// ---------- generate_qr ----------

	@Test
	void generateQrSucceedsAndSetsQrCodeAndExpiresAt() {
		Map<String, Object> row = dataOf(send(
				GENERATE_QR, ADMIN_1, HttpMethod.POST, "{\"expires_at\":\"2026-12-31T00:00:00Z\"}", 200, "?id=8901"));
		assertThat(row.get("qr_code")).isNotNull();
		assertThat(row.get("expires_at")).isNotNull();
	}

	@Test
	void generateQrWithoutExpiresAtReturns400() {
		Map<String, Object> body = send(GENERATE_QR, ADMIN_1, HttpMethod.POST, "{}", 400, "?id=8901");
		assertThat(body.get("message")).isEqualTo("Field 'expires_at' is required");
	}

	/** Legacy's own {@code fail(LangKey::FORBIDDEN)} omits the status arg -- default 400, not 403/404. */
	@Test
	void generateQrForAnotherCompanysBranchReturns400WithForbiddenMessage() {
		Map<String, Object> body = send(
				GENERATE_QR, ADMIN_1, HttpMethod.POST, "{\"expires_at\":\"2026-12-31T00:00:00Z\"}", 400, "?id=8904");
		assertThat(body.get("message")).isEqualTo("Forbidden");
	}

	/**
	 * The parity harness reduces {@code qr_code} to its SHAPE when comparing
	 * PHP against Java, because two correct implementations must produce
	 * different random values. That normalisation cannot tell a random code
	 * from a constant of the same shape, so the randomness is asserted here,
	 * where more than one sample is available.
	 */
	@Test
	void generateQrProducesADifferentCodeEachTimeInLowercaseHex() {
		String first = String.valueOf(dataOf(send(
				GENERATE_QR, ADMIN_1, HttpMethod.POST, "{\"expires_at\":\"2026-12-31T00:00:00Z\"}", 200, "?id=8901"))
				.get("qr_code"));
		String second = String.valueOf(dataOf(send(
				GENERATE_QR, ADMIN_1, HttpMethod.POST, "{\"expires_at\":\"2026-12-31T00:00:00Z\"}", 200, "?id=8901"))
				.get("qr_code"));

		assertThat(first)
				.as("bin2hex(random_bytes(16)) is 32 lowercase hex characters")
				.matches("^[0-9a-f]{32}$");
		assertThat(second).matches("^[0-9a-f]{32}$");
		assertThat(first)
				.as("a constant would pass the harness's shape check; this is what rules it out")
				.isNotEqualTo(second);
	}

	/**
	 * {@code strtotime()} accepts far more than ISO-8601, and Java rejected the
	 * one form PHP itself writes: {@code date('Y-m-d H:i:s')}, which is also
	 * what the column stores and what {@code expires_at} is returned as. So
	 * reading a branch and posting its own {@code expires_at} back answered 400
	 * on Java and 200 on PHP. Found by the parity harness's mutation sweep.
	 *
	 * <p>The ISO forms are asserted alongside deliberately: the desktop client
	 * sends {@code DateTime.toIso8601String()}, which emits fractional seconds,
	 * and that form must keep working -- the bounded {@code LegacyPhpStrtotime}
	 * grammar does not cover it, so the ISO attempts have to stay first.
	 */
	@Test
	void generateQrAcceptsEveryExpiresAtSpellingStrtotimeDoes() {
		// The form PHP writes and the column stores -- the regression.
		assertThat(dataOf(send(GENERATE_QR, ADMIN_1, HttpMethod.POST,
				"{\"expires_at\":\"2026-12-31 00:00:00\"}", 200, "?id=8901")).get("expires_at"))
				.isEqualTo("2026-12-31 00:00:00");
		// Without seconds.
		assertThat(dataOf(send(GENERATE_QR, ADMIN_1, HttpMethod.POST,
				"{\"expires_at\":\"2026-12-31 12:30\"}", 200, "?id=8901")).get("expires_at"))
				.isEqualTo("2026-12-31 12:30:00");
		// American slashes, which strtotime reads month-first.
		assertThat(dataOf(send(GENERATE_QR, ADMIN_1, HttpMethod.POST,
				"{\"expires_at\":\"12/31/2026\"}", 200, "?id=8901")).get("expires_at"))
				.isEqualTo("2026-12-31 00:00:00");
		// What the desktop client actually sends: ISO with fractional seconds.
		assertThat(dataOf(send(GENERATE_QR, ADMIN_1, HttpMethod.POST,
				"{\"expires_at\":\"2026-12-31T00:00:00.000\"}", 200, "?id=8901")).get("expires_at"))
				.isEqualTo("2026-12-31 00:00:00");
		// Date only, and ISO with a zone.
		assertThat(dataOf(send(GENERATE_QR, ADMIN_1, HttpMethod.POST,
				"{\"expires_at\":\"2026-12-31\"}", 200, "?id=8901")).get("expires_at"))
				.isEqualTo("2026-12-31 00:00:00");
		assertThat(dataOf(send(GENERATE_QR, ADMIN_1, HttpMethod.POST,
				"{\"expires_at\":\"2026-12-31T00:00:00Z\"}", 200, "?id=8901")).get("expires_at"))
				.isEqualTo("2026-12-31 00:00:00");

		// Still refused, and recorded as such: LegacyPhpStrtotime does not
		// implement the relative-offset family (D-094), and no client builds a
		// QR expiry that way. Garbage is refused by both.
		assertThat(send(GENERATE_QR, ADMIN_1, HttpMethod.POST,
				"{\"expires_at\":\"garbage\"}", 400, "?id=8901").get("message"))
				.isEqualTo("Invalid date");
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

	private HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

	private Map<String, Object> send(
			String path, long actor, HttpMethod method, String json, int expectedStatus, String query) {
		HttpHeaders headers = headersFor(tokenFor(actor));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path + query), method,
				new HttpEntity<>(json, headers), mapType());
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(expectedStatus);
		return response.getBody();
	}

	private static ParameterizedTypeReference<Map<String, Object>> mapType() {
		return new ParameterizedTypeReference<Map<String, Object>>() { };
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rowsOf(Map<String, Object> body) {
		return (List<Map<String, Object>>) body.get("data");
	}

}
