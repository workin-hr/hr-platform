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
		// The current session is the row carrying the badge. Asserted as markup
		// rather than as its label: this surface renders in Arabic by default
		// and the wording is a translation now (D-208), which is not what this
		// journey is about -- AdminTemplateMessageKeyTest owns the key.
		assertThat(sessions.response().getBody()).contains("<span class=\"badge\">");

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
	void eachCompanyRowOffersEditRightAfterDetails() {
		// company_helper.php:190-197: Details, then Edit. The controller already
		// opens the prefilled form at ?edit=<id>; only the way in was missing.
		String cookie = signIn();
		long companyId = createCompany();

		Page companies = get("/admin/companies", cookie);
		String html = companies.response().getBody();
		int start = html.indexOf("id=\"row-actions-menu-" + companyId + "\"");
		assertThat(start).as("the row menu for company %s", companyId).isPositive();
		String menu = html.substring(start, html.indexOf("</div>", start));

		int details = menu.indexOf("href=\"/admin/companies/" + companyId + "\"");
		int edit = menu.indexOf("href=\"/admin/companies?edit=" + companyId + "\"");
		assertThat(details).as("the menu still links to the detail page").isPositive();
		assertThat(edit).as("the menu links to this company's edit form").isPositive();
		assertThat(edit).as("Edit comes right after Details, as in legacy").isGreaterThan(details);

		String form = get("/admin/companies?edit=" + companyId, cookie).response().getBody();
		assertThat(form).as("the link opens the company form").contains("class=\"modal-bg open\" id=\"companyModal\"");
		// ?action=add opens the same modal, so the markup must also be this company's edit form.
		String name = new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT company_name FROM companies WHERE id = ?", String.class, companyId);
		assertThat(form).as("the form saves an edit, not an add")
				.containsPattern("name=\"action\"\\s+value=\"save_edit\"");
		assertThat(form).as("for this company").contains("name=\"id\" value=\"" + companyId + "\"");
		assertThat(form).as("prefilled with its name")
				.containsPattern("id=\"co_name\"[^>]*value=\"" + Pattern.quote(name) + "\"");
	}

	@Test
	void aCompanyWithoutLookupsOpensItsFormWithNothingChosen() {
		// _company_form.php:74-93 starts each required lookup select with an empty
		// "choose" option. Without it, a company whose lookups are NULL (a
		// registration stopped after step one) shows, and would save, the first
		// activity, title and size as if they were stored.
		String cookie = signIn();
		long companyId = createCompany();
		// Real options, so a select without its empty choice would show the first of them.
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		jdbc.update("INSERT INTO company_activities (id, name) VALUES (24301, 'Flow activity')");
		jdbc.update("INSERT INTO company_titles (id, name) VALUES (24311, 'Flow title')");
		jdbc.update("INSERT INTO company_sizes (id, name, min_employees, max_employees) VALUES (24321, 'Flow size', 1, 10)");

		String form = get("/admin/companies?edit=" + companyId, cookie).response().getBody();
		java.util.Map<String, Long> seeded = java.util.Map.of("co_act", 24301L, "co_title", 24311L, "co_size", 24321L);
		for (String select : List.of("co_act", "co_title", "co_size")) {
			int start = form.indexOf("<select id=\"" + select + "\"");
			assertThat(start).as("the %s select renders", select).isPositive();
			String markup = form.substring(start, form.indexOf("</select>", start));
			assertThat(markup).as("%s lists the stored options", select)
					.contains("value=\"" + seeded.get(select) + "\"");
			assertThat(markup).as("%s starts with an empty choice", select)
					.containsPattern("^<select[^>]*>\\s*<option value=\"\">");
			assertThat(markup).as("%s preselects nothing for a company without one", select)
					.doesNotContain("selected");
		}
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
