package com.workin.backend.platformadmin.web;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import java.net.http.HttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0015 prerequisites 4 (idle/absolute bounds and session-id rotation),
 * 6 (cookie flags), 9 (per-request active-admin revalidation) and the CSRF half
 * of 5, exercised end to end over real HTTP against a real Postgres session
 * store.
 *
 * <p>Cookies are carried by hand rather than by a cookie-managing client: the
 * session cookie is {@code Secure}, and a client that honoured that would refuse
 * to send it back over the test's plain HTTP. Handling the header directly
 * tests the server's behaviour without weakening the flag to make the test
 * convenient.
 */
class PlatformAdminWebSessionTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF_INPUT = Pattern.compile(
			"name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	@Autowired
	private TestRestTemplate restTemplate;

	/**
	 * Redirects are the assertion here -- "unauthenticated goes to the login
	 * page", "logout goes to the login page" -- so the client must hand them
	 * back rather than quietly following them and returning the destination's
	 * 200. Following them made every one of these tests pass or fail for the
	 * wrong reason.
	 */
	@BeforeEach
	void doNotFollowRedirects() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
	}

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	// --- the surface's basic guarantees -------------------------------------

	@Test
	void anUnauthenticatedPageRedirectsToLogin() {
		ResponseEntity<String> response = get("/admin", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().endsWith("/admin/login");
	}

	@Test
	void theSessionCookieCarriesTheFlagsThatWerePinned() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, true);

		Session session = logIn(phone, PASSWORD);

		assertThat(session.setCookieHeader())
			.contains("WORKIN_ADMIN_SESSION=")
			.contains("HttpOnly")
			.contains("Secure")
			.contains("SameSite=Lax")
			.contains("Path=/");
	}

	@Test
	void theSessionIdRotatesOnLogin() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, true);

		// The GET establishes a pre-authentication session (CSRF needs one).
		LoginForm form = fetchLoginForm();
		Session session = submitLogin(form, phone, PASSWORD);

		assertThat(session.cookieValue())
			.as("a session id that survives authentication is a session-fixation foothold")
			.isNotEqualTo(form.cookieValue());
	}

	@Test
	void anAuthenticatedAdministratorSeesTheirOwnPage() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, true);

		Session session = logIn(phone, PASSWORD);
		ResponseEntity<String> page = get("/admin", session.cookieValue());

		assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(page.getBody()).contains(phone);
	}

	@Test
	void wrongCredentialsRenderTheFormAgainWithoutASession() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, true);

		LoginForm form = fetchLoginForm();
		ResponseEntity<String> response = postLogin(form, phone, "not the password");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("were not accepted");
	}

	// --- prerequisite 9 -----------------------------------------------------

	@Test
	void deactivatingAnAdministratorRefusesTheirNextPageRequest() {
		String phone = uniquePhone();
		long id = createPlatformAdmin(phone, true);
		Session session = logIn(phone, PASSWORD);

		assertThat(get("/admin", session.cookieValue()).getStatusCode()).isEqualTo(HttpStatus.OK);

		new JdbcTemplate(this.flywayDataSource)
			.update("UPDATE platform_admins SET active = false WHERE id = ?", id);

		ResponseEntity<String> afterDeactivation = get("/admin", session.cookieValue());

		assertThat(afterDeactivation.getStatusCode())
			.as("a deactivated administrator must lose the session immediately, "
					+ "not when it happens to expire (D-145)")
			.isEqualTo(HttpStatus.FOUND);
		assertThat(afterDeactivation.getHeaders().getLocation()).asString().endsWith("/admin/login");
	}

	// --- logout -------------------------------------------------------------

	@Test
	void logoutInvalidatesTheSessionEverywhereNotJustLocally() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, true);
		Session session = logIn(phone, PASSWORD);

		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);
		String sessionId = sessionIdOf(session);
		assertThat(storedSessions(jdbc, sessionId))
			.as("the session must live in shared storage, not in one worker's heap")
			.isOne();

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + session.cookieValue());
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add(session.csrfParameterName(), session.csrfToken());
		ResponseEntity<String> logout = this.restTemplate.exchange(
				"/admin/logout", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

		assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(get("/admin", session.cookieValue()).getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(storedSessions(jdbc, sessionId))
			.as("the shared session row must be deleted, or another worker still honours the cookie")
			.isZero();
	}

	// --- prerequisite 5, CSRF half ------------------------------------------

	@Test
	void aStateChangingPostWithoutTheCsrfTokenIsRejected() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, true);
		Session session = logIn(phone, PASSWORD);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + session.cookieValue());
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/logout", HttpMethod.POST,
				new HttpEntity<>(new LinkedMultiValueMap<String, String>(), headers), String.class);

		assertThat(response.getStatusCode())
			.as("a cookie-authenticated state-changing route without CSRF protection "
					+ "is exactly what this chain exists to prevent")
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(get("/admin", session.cookieValue()).getStatusCode())
			.as("the rejected request must not have logged the session out either")
			.isEqualTo(HttpStatus.OK);
	}

	@Test
	void loginItselfRequiresTheCsrfToken() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, true);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("phone", phone);
		body.add("password", PASSWORD);

		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/login", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// --- helpers ------------------------------------------------------------

	private record LoginForm(String cookieValue, String csrfParameterName, String csrfToken) {
	}

	private record Session(String cookieValue, String setCookieHeader,
			String csrfParameterName, String csrfToken) {
	}

	private int storedSessions(JdbcTemplate jdbc, String sessionId) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM spring_session WHERE session_id = ?", Integer.class, sessionId);
		return count == null ? 0 : count;
	}

	/**
	 * The cookie carries the session id base64-encoded (Spring Session's
	 * DefaultCookieSerializer default), while the table stores it raw.
	 */
	private static String sessionIdOf(Session session) {
		return new String(java.util.Base64.getDecoder().decode(session.cookieValue()),
				java.nio.charset.StandardCharsets.UTF_8);
	}

	private ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private LoginForm fetchLoginForm() {
		ResponseEntity<String> response = get("/admin/login", null);
		Matcher matcher = CSRF_INPUT.matcher(response.getBody());
		assertThat(matcher.find()).as("the login form must render a CSRF token").isTrue();
		return new LoginForm(cookieValueOf(response), matcher.group(1), matcher.group(2));
	}

	private ResponseEntity<String> postLogin(LoginForm form, String phone, String password) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + form.cookieValue());
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("phone", phone);
		body.add("password", password);
		body.add(form.csrfParameterName(), form.csrfToken());
		return this.restTemplate.exchange(
				"/admin/login", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
	}

	private Session submitLogin(LoginForm form, String phone, String password) {
		ResponseEntity<String> response = postLogin(form, phone, password);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);

		String cookie = cookieValueOf(response);
		String setCookie = setCookieHeaderOf(response);
		// The post-login CSRF token belongs to the new session, so it is read
		// back from an authenticated page rather than carried over.
		ResponseEntity<String> page = get("/admin", cookie);
		Matcher matcher = CSRF_INPUT.matcher(page.getBody());
		assertThat(matcher.find()).as("the signed-in page must render a CSRF token").isTrue();
		return new Session(cookie, setCookie, matcher.group(1), matcher.group(2));
	}

	private Session logIn(String phone, String password) {
		return submitLogin(fetchLoginForm(), phone, password);
	}

	private static String setCookieHeaderOf(ResponseEntity<String> response) {
		List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		assertThat(cookies).as("expected a session cookie").isNotNull().isNotEmpty();
		return cookies.stream()
			.filter(value -> value.startsWith("WORKIN_ADMIN_SESSION="))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no WORKIN_ADMIN_SESSION cookie in " + cookies));
	}

	private static String cookieValueOf(ResponseEntity<String> response) {
		String header = setCookieHeaderOf(response);
		int start = header.indexOf('=') + 1;
		int end = header.indexOf(';', start);
		return end < 0 ? header.substring(start) : header.substring(start, end);
	}

	private long createPlatformAdmin(String phone, boolean active) {
		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);
		return jdbc.queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, ?) RETURNING id",
				Long.class, phone, this.passwordEncoder.encode(PASSWORD), active);
	}

	private static String uniquePhone() {
		return "+99" + System.nanoTime();
	}

}
