package com.workin.legacy.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Wave 13.4a's ten endpoints. The assertions concentrate on where the two
 * modules <em>disagree</em> with each other, because that is what a shared
 * abstraction would quietly erase.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyRecordsEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String ASSET_LIST = "/apis/api/assets/list.php";
	private static final String ASSET_ONE = "/apis/api/assets/one.php";
	private static final String ASSET_CREATE = "/apis/api/assets/create.php";
	private static final String ASSET_UPDATE = "/apis/api/assets/update.php";
	private static final String ASSET_DELETE = "/apis/api/assets/delete.php";
	private static final String DEC_LIST = "/apis/api/administrative_decisions/list.php";
	private static final String DEC_ONE = "/apis/api/administrative_decisions/one.php";
	private static final String DEC_CREATE = "/apis/api/administrative_decisions/create.php";
	private static final String DEC_UPDATE = "/apis/api/administrative_decisions/update.php";
	private static final String DEC_DELETE = "/apis/api/administrative_decisions/delete.php";

	private static final long COMPANY = 27001L;
	private static final long OTHER_COMPANY = 27002L;
	private static final long ADMIN = 270011L;
	private static final long HR_NO_PERMISSION = 270012L;
	private static final long MANAGER = 270013L;
	private static final long STAFF = 270014L;
	private static final long OTHER_STAFF = 270021L;
	private static final long BRANCH = 27011L;

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
			throw new IllegalStateException("could not prepare the records fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ---------------- the two modules disagree ----------------

	/**
	 * {@code assets} enforces no {@code hr_permissions} gate at all while
	 * {@code administrative_decisions} requires {@code can_employees} on every
	 * route but its list. The desktop client hides Assets behind a permission
	 * flag the server does not check (D-130).
	 */
	@Test
	@Order(1)
	void assetsEnforceNoPermissionWhileDecisionsRequireCanEmployees() {
		assertThat(send(ASSET_LIST, HttpMethod.GET, token(HR_NO_PERMISSION, "hr"), null)
				.getStatusCode().value())
				.as("no permission gate anywhere in assets")
				.isEqualTo(200);
		assertThat(send(DEC_ONE + "?id=1", HttpMethod.GET, token(HR_NO_PERMISSION, "hr"), null)
				.getStatusCode().value())
				.as("can_employees is required here")
				.isEqualTo(403);
	}

	/** An employee may page their own assets but cannot fetch one by id. */
	@Test
	@Order(2)
	@SuppressWarnings("unchecked")
	void anEmployeeMayListAssetsButNotReadOne() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(
				send(ASSET_LIST, HttpMethod.GET, token(STAFF, "employee"), null));
		assertThat(rows).as("scoped to their own custody records").hasSize(1);
		assertThat(rows.get(0)).containsEntry("asset_text", "Laptop");

		assertThat(send(ASSET_ONE + "?id=1", HttpMethod.GET, token(STAFF, "employee"), null)
				.getStatusCode().value())
				.as("one.php does not admit EMPLOYEE at all")
				.isEqualTo(403);
	}

	/**
	 * The decisions list admits an employee <em>and</em> a manager, but hides
	 * inactive rows from the employee only -- while {@code one.php} admits
	 * neither role.
	 */
	@Test
	@Order(3)
	@SuppressWarnings("unchecked")
	void theDecisionsListAdmitsEmployeeAndManagerWithDifferentRowVisibility() {
		List<Map<String, Object>> employeeRows = (List<Map<String, Object>>) data(
				send(DEC_LIST, HttpMethod.GET, token(STAFF, "employee"), null));
		List<Map<String, Object>> managerRows = (List<Map<String, Object>>) data(
				send(DEC_LIST, HttpMethod.GET, token(MANAGER, "manager"), null));

		assertThat(employeeRows).as("is_active = 1 only").hasSize(1);
		assertThat(managerRows).as("a manager sees the inactive one too").hasSize(2);

		assertThat(send(DEC_ONE + "?id=1", HttpMethod.GET, token(MANAGER, "manager"), null)
				.getStatusCode().value())
				.as("a manager can list but cannot read one")
				.isEqualTo(403);
	}

	/**
	 * The same request body sets opposite booleans in the two modules:
	 * {@code assets} uses {@code FILTER_VALIDATE_BOOLEAN} while
	 * {@code administrative_decisions} uses an exact {@code (int) === 1}.
	 */
	@Test
	@Order(4)
	@SuppressWarnings("unchecked")
	void theTwoModulesReadTheStringTrueAsOppositeBooleans() {
		Map<String, Object> asset = (Map<String, Object>) data(send(ASSET_UPDATE + "?id=1",
				HttpMethod.PUT, token(ADMIN, "company_admin"), "{\"is_returned\":\"true\"}"));
		assertThat(((Number) asset.get("is_returned")).intValue())
				.as("filter_var(\"true\", FILTER_VALIDATE_BOOLEAN) is true")
				.isEqualTo(1);

		Map<String, Object> decision = (Map<String, Object>) data(send(DEC_UPDATE + "?id=1",
				HttpMethod.PUT, token(ADMIN, "company_admin"), "{\"is_active\":\"true\"}"));
		assertThat(((Number) decision.get("is_active")).intValue())
				.as("(int) \"true\" is 0, so the same value DEACTIVATES here")
				.isZero();

		// Restore for the later tests.
		data(send(DEC_UPDATE + "?id=1", HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"is_active\":1}"));
		data(send(ASSET_UPDATE + "?id=1", HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"is_returned\":0}"));
	}

	/** Two deletes, two body shapes. */
	@Test
	@Order(5)
	void theTwoDeletesReturnDifferentBodies() {
		long assetId = createAsset("Spare phone");
		ResponseEntity<Map<String, Object>> assetDeleted =
				send(ASSET_DELETE + "?id=" + assetId, HttpMethod.DELETE, token(ADMIN, "company_admin"), null);
		assertThat(assetDeleted.getStatusCode().value()).isEqualTo(200);
		assertThat(assetDeleted.getBody().get("data")).isEqualTo(Map.of("deleted", true));

		long decisionId = createDecision("Temp", "Body");
		ResponseEntity<Map<String, Object>> decisionDeleted = send(
				DEC_DELETE + "?id=" + decisionId, HttpMethod.DELETE, token(ADMIN, "company_admin"), null);
		assertThat(decisionDeleted.getStatusCode().value()).isEqualTo(200);
		assertThat(decisionDeleted.getBody())
				.as("ok(OK, null) omits the data key entirely")
				.doesNotContainKey("data");
	}

	// ---------------- assets behaviour ----------------

	@Test
	@Order(6)
	@SuppressWarnings("unchecked")
	void theListAndOneResponsesCarryDifferentEmployeeColumns() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(
				send(ASSET_LIST, HttpMethod.GET, token(ADMIN, "company_admin"), null));
		Map<String, Object> one = (Map<String, Object>) data(
				send(ASSET_ONE + "?id=1", HttpMethod.GET, token(ADMIN, "company_admin"), null));

		assertThat(rows.get(0)).containsKeys("employee_name", "employee_code", "photo_url");
		assertThat(one).containsKeys("employee_name", "employee_code");
		assertThat(one).as("one.php selects no photo_url").doesNotContainKey("photo_url");
	}

	/**
	 * The two query filters use different PHP guards in the same handler:
	 * {@code employee_id} is {@code !empty()} and {@code is_returned} is
	 * {@code isset()}, so a zero is ignored by one and honoured by the other.
	 */
	@Test
	@Order(7)
	@SuppressWarnings("unchecked")
	void aZeroIsIgnoredByTheEmployeeFilterAndHonouredByTheReturnedFilter() {
		List<Map<String, Object>> byZeroEmployee = (List<Map<String, Object>>) data(send(
				ASSET_LIST + "?employee_id=0", HttpMethod.GET, token(ADMIN, "company_admin"), null));
		assertThat(byZeroEmployee)
				.as("!empty() drops the filter, so every asset is returned")
				.hasSize(2);

		List<Map<String, Object>> notReturned = (List<Map<String, Object>>) data(send(
				ASSET_LIST + "?is_returned=0", HttpMethod.GET, token(ADMIN, "company_admin"), null));
		assertThat(notReturned)
				.as("isset() keeps the filter, so only the unreturned asset matches")
				.hasSize(1);
	}

	/** An explicit false beats the returned_at inference. */
	@Test
	@Order(8)
	@SuppressWarnings("unchecked")
	void anExplicitIsReturnedFalseBeatsTheReturnedAtInference() {
		Map<String, Object> inferred = (Map<String, Object>) create(ASSET_CREATE,
				"{\"employee_id\":" + STAFF + ",\"asset_date\":\"2026-01-01\","
						+ "\"asset_text\":\"Badge\",\"returned_at\":\"2026-02-01\"}");
		assertThat(((Number) inferred.get("is_returned")).intValue())
				.as("returned_at with no is_returned key infers 1")
				.isEqualTo(1);

		Map<String, Object> explicit = (Map<String, Object>) create(ASSET_CREATE,
				"{\"employee_id\":" + STAFF + ",\"asset_date\":\"2026-01-01\","
						+ "\"asset_text\":\"Key\",\"returned_at\":\"2026-02-01\","
						+ "\"is_returned\":false}");
		assertThat(((Number) explicit.get("is_returned")).intValue())
				.as("an explicit false wins over the date")
				.isZero();
	}

	@Test
	@Order(9)
	void creatingAnAssetForAnotherCompanysEmployeeIsEmployeeNotFound() {
		ResponseEntity<Map<String, Object>> response = send(ASSET_CREATE, HttpMethod.POST,
				token(ADMIN, "company_admin"),
				"{\"employee_id\":" + OTHER_STAFF + ",\"asset_date\":\"2026-01-01\","
						+ "\"asset_text\":\"Laptop\"}");
		assertThat(response.getStatusCode().value())
				.as("the same answer a genuinely missing id gives, so it confirms nothing")
				.isEqualTo(404);
	}

	/**
	 * {@code whitelist_update_fields()} binds the raw JSON value to a PDO
	 * placeholder. A scalar goes through unchanged; an <b>array or object</b>
	 * cannot be bound, so PHP converts it to the literal string {@code "Array"}
	 * and that is what lands in the column.
	 *
	 * <p>Passing Jackson's {@code Map} straight to JDBC instead would produce a
	 * driver-specific rendering or a database error — a different stored value
	 * and a different response.
	 */
	@Test
	@Order(10)
	@SuppressWarnings("unchecked")
	void anObjectShapedFieldIsStoredAsTheLiteralArrayJustAsPdoDoes() {
		Map<String, Object> updated = (Map<String, Object>) data(send(ASSET_UPDATE + "?id=1",
				HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"asset_text\":{\"x\":1}}"));

		assertThat(updated).containsEntry("asset_text", "Array");

		Map<String, Object> listShaped = (Map<String, Object>) data(send(ASSET_UPDATE + "?id=1",
				HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"asset_text\":[\"a\",\"b\"]}"));
		assertThat(listShaped).as("a JSON array converts the same way")
				.containsEntry("asset_text", "Array");

		// PDO binds with PARAM_STR, so PHP's own string conversion applies:
		// false is "", true is "1", and a number keeps its digits. Measured
		// against a real MariaDB PDO probe on the sibling `name` path.
		assertThat((Map<String, Object>) data(send(ASSET_UPDATE + "?id=1", HttpMethod.PUT,
				token(ADMIN, "company_admin"), "{\"asset_text\":false}")))
				.as("false persists as the empty string, not 0")
				.containsEntry("asset_text", "");
		assertThat((Map<String, Object>) data(send(ASSET_UPDATE + "?id=1", HttpMethod.PUT,
				token(ADMIN, "company_admin"), "{\"asset_text\":true}")))
				.containsEntry("asset_text", "1");
		assertThat((Map<String, Object>) data(send(ASSET_UPDATE + "?id=1", HttpMethod.PUT,
				token(ADMIN, "company_admin"), "{\"asset_text\":42}")))
				.containsEntry("asset_text", "42");

		// null is the exception: PDO binds PARAM_NULL rather than converting,
		// so a nullable column really goes NULL instead of empty string.
		assertThat((Map<String, Object>) data(send(ASSET_UPDATE + "?id=1", HttpMethod.PUT,
				token(ADMIN, "company_admin"), "{\"returned_at\":null}")))
				.containsEntry("returned_at", null);

		// Restore, so the ordered tests after this one read the seeded value.
		data(send(ASSET_UPDATE + "?id=1", HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"asset_text\":\"Laptop\"}"));
	}

	@Test
	@Order(11)
	void anUpdateWithNoWhitelistedFieldIsNothingToUpdate() {
		assertThat(send(ASSET_UPDATE + "?id=1", HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"company_id\":999}").getStatusCode().value())
				.as("company_id is not whitelisted, so nothing is written")
				.isEqualTo(400);
	}

	@Test
	@Order(12)
	void anotherCompanysAssetIsNotFoundRatherThanForbidden() {
		assertThat(send(ASSET_ONE + "?id=99", HttpMethod.GET, token(ADMIN, "company_admin"), null)
				.getStatusCode().value()).isEqualTo(404);
		assertThat(send(ASSET_UPDATE + "?id=99", HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"asset_text\":\"x\"}").getStatusCode().value()).isEqualTo(404);
	}

	// ---------------- decisions behaviour ----------------

	@Test
	@Order(13)
	@SuppressWarnings("unchecked")
	void updatingADecisionKeepsUnsuppliedFieldsAndRejectsBlankedOnes() {
		long id = createDecision("Original", "Original body");

		Map<String, Object> updated = (Map<String, Object>) data(send(DEC_UPDATE + "?id=" + id,
				HttpMethod.PUT, token(ADMIN, "company_admin"), "{\"title\":\"Changed\"}"));
		assertThat(updated).containsEntry("title", "Changed")
				.as("body is untouched because the key was absent")
				.containsEntry("body", "Original body");

		assertThat(send(DEC_UPDATE + "?id=" + id, HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"title\":\"   \"}").getStatusCode().value())
				.as("a supplied but blank title is field_required")
				.isEqualTo(400);
	}

	@Test
	@Order(14)
	void aMissingIdIsFieldRequiredWhileAZeroIdIsNotFound() {
		assertThat(send(DEC_ONE, HttpMethod.GET, token(ADMIN, "company_admin"), null)
				.getStatusCode().value()).isEqualTo(400);
		assertThat(send(DEC_ONE + "?id=0", HttpMethod.GET, token(ADMIN, "company_admin"), null)
				.getStatusCode().value())
				.as("\"0\" passes required() and then matches no row")
				.isEqualTo(404);
		assertThat(send(DEC_ONE + "?id=abc", HttpMethod.GET, token(ADMIN, "company_admin"), null)
				.getStatusCode().value()).isEqualTo(404);
	}

	@Test
	@Order(15)
	void everyRouteChecksItsMethodFirst() {
		assertThat(send(ASSET_LIST, HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(ASSET_CREATE, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(DEC_LIST, HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(DEC_DELETE, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
	}

	// ---------------- fixture ----------------

	private static Object data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return response.getBody().get("data");
	}

	private Object create(String path, String body) {
		ResponseEntity<Map<String, Object>> response =
				send(path, HttpMethod.POST, token(ADMIN, "company_admin"), body);
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		return response.getBody().get("data");
	}

	@SuppressWarnings("unchecked")
	private long createAsset(String text) {
		Map<String, Object> row = (Map<String, Object>) create(ASSET_CREATE,
				"{\"employee_id\":" + STAFF + ",\"asset_date\":\"2026-03-01\","
						+ "\"asset_text\":\"" + text + "\"}");
		return ((Number) row.get("id")).longValue();
	}

	@SuppressWarnings("unchecked")
	private long createDecision(String title, String body) {
		Map<String, Object> row = (Map<String, Object>) create(DEC_CREATE,
				"{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}");
		return ((Number) row.get("id")).longValue();
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

	private String token(long employeeId, String role) {
		long company = employeeId == OTHER_STAFF ? OTHER_COMPANY : COMPANY;
		return jwtService.issueAccessToken(employeeId, employeeId, company, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Records Co', '+201000027001', 'active', '2019-01-15 09:00:00'),"
					+ " (" + OTHER_COMPANY + ", 'Other Co', '+201000027002', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00'),"
					+ " (" + (BRANCH + 1) + ", " + OTHER_COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");

			employee(st, ADMIN, COMPANY, BRANCH, "company_admin");
			employee(st, HR_NO_PERMISSION, COMPANY, BRANCH, "hr");
			employee(st, MANAGER, COMPANY, BRANCH, "manager");
			employee(st, STAFF, COMPANY, BRANCH, "employee");
			employee(st, OTHER_STAFF, OTHER_COMPANY, BRANCH + 1, "employee");

			st.execute("INSERT INTO hr_permissions (employee_id, can_employees) VALUES"
					+ " (" + ADMIN + ", 1), (" + HR_NO_PERMISSION + ", 0), (" + MANAGER + ", 0)");

			st.execute("INSERT INTO assets (id, company_id, employee_id, asset_date, asset_text,"
					+ " is_returned) VALUES"
					+ " (1, " + COMPANY + ", " + STAFF + ", '2026-01-10', 'Laptop', 0),"
					+ " (2, " + COMPANY + ", " + ADMIN + ", '2026-01-11', 'Monitor', 1),"
					+ " (99, " + OTHER_COMPANY + ", " + OTHER_STAFF + ", '2026-01-12', 'Foreign', 0)");

			st.execute("INSERT INTO administrative_decisions (id, company_id, title, body, is_active)"
					+ " VALUES"
					+ " (1, " + COMPANY + ", 'Active notice', 'Body', 1),"
					+ " (2, " + COMPANY + ", 'Retired notice', 'Body', 0)");
		}
	}

	private static void employee(Statement st, long id, long companyId, long branchId, String role)
			throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES (" + id + ", " + companyId
				+ ", " + branchId + ", '" + id + "', 'F', 'L', '+2010000" + id + "', '" + role
				+ "', 1, '2019-04-01 08:00:00')");
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
		try (InputStream in = LegacyRecordsEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
