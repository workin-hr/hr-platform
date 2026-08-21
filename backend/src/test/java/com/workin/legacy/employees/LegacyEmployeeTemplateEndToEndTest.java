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
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetColumns;
import com.workin.legacy.spreadsheet.LegacyCsvReader;
import com.workin.legacy.spreadsheet.LegacySpreadsheetFormat;
import com.workin.legacy.spreadsheet.LegacyXlsxReader;

/**
 * Wave 12.4, slice 6: {@code employees/template_excel.php} over real HTTP.
 *
 * <p>The file's shape is proved against PHP's own output in
 * {@code LegacyEmployeeTemplateTest}; what is proved here is everything only
 * the endpoint can answer -- which role reaches it, which query value selects
 * which branch, what the headers say, and that the examples come from this
 * company's own lookups.
 *
 * <p>One behaviour deserves its own note: this endpoint has <em>no</em> method
 * guard in PHP, alone in the module. That is asserted rather than assumed, over
 * several methods, because "the PHP has no check" is exactly the kind of claim
 * that quietly stops being true when a framework supplies a default.
 *
 * <p>Every lookup name here is unique within its table. J2 -- what legacy does
 * when two rows share a name, given that the lookup is keyed by name and the
 * query has no {@code ORDER BY} -- is still unresolved, and these tests are
 * written so they do not depend on an answer to it.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeTemplateEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	/**
	 * The date legacy would print. {@link com.workin.legacy.LegacyClock} is
	 * request-scoped, so it cannot be autowired into a test thread; this
	 * fixture seeds no {@code configs} row, which leaves legacy's own +02:00
	 * default in place, and the expectation is computed from that offset rather
	 * than from the class under test.
	 */
	private static String today() {
		return java.time.LocalDate.now(java.time.ZoneOffset.ofHours(2)).toString();
	}

	private static final String TEMPLATE = "/apis/api/employees/template_excel.php";

	private static final long COMPANY_1 = 19601L;
	private static final long COMPANY_EMPTY = 19602L;
	private static final long COMPANY_SUSPENDED = 19603L;

	private static final long ADMIN_1 = 196011L;
	private static final long HR_1 = 196012L;
	private static final long MANAGER_1 = 196013L;
	private static final long PLAIN_EMPLOYEE = 196014L;
	private static final long ADMIN_EMPTY = 196021L;
	private static final long ADMIN_SUSPENDED = 196031L;

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
			throw new IllegalStateException("could not prepare the template_excel fixture", ex);
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
	void theDefaultDownloadIsAnXlsxWithTheGroupRowAndThe28Headers() {
		ResponseEntity<byte[]> response = download(TEMPLATE, ADMIN_1, HttpMethod.GET);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getHeaders().getFirst("Content-Type"))
				.isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		assertThat(response.getHeaders().getFirst("Content-Disposition"))
				.isEqualTo("attachment; filename=\"employees_template_" + today() + ".xlsx\"");

		byte[] body = response.getBody();
		assertThat(LegacySpreadsheetFormat.detect(body)).isEqualTo(LegacySpreadsheetFormat.XLSX);

		// Parsed back through the reader the analyzer itself uses.
		List<List<String>> matrix = LegacyXlsxReader.readFirstSheet(body);
		assertThat(matrix).hasSize(2);
		assertThat(LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(matrix.get(0))).isTrue();
		assertThat(matrix.get(1)).hasSize(28);
		assertThat(normalized(matrix.get(1))).containsExactlyElementsOf(expectedKeys());
	}

	@Test
	void theCsvBranchIsSelectedOnlyByTheExactValueCsv() {
		ResponseEntity<byte[]> csv = download(TEMPLATE + "?format=csv", ADMIN_1, HttpMethod.GET);
		// D-074a: PHP sends "text/csv; charset=utf-8" and Tomcat re-serializes
		// this one header itself, dropping the optional space. RFC 9110 makes
		// the two spellings the same media type, and the decision records that
		// the semantic value -- media type plus UTF-8 charset -- is what the
		// contract turns on, so it is what is asserted.
		String contentType = csv.getHeaders().getFirst("Content-Type");
		assertThat(contentType.replace(" ", "").toLowerCase(java.util.Locale.ROOT))
				.isEqualTo("text/csv;charset=utf-8");
		assertThat(csv.getHeaders().getFirst("Content-Disposition"))
				.isEqualTo("attachment; filename=\"employees_template_" + today() + ".csv\"");
		assertThat(csv.getBody()[0]).isEqualTo((byte) 0xEF);

		List<List<String>> records = LegacyCsvReader.read(csv.getBody());
		assertThat(records).hasSize(2);
		// The CSV group row repeats the label across every column of its block.
		assertThat(records.get(0).subList(18, 23)).containsOnly("استحقاقات");
		assertThat(records.get(0).subList(23, 28)).containsOnly("استقطاعات");
		assertThat(LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(records.get(0))).isTrue();
		assertThat(normalized(records.get(1))).containsExactlyElementsOf(expectedKeys());
	}

	@Test
	void everyOtherFormatValueIsAnXlsx() {
		// `$format === 'csv'` is the whole test in PHP, so nothing else is an
		// error and nothing else is a CSV.
		// `?format[]=csv` is deliberately absent: Tomcat rejects an unencoded
		// square bracket in the query string with its own 400 before any
		// handler runs, so testing it here would be testing the container.
		// D-070's 2026-08-22 addendum covers it -- PHP would take the value as
		// an array, stringify it to "Array" and serve an XLSX, and Phase 1 does
		// not weaken or bypass Tomcat parsing to reproduce that.
		for (String query : List.of("", "?format=xlsx", "?format=XLSX", "?format=garbage",
				"?format=", "?format=CSVX", "?format=%20csv%20")) {
			ResponseEntity<byte[]> response = download(TEMPLATE + query, ADMIN_1, HttpMethod.GET);
			assertThat(response.getStatusCode().value()).as("status for '%s'", query).isEqualTo(200);
			assertThat(LegacySpreadsheetFormat.detect(response.getBody()))
					.as("format for '%s'", query)
					.isEqualTo(query.equals("?format=%20csv%20")
							// strtolower(trim(...)) runs before the comparison,
							// so a padded value still selects the CSV branch.
							? LegacySpreadsheetFormat.CSV : LegacySpreadsheetFormat.XLSX);
		}
		// Case is folded, so an upper-case CSV does select the CSV branch.
		assertThat(LegacySpreadsheetFormat.detect(
				download(TEMPLATE + "?format=CSV", ADMIN_1, HttpMethod.GET).getBody()))
				.isEqualTo(LegacySpreadsheetFormat.CSV);
	}

	@Test
	void thereIsNoMethodGuardOnThisEndpointAlone() {
		// template_excel.php has no REQUEST_METHOD check, so every method
		// downloads the template. The auth guards still run.
		for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
			ResponseEntity<byte[]> response = download(TEMPLATE, ADMIN_1, method);
			assertThat(response.getStatusCode().value()).as("%s", method).isEqualTo(200);
			assertThat(LegacySpreadsheetFormat.detect(response.getBody()))
					.as("%s body", method).isEqualTo(LegacySpreadsheetFormat.XLSX);
		}
		// And the sibling endpoints still have theirs, so this is not a
		// module-wide loosening.
		assertThat(download("/apis/api/employees/list.php", ADMIN_1, HttpMethod.POST)
				.getStatusCode().value()).isEqualTo(405);
	}

	@Test
	void hrReachesItAndManagersAndEmployeesDoNot() {
		assertThat(download(TEMPLATE, HR_1, HttpMethod.GET).getStatusCode().value()).isEqualTo(200);
		// requireAuth([COMPANY_ADMIN, HR]) -- the write-side list, without MANAGER.
		assertThat(download(TEMPLATE, MANAGER_1, HttpMethod.GET).getStatusCode().value()).isEqualTo(403);
		assertThat(download(TEMPLATE, PLAIN_EMPLOYEE, HttpMethod.GET).getStatusCode().value()).isEqualTo(403);
		// requireCompanyActive($company_id), which runs after the role check.
		assertThat(download(TEMPLATE, ADMIN_SUSPENDED, HttpMethod.GET).getStatusCode().value()).isEqualTo(403);
		// The failures keep the PHP envelope rather than the file content type.
		ResponseEntity<Map<String, Object>> denied = envelope(TEMPLATE, PLAIN_EMPLOYEE);
		assertThat(denied.getBody().get("success")).isEqualTo(false);
		assertThat(denied.getBody().get("message")).isEqualTo("Forbidden — insufficient role");
	}

	@Test
	void anUnauthenticatedRequestNeverReachesTheGenerator() {
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + TEMPLATE), HttpMethod.GET,
				new HttpEntity<>(new HttpHeaders()),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().get("success")).isEqualTo(false);
	}

	@Test
	void theExamplesComeFromThisCompanysOwnLookupsLowerCased() {
		List<String> headers = headersOf(download(TEMPLATE, ADMIN_1, HttpMethod.GET).getBody());

		// array_key_first() returns the *key*, and the keys are
		// mb_strtolower(trim($name)) -- so the example is the folded name.
		assertThat(headers.get(6)).endsWith("مثال: night watch");
		assertThat(headers.get(13)).endsWith("مثال: riverside branch");
		assertThat(headers.get(14)).endsWith("مثال: field operations");
		assertThat(headers.get(15)).endsWith("مثال: senior agent");
		// hire_date's example is today, from the legacy clock.
		assertThat(headers.get(12)).endsWith("مثال: " + today());
	}

	@Test
	void inactiveLookupRowsAreExcludedExceptForShifts() {
		List<String> headers = headersOf(download(TEMPLATE, ADMIN_1, HttpMethod.GET).getBody());

		// branches, departments and job_titles filter on is_active=1, so the
		// inactive rows seeded ahead of them are not the example...
		assertThat(headers.get(13)).doesNotContain("closed branch");
		assertThat(headers.get(14)).doesNotContain("retired department");
		assertThat(headers.get(15)).doesNotContain("retired title");
		// ...while the shifts query has no is_active predicate at all, which is
		// legacy's asymmetry, not an oversight here. The shifts table has no
		// such column, so every seeded shift is a candidate.
		assertThat(headers.get(6)).endsWith("مثال: night watch");
	}

	@Test
	void aCompanyWithNoLookupsFallsBackToLegacysPlaceholders() {
		List<String> headers = headersOf(download(TEMPLATE, ADMIN_EMPTY, HttpMethod.GET).getBody());

		assertThat(headers.get(6)).endsWith("مثال: صباحي");
		assertThat(headers.get(13)).endsWith("مثال: الفرع الرئيسي");
		assertThat(headers.get(14)).endsWith("مثال: الموارد البشرية");
		assertThat(headers.get(15)).endsWith("مثال: موظف");
	}

	@Test
	void theTemplateThisEndpointHandsOutSurvivesItsOwnHeaderNormalization() {
		// The round trip D-085 is about, at the header level: both branches of
		// this endpoint produce a file whose header row maps onto all 28 keys.
		for (String query : List.of("", "?format=csv")) {
			byte[] body = download(TEMPLATE + query, ADMIN_1, HttpMethod.GET).getBody();
			List<List<String>> records = query.isEmpty()
					? LegacyXlsxReader.readFirstSheet(body) : LegacyCsvReader.read(body);
			assertThat(LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(records.get(0)))
					.as("group row for '%s'", query).isTrue();
			assertThat(normalized(records.get(1))).as("headers for '%s'", query)
					.containsExactlyElementsOf(expectedKeys());
		}
	}

	// ------------------------------------------------------------------

	private static List<String> expectedKeys() {
		return LegacyEmployeeSpreadsheetColumns.columns().stream()
				.map(LegacyEmployeeSpreadsheetColumns.Column::key).toList();
	}

	private static List<String> normalized(List<String> headerRow) {
		return headerRow.stream().map(LegacyEmployeeSpreadsheetColumns::normalizeHeaderKey).toList();
	}

	private static List<String> headersOf(byte[] workbook) {
		return LegacyXlsxReader.readFirstSheet(workbook).get(1);
	}

	private ResponseEntity<byte[]> download(String path, long employeeId, HttpMethod method) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(headersFor(tokenFor(employeeId))), byte[].class);
	}

	private ResponseEntity<Map<String, Object>> envelope(String path, long employeeId) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET,
				new HttpEntity<>(headersFor(tokenFor(employeeId))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == PLAIN_EMPLOYEE ? "employee"
				: employeeId == HR_1 ? "hr" : "company_admin";
		long companyId = employeeId == ADMIN_EMPTY ? COMPANY_EMPTY
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (19601, 'Template Co', '+201000019601', 'active', '2025-01-15 09:00:00'),
					  (19602, 'Template Empty Co', '+201000019602', 'active', '2025-01-15 09:00:00'),
					  (19603, 'Template Suspended Co', '+201000019603', 'suspended', '2025-01-15 09:00:00')
					""");
			// The inactive rows are inserted first on purpose: if the is_active
			// filter were missing, they -- not the active ones -- would be the
			// examples, so their position is the assertion's teeth.
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19610, 19601, 'Closed Branch', 0, '2025-03-01 10:00:00'),
					  (19611, 19601, 'Riverside Branch', 1, '2025-03-01 10:00:00'),
					  (19612, 19601, 'Harbour Branch', 1, '2025-03-01 10:00:00'),
					  (19621, 19602, 'Empty Co Branch', 0, '2025-03-01 10:00:00'),
					  (19631, 19603, 'Suspended Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (19640, 19601, 'Retired Department', 0, '2025-04-10 10:00:00'),
					  (19641, 19601, 'Field Operations', 1, '2025-04-10 10:00:00'),
					  (19642, 19601, 'Quality Assurance', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at) VALUES
					  (19650, 19601, 19641, 'Retired Title', 0, '2025-04-11 10:00:00'),
					  (19651, 19601, 19641, 'Senior Agent', 1, '2025-04-11 10:00:00'),
					  (19652, 19601, 19641, 'Junior Agent', 1, '2025-04-11 10:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES
					  (19661, 19601, 'Night Watch', '22:00:00', '06:00:00', '2025-04-12 10:00:00'),
					  (19662, 19601, 'Day Watch', '09:00:00', '17:00:00', '2025-04-12 10:00:00')
					""");

			insertEmployee(st, ADMIN_1, COMPANY_1, 19611L, "'1001'", "company_admin", "+201000196011", "Rana");
			insertEmployee(st, HR_1, COMPANY_1, 19611L, "'1002'", "hr", "+201000196012", "Mona");
			insertEmployee(st, MANAGER_1, COMPANY_1, 19611L, "'1003'", "manager", "+201000196013", "Mostafa");
			insertEmployee(st, PLAIN_EMPLOYEE, COMPANY_1, 19611L, "'1004'", "employee", "+201000196014", "Omar");
			insertEmployee(st, ADMIN_EMPTY, COMPANY_EMPTY, 19621L, "'2001'", "company_admin",
					"+201000196021", "Laila");
			insertEmployee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 19631L, "'3001'", "company_admin",
					"+201000196031", "Tarek");
		}
	}

	private static void insertEmployee(
			Statement st, long id, long companyId, long branchId, String code, String role,
			String phone, String firstName) throws Exception {
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, department_id, job_title_id, employee_code, expected_daily_hours,
				   first_name, last_name, phone, country_code, password_hash, token_version, role, national_id,
				   birth_date, gender, address, photo_url, hire_date, contract_duration_months, is_active,
				   is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, created_at)
				VALUES (%d, %d, %d, NULL, NULL, %s, 8.00, '%s', 'Adel', '%s', '+20',
				   '$2y$10$abcdefghijklmnopqrstuv', 1, '%s', '29001011200011', '0000-00-00', 'female',
				   'Cairo', NULL, '2024-01-01', 12, 1, 1, 0, 'accepted', '2025-05-01 09:00:00')
				""".formatted(id, companyId, branchId, code, firstName, phone, role));
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
		try (InputStream stream =
				LegacyEmployeeTemplateEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
