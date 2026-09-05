package com.workin.legacy.employees;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetColumns;
import com.workin.legacy.spreadsheet.LegacyCsvWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code employees/analyze_excel_update.php}.
 *
 * <p>The same upload and the same template check as the create sheet, and the
 * opposite validation stance: <b>an empty cell means "leave that field
 * alone"</b>, so a row is valid when its employee code resolves to an existing
 * employee and nothing that <em>was</em> filled in is wrong.
 *
 * <p>Everything here goes over HTTP with a real spreadsheet, because the
 * template structure check reads the file's own header row -- a hand-built
 * map would skip the part most likely to break.
 */
@SpringBootTest(classes = BackendApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeAnalyzeExcelUpdateEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String ANALYZE_UPDATE = "/apis/api/employees/analyze_excel_update.php";
	private static final String TEMPLATE = "/apis/api/employees/template_excel.php";

	private static final long COMPANY = 19801L;
	private static final long ADMIN = 198011L;
	private static final long PLAIN_EMPLOYEE = 198014L;
	private static final long EXISTING = 198021L;

	private static final String SHIFT_NAME = "Night Watch";
	private static final String BRANCH_NAME = "Main Branch";

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
			throw new IllegalStateException("could not prepare the analyze_excel_update fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.runtime-db.username", () -> "unused");
		registry.add("app.runtime-db.password", () -> "unused");
	}

	// ---------------- guards ----------------

	@Test
	void aGetIs405() {
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE_UPDATE), HttpMethod.GET,
				new HttpEntity<>(plainHeaders(tokenFor(ADMIN))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
	}

	@Test
	void aPlainEmployeeIsForbidden() {
		assertThat(post(sheetWith(rowFor("2001", Map.of("first_name", "X"))), "u.csv", PLAIN_EMPLOYEE)
				.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void noFileIs400() {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE_UPDATE), HttpMethod.POST,
				new HttpEntity<>(parts, multipartHeaders(tokenFor(ADMIN))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("success", false);
	}

	// ---------------- malformed input ----------------

	/** Not a spreadsheet at all: rejected by the structure check, with 400. */
	@Test
	void arbitraryBytesAreRejectedAsAMalformedSheet() {
		ResponseEntity<Map<String, Object>> response =
				post("this is not a spreadsheet".getBytes(StandardCharsets.UTF_8), "junk.csv", ADMIN);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("success", false);
	}

	@Test
	void anEmptyFileIsRejected() {
		assertThat(post(new byte[0], "empty.csv", ADMIN).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/** A sheet whose headers are not the template's is refused before any row is read. */
	@Test
	void aSheetWithForeignHeadersIsRejected() {
		byte[] file = ("alpha,beta,gamma\n1,2,3\n").getBytes(StandardCharsets.UTF_8);

		assertThat(post(file, "foreign.csv", ADMIN).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/** The template with no data rows analyses to an empty, valid result. */
	@Test
	void theBareTemplateAnalysesToZeroRows() {
		Map<String, Object> summary = summaryOf(analyze(template(), "template.csv"));

		assertThat(summary).containsEntry("total", 0).containsEntry("valid", 0).containsEntry("invalid", 0);
	}

	// ---------------- update semantics ----------------

	@Test
	void aRowNamingAnExistingEmployeeWithOneChangedCellIsValid() {
		Map<String, Object> body = analyze(
				sheetWith(rowFor("2001", Map.of("first_name", "Renamed"))), "one.csv");

		assertThat(summaryOf(body)).containsEntry("total", 1).containsEntry("valid", 1);
		assertThat(firstRow(body)).containsEntry("status", "valid");
		assertThat(payloadOf(firstRow(body)))
				.as("only the filled cell and the identifiers travel")
				.containsKeys("id", "employee_code", "first_name")
				.doesNotContainKey("last_name");
	}

	@Test
	void aRowWithNoFilledCellsIsNothingToUpdate() {
		Map<String, Object> body = analyze(sheetWith(rowFor("2001", Map.of())), "bare.csv");

		assertThat(firstRow(body)).containsEntry("status", "invalid");
		assertThat(errorsOf(firstRow(body))).containsExactly("nothing_to_update");
	}

	@Test
	void anEmployeeCodeThatDoesNotExistIsNotFound() {
		Map<String, Object> body = analyze(
				sheetWith(rowFor("999999", Map.of("first_name", "Ghost"))), "missing.csv");

		assertThat(errorsOf(firstRow(body))).containsExactly("employee_not_found");
		// PHP's `$payload = []` with nothing added encodes as a JSON *array*,
		// not an object, and the wire layer reproduces that -- so this asserts
		// emptiness without asserting a shape the client does not get.
		Object payload = firstRow(body).get("payload");
		assertThat(payload instanceof List<?> list ? list.isEmpty() : ((Map<?, ?>) payload).isEmpty())
				.as("the three code failures return before any payload is built")
				.isTrue();
	}

	@Test
	void aBlankEmployeeCodeIsRequired() {
		Map<String, Object> body = analyze(
				sheetWith(rowFor("", Map.of("first_name", "X"))), "nocode.csv");

		assertThat(errorsOf(firstRow(body))).containsExactly("employee_code_required");
	}

	@Test
	void aNonNumericEmployeeCodeIsInvalid() {
		Map<String, Object> body = analyze(
				sheetWith(rowFor("20-01", Map.of("first_name", "X"))), "badcode.csv");

		assertThat(errorsOf(firstRow(body))).containsExactly("employee_code_invalid");
	}

	/** The first stays valid; only the repeat is marked. */
	@Test
	void aCodeRepeatedInTheFileMarksOnlyTheSecondRow() {
		Map<String, Object> body = analyze(sheetWith(
				rowFor("2001", Map.of("first_name", "First")),
				rowFor("2001", Map.of("first_name", "Second"))), "dupe.csv");

		assertThat(summaryOf(body)).containsEntry("valid", 1).containsEntry("invalid", 1);
		assertThat(rowsOf(body).get(0)).containsEntry("status", "valid");
		assertThat(errorsOf(rowsOf(body).get(1))).containsExactly("employee_code_duplicate_in_file");
	}

	@Test
	void anUnknownShiftNameIsRejectedButAKnownOneIsNot() {
		assertThat(errorsOf(firstRow(analyze(
				sheetWith(rowFor("2001", Map.of("shift_name", "No Such Shift"))), "badshift.csv"))))
				.contains("shift_not_found");

		assertThat(firstRow(analyze(
				sheetWith(rowFor("2001", Map.of("shift_name", SHIFT_NAME))), "goodshift.csv")))
				.containsEntry("status", "valid");
	}

	/** Partial input: some rows fine, some not, all reported with their index. */
	@Test
	void aMixedSheetReportsEachRowIndependently() {
		Map<String, Object> body = analyze(sheetWith(
				rowFor("2001", Map.of("first_name", "Fine")),
				rowFor("999998", Map.of("first_name", "Ghost")),
				rowFor("2002", Map.of("address", "Also fine"))), "mixed.csv");

		assertThat(summaryOf(body)).containsEntry("total", 3)
				.containsEntry("valid", 2).containsEntry("invalid", 1);
		assertThat(rowsOf(body).get(1)).containsEntry("row_index", 2);
		assertThat(errorsOf(rowsOf(body).get(1))).containsExactly("employee_not_found");
	}

	/** The update sheet's own column metadata, not the create sheet's. */
	@Test
	void theColumnsAreTheUpdateVariant() {
		Map<String, Object> body = analyze(template(), "template.csv");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> columns = (List<Map<String, Object>>) dataOf(body).get("columns");

		Map<String, Object> employeeCode = columns.stream()
				.filter(column -> "employee_code".equals(column.get("key"))).findFirst().orElseThrow();
		Map<String, Object> firstName = columns.stream()
				.filter(column -> "first_name".equals(column.get("key"))).findFirst().orElseThrow();

		assertThat(employeeCode).containsEntry("required", true);
		assertThat(String.valueOf(employeeCode.get("label_en"))).contains("Must already exist");
		assertThat(firstName)
				.as("every other column is optional on the update sheet")
				.containsEntry("required", false);
		assertThat(String.valueOf(firstName.get("label_en"))).contains("Leave empty to skip");
	}

	// ---------------- helpers ----------------

	private byte[] template() {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + TEMPLATE + "?format=csv"), HttpMethod.GET,
				new HttpEntity<>(plainHeaders(tokenFor(ADMIN))), byte[].class).getBody();
	}

	private byte[] sheetWith(Map<String, String>... rows) {
		List<String> headers = LegacyEmployeeSpreadsheetColumns.columns().stream()
				.map(LegacyEmployeeSpreadsheetColumns.Column::key).toList();
		StringBuilder appended = new StringBuilder(new String(template(), StandardCharsets.UTF_8));
		for (Map<String, String> row : rows) {
			List<String> cells = new ArrayList<>(headers.size());
			for (String header : headers) {
				cells.add(row.getOrDefault(header, ""));
			}
			appended.append(LegacyCsvWriter.record(cells));
		}
		return appended.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static Map<String, String> rowFor(String code, Map<String, String> filled) {
		Map<String, String> row = new LinkedHashMap<>();
		row.put("employee_code", code);
		row.putAll(filled);
		return row;
	}

	private Map<String, Object> analyze(byte[] file, String filename) {
		ResponseEntity<Map<String, Object>> response = post(file, filename, ADMIN);
		assertThat(response.getStatusCode().value())
				.as("analyze %s: %s", filename, response.getBody()).isEqualTo(200);
		return response.getBody();
	}

	private ResponseEntity<Map<String, Object>> post(byte[] file, String filename, long employeeId) {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", filePart(file, filename));
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ANALYZE_UPDATE), HttpMethod.POST,
				new HttpEntity<>(parts, multipartHeaders(tokenFor(employeeId))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static HttpEntity<ByteArrayResource> filePart(byte[] content, String filename) {
		HttpHeaders partHeaders = new HttpHeaders();
		partHeaders.setContentDispositionFormData("file", filename);
		return new HttpEntity<>(new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		}, partHeaders);
	}

	/**
	 * For GETs. A multipart content type on a request with no body is rejected
	 * by the framework before any handler runs, which turns a 405 assertion
	 * into a 400 and a template download into an error page.
	 */
	private static HttpHeaders plainHeaders(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.set("Accept-Language", "en");
		return headers;
	}

	private static HttpHeaders multipartHeaders(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.set("Accept-Language", "en");
		return headers;
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == PLAIN_EMPLOYEE ? "employee" : "company_admin";
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> summaryOf(Map<String, Object> body) {
		return (Map<String, Object>) dataOf(body).get("summary");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rowsOf(Map<String, Object> body) {
		return (List<Map<String, Object>>) dataOf(body).get("rows");
	}

	private static Map<String, Object> firstRow(Map<String, Object> body) {
		assertThat(rowsOf(body)).isNotEmpty();
		return rowsOf(body).get(0);
	}

	@SuppressWarnings("unchecked")
	private static List<String> errorsOf(Map<String, Object> row) {
		return (List<String>) row.get("errors");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> payloadOf(Map<String, Object> row) {
		return (Map<String, Object>) row.get("payload");
	}


	private static Connection connect() throws Exception {
		return DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static void applySchema(String resourceName) throws Exception {
		String schema;
		try (java.io.InputStream stream = LegacyEmployeeAnalyzeExcelUpdateEndToEndTest.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
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
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (19801, 'Analyze Update Co', '+201000019801', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (19811, 19801, '" + BRANCH_NAME + "', 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES"
					+ " (19841, 19801, '" + SHIFT_NAME + "', '22:00:00', '06:00:00', '2025-04-12 10:00:00')");
			insertEmployee(st, ADMIN, "1001", "company_admin", "+201000198011", "Rana");
			insertEmployee(st, PLAIN_EMPLOYEE, "1004", "employee", "+201000198014", "Omar");
			insertEmployee(st, EXISTING, "2001", "employee", "+201000198021", "Aya");
			insertEmployee(st, 198022L, "2002", "employee", "+201000198022", "Basel");
		}
	}

	private static void insertEmployee(Statement st, long id, String code, String role,
			String phone, String firstName) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES ("
				+ id + ", 19801, 19811, '" + code + "', '" + firstName + "', 'Original', '"
				+ phone + "', '" + role + "', 1, '2025-04-01 08:00:00')");
	}

}
