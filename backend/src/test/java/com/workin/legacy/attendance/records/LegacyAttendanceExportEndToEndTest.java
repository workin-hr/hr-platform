package com.workin.legacy.attendance.records;

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
 * G6 evidence for {@code attendance/export.php} (Wave 12.6.6d).
 *
 * <p>Parity is asserted on what a reader and an HTTP client see -- headers,
 * filename, and the workbook's own parts -- never on archive bytes, which D-085
 * settled are incidental.
 *
 * <p>The cases cover what separates this endpoint from
 * {@code overall_report.php}: no role list, so an EMPLOYEE reaches it; a second
 * sheet behind three {@code type} values; a config gate per sheet; and its own
 * date fallback and inverted-range error code.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyAttendanceExportEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String EXPORT = "/apis/api/attendance/export.php";
	private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private static final long COMPANY = 21801L;
	private static final long BRANCH = 21811L;
	private static final long ADMIN = 218011L;
	private static final long EMPLOYEE = 218012L;
	private static final long SHIFT = 218021L;

	private static final String FROM = "2026-05-01";
	private static final String TO = "2026-05-31";

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
			throw new IllegalStateException("could not prepare the attendance export fixture", ex);
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
	void theOverallSheetIsAnXlsxAttachmentNamedForItsPeriod() {
		ResponseEntity<byte[]> response = get(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo(XLSX);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.contains("attachment", "overall_attendance_" + FROM + "_" + TO + ".xlsx")
				.doesNotContain(".csv");
		assertThat(response.getHeaders().getContentLength()).isEqualTo(response.getBody().length);
		assertThatIsARealWorkbook(response.getBody());
	}

	/**
	 * The three `type` values that select the day-level sheet. Its filename and
	 * sheet differ, which is the observable half of the branch.
	 */
	@Test
	void allThreeFingerprintTypesSelectTheDayLevelSheet() {
		for (String type : List.of("fingerprints", "details", "days")) {
			ResponseEntity<byte[]> response =
					get(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO + "&type=" + type);

			assertThat(response.getStatusCode().value()).as("type=%s", type).isEqualTo(200);
			assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
					.as("type=%s", type)
					.contains("fingerprints_" + FROM + "_" + TO + ".xlsx");
			assertThatIsARealWorkbook(response.getBody());
		}
	}

	/**
	 * Every style id a sheet references must exist in {@code styles.xml}.
	 *
	 * <p>The fingerprints sheet colours each row 2, 6 or 7, and the Java writer
	 * declared only {@code cellXfs count="6"} -- ids 0-5 -- where PHP declares
	 * eight. A cell pointing at a style the table does not declare is a workbook
	 * a reader may repair or reject, and it renders none of the promised green
	 * and red rows.
	 */
	@Test
	void everyStyleTheFingerprintsSheetReferencesExistsInTheStyleTable() {
		byte[] body = get(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO + "&type=fingerprints").getBody();

		String styles = entry(body, "xl/styles.xml");
		assertThat(styles).as("the workbook must carry a style table").isNotNull();
		java.util.regex.Matcher declared = java.util.regex.Pattern
				.compile("<cellXfs count=\"(\\d+)\"").matcher(styles);
		assertThat(declared.find()).isTrue();
		int styleCount = Integer.parseInt(declared.group(1));

		String sheet = entry(body, "xl/worksheets/sheet1.xml");
		assertThat(sheet).isNotNull();
		java.util.regex.Matcher used = java.util.regex.Pattern.compile("s=\"(\\d+)\"").matcher(sheet);
		java.util.Set<Integer> referenced = new java.util.LinkedHashSet<>();
		while (used.find()) {
			referenced.add(Integer.parseInt(used.group(1)));
		}

		assertThat(referenced).as("the sheet must actually style its rows").isNotEmpty();
		assertThat(referenced).allSatisfy(id -> assertThat(id)
				.as("style id %s must be inside the declared cellXfs range", id)
				.isLessThan(styleCount));
	}

	@Test
	void anUnknownTypeFallsThroughToTheOverallSheet() {
		ResponseEntity<byte[]> response =
				get(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO + "&type=something-else");

		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.contains("overall_attendance_");
	}

	/**
	 * `export.php` calls `requireAuth()` with no role list, unlike
	 * `overall_report.php` -- so an EMPLOYEE is served here, and the builder's
	 * employee branch is reachable only through this endpoint.
	 */
	@Test
	void anEmployeeIsServedHereEvenThoughTheReportEndpointRefusesThem() {
		assertThat(get(EMPLOYEE, "employee", "?from=" + FROM + "&to=" + TO).getStatusCode().value())
				.isEqualTo(200);
	}

	@Test
	void aNonGetMethodIsRefused() {
		HttpHeaders headers = authHeaders(ADMIN, "company_admin");
		ResponseEntity<byte[]> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + EXPORT), HttpMethod.POST,
				new HttpEntity<>(headers), byte[].class);
		assertThat(response.getStatusCode().value()).isEqualTo(405);
	}

	/**
	 * The gate runs after the `type` branch is chosen, so disabling one sheet
	 * refuses it rather than falling back to the other.
	 */
	@Test
	void aDisabledSheetIsRefusedRatherThanFallingBackToTheOther() throws Exception {
		setConfig(LegacyExportSheetAvailability.FINGERPRINTS_KEY, "false");
		try {
			assertThat(get(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO + "&type=fingerprints")
					.getStatusCode().value())
					.as("the disabled sheet is 403, not a redirect to the overall one")
					.isEqualTo(403);
			assertThat(get(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO).getStatusCode().value())
					.as("the other sheet is unaffected")
					.isEqualTo(200);
		} finally {
			setConfig(LegacyExportSheetAvailability.FINGERPRINTS_KEY, "true");
		}
	}

	/** A missing row resolves to the `'true'` default, so both sheets are on by default. */
	@Test
	void aSheetWithNoConfigRowIsEnabledByDefault() throws Exception {
		deleteConfig(LegacyExportSheetAvailability.OVERALL_KEY);
		try {
			assertThat(get(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO).getStatusCode().value())
					.isEqualTo(200);
		} finally {
			setConfig(LegacyExportSheetAvailability.OVERALL_KEY, "true");
		}
	}

	/**
	 * The fingerprints sheet's inverted range is `invalid_input`, where the
	 * report's is `invalid_date` -- two error codes in one endpoint file.
	 */
	@Test
	void anInvertedRangeIsRefused() {
		assertThat(get(ADMIN, "company_admin", "?from=" + TO + "&to=" + FROM + "&type=fingerprints")
				.getStatusCode().value()).isEqualTo(400);
	}

	/** Absent dates fall back to a period rather than failing, and still produce a workbook. */
	@Test
	void absentDatesStillProduceAWorkbook() {
		ResponseEntity<byte[]> response = get(ADMIN, "company_admin", "");
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThatIsARealWorkbook(response.getBody());
	}

	/**
	 * Reader-observable parity, per D-085: the response is a genuine XLSX
	 * container carrying the parts a spreadsheet reader opens -- not merely a
	 * blob served under the right content type.
	 */
	private static void assertThatIsARealWorkbook(byte[] body) {
		assertThat(body).isNotNull();
		assertThat(new String(body, 0, 2, StandardCharsets.US_ASCII))
				.as("XLSX is a ZIP container")
				.isEqualTo("PK");
		assertThat(entryNames(body))
				.contains("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml");
	}

	/** One entry's text, or null when the workbook does not carry it. */
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

	private static List<String> entryNames(byte[] body) {
		List<String> names = new java.util.ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				names.add(entry.getName());
			}
		} catch (Exception ex) {
			throw new AssertionError("the response is not a readable ZIP container", ex);
		}
		return names;
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

	private static void setConfig(String key, String value) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("DELETE FROM configs WHERE config_key = '" + key + "'");
			st.execute("INSERT INTO configs (config_key, config_value) VALUES ('" + key + "', '" + value + "')");
		}
	}

	private static void deleteConfig(String key) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("DELETE FROM configs WHERE config_key = '" + key + "'");
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Export Co', '+201000021801', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active,"
					+ " created_at) VALUES (" + SHIFT + ", " + COMPANY + ", 'Day', '09:00:00', '17:00:00', '', 1,"
					+ " '2019-05-01 08:00:00')");
			for (long id : new long[] { ADMIN, EMPLOYEE }) {
				st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
						+ " last_name, phone, role, is_active, expected_daily_hours, created_at) VALUES ("
						+ id + ", " + COMPANY + ", " + BRANCH + ", " + id + ", 'Exp', 'One', '+2010002" + id
						+ "', '" + (id == ADMIN ? "company_admin" : "employee") + "', 1, 8,"
						+ " '2019-04-01 08:00:00')");
			}
			st.execute("INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES"
					+ " (" + EMPLOYEE + ", " + SHIFT + ", '2019-05-01')");
			st.execute("INSERT INTO attendance (employee_id, check_in, check_out, created_at) VALUES"
					+ " (" + EMPLOYEE + ", '2026-05-04 09:00:00', '2026-05-04 17:00:00', '2026-05-04 17:00:00')");
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
		try (InputStream in = LegacyAttendanceExportEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
