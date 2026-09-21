package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.AbstractIntegrationTest;

/**
 * The penalties and advances CSV (spreadsheet) exports with
 * {@code app.platform-admin.actions.enabled} at its <b>default</b>, which is
 * {@code false} ({@link com.workin.backend.platformadmin.PlatformAdminCompanyActionDisabledTest}).
 *
 * <p>The export is a read: {@code GET /admin/penalties?export=csv} and
 * {@code GET /admin/advances?export=csv} run no write and touch no row. The
 * surrounding row actions -- edit, delete, mark applied, approve, reject --
 * are gated on {@code canWrite}, which the switch turns off, but copying that
 * guard onto the export control would hide a working read behind a write
 * switch that has nothing to do with it. Both cases the task calls out are
 * here: the control still renders, and the endpoint still answers.
 */
class AdminHrExportsWithActionsDisabledEndToEndTest extends AbstractIntegrationTest {

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private String cookie;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);

		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(),
				"password", TEST_ADMIN_PASSWORD));
	}

	@Test
	void theSwitchIsOffForThisContext() {
		// Pins the fixture this whole class depends on: no property here turns
		// it on, so a future default flip would silently invalidate every case
		// below rather than fail loudly -- the write button is gone, which is
		// only true while the switch is off.
		assertThat(body("/admin/penalties")).doesNotContain("crudOpenAdd('penModal')");
	}

	@Test
	void thePenaltiesExportControlRendersWithActionsDisabled() {
		long companyId = createCompany();
		createEmployeeAndPenalty(companyId);

		String html = body("/admin/penalties?company_id=" + companyId);
		assertThat(headActions(html))
				.as("a read is not behind the write switch")
				.contains("/admin/penalties?export=csv")
				.as("the add button is, and stays gone")
				.doesNotContain("crudOpenAdd('penModal')");
	}

	@Test
	void thePenaltiesExportStillAnswersWithActionsDisabled() {
		long companyId = createCompany();
		createEmployeeAndPenalty(companyId);

		ResponseEntity<byte[]> response = getBytes(
				"/admin/penalties?export=csv&company_id=" + companyId);
		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).asString()
				.isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		assertThat(sheetRows(response.getBody())).hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	void theAdvancesExportControlRendersWithActionsDisabled() {
		long companyId = createCompany();
		createEmployeeAndAdvance(companyId);

		String html = body("/admin/advances?company_id=" + companyId);
		assertThat(headActions(html))
				.as("a read is not behind the write switch")
				.contains("/admin/advances?export=csv")
				.as("the add button is, and stays gone")
				.doesNotContain("crudOpenAdd('advModal')");
	}

	@Test
	void theAdvancesExportStillAnswersWithActionsDisabled() {
		long companyId = createCompany();
		createEmployeeAndAdvance(companyId);

		ResponseEntity<byte[]> response = getBytes(
				"/admin/advances?export=csv&company_id=" + companyId);
		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).asString()
				.isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		assertThat(sheetRows(response.getBody())).hasSizeGreaterThanOrEqualTo(2);
	}

	// ------------------------------------------------------------------

	private void createEmployeeAndPenalty(long companyId) {
		long employeeId = createEmployee(companyId);
		this.jdbc.update("INSERT INTO penalties (employee_id, penalty_type, penalty_days,"
				+ " penalty_date, applied_to_payroll, created_at)"
				+ " VALUES (?, 'Lateness', '1', '2026-03-02', 0, NOW())", employeeId);
	}

	private void createEmployeeAndAdvance(long companyId) {
		long employeeId = createEmployee(companyId);
		this.jdbc.update("INSERT INTO advances (employee_id, amount, remaining, status,"
				+ " request_date, created_at) VALUES (?, 1000, 1000, 'approved', '2026-03-02', NOW())",
				employeeId);
	}

	private long createCompany() {
		String phone = "02" + System.nanoTime() % 1_000_000_000L;
		return this.jdbc.queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status,"
						+ " otp_verified, profile_completed, created_at)"
						+ " VALUES ('Switch Off Co', ?, ?, 'active', 1, 1, NOW()) RETURNING id",
				Long.class, phone, this.passwordEncoder.encode(TEST_ADMIN_PASSWORD));
	}

	private long createEmployee(long companyId) {
		long branchId = this.jdbc.queryForObject(
				"INSERT INTO branches (company_id, name, is_active, created_at)"
						+ " VALUES (?, 'Switch Off Branch', 1, NOW()) RETURNING id",
				Long.class, companyId);
		return this.jdbc.queryForObject(
				"INSERT INTO employees (company_id, branch_id, employee_code, first_name, last_name,"
						+ " role, is_active, is_mobile_attendance_enabled, can_check_in_any_branch,"
						+ " join_request_status, token_version, created_at, updated_at)"
						+ " VALUES (?, ?, 'SW100', 'Switch', 'Off', 'employee', 1, 1, 0, 'accepted',"
						+ " 1, NOW(), NOW()) RETURNING id",
				Long.class, companyId, branchId);
	}

	/** The actions beside the list's title. */
	private static String headActions(String html) {
		Matcher actions = Pattern.compile("(?s)<div class=\"data-table-head__actions\">.*?</div>")
				.matcher(html);
		assertThat(actions.find()).as("the list's head actions").isTrue();
		return actions.group();
	}

	/** The workbook's one sheet, row by row, each cell's inline string. */
	private static List<List<String>> sheetRows(byte[] workbook) {
		String sheet = null;
		try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
				new java.io.ByteArrayInputStream(workbook))) {
			for (java.util.zip.ZipEntry entry = zip.getNextEntry(); entry != null;
					entry = zip.getNextEntry()) {
				if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
					sheet = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				}
			}
		} catch (java.io.IOException ex) {
			throw new AssertionError("the response is not a readable ZIP container", ex);
		}
		assertThat(sheet).as("the workbook's sheet").isNotNull();
		List<List<String>> rows = new ArrayList<>();
		Matcher row = Pattern.compile("(?s)<row\\b.*?</row>").matcher(sheet);
		while (row.find()) {
			List<String> cells = new ArrayList<>();
			Matcher cell = Pattern.compile("(?s)<is><t[^>]*>(.*?)</t></is>").matcher(row.group());
			while (cell.find()) {
				cells.add(cell.group(1));
			}
			rows.add(cells);
		}
		return rows;
	}

	private String body(String path) {
		return get(path, this.cookie).getBody();
	}

	private ResponseEntity<byte[]> getBytes(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + this.cookie);
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
	}

	private ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private Page page(String path, String sessionCookie) {
		ResponseEntity<String> response = get(path, sessionCookie);
		String resolved = sessionCookie != null ? sessionCookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	private ResponseEntity<String> post(String path, String sessionCookie, Csrf csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int index = 0; index < fields.length; index += 2) {
			form.add(fields[index], fields[index + 1]);
		}
		form.add(csrf.name(), csrf.value());
		return this.restTemplate.exchange(path, HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class);
	}

	private static Csrf csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new Csrf(matcher.group(1), matcher.group(2));
	}

	private static String cookieOf(ResponseEntity<String> response) {
		String value = tryCookieOf(response);
		assertThat(value).as("expected a session cookie").isNotNull();
		return value;
	}

	private static String tryCookieOf(ResponseEntity<String> response) {
		List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		if (cookies == null) {
			return null;
		}
		return cookies.stream()
				.filter(value -> value.startsWith("WORKIN_ADMIN_SESSION="))
				.map(header -> {
					int start = header.indexOf('=') + 1;
					int end = header.indexOf(';', start);
					return end < 0 ? header.substring(start) : header.substring(start, end);
				})
				.findFirst().orElse(null);
	}

	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

}
