package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * {@code penalties/report.php} is the one delivered route whose <em>wire contract</em> depends
 * on a query parameter: {@code ?format=csv} reaches the file's own local {@code streamCSV()}
 * ({@code penalties/report.php:24}) and exits with a workbook download, while anything else
 * falls through to {@code ok()} and the D-074 JSON envelope.
 *
 * <p>The completion plan's §5 G3 partitions the 125 delivered routes 122 / 2 / 1 by response
 * shape, and {@code LegacyPhpRouteInventoryTest} keeps that partition honest -- but only by
 * reading each handler's declared return type. That catches a <em>new</em> non-envelope route;
 * it cannot catch this route losing its download branch while still returning
 * {@code ResponseEntity<?>}. These two tests close that gap by exercising both branches at the
 * request level, so the documented inventory follows live behaviour rather than a type
 * signature.
 *
 * <p>Two legacy traps are asserted rather than described, because both are easy to "simplify"
 * away:
 * <ul>
 *   <li>the local {@code streamCSV()} <b>shadows</b> the global one in
 *       {@code functions.php:398}, which genuinely emits {@code text/csv} -- so a correct port
 *       must <em>not</em> answer with a CSV content type;</li>
 *   <li>it rewrites the {@code .csv} filename it is handed to {@code .xlsx}, so the attachment
 *       is a workbook despite every name in the call path saying CSV.</li>
 * </ul>
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyPenaltyReportBranchesEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String REPORT = "/apis/api/penalties/report.php";

	private static final String XLSX_CONTENT_TYPE =
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private static final long COMPANY_1 = 21901L;
	private static final long ADMIN_1 = 219011L;
	private static final long EMPLOYEE_1 = 219012L;
	private static final long BRANCH_1 = 21911L;

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
			throw new IllegalStateException("could not prepare the penalty report fixture", ex);
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
	void withoutTheCsvFormatTheReportAnswersInTheD074Envelope() {
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + REPORT), HttpMethod.GET,
				new HttpEntity<>(authHeaders()), new ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
				.as("the default branch is JSON, not a download")
				.startsWith("application/json");
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
		assertThat(response.getBody()).containsKey("success");
		assertThat(response.getBody().get("success")).isEqualTo(true);
	}

	@Test
	void theCsvFormatStreamsAnXlsxWorkbookAttachmentInsteadOfTheEnvelope() {
		ResponseEntity<byte[]> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + REPORT + "?format=csv"), HttpMethod.GET,
				new HttpEntity<>(authHeaders()), byte[].class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
				.as("the local streamCSV() shadows the global text/csv one and sends a workbook")
				.isEqualTo(XLSX_CONTENT_TYPE);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.as("PHP rewrites the .csv name it is handed to .xlsx")
				.contains("attachment", "penalties_report_", ".xlsx")
				.doesNotContain(".csv\"");

		byte[] body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.length).isGreaterThan(0);
		// XLSX is a ZIP container: "PK\003\004". Asserting the magic bytes proves a real
		// workbook was streamed rather than an envelope that merely carried the headers.
		assertThat(new String(body, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
		assertThat(new String(body, 0, Math.min(body.length, 64), StandardCharsets.US_ASCII))
				.as("a JSON envelope must not be served under the workbook content type")
				.doesNotContain("\"success\"");
	}

	private HttpHeaders authHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(jwtService.issueAccessToken(ADMIN_1, ADMIN_1, COMPANY_1, "test-session",
				Map.of("role", "company_admin", "token_version", 1L)));
		headers.set("Accept-Language", "en");
		return headers;
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY_1 + ", 'Penalty Report Co', '+201000021901', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH_1 + ", " + COMPANY_1 + ", 'Main', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + ADMIN_1 + ", " + COMPANY_1
					+ ", " + BRANCH_1 + ", " + ADMIN_1 + ", 'Admin', 'One', '+201000219011', 'company_admin',"
					+ " 1, '2019-04-01 08:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + EMPLOYEE_1 + ", " + COMPANY_1
					+ ", " + BRANCH_1 + ", " + EMPLOYEE_1 + ", 'Emp', 'One', '+201000219012', 'employee',"
					+ " 1, '2019-04-01 08:00:00')");
			st.execute("INSERT INTO penalties (id, employee_id, penalty_type, penalty_days, reason,"
					+ " penalty_date, applied_to_payroll, created_at) VALUES"
					+ " (2190100, " + EMPLOYEE_1 + ", 'late', 1.5, 'Late arrival', '2020-06-10', 0,"
					+ " '2020-06-10 08:00:00')");
		}
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
		try (InputStream in = LegacyPenaltyReportBranchesEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
