package com.workin.legacy;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform-admin surface running on **MySQL**, under the same profile that
 * serves the Flutter clients.
 *
 * <p>Legacy has a platform admin web of its own -- `dashboard/pages/companies/`
 * -- so a deployment that stays on MySQL needs one too. What is deliberately
 * not carried over is how legacy authenticates it: `doAdminLogin()` verifies a
 * single shared password held in a config constant (`hr-legacy#11`), and there
 * is no admin table in the legacy schema at all. The identity model here is the
 * one F-26 requires, over MariaDB instead of PostgreSQL.
 *
 * <p>The point of this test is that the *same code* works on the other
 * database. The PostgreSQL suite proves the behaviour; this proves the
 * portability, which is a different claim and the one that breaks on a dialect
 * difference.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class LegacyPlatformAdminOnMySqlTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");



	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.password", () -> PASSWORD);
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;


	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	@BeforeEach
	void doNotFollowRedirects() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
	}

	@Test
	void theLegacyApiAndTheAdminSurfaceAreServedByTheSameApplication() {
		// The clients' surface is still there...
		assertThat(get("/apis/api/phone_countries/list", null).getStatusCode())
			.isNotEqualTo(HttpStatus.NOT_FOUND);
		// ...and so is the admin one.
		assertThat(get("/admin", null).getStatusCode()).isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void theWholeAdminJourneyWorksOnMySql() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long adminId = adminId(jdbc);
		long company = createCompany(jdbc);

		String cookie = signIn();
		assertThat(get("/admin", cookie).getStatusCode()).isEqualTo(HttpStatus.OK);

		Page companies = get2("/admin/companies", cookie);
		assertThat(post("/admin/companies/action", cookie, companies.csrf(),
				"action", "COMPANY_SUSPEND", "companyId", String.valueOf(company),
				"reason", "non-payment").getStatusCode()).isEqualTo(HttpStatus.FOUND);

		assertThat(jdbc.queryForObject("SELECT status FROM companies WHERE id = ?", String.class, company))
			.isEqualTo("suspended");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events WHERE platform_admin_id = ? "
						+ "AND event_type = 'COMPANY_SUSPENDED' AND target_id = ?",
				Integer.class, adminId, String.valueOf(company)))
			.as("the audit row is in the legacy database, in the same transaction")
			.isEqualTo(1);
	}

	@Test
	void approveAndRejectWorkOnMySqlAndRejectRecordsWhy() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long approved = createCompany(jdbc);
		long rejected = createCompany(jdbc);
		jdbc.update("UPDATE companies SET status = 'pending' WHERE id IN (?, ?)", approved, rejected);
		String cookie = signIn();

		Page companies = get2("/admin/companies", cookie);
		assertThat(post("/admin/companies/action", cookie, companies.csrf(),
				"action", "COMPANY_APPROVE", "companyId", String.valueOf(approved))
			.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(post("/admin/companies/action", cookie, get2("/admin/companies", cookie).csrf(),
				"action", "COMPANY_REJECT", "companyId", String.valueOf(rejected),
				"reason", "no commercial registration")
			.getStatusCode()).isEqualTo(HttpStatus.FOUND);

		assertThat(jdbc.queryForObject("SELECT status FROM companies WHERE id = ?", String.class, approved))
			.isEqualTo("active");
		assertThat(jdbc.queryForObject("SELECT status FROM companies WHERE id = ?", String.class, rejected))
			.isEqualTo("rejected");
		assertThat(jdbc.queryForObject("SELECT rejection_reason FROM companies WHERE id = ?", String.class, rejected))
			.as("rejecting records why, in the column PHP writes")
			.isEqualTo("no commercial registration");
	}

	@Test
	void theCompanyDetailPageCountsOutstandingWork() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long company = createCompany(jdbc);
		String cookie = signIn();
		// ?lang=en because the page is localised now (D-209): it used to carry
		// these two labels as hardcoded English, which is the only reason
		// asserting on them worked without asking for a language. The wording
		// is the catalogue's, not this test's.
		ResponseEntity<String> detail = get("/admin/companies/" + company + "?lang=en", cookie);
		assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(detail.getBody())
			.as("the counts legacy's detail.php shows, over the same join through employees")
			.contains("Pending Requests", "Pending Advances");
	}

	private String signIn() {
		Page login = get2("/admin/login", null);
		return cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private record Csrf(String name, String value) {
	}


	private ResponseEntity<String> get(String path, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		if (cookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + cookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private Page get2(String path, String cookie) {
		ResponseEntity<String> response = get(path, cookie);
		String resolved = cookie != null ? cookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	private ResponseEntity<String> post(String path, String cookie, Csrf csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + cookie);
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		for (int i = 0; i < fields.length; i += 2) {
			body.add(fields[i], fields[i + 1]);
		}
		body.add(csrf.name(), csrf.value());
		return this.restTemplate.exchange(path, HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
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


	private long adminId(JdbcTemplate jdbc) {
		return jdbc.queryForObject("SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
	}

	private long createCompany(JdbcTemplate jdbc) {
		String companyPhone = "01" + System.nanoTime() % 1_000_000_000L;
		// company_name, not name -- the legacy column. password_hash is NOT NULL
		// with no default, so a fixture has to supply it.
		jdbc.update("INSERT INTO companies (company_name, phone, password_hash, status) "
				+ "VALUES (?, ?, ?, 'active')",
				"MySQL Admin Fixture", companyPhone, "unused-hash");
		return jdbc.queryForObject("SELECT id FROM companies WHERE phone = ?", Long.class, companyPhone);
	}


}
