package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
 * G6 evidence for {@code payslips/export.php} (Wave 12.9) -- the last of C9's
 * three endpoints, and the one that closes gate G2's numerator.
 *
 * <p>Parity is asserted on what a reader and an HTTP client see, per D-085: the
 * workbook's own parts, the headers, and the filename. Never archive bytes.
 *
 * <p>The cases are this endpoint's own rules rather than the export machinery's:
 * the date pair's two distinct refusals, the period <b>overlap</b> filter, the
 * filename's three-way branch and its precedence, and the employee scope.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyPayslipExportEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String EXPORT = "/apis/api/payslips/export.php";
	private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private static final long COMPANY = 21901L;
	private static final long BRANCH = 21911L;
	private static final long ADMIN = 219011L;
	private static final long EMPLOYEE_A = 219012L;
	private static final long EMPLOYEE_B = 219013L;
	private static final long BATCH = 2190100L;

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
			throw new IllegalStateException("could not prepare the payslip export fixture", ex);
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
	void theExportIsAnXlsxAttachmentNamedForToday() {
		ResponseEntity<byte[]> response = get(ADMIN, "company_admin", "");

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo(XLSX);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.contains("attachment", "payslips_").endsWith(".xlsx\"");
		assertThat(response.getHeaders().getContentLength()).isEqualTo(response.getBody().length);
		assertThatIsARealWorkbook(response.getBody());
	}

	@Test
	void aBatchFilterNamesTheFileForTheBatch() {
		assertThat(get(ADMIN, "company_admin", "?batch_id=" + BATCH)
				.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.contains("payslips_batch_" + BATCH + ".xlsx");
	}

	@Test
	void aCompleteDateRangeNamesTheFileForThePeriodAndBeatsTheBatch() {
		assertThat(get(ADMIN, "company_admin", "?batch_id=" + BATCH + "&from=2026-06-01&to=2026-06-30")
				.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.as("the date branch runs second and overwrites the batch suffix")
				.contains("payslips_2026-06-01_2026-06-30.xlsx");
	}

	/** Both bounds or neither: one alone is `invalid_date`, not a default. */
	@Test
	void aHalfSuppliedDateRangeIsRefused() {
		assertThat(get(ADMIN, "company_admin", "?from=2026-06-01").getStatusCode().value()).isEqualTo(400);
		assertThat(get(ADMIN, "company_admin", "?to=2026-06-30").getStatusCode().value()).isEqualTo(400);
	}

	@Test
	void anInvertedDateRangeIsRefused() {
		assertThat(get(ADMIN, "company_admin", "?from=2026-06-30&to=2026-06-01").getStatusCode().value())
				.isEqualTo(400);
	}

	/**
	 * The date filter is an <b>overlap</b> test -- `period_to >= from AND
	 * period_from <= to` -- so a batch straddling either bound is included. A
	 * containment test would drop this batch, and the workbook would silently
	 * lose rows rather than fail.
	 */
	@Test
	void aBatchStraddlingTheRangeBoundIsIncluded() {
		// The batch runs 2026-06-01..2026-06-30; the filter starts mid-period.
		byte[] straddling = get(ADMIN, "company_admin", "?from=2026-06-15&to=2026-07-15").getBody();
		byte[] disjoint = get(ADMIN, "company_admin", "?from=2026-09-01&to=2026-09-30").getBody();

		assertThat(sheetXml(straddling))
				.as("the straddled batch's rows are in the sheet")
				.contains("Pay A");
		assertThat(sheetXml(disjoint))
				.as("a range that does not overlap the batch selects nothing")
				.doesNotContain("Pay A");
	}

	/** An EMPLOYEE is served, but only their own payslips. */
	@Test
	void anEmployeeSeesOnlyTheirOwnPayslips() {
		String sheet = sheetXml(get(EMPLOYEE_A, "employee", "").getBody());

		assertThat(sheet).contains("Pay A");
		assertThat(sheet).as("employee B's row must not reach employee A").doesNotContain("Pay B");
	}

	@Test
	void anAdminSeesEveryEmployeesPayslips() {
		String sheet = sheetXml(get(ADMIN, "company_admin", "").getBody());
		assertThat(sheet).contains("Pay A", "Pay B");
	}

	/**
	 * The {@code from}/{@code to} pair reaches the filename, and the filename
	 * reaches {@code Content-Disposition}. PHP sanitizes inside
	 * {@code api_xlsx_export_send()} -- the terminator both exports share -- so a
	 * port that skips it is both a header-injection vector and a parity defect.
	 */
	@Test
	void aFilenameCannotCarryQuotesOrControlCharactersIntoTheHeader() {
		ResponseEntity<byte[]> response = get(ADMIN, "company_admin",
				"?from=2026-06-01%22;x=%22a&to=2026-06-30%0d%0aX-Injected:%201");
		assertThat(response.getStatusCode().value())
				.as("a hostile filename must not turn into a server error either")
				.isEqualTo(200);
		String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);

		assertThat(disposition).as("the header must still be well-formed").isNotNull();
		assertThat(disposition.chars().filter(c -> c == '"').count())
				.as("exactly the two quotes that delimit the filename -- four would mean the "
						+ "attacker closed the value and appended a parameter of their own")
				.isEqualTo(2);
		assertThat(disposition).doesNotContain("\r", "\n");
		assertThat(disposition).endsWith(".xlsx\"");

		// The hostile text survives *as filename content*, which is the point:
		// its quote, colon, semicolon and CRLF are collapsed to underscores, so
		// it can no longer close the value or start a parameter or a header.
		String value = disposition.substring(disposition.indexOf('"') + 1, disposition.length() - 1);
		assertThat(value)
				.as("every character is inside api_xlsx_export_send()'s own class")
				.matches("[A-Za-z0-9._-]+");
	}

	/**
	 * `month`/`year` stay 64-bit to the SQL bind. Narrowing to `int` wraps --
	 * `4294967302` becomes 6 -- so a caller could export June by asking for a
	 * month PHP would have matched against no batch at all.
	 */
	@Test
	void anOutOfRangeMonthDoesNotWrapIntoARealMonth() {
		String june = sheetXml(get(ADMIN, "company_admin", "?month=6").getBody());
		assertThat(june).as("the fixture batch is month 6").contains("Pay A");

		String wrapped = sheetXml(get(ADMIN, "company_admin", "?month=4294967302").getBody());
		assertThat(wrapped)
				.as("4294967302 truncates to 6 in 32 bits; it must not select June's batch")
				.doesNotContain("Pay A");
	}

	/**
	 * `search_query_param()` uses PHP's `trim()`, which leaves a form feed in
	 * place. Java's `String.trim()` would strip it, turning the filter into null
	 * and exporting everything in scope rather than nothing.
	 */
	@Test
	void aFormFeedSearchFiltersRatherThanMatchingEverything() {
		String filtered = sheetXml(get(ADMIN, "company_admin", "?search=%0C").getBody());
		assertThat(filtered)
				.as("a form-feed search is a LIKE that matches no employee")
				.doesNotContain("Pay A", "Pay B");
	}

	@Test
	void aNonGetMethodIsRefused() {
		ResponseEntity<byte[]> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + EXPORT), HttpMethod.POST,
				new HttpEntity<>(authHeaders(ADMIN, "company_admin")), byte[].class);
		assertThat(response.getStatusCode().value()).isEqualTo(405);
	}

	private static void assertThatIsARealWorkbook(byte[] body) {
		assertThat(body).isNotNull();
		assertThat(new String(body, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
		assertThat(entry(body, "xl/worksheets/sheet1.xml")).isNotNull();
		assertThat(entry(body, "xl/workbook.xml")).isNotNull();
		assertThat(entry(body, "[Content_Types].xml")).isNotNull();
	}

	/** The sheet's own XML, so row content is asserted rather than byte length. */
	private static String sheetXml(byte[] body) {
		String xml = entry(body, "xl/worksheets/sheet1.xml");
		assertThat(xml).as("the workbook must carry a readable sheet").isNotNull();
		return xml;
	}

	private static String entry(byte[] body, String name) {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				if (name.equals(entry.getName())) {
					return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		} catch (Exception ex) {
			throw new AssertionError("the response is not a readable ZIP container", ex);
		}
		return null;
	}

	private ResponseEntity<byte[]> get(long employeeId, String role, String query) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + EXPORT + query), HttpMethod.GET,
				new HttpEntity<>(authHeaders(employeeId, role)), byte[].class);
	}

	private HttpHeaders authHeaders(long employeeId, String role) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(jwtService.issueAccessToken(employeeId, employeeId, COMPANY, "test-session",
				Map.of("role", role, "token_version", 1L)));
		headers.set("Accept-Language", "en");
		return headers;
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Payslip Export Co', '+201000021901', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");
			for (Object[] employee : List.<Object[]>of(
					new Object[] { ADMIN, "Adm", "One", "company_admin" },
					new Object[] { EMPLOYEE_A, "Pay", "A", "employee" },
					new Object[] { EMPLOYEE_B, "Pay", "B", "employee" })) {
				st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
						+ " last_name, phone, role, is_active, created_at) VALUES (" + employee[0] + ", "
						+ COMPANY + ", " + BRANCH + ", " + employee[0] + ", '" + employee[1] + "', '"
						+ employee[2] + "', '+2010002" + employee[0] + "', '" + employee[3] + "', 1,"
						+ " '2019-04-01 08:00:00')");
			}
			st.execute("INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to,"
					+ " status, created_at) VALUES (" + BATCH + ", " + COMPANY + ", 6, 2026, '2026-06-01',"
					+ " '2026-06-30', 'draft', '2026-06-01 08:00:00')");
			for (long employeeId : new long[] { EMPLOYEE_A, EMPLOYEE_B }) {
				// `payslips` has no created_at -- it is one of the few legacy
				// tables without one.
				st.execute("INSERT INTO payslips (batch_id, employee_id, days_present, days_absent,"
						+ " days_leave, overtime_hours, basic_salary, gross_salary, net_salary)"
						+ " VALUES (" + BATCH + ", " + employeeId + ", 22, 0, 0, 0, 5000.00, 5000.00,"
						+ " 4321.00)");
			}
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
		try (InputStream in = LegacyPayslipExportEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
