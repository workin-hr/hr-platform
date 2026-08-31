package com.workin.legacy.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/** Focused parity regressions for Wave 12.7 leave balances. */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyLeaveBalanceEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final long COMPANY = 21701L;
	private static final long BRANCH_A = 21711L;
	private static final long BRANCH_B = 21712L;
	private static final long MANAGER = 217011L;
	private static final long EMPLOYEE_A1 = 217012L;
	private static final long EMPLOYEE_A2 = 217013L;
	private static final long EMPLOYEE_B = 217014L;
	private static final long ADMIN = 217015L;
	private static final int YEAR = 2026;

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
			throw new IllegalStateException("could not prepare leave-balance fixture", ex);
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
	void managerEmployeeFilterNarrowsInsideSameBranch() {
		Map<String, Object> body = get("/apis/api/leave_balances/list.php?year=" + YEAR
				+ "&employee_id=" + EMPLOYEE_A2 + "&limit=100", MANAGER);

		assertThat(employeeIds(body)).containsExactly(EMPLOYEE_A2);
	}

	@Test
	void managerEmployeeFilterCannotEscapeSameBranch() {
		Map<String, Object> body = get("/apis/api/leave_balances/list.php?year=" + YEAR
				+ "&employee_id=" + EMPLOYEE_B + "&limit=100", MANAGER);

		assertThat(employeeIds(body)).isEmpty();
	}


	/**
	 * PHP guards this with {@code isset($_FILES['file'])}. A multipart part
	 * carrying no filename is a {@code $_POST} field there and never reaches
	 * {@code $_FILES}, so PHP answers {@code no_file_uploaded}.
	 *
	 * <p>{@code request.getPart("file")} does not make that distinction and
	 * returns the text part, so Java parsed it as a spreadsheet and answered
	 * 200 — measured against both stacks before the fix. The other four
	 * multipart endpoints were already correct because they resolve through
	 * {@code MultipartHttpServletRequest}, which only exposes parts that have a
	 * filename; this endpoint was the one still calling {@code getPart}.
	 */
	@Test
	void aTextFieldNamedFileIsNotAnUpload() {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", "not-a-file");
		parts.add("year", String.valueOf(YEAR));

		ResponseEntity<Map<String, Object>> response = postMultipart(parts);

		assertThat(response.getStatusCode().value())
				.as("a text field named `file` is not an upload: %s", response.getBody())
				.isEqualTo(400);
		assertThat(response.getBody().get("message")).isEqualTo("No file uploaded");
	}

	/**
	 * {@code filename=""} is {@code UPLOAD_ERR_NO_FILE} in PHP, so it answers
	 * {@code no_file_uploaded} — but Spring still exposes a {@code
	 * MultipartFile} for it, and skipping only a <em>null</em> submitted
	 * filename let it reach the analyzer, which answered
	 * {@code Empty or unreadable file} instead.
	 */
	@Test
	void anEmptyFilenameIsNotAnUploadEither() {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", filePart("employee_code,total_days\n", ""));
		parts.add("year", String.valueOf(YEAR));

		ResponseEntity<Map<String, Object>> response = postMultipart(parts);

		assertThat(response.getStatusCode().value())
				.as("empty filename is UPLOAD_ERR_NO_FILE in PHP: %s", response.getBody())
				.isEqualTo(400);
		assertThat(response.getBody().get("message")).isEqualTo("No file uploaded");
	}

	/**
	 * The boundary the guard must <em>not</em> swallow: a zero-byte file the
	 * user actually chose is {@code UPLOAD_ERR_OK} in PHP, so it falls through
	 * to the format check and fails there — not as {@code no_file_uploaded}.
	 * Without this, tightening the guard to an emptiness test would look
	 * correct and silently change a second behaviour.
	 */
	@Test
	void aZeroByteFileWithARealNameFallsThroughToTheFormatCheck() {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", filePart("", "balances.csv"));
		parts.add("year", String.valueOf(YEAR));

		ResponseEntity<Map<String, Object>> response = postMultipart(parts);

		// The exact contract, not merely "not the other message": a negative
		// assertion here would also pass on a 200, a 500, or any other 400.
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("message"))
				.as("a chosen zero-byte file is UPLOAD_ERR_OK in PHP, so it reaches the "
						+ "format check rather than being refused as a missing upload")
				.isEqualTo("Empty or unreadable file");
	}

	/** The same endpoint still accepts a genuine upload. */
	@Test
	void aRealUploadIsStillAccepted() {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		parts.add("file", filePart("employee_code,total_days\n" + EMPLOYEE_A1 + ",21\n"));
		parts.add("year", String.valueOf(YEAR));

		ResponseEntity<Map<String, Object>> response = postMultipart(parts);

		assertThat(response.getStatusCode().value())
				.as("a real upload must still be analyzed: %s", response.getBody())
				.isEqualTo(200);
	}

	private ResponseEntity<Map<String, Object>> postMultipart(MultiValueMap<String, Object> parts) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(ADMIN));
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.set("Accept-Language", "en");
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/leave_balances/analyze_excel.php"),
				HttpMethod.POST, new HttpEntity<>(parts, headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static HttpEntity<ByteArrayResource> filePart(String csv) {
		return filePart(csv, "balances.csv");
	}

	private static HttpEntity<ByteArrayResource> filePart(String csv, String filename) {
		HttpHeaders partHeaders = new HttpHeaders();
		partHeaders.setContentDispositionFormData("file", filename);
		return new HttpEntity<>(new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)), partHeaders);
	}

	private Map<String, Object> get(String path, long employeeId) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		headers.set("Accept-Language", "en");
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).as("%s: %s", path, response.getBody()).isEqualTo(200);
		return response.getBody();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == ADMIN ? "company_admin"
				: employeeId == MANAGER ? "manager" : "employee";
		return jwtService.issueAccessToken(
				employeeId, employeeId, COMPANY, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static List<Long> employeeIds(Map<String, Object> body) {
		List<Long> ids = new ArrayList<>();
		for (Object row : (List<?>) body.get("data")) {
			ids.add(((Number) ((Map<?, ?>) row).get("employee_id")).longValue());
		}
		return ids;
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES ("
					+ COMPANY + ", 'Leave Balance Co', '+201000021701', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES "
					+ "(" + BRANCH_A + ", " + COMPANY + ", 'Branch A', 1, '2025-03-01 10:00:00'),"
					+ "(" + BRANCH_B + ", " + COMPANY + ", 'Branch B', 1, '2025-03-01 10:00:00')");
			employee(st, MANAGER, BRANCH_A, "manager", "Manager");
			employee(st, EMPLOYEE_A1, BRANCH_A, "employee", "Employee A1");
			employee(st, EMPLOYEE_A2, BRANCH_A, "employee", "Employee A2");
			employee(st, EMPLOYEE_B, BRANCH_B, "employee", "Employee B");
			employee(st, ADMIN, BRANCH_A, "company_admin", "Admin");
			st.execute("INSERT INTO leave_balance (employee_id, year, total_days, used_days) VALUES "
					+ "(" + EMPLOYEE_A1 + ", " + YEAR + ", 21, 1),"
					+ "(" + EMPLOYEE_A2 + ", " + YEAR + ", 22, 2),"
					+ "(" + EMPLOYEE_B + ", " + YEAR + ", 23, 3)");
		}
	}

	private static void employee(Statement st, long id, long branchId, String role, String name) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, role, is_active,"
				+ " join_request_status, phone, first_name, last_name, created_at) VALUES ("
				+ id + ", " + COMPANY + ", " + branchId + ", '" + id + "', '" + role + "', 1,"
				+ " 'accepted', '+2010" + id + "', '" + name + "', 'Test', '2025-01-20 09:00:00')");
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

	private static String readResource(String resourceName) throws Exception {
		try (InputStream stream = LegacyLeaveBalanceEndToEndTest.class.getClassLoader().getResourceAsStream(resourceName)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + resourceName);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
