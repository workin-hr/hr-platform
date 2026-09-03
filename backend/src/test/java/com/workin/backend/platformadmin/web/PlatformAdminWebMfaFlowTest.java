package com.workin.backend.platformadmin.web;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.platformadmin.mfa.Totp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second factor as the login flow actually presents it (ADR-0015
 * prerequisite 1's UI half).
 *
 * <p>The claim being tested is narrow and important: with a factor bound, a
 * correct password on its own reaches nothing. Everything else here exists to
 * show that the half-authenticated state is not quietly a whole one.
 */
class PlatformAdminWebMfaFlowTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF_INPUT = Pattern.compile(
			"name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");


	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PlatformAdminMfaService mfaService;

	@BeforeEach
	void doNotFollowRedirects() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
	}

	// --- the claim ----------------------------------------------------------

	@Test
	void withAFactorBoundThePasswordAloneReachesNothing() {
		Admin admin = createEnrolledAdmin();

		Form login = form("/admin/login");
		ResponseEntity<String> afterPassword = submit("/admin/login", login,
				"phone", admin.phone(), "password", PASSWORD);

		assertThat(afterPassword.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(afterPassword.getHeaders().getLocation()).asString().endsWith("/admin/mfa");

		String cookie = cookieValueOf(afterPassword);
		ResponseEntity<String> home = get("/admin", cookie);

		assertThat(home.getStatusCode())
			.as("a password-only session must not reach a protected page")
			.isEqualTo(HttpStatus.FOUND);
		assertThat(home.getHeaders().getLocation()).asString().endsWith("/admin/login");
	}

	@Test
	void theCorrectCodeCompletesTheSignIn() {
		Admin admin = createEnrolledAdmin();
		String cookie = passwordStep(admin);

		Form challenge = formWithCookie("/admin/mfa", cookie);
		ResponseEntity<String> verified = submitWithCookie("/admin/mfa", challenge, cookie,
				"code", nextCodeFor(admin.seed()));

		assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(verified.getHeaders().getLocation()).asString().endsWith("/admin");

		ResponseEntity<String> home = get("/admin", cookieValueOf(verified));
		assertThat(home.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(home.getBody()).contains("bound");
	}

	@Test
	void theSessionIdRotatesAgainAfterTheSecondFactor() {
		Admin admin = createEnrolledAdmin();
		String pendingCookie = passwordStep(admin);

		Form challenge = formWithCookie("/admin/mfa", pendingCookie);
		ResponseEntity<String> verified = submitWithCookie("/admin/mfa", challenge, pendingCookie,
				"code", nextCodeFor(admin.seed()));

		assertThat(cookieValueOf(verified))
			.as("the id that carried the half-authenticated state must not carry the authenticated one")
			.isNotEqualTo(pendingCookie);
	}

	@Test
	void aWrongCodeLeavesTheSessionUnauthenticated() {
		Admin admin = createEnrolledAdmin();
		String cookie = passwordStep(admin);

		Form challenge = formWithCookie("/admin/mfa", cookie);
		ResponseEntity<String> rejected = submitWithCookie("/admin/mfa", challenge, cookie,
				"code", "000000");

		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(rejected.getBody()).contains("was not accepted");
		assertThat(get("/admin", cookie).getStatusCode()).isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void theChallengePageIsUnreachableWithoutHavingPassedThePasswordStep() {
		ResponseEntity<String> direct = get("/admin/mfa", null);

		assertThat(direct.getStatusCode())
			.as("the challenge is permitAll on the chain, so the pending marker is the only gate")
			.isEqualTo(HttpStatus.FOUND);
		assertThat(direct.getHeaders().getLocation()).asString().endsWith("/admin/login");
	}

	@Test
	void anAdministratorWithNoBoundFactorSignsInButIsToldToEnrol() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);

		Form login = form("/admin/login");
		ResponseEntity<String> afterPassword = submit("/admin/login", login,
				"phone", phone, "password", PASSWORD);

		assertThat(afterPassword.getHeaders().getLocation()).asString().endsWith("/admin");

		ResponseEntity<String> home = get("/admin", cookieValueOf(afterPassword));
		assertThat(home.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(home.getBody())
			.as("D-152: existing rows migrate unbound and must be able to reach enrolment")
			.contains("Set up two-factor authentication");
	}

	// --- D-152's ceremony through the UI ------------------------------------

	@Test
	void enrolmentThroughTheUiNeedsThePasswordAndTheBootstrapToken() {
		String phone = uniquePhone();
		long id = createPlatformAdmin(phone);
		String token = this.mfaService.issueBootstrapToken(id, id);

		Form enrol = form("/admin/enrol");

		assertThat(submit("/admin/enrol", enrol, "phone", phone,
				"password", PASSWORD, "bootstrapToken", "not-the-token").getBody())
			.as("a password alone must not begin enrolment")
			.contains("were not accepted");

		assertThat(submit("/admin/enrol", form("/admin/enrol"), "phone", phone,
				"password", "wrong", "bootstrapToken", token).getBody())
			.as("a stolen bootstrap token alone must not begin enrolment either")
			.contains("were not accepted");
	}

	@Test
	void aFullEnrolmentThenSignInWorksEndToEnd() {
		String phone = uniquePhone();
		long id = createPlatformAdmin(phone);
		String token = this.mfaService.issueBootstrapToken(id, id);

		Form enrol = form("/admin/enrol");
		ResponseEntity<String> shown = submit("/admin/enrol", enrol,
				"phone", phone, "password", PASSWORD, "bootstrapToken", token);

		assertThat(shown.getStatusCode()).isEqualTo(HttpStatus.OK);
		Matcher seedMatch = Pattern.compile("<code>([A-Z2-7]+)</code>").matcher(shown.getBody());
		assertThat(seedMatch.find()).as("the seed must be displayed once").isTrue();
		String seed = seedMatch.group(1);

		// The same session throughout: the begin step deliberately does not
		// rotate, because it grants no privilege to fixate.
		String cookie = enrol.cookieValue();
		Form confirm = formWithCookie2(shown, cookie);
		ResponseEntity<String> bound = submitWithCookie("/admin/enrol/confirm", confirm, cookie,
				"code", codeFor(seed));

		assertThat(bound.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(bound.getHeaders().getLocation()).asString().endsWith("/admin/login");
		// The binding, not the destination. An unpermitted route also redirects
		// here, so the redirect alone cannot tell success from a chain gap.
		assertThat(this.mfaService.isBound(id))
			.as("the factor must actually be bound, not merely redirected as if it were")
			.isTrue();

		// And the factor now actually gates the sign-in.
		Form login = form("/admin/login");
		ResponseEntity<String> afterPassword = submit("/admin/login", login,
				"phone", phone, "password", PASSWORD);
		assertThat(afterPassword.getHeaders().getLocation()).asString().endsWith("/admin/mfa");
	}

	// --- helpers ------------------------------------------------------------

	/** Reads the CSRF token out of a page already fetched, without re-fetching. */
	private Form formWithCookie2(ResponseEntity<String> page, String cookie) {
		Matcher matcher = CSRF_INPUT.matcher(page.getBody());
		assertThat(matcher.find()).isTrue();
		return new Form(cookie, matcher.group(1), matcher.group(2));
	}


	private record Admin(String phone, String seed) {
	}

	private record Form(String cookieValue, String csrfName, String csrfToken) {
	}

	/** Creates an administrator and completes D-152's ceremony for them. */
	private Admin createEnrolledAdmin() {
		String phone = uniquePhone();
		long id = createPlatformAdmin(phone);
		String token = this.mfaService.issueBootstrapToken(id, id);
		String seed = this.mfaService.beginEnrolment(id, token).orElseThrow();
		assertThat(this.mfaService.confirmEnrolment(id, codeFor(seed))).isTrue();
		return new Admin(phone, seed);
	}

	/** Signs in with the password only, returning the pending session cookie. */
	private String passwordStep(Admin admin) {
		Form login = form("/admin/login");
		ResponseEntity<String> response = submit("/admin/login", login,
				"phone", admin.phone(), "password", PASSWORD);
		assertThat(response.getHeaders().getLocation()).asString().endsWith("/admin/mfa");
		return cookieValueOf(response);
	}

	private static String codeFor(String base32Seed) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(Instant.now()));
	}

	/**
	 * The code for the next time step.
	 *
	 * <p>Needed because the enrolment ceremony has already spent the current
	 * one, and prerequisite 12 refuses a code at or below the last accepted
	 * step -- correctly. Waiting thirty seconds would prove the same thing more
	 * slowly; the next step is inside the accepted skew, so this is what the
	 * authenticator shows a moment later.
	 */
	private static String nextCodeFor(String base32Seed) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(Instant.now()) + 1);
	}

	private static byte[] fromBase32(String encoded) {
		final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bitsLeft = 0;
		for (char c : encoded.toCharArray()) {
			buffer = (buffer << 5) | alphabet.indexOf(c);
			bitsLeft += 5;
			if (bitsLeft >= 8) {
				out.write((buffer >> (bitsLeft - 8)) & 0xFF);
				bitsLeft -= 8;
			}
		}
		return out.toByteArray();
	}

	private ResponseEntity<String> get(String path, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		if (cookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + cookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private Form form(String path) {
		return formWithCookie(path, null);
	}

	private Form formWithCookie(String path, String cookie) {
		ResponseEntity<String> response = get(path, cookie);
		Matcher matcher = CSRF_INPUT.matcher(response.getBody());
		assertThat(matcher.find()).as("%s must render a CSRF token", path).isTrue();
		return new Form(cookie != null ? cookie : cookieValueOf(response),
				matcher.group(1), matcher.group(2));
	}

	private ResponseEntity<String> submit(String path, Form form, String... fields) {
		return submitWithCookie(path, form, form.cookieValue(), fields);
	}

	private ResponseEntity<String> submitWithCookie(String path, Form form, String cookie, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + cookie);
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		for (int i = 0; i < fields.length; i += 2) {
			body.add(fields[i], fields[i + 1]);
		}
		body.add(form.csrfName(), form.csrfToken());
		return this.restTemplate.exchange(path, HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
	}

	private static String cookieValueOf(ResponseEntity<String> response) {
		List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		assertThat(cookies).as("expected a session cookie").isNotNull().isNotEmpty();
		String header = cookies.stream()
			.filter(value -> value.startsWith("WORKIN_ADMIN_SESSION="))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no session cookie in " + cookies));
		int start = header.indexOf('=') + 1;
		int end = header.indexOf(';', start);
		return end < 0 ? header.substring(start) : header.substring(start, end);
	}

	private long createPlatformAdmin(String phone) {
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, true) RETURNING id",
				Long.class, phone, this.passwordEncoder.encode(PASSWORD));
	}

	private static String uniquePhone() {
		return "+94" + System.nanoTime();
	}

}
