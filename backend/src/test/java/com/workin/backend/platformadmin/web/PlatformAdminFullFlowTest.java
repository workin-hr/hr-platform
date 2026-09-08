package com.workin.backend.platformadmin.web;

import java.net.http.HttpClient;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole administrative journey over real HTTP: the password, the pages,
 * one company action with its audit row, the logout that ends the shared
 * session, and a deactivation that ends a live one.
 *
 * <p>End to end because the pieces have been green in isolation while the
 * journey was broken between them -- a route omitted from the public list
 * lands on the entry point and redirects to the login page, which is also
 * where success goes, so a test that trusts the destination passes either way.
 */
@TestPropertySource(properties = "app.platform-admin.actions.enabled=true")
class PlatformAdminFullFlowTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");



	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;


	@BeforeEach
	void doNotFollowRedirects() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
	}

	@Test
	void theCompleteAdministrativeJourney() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long adminId = adminId();
		long companyId = createCompany();

		String cookie = signIn();
		Page home = get("/admin", cookie);
		assertThat(home.response().getStatusCode()).isEqualTo(HttpStatus.OK);
		Page sessions = get("/admin/sessions", cookie);
		assertThat(sessions.response().getBody()).contains("this one");

		// One POST, like every other page's actions (ADR-0018).
		Page companies = get("/admin/companies", cookie);
		ResponseEntity<String> applied = post("/admin/companies/action", cookie, companies.csrf(),
				"action", "COMPANY_SUSPEND", "companyId", String.valueOf(companyId),
				"reason", "non-payment");
		assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(statusOf(companyId)).isEqualTo("suspended");
		assertThat(jdbc.queryForList(
				"SELECT event_type, target_type, target_id "
						+ "FROM platform_admin_audit_events WHERE platform_admin_id = ? "
						+ "AND event_type = 'COMPANY_SUSPENDED'", adminId))
			.as("a committed change cannot exist without its audit row")
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("target_type")).isEqualTo("COMPANY");
				assertThat(row.get("target_id")).isEqualTo(String.valueOf(companyId));
			});

		ResponseEntity<String> loggedOut = post("/admin/logout", cookie, get("/admin", cookie).csrf());
		assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(get("/admin", cookie).response().getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?",
				Integer.class, sessionIdOf(cookie)))
			.as("the shared row must be gone, or another worker still honours the cookie")
			.isZero();
	}

	@Test
	void aWrongPasswordIsRefusedAndOpensNothing() {
		Page loginForm = get("/admin/login", null);
		ResponseEntity<String> refused = post("/admin/login", loginForm.cookie(), loginForm.csrf(),
				"password", "not the password");

		// PHP re-renders the form with error_auth; so does this, on the same URL.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(refused.getBody()).contains("login-alert--error");
		assertThat(get("/admin", loginForm.cookie()).response().getStatusCode())
			.as("the pre-login session opens no page")
			.isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void deactivationEndsALiveSessionOnTheNextRequest() {
		String cookie = signIn();
		assertThat(get("/admin", cookie).response().getStatusCode()).isEqualTo(HttpStatus.OK);

		new JdbcTemplate(this.legacyDataSource)
			.update("UPDATE platform_admins SET active = false WHERE phone = 'admin'");

		assertThat(get("/admin", cookie).response().getStatusCode())
			.as("D-145: revocation must take effect on the next request, not at expiry")
			.isEqualTo(HttpStatus.FOUND);
		new JdbcTemplate(this.legacyDataSource)
			.update("UPDATE platform_admins SET active = true WHERE phone = 'admin'");
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private record Csrf(String name, String value) {
	}

	private String signIn() {
		Page loginForm = get("/admin/login", null);
		ResponseEntity<String> signedIn = post("/admin/login", loginForm.cookie(), loginForm.csrf(),
				"password", PASSWORD);
		assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		String cookie = cookieOf(signedIn);
		assertThat(cookie).as("the session id rotates on login").isNotEqualTo(loginForm.cookie());
		return cookie;
	}

	private Page get(String path, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		if (cookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + cookie);
		}
		ResponseEntity<String> response = this.restTemplate.exchange(
				path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
		String resolved = cookie != null ? cookie : tryCookieOf(response);
		return new Page(response, resolved, response.getBody() == null ? null : csrfOf(response));
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
		assertThat(matcher.find()).as("expected a CSRF token in the response").isTrue();
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

	private static String sessionIdOf(String cookie) {
		return new String(java.util.Base64.getDecoder().decode(cookie),
				java.nio.charset.StandardCharsets.UTF_8);
	}

	private String statusOf(long companyId) {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT status FROM companies WHERE id = ?", String.class, companyId);
	}

	private long adminId() {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
	}

	private long createCompany() {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status) VALUES (?, ?, 'unused-hash', 'active') RETURNING id",
				Long.class, "Flow " + System.nanoTime(), "+90" + System.nanoTime());
	}




}
