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
 * {@code /admin/job_titles} over real HTTP against a real MariaDB.
 *
 * <p>The list and filter machinery is proved on the branches page. This
 * concentrates on the two rules only this page has: a department that is
 * <b>optional</b> but must belong to the same company when given, and
 * {@code work_hours} that is required and must be strictly positive.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminJobTitlesEndToEndTest {

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

	private long departmentA;

	private long departmentB;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM job_titles");
		this.jdbc.update("DELETE FROM department_branches");
		this.jdbc.update("DELETE FROM departments");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2093" + System.nanoTime() % 100_000_000L;
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
		this.departmentA = seedDepartment(this.companyA, "Alpha Dept");
		this.departmentB = seedDepartment(this.companyB, "Beta Dept");
	}

	@Test
	void addingAJobTitleWithADepartmentWritesBoth() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Engineer",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, department_id, work_hours, is_active FROM job_titles"
						+ " WHERE name = 'Engineer'");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("department_id").toString()).isEqualTo(String.valueOf(this.departmentA));
		assertThat(((java.math.BigDecimal) row.get("work_hours")).compareTo(
				new java.math.BigDecimal("8"))).isZero();
		assertThat(row.get("is_active")).isEqualTo(Boolean.TRUE);
		assertThat(body("/admin/job_titles")).contains("Engineer").contains("Alpha Dept");
	}

	@Test
	void theDepartmentIsOptionalAndZeroMeansNone() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Floater",
				"department_id", "0", "work_hours", "6");

		assertThat(this.jdbc.queryForObject(
				"SELECT department_id FROM job_titles WHERE name = 'Floater'", Long.class))
				.as("`?: null` makes 0 mean none, and none is not checked for ownership")
				.isNull();
		// And it renders as an em dash rather than as a broken join.
		assertThat(body("/admin/job_titles")).contains("Floater");
	}

	@Test
	void aDepartmentFromAnotherCompanyIsRefusedForTheAdministratorToo() {
		// Same rule as the department page's branch check, and the same reason:
		// pointing at another company's row is a broken link, not cross-company
		// editing.
		assertThat(post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Crossed",
				"department_id", String.valueOf(this.departmentB), "work_hours", "8")
				.getHeaders().getLocation()).asString()
				.contains("error=select_company_first_department");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM job_titles", Integer.class)).isZero();
	}

	@Test
	void workHoursMustBePresentAndPositive() {
		for (String hours : List.of("", "0", "-3", "eight")) {
			assertThat(post("/admin/job_titles", this.cookie,
					page("/admin/job_titles?action=add", this.cookie).csrf(),
					"action", "add", "company_id", String.valueOf(this.companyA),
					"name", "Hours " + hours, "work_hours", hours)
					.getHeaders().getLocation()).asString()
					.as("work_hours '%s'", hours)
					.contains("action=add").contains("error=error_required");
		}
		// "eight" is worth naming: PHP's float cast yields 0.0 rather than
		// raising, so it fails the positivity test, not a parse.
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM job_titles", Integer.class)).isZero();
	}

	@Test
	void aFractionalWorkHoursValueIsKept() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Part Time",
				"work_hours", "7.5");

		assertThat(this.jdbc.queryForObject(
				"SELECT work_hours FROM job_titles WHERE name = 'Part Time'", java.math.BigDecimal.class)
				.compareTo(new java.math.BigDecimal("7.5"))).isZero();
	}

	@Test
	void anEmptyNameIsRefused() {
		assertThat(post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "  ",
				"work_hours", "8").getHeaders().getLocation()).asString()
				.contains("error=error_required");
	}

	@Test
	void editingCanClearTheDepartment() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Movable",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");
		long id = this.jdbc.queryForObject(
				"SELECT id FROM job_titles WHERE name = 'Movable'", Long.class);

		post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=edit&id=" + id, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA), "name", "Movable",
				"department_id", "0", "work_hours", "8", "is_active", "1");

		assertThat(this.jdbc.queryForObject(
				"SELECT department_id FROM job_titles WHERE id = " + id, Long.class)).isNull();
	}

	@Test
	void theDepartmentFilterNarrows() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "In Dept",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "No Dept",
				"work_hours", "8");

		assertThat(body("/admin/job_titles?filter_department=" + this.departmentA))
				.contains("In Dept").doesNotContain("No Dept");
	}

	@Test
	void deleteDeactivatesRatherThanRemoving() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Closing",
				"work_hours", "8");
		long id = this.jdbc.queryForObject(
				"SELECT id FROM job_titles WHERE name = 'Closing'", Long.class);

		post("/admin/job_titles", this.cookie, page("/admin/job_titles", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM job_titles WHERE id = " + id, Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM job_titles WHERE id = " + id, Integer.class)).isEqualTo(1);
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Audited",
				"work_hours", "8");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'job_title'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/job_titles", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
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

	private long seedDepartment(long companyId, String name) {
		this.jdbc.update("INSERT INTO departments (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE company_id = ? AND name = ?", Long.class,
				companyId, name);
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
		String sql = new String(AdminJobTitlesEndToEndTest.class.getClassLoader()
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
