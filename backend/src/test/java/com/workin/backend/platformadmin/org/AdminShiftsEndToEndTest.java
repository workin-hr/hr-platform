package com.workin.backend.platformadmin.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.platformadmin.mfa.Totp;

/**
 * {@code /admin/shifts} over real HTTP against a real MariaDB.
 *
 * <p>The simplest of the four org pages, so this is mostly about proving that
 * the shared machinery still holds on a page with almost no rules of its own:
 * a non-empty name, and two times that are deliberately <b>not</b> validated.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminShiftsEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
		} catch (Exception ex) {
			throw new IllegalStateException("could not apply the legacy schema", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.mfa.encryption-key", () -> {
			byte[] key = new byte[32];
			new java.security.SecureRandom().nextBytes(key);
			return java.util.Base64.getEncoder().encodeToString(key);
		});
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private PlatformAdminMfaService mfaService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private String cookie;

	private long companyA;

	private long companyB;


	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM employee_shift_assignments");
		this.jdbc.update("DELETE FROM shifts");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2094" + System.nanoTime() % 100_000_000L;
		this.jdbc.update("INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, 1)",
				phone, this.passwordEncoder.encode(PASSWORD));
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = ?", Long.class, phone);

		String token = this.mfaService.issueBootstrapToken(adminId, adminId);
		String seed = this.mfaService.beginEnrolment(adminId, token).orElseThrow();
		assertThat(this.mfaService.confirmEnrolment(adminId, code(seed))).isTrue();

		Page login = page("/admin/login", null);
		String pending = cookieOf(post("/admin/login", login.cookie(), login.csrf(),
				"phone", phone, "password", PASSWORD));
		this.jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL"
				+ " WHERE platform_admin_id = ?", adminId);
		this.cookie = cookieOf(post("/admin/mfa", pending,
				page("/admin/mfa", pending).csrf(), "code", code(seed)));

		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");

	}

	@Test
	void addingAShiftStoresItsTimesAndDefaultsToActive() {
		post("/admin/shifts", this.cookie, page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Morning",
				"start_time", "09:00", "end_time", "17:00");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, start_time, end_time, is_active FROM shifts WHERE name = 'Morning'");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("start_time").toString()).startsWith("09:00");
		assertThat(row.get("end_time").toString()).startsWith("17:00");
		assertThat(row.get("is_active")).isEqualTo(Boolean.TRUE);
		assertThat(body("/admin/shifts")).contains("Morning").contains("09:00");
	}

	@Test
	void absentTimesTakeTheDefaultsAndEmptyOnesDoNot() {
		// `$_POST['start_time'] ?? '08:00'` is a null coalesce, so it fires
		// only when the field is missing. An empty box stores '' and
		// non-strict MariaDB coerces it to midnight -- wrong, and what the
		// live system holds.
		post("/admin/shifts", this.cookie, page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Defaulted");
		Map<String, Object> defaulted = this.jdbc.queryForMap(
				"SELECT start_time, end_time FROM shifts WHERE name = 'Defaulted'");
		assertThat(defaulted.get("start_time").toString()).startsWith("08:00");
		assertThat(defaulted.get("end_time").toString()).startsWith("16:00");

		post("/admin/shifts", this.cookie, page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Emptied",
				"start_time", "", "end_time", "");
		assertThat(this.jdbc.queryForMap(
				"SELECT start_time FROM shifts WHERE name = 'Emptied'").get("start_time").toString())
				.as("empty is not absent")
				.startsWith("00:00");
	}

	@Test
	void anEmptyNameIsRefused() {
		assertThat(post("/admin/shifts", this.cookie,
				page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "   ",
				"start_time", "09:00").getHeaders().getLocation()).asString()
				.contains("action=add").contains("error=error_required");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM shifts", Integer.class)).isZero();
	}

	@Test
	void anAddWithNoCompanyIsRefused() {
		assertThat(post("/admin/shifts", this.cookie,
				page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", "0", "name", "Nowhere")
				.getHeaders().getLocation()).asString().contains("error=select_company_first");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM shifts", Integer.class)).isZero();
	}

	@Test
	void editingKeepsTheShiftInItsCompany() {
		long id = seedShift(this.companyA, "Original");
		post("/admin/shifts", this.cookie, page("/admin/shifts?action=edit&id=" + id, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyB), "name", "Renamed",
				"start_time", "10:00", "end_time", "18:00", "is_active", "1");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, name FROM shifts WHERE id = " + id);
		assertThat(row.get("name")).isEqualTo("Renamed");
		assertThat(row.get("company_id").toString())
				.as("company_id is not among the updated columns")
				.isEqualTo(String.valueOf(this.companyA));
	}

	@Test
	void deleteDeactivatesAndLeavesAssignmentsAlone() {
		long id = seedShift(this.companyA, "Closing");
		post("/admin/shifts", this.cookie, page("/admin/shifts", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM shifts WHERE id = " + id, Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM shifts WHERE id = " + id, Integer.class)).isEqualTo(1);
	}

	@Test
	void theListFiltersAndTheCompanyFilterOutlivesItsRequest() {
		seedShift(this.companyA, "Alpha Morning");
		seedShift(this.companyB, "Beta Morning");

		assertThat(body("/admin/shifts")).contains("Alpha Morning").contains("Beta Morning");
		assertThat(body("/admin/shifts?company_id=" + this.companyA))
				.contains("Alpha Morning").doesNotContain("Beta Morning");
		assertThat(body("/admin/shifts"))
				.as("the filter is session state")
				.contains("Alpha Morning").doesNotContain("Beta Morning");
		assertThat(body("/admin/shifts?search=Alpha")).contains("Alpha Morning");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/shifts", this.cookie, page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Audited");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'shift'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/shifts", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private long seedShift(long companyId, String name) {
		this.jdbc.update("INSERT INTO shifts (company_id, name, start_time, end_time, is_active,"
				+ " created_at) VALUES (?, ?, '08:00:00', '16:00:00', 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM shifts WHERE company_id = ? AND name = ?", Long.class, companyId, name);
	}

	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private long createCompany(String name) {
		String phone = "01" + System.nanoTime() % 1_000_000_000L;
		this.jdbc.update("INSERT INTO companies (company_name, phone, password_hash, status,"
				+ " otp_verified, profile_completed, created_at)"
				+ " VALUES (?, ?, ?, 'active', 1, 1, NOW())",
				name, phone, this.passwordEncoder.encode(PASSWORD));
		return this.jdbc.queryForObject("SELECT id FROM companies WHERE phone = ?", Long.class, phone);
	}

	private String body(String path) {
		return get(path, this.cookie).getBody();
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

	private static String code(String base32Seed) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(Instant.now()));
	}

	private static byte[] fromBase32(String seed) {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char character : seed.toCharArray()) {
			int value = alphabet.indexOf(character);
			if (value < 0) {
				continue;
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xFF);
				bits -= 8;
			}
		}
		return out.toByteArray();
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

	private static void applySchema(String resource) throws Exception {
		String sql = new String(AdminShiftsEndToEndTest.class.getClassLoader()
				.getResourceAsStream(resource).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute("SET SESSION sql_mode = ''");
			for (String piece : sql.split(";\\R")) {
				if (!piece.isBlank()) {
					statement.execute(piece);
				}
			}
		}
	}

}
