package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
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
import com.workin.backend.identity.JwtService;

/**
 * Wave 12.4, slice 1: {@code employees/list.php} and {@code employees/one.php}
 * over real HTTP against real MariaDB, on legacy's own URL surface (D-074).
 *
 * <p>The assertions are deliberately about the wire bytes -- envelope keys,
 * message text, key presence and JSON value types -- because that is what D-074
 * makes authoritative. The value types were measured first with a PHP 8.3 +
 * MariaDB 11.8 PDO probe over the same query and the same vendored schema, not
 * inferred from the Java side.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeReadEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/employees/list.php";
	private static final String ONE = "/apis/api/employees/one.php";

	private static final long COMPANY_1 = 19401L;
	private static final long COMPANY_2 = 19402L;
	private static final long COMPANY_SUSPENDED = 19403L;
	private static final long BRANCH_MAIN = 19411L;
	private static final long BRANCH_OTHER = 19412L;

	private static final long ADMIN_1 = 194011L;
	private static final long MANAGER_MAIN = 194012L;
	private static final long STAFF_MAIN = 194013L;
	private static final long STAFF_OTHER_BRANCH = 194014L;
	private static final long STAFF_INACTIVE = 194015L;
	private static final long STAFF_PENDING = 194016L;
	private static final long PLAIN_EMPLOYEE = 194017L;
	private static final long ADMIN_2 = 194021L;
	private static final long STAFF_COMPANY_2 = 194022L;
	private static final long ADMIN_SUSPENDED = 194031L;

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
			throw new IllegalStateException("could not prepare the Wave 12.4 employee read fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@Test
	void listReturnsThePhpEnvelopeWithRawPdoValueTypes() {
		Map<String, Object> body = getMap(LIST, ADMIN_1);

		// respond(): success, message, data, meta -- in that order, and nothing else.
		assertThat(body.keySet()).containsExactly("success", "message", "data", "meta");
		assertThat(body.get("success")).isEqualTo(true);
		assertThat(body.get("message")).isEqualTo("Employees");

		Map<String, Object> row = firstRowFor(body, STAFF_MAIN);

		// public_row() strips exactly two keys, and no more.
		assertThat(row).doesNotContainKeys("password_hash", "token_version");
		assertThat(row).containsKeys("id", "company_id", "employee_code", "national_id", "join_request_status");

		// Measured against PHP: INT columns are JSON numbers...
		assertThat(row.get("id")).isInstanceOf(Number.class);
		assertThat(row.get("is_active")).isEqualTo(1);
		assertThat(row.get("can_check_in_any_branch")).isEqualTo(0);
		// ...DECIMAL columns are JSON strings, scale included...
		assertThat(row.get("expected_daily_hours")).isEqualTo("8.00");
		assertThat(row.get("basic_salary")).isEqualTo("12000.50");
		// ...and dates stay raw strings, zero dates included.
		assertThat(row.get("birth_date")).isEqualTo("0000-00-00");
		assertThat(row.get("hire_date")).isEqualTo("2024-03-01");

		// list.php's aliases, always present even when the correlated row is missing.
		assertThat(row.get("branch_name")).isEqualTo("Main Branch");
		assertThat(row.get("assigned_shift_name")).isEqualTo("Morning");
		assertThat(row.get("assigned_shift_id")).isInstanceOf(Number.class);

		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) body.get("meta");
		assertThat(meta.keySet())
				.containsExactly("page", "limit", "total", "total_pages", "has_next", "has_previous");
		assertThat(meta.get("page")).isEqualTo(1);
		assertThat(meta.get("limit")).isEqualTo(20);
		assertThat(meta.get("has_previous")).isEqualTo(false);
	}

	@Test
	void listAppliesTheAcceptedRosterClauseAndTheCompanyScope() {
		List<Long> ids = idsOf(getMap(LIST, ADMIN_1));

		// COALESCE(join_request_status,'accepted')='accepted' hides the pending row.
		assertThat(ids).doesNotContain(STAFF_PENDING);
		// e.company_id = ? hides the other tenant entirely.
		assertThat(ids).doesNotContain(STAFF_COMPANY_2);
		assertThat(ids).contains(STAFF_MAIN, STAFF_INACTIVE);
	}

	@Test
	void isActiveFiltersOnIssetSoZeroSelectsInactiveRows() {
		// !empty() would drop '0' here; list.php uses isset() for this one filter.
		assertThat(idsOf(getMap(LIST + "?is_active=0", ADMIN_1))).containsExactly(STAFF_INACTIVE);
		assertThat(idsOf(getMap(LIST + "?is_active=1", ADMIN_1))).doesNotContain(STAFF_INACTIVE);
		// branch_id uses !empty(), so a zero-like value is not a filter at all.
		assertThat(idsOf(getMap(LIST + "?branch_id=0", ADMIN_1)))
				.isEqualTo(idsOf(getMap(LIST, ADMIN_1)));
	}

	@Test
	void theDateRangeFiltersAreNamedFromAndTo() {
		// Request::DATE_FROM/DATE_TO are 'from'/'to' (apis/config/request.php:26-27).
		assertThat(idsOf(getMap(LIST + "?from=2025-01-01", ADMIN_1))).containsOnly(STAFF_INACTIVE);
		assertThat(idsOf(getMap(LIST + "?to=2024-12-31", ADMIN_1))).doesNotContain(STAFF_INACTIVE);
		// The names the Wave 12.4 discovery reported do not filter anything.
		assertThat(idsOf(getMap(LIST + "?date_from=2025-01-01", ADMIN_1)))
				.isEqualTo(idsOf(getMap(LIST, ADMIN_1)));
	}

	@Test
	void paginationReproducesPhpsClampAndFalsyDefault() {
		// $raw ?: $defaultLimit -- limit=0 falls back to 20, it does not clamp to 1.
		assertThat(metaOf(getMap(LIST + "?limit=0", ADMIN_1)).get("limit")).isEqualTo(20);
		// min(max(1, $raw), 100)
		assertThat(metaOf(getMap(LIST + "?limit=500", ADMIN_1)).get("limit")).isEqualTo(100);
		assertThat(metaOf(getMap(LIST + "?limit=abc", ADMIN_1)).get("limit")).isEqualTo(20);
		// per_page is the documented alias, used only when limit is absent.
		assertThat(metaOf(getMap(LIST + "?per_page=1", ADMIN_1)).get("limit")).isEqualTo(1);
		assertThat(metaOf(getMap(LIST + "?limit=2&per_page=1", ADMIN_1)).get("limit")).isEqualTo(2);

		Map<String, Object> firstPage = metaOf(getMap(LIST + "?limit=1", ADMIN_1));
		assertThat(firstPage.get("has_next")).isEqualTo(true);
		assertThat(firstPage.get("has_previous")).isEqualTo(false);
		assertThat(metaOf(getMap(LIST + "?limit=1&page=2", ADMIN_1)).get("has_previous")).isEqualTo(true);
		// max(1, (int)page)
		assertThat(metaOf(getMap(LIST + "?page=0", ADMIN_1)).get("page")).isEqualTo(1);
	}

	@Test
	void searchSplitsOnTheDigitsOnlyPattern() {
		// A numeric needle searches employee_code only, never the display name.
		assertThat(idsOf(getMap(LIST + "?search=7001", ADMIN_1))).containsExactly(STAFF_MAIN);
		// A text needle searches TRIM(CONCAT(first,' ',last)) or the code.
		assertThat(idsOf(getMap(LIST + "?search=Nour%20Adel", ADMIN_1))).containsExactly(STAFF_MAIN);
		// A blank search is no filter at all.
		assertThat(idsOf(getMap(LIST + "?search=%20%20", ADMIN_1))).isEqualTo(idsOf(getMap(LIST, ADMIN_1)));
	}

	@Test
	void sortIsMatchedExactlyAndOrdersCodesNumerically() {
		List<Long> sorted = idsOf(getMap(LIST + "?sort=employee_code", ADMIN_1));
		List<Long> defaultOrder = idsOf(getMap(LIST, ADMIN_1));
		assertThat(sorted).isNotEqualTo(defaultOrder);
		// CAST(NULLIF(code,'') AS UNSIGNED) ASC -- '90' sorts after '7001' only if
		// the cast is numeric, which is the point of the expression.
		assertThat(sorted.indexOf(MANAGER_MAIN)).isLessThan(sorted.indexOf(STAFF_MAIN));
		// Anything other than the exact string falls back to created_at DESC, id DESC.
		assertThat(idsOf(getMap(LIST + "?sort=EMPLOYEE_CODE", ADMIN_1))).isEqualTo(defaultOrder);
	}

	@Test
	void managersSeeOnlyTheirOwnBranchOnListAndAre403ElsewhereOnOne() {
		assertThat(idsOf(getMap(LIST, MANAGER_MAIN)))
				.contains(STAFF_MAIN)
				.doesNotContain(STAFF_OTHER_BRANCH);

		Map<String, Object> denied = getMap(ONE + "?id=" + STAFF_OTHER_BRANCH, MANAGER_MAIN, 403);
		assertThat(denied.get("success")).isEqualTo(false);
		assertThat(denied.get("message")).isEqualTo("Forbidden");
		// fail() with $data === null omits the key entirely.
		assertThat(denied).doesNotContainKey("data");

		assertThat(getMap(ONE + "?id=" + STAFF_MAIN, MANAGER_MAIN).get("success")).isEqualTo(true);
	}

	@Test
	void oneAppendsSalaryAndShiftKeysOnlyWhenThoseRowsExist() {
		Map<String, Object> withHistory = dataOf(getMap(ONE + "?id=" + STAFF_MAIN, ADMIN_1));
		assertThat(withHistory.get("basic_salary")).isEqualTo("12000.50");
		assertThat(withHistory.get("gross_salary")).isEqualTo("12250.75");
		assertThat(withHistory.get("assigned_shift_name")).isEqualTo("Morning");

		// The attach helpers return early when there is no row, so unlike
		// list.php these keys are absent rather than null.
		Map<String, Object> withoutHistory = dataOf(getMap(ONE + "?id=" + STAFF_OTHER_BRANCH, ADMIN_1));
		assertThat(withoutHistory).doesNotContainKeys(
				"basic_salary", "gross_salary", "assigned_shift_id", "assigned_shift_name");
	}

	@Test
	void oneRequiresTheIdAndHidesMissingAndForeignRowsBehindOne404() {
		Map<String, Object> missingParameter = getMap(ONE, ADMIN_1, 400);
		// required() passes the field name as a {field} placeholder, not as data.
		assertThat(missingParameter.get("message")).isEqualTo("Field 'id' is required");
		assertThat(missingParameter).doesNotContainKey("data");

		assertThat(getMap(ONE + "?id=", ADMIN_1, 400).get("message")).isEqualTo("Field 'id' is required");
		// '0' passes required() and only then casts to 0, so it is a lookup miss.
		assertThat(getMap(ONE + "?id=0", ADMIN_1, 404).get("message")).isEqualTo("Employee not found");
		assertThat(getMap(ONE + "?id=" + STAFF_COMPANY_2, ADMIN_1, 404).get("message"))
				.isEqualTo("Employee not found");
	}

	@Test
	void theGuardStackAnswersInThePhpEnvelope() {
		// P-8: employee is not one of list.php's three allowed roles.
		Map<String, Object> role = getMap(LIST, PLAIN_EMPLOYEE, 403);
		assertThat(role.get("success")).isEqualTo(false);
		assertThat(role.get("message")).isEqualTo("Forbidden — insufficient role");

		// P-9: requireCompanyActive($company_id).
		assertThat(getMap(LIST, ADMIN_SUSPENDED, 403).get("message"))
				.isEqualTo("Company account is not active");

		// Every method-guarded endpoint's opening line.
		ResponseEntity<Map<String, Object>> wrongMethod = exchange(LIST, HttpMethod.POST, ADMIN_1);
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
		assertThat(wrongMethod.getBody().get("message")).isEqualTo("Invalid method");
	}

	@Test
	void theMessageFollowsAppLocale() {
		// ?lang=ar wins outright.
		assertThat(getMap(LIST + "?lang=ar", ADMIN_1).get("message")).isEqualTo("الموظفون");
		// '0' is empty in PHP, so ?lang=0 falls through to the header, which is absent here.
		assertThat(getMap(LIST + "?lang=0", ADMIN_1).get("message")).isEqualTo("Employees");
		// Accept-Language is the fallback, matched on a word boundary.
		assertThat(getMapWithLocale(LIST, ADMIN_1, "ar-EG,ar;q=0.9").get("message"))
				.isEqualTo("الموظفون");
		assertThat(getMapWithLocale(LIST, ADMIN_1, "en-GB,en;q=0.9").get("message")).isEqualTo("Employees");
	}

	private Map<String, Object> firstRowFor(Map<String, Object> body, long employeeId) {
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");
		return rows.stream()
				.filter(row -> ((Number) row.get("id")).longValue() == employeeId)
				.findFirst()
				.orElseThrow(() -> new AssertionError("employee " + employeeId + " is not in the page"));
	}

	private static List<Long> idsOf(Map<String, Object> body) {
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");
		return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> metaOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("meta");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	private Map<String, Object> getMap(String path, long employeeId) {
		return getMap(path, employeeId, 200);
	}

	private Map<String, Object> getMap(String path, long employeeId, int expectedStatus) {
		ResponseEntity<Map<String, Object>> response = exchange(path, HttpMethod.GET, employeeId);
		assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
		return response.getBody();
	}

	private Map<String, Object> getMapWithLocale(String path, long employeeId, String acceptLanguage) {
		HttpHeaders headers = headersFor(tokenFor(employeeId));
		headers.set("Accept-Language", acceptLanguage);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		return response.getBody();
	}

	private ResponseEntity<Map<String, Object>> exchange(String path, HttpMethod method, long employeeId) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(headersFor(tokenFor(employeeId))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_MAIN ? "manager"
				: employeeId == PLAIN_EMPLOYEE ? "employee" : "company_admin";
		long companyId = employeeId == ADMIN_2 || employeeId == STAFF_COMPANY_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
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
					  (19401, 'Employee E2E Co 1', '+201000019401', 'active', '2025-01-15 09:00:00'),
					  (19402, 'Employee E2E Co 2', '+201000019402', 'active', '2025-01-15 09:00:00'),
					  (19403, 'Employee E2E Suspended', '+201000019403', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19411, 19401, 'Main Branch', 1, '2025-03-01 10:00:00'),
					  (19412, 19401, 'Second Branch', 1, '2025-03-01 10:00:00'),
					  (19421, 19402, 'Other Company Branch', 1, '2025-03-01 10:00:00'),
					  (19431, 19403, 'Suspended Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (19441, 19401, 'Operations', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at) VALUES
					  (19451, 19401, 19441, 'Agent', 1, '2025-04-11 10:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES
					  (19461, 19401, 'Morning', '09:00:00', '17:00:00', '2025-04-12 10:00:00')
					""");
			// created_at drives the default ordering; employee_code drives the
			// numeric sort, and '90' vs '7001' only orders correctly under the
			// UNSIGNED cast.
			insertEmployee(st, ADMIN_1, COMPANY_1, BRANCH_MAIN, "'1001'", "company_admin",
					"'2024-01-01'", 1, "'accepted'", "'2025-05-01 09:00:00'", "+201000194011", "Rana");
			insertEmployee(st, MANAGER_MAIN, COMPANY_1, BRANCH_MAIN, "'90'", "manager",
					"'2024-02-01'", 1, "'accepted'", "'2025-05-02 09:00:00'", "+201000194012", "Mostafa");
			insertEmployee(st, STAFF_MAIN, COMPANY_1, BRANCH_MAIN, "'7001'", "employee",
					"'2024-03-01'", 1, "'accepted'", "'2025-05-03 09:00:00'", "+201000194013", "Nour");
			insertEmployee(st, STAFF_OTHER_BRANCH, COMPANY_1, BRANCH_OTHER, "'7002'", "employee",
					"'2024-04-01'", 1, "'accepted'", "'2025-05-04 09:00:00'", "+201000194014", "Hala");
			insertEmployee(st, STAFF_INACTIVE, COMPANY_1, BRANCH_MAIN, "'7003'", "employee",
					"'2025-06-01'", 0, "'accepted'", "'2025-05-05 09:00:00'", "+201000194015", "Karim");
			insertEmployee(st, STAFF_PENDING, COMPANY_1, BRANCH_MAIN, "'7004'", "employee",
					"'2024-06-01'", 1, "'pending'", "'2025-05-06 09:00:00'", "+201000194016", "Sara");
			insertEmployee(st, PLAIN_EMPLOYEE, COMPANY_1, BRANCH_MAIN, "'7005'", "employee",
					"'2024-07-01'", 1, "'accepted'", "'2025-05-07 09:00:00'", "+201000194017", "Omar");
			insertEmployee(st, ADMIN_2, COMPANY_2, 19421L, "'2001'", "company_admin",
					"'2024-01-01'", 1, "'accepted'", "'2025-05-01 09:00:00'", "+201000194021", "Laila");
			insertEmployee(st, STAFF_COMPANY_2, COMPANY_2, 19421L, "'2002'", "employee",
					"'2024-01-01'", 1, "'accepted'", "'2025-05-02 09:00:00'", "+201000194022", "Yasmin");
			insertEmployee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 19431L, "'3001'", "company_admin",
					"'2024-01-01'", 1, "'accepted'", "'2025-05-01 09:00:00'", "+201000194031", "Tarek");

			st.execute("""
					INSERT INTO employee_shift_assignments (id, employee_id, shift_id, effective_from) VALUES
					  (19471, 194013, 19461, '2024-03-01')
					""");
			st.execute("""
					INSERT INTO salary_contracts
					  (id, employee_id, basic_salary, housing_allowance, transport_allowance, effective_from)
					VALUES (19481, 194013, 12000.50, 0, 250.25, '2024-03-01')
					""");
		}
	}

	/** One roster row. {@code birth_date} is the zero date on purpose: it has to stay readable. */
	private static void insertEmployee(
			Statement st, long id, long companyId, long branchId, String code, String role,
			String hireDate, int active, String joinStatus, String createdAt, String phone,
			String firstName) throws Exception {
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, department_id, job_title_id, employee_code, expected_daily_hours,
				   first_name, last_name, phone, country_code, password_hash, token_version, role, national_id,
				   birth_date, gender, address, photo_url, hire_date, contract_duration_months, is_active,
				   is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, created_at)
				VALUES (%d, %d, %d, 19441, 19451, %s, 8.00, '%s', 'Adel', '%s', '+20',
				   '$2y$10$abcdefghijklmnopqrstuv', 1, '%s', '29001011200011', '0000-00-00', 'female',
				   'Cairo', '/uploads/photos/x.jpg', %s, 12, %d, 1, 0, %s, %s)
				""".formatted(id, companyId, branchId, code, firstName, phone, role, hireDate, active, joinStatus, createdAt));
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream = LegacyEmployeeReadEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
