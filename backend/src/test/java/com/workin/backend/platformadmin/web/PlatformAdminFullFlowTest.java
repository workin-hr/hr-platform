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
import com.workin.backend.platformadmin.PlatformAdminMfaTestSupport;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.platformadmin.mfa.Totp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole surface, end to end, over real HTTP against a real database:
 * <b>enrol -> login -> MFA -> session -> step-up -> admin action -> logout</b>.
 *
 * <p>Every other test here proves one control in isolation. This one exists
 * because the controls have to hold <em>in sequence</em> -- the failure this
 * catches is the one where each piece works and the composition does not, which
 * is how the {@code /admin/enrol/confirm} gap survived a green suite.
 *
 * <p>Administrative actions are switched on for this class only. They ship off
 * (ADR-0015 prerequisite 7 is a deployment condition about the legacy PHP
 * surface), and {@code PlatformAdminCompanyActionDisabledTest} pins that
 * default.
 */
@TestPropertySource(properties = "app.platform-admin.actions.enabled=true")
class PlatformAdminFullFlowTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	private static final Pattern SEED = Pattern.compile("<code>([A-Z2-7]+)</code>");

	private static final Pattern APPROVAL = Pattern.compile("name=\"approvalId\" value=\"([0-9a-f]+)\"");

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

	@Test
	void theCompleteAdministrativeJourney() {
		String phone = uniquePhone();
		long adminId = createPlatformAdmin(phone);
		long companyId = createCompany();
		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);

		// 1. Enrol. Needs the password AND an operator-issued bootstrap token.
		String bootstrapToken = this.mfaService.issueBootstrapToken(adminId, adminId);
		Page enrolForm = get("/admin/enrol", null);
		ResponseEntity<String> seedPage = post("/admin/enrol", enrolForm.cookie(), enrolForm.csrf(),
				"phone", phone, "password", PASSWORD, "bootstrapToken", bootstrapToken);
		Matcher seedMatch = SEED.matcher(seedPage.getBody());
		assertThat(seedMatch.find()).as("the seed is shown exactly once").isTrue();
		String seed = seedMatch.group(1);

		ResponseEntity<String> bound = post("/admin/enrol/confirm", enrolForm.cookie(),
				csrfOf(seedPage), "code", code(seed, 0));
		assertThat(bound.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(this.mfaService.isBound(adminId))
			.as("the factor binds only when a code verifies")
			.isTrue();

		// 2. Password alone reaches only the challenge.
		Page loginForm = get("/admin/login", null);
		ResponseEntity<String> afterPassword = post("/admin/login", loginForm.cookie(), loginForm.csrf(),
				"phone", phone, "password", PASSWORD);
		assertThat(afterPassword.getHeaders().getLocation()).asString().endsWith("/admin/mfa");
		String pendingCookie = cookieOf(afterPassword);
		assertThat(get("/admin", pendingCookie).response().getStatusCode())
			.as("a password-only session must reach no protected page")
			.isEqualTo(HttpStatus.FOUND);

		// 3. Second factor completes the session.
		PlatformAdminMfaTestSupport.allowAnotherCode(jdbc, adminId);
		Page challenge = get("/admin/mfa", pendingCookie);
		ResponseEntity<String> signedIn = post("/admin/mfa", pendingCookie, challenge.csrf(),
				"code", code(seed, 0));
		assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		String cookie = cookieOf(signedIn);
		assertThat(cookie).as("the session id rotates after the second factor").isNotEqualTo(pendingCookie);

		// 4. The session works, and is listed as the administrator's own.
		Page home = get("/admin", cookie);
		assertThat(home.response().getStatusCode()).isEqualTo(HttpStatus.OK);
		Page sessions = get("/admin/sessions", cookie);
		assertThat(sessions.response().getBody()).contains("this one");

		// 5. Step-up: an approval bound to this company and this reason.
		PlatformAdminMfaTestSupport.allowAnotherCode(jdbc, adminId);
		Page companies = get("/admin/companies", cookie);
		ResponseEntity<String> confirm = post("/admin/companies/confirm", cookie, companies.csrf(),
				"action", "COMPANY_SUSPEND", "companyId", String.valueOf(companyId),
				"reason", "non-payment", "code", code(seed, 0));
		Matcher approvalMatch = APPROVAL.matcher(confirm.getBody());
		assertThat(approvalMatch.find()).as("a verified code mints an approval").isTrue();
		String approvalId = approvalMatch.group(1);

		// 6. The admin action, spending that approval.
		ResponseEntity<String> applied = post("/admin/companies/apply", cookie, csrfOf(confirm),
				"action", "COMPANY_SUSPEND", "companyId", String.valueOf(companyId),
				"reason", "non-payment", "approvalId", approvalId);
		assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(statusOf(companyId)).isEqualTo("suspended");

		// ... audited, with its target and the approval that authorised it.
		assertThat(jdbc.queryForList(
				"SELECT event_type, target_type, target_id, step_up_approval_id "
						+ "FROM platform_admin_audit_events WHERE platform_admin_id = ? "
						+ "AND event_type = 'COMPANY_SUSPENDED'", adminId))
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("target_type")).isEqualTo("COMPANY");
				assertThat(row.get("target_id")).isEqualTo(String.valueOf(companyId));
				assertThat(row.get("step_up_approval_id")).isEqualTo(approvalId);
			});

		// ... and the approval is spent.
		Page companiesAgain = get("/admin/companies", cookie);
		ResponseEntity<String> replay = post("/admin/companies/apply", cookie, companiesAgain.csrf(),
				"action", "COMPANY_SUSPEND", "companyId", String.valueOf(companyId),
				"reason", "non-payment", "approvalId", approvalId);
		assertThat(replay.getBody()).contains("was not accepted");

		// 7. Logout ends the session everywhere, not just locally.
		ResponseEntity<String> loggedOut = post("/admin/logout", cookie, get("/admin", cookie).csrf());
		assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(get("/admin", cookie).response().getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spring_session WHERE session_id = ?",
				Integer.class, sessionIdOf(cookie)))
			.as("the shared row must be gone, or another worker still honours the cookie")
			.isZero();
	}

	@Test
	void deactivationEndsALiveSessionOnTheNextRequest() {
		String phone = uniquePhone();
		long adminId = createPlatformAdmin(phone);
		String seed = PlatformAdminMfaTestSupport.enrol(this.mfaService, adminId);
		String cookie = signIn(phone, seed, adminId);

		assertThat(get("/admin", cookie).response().getStatusCode()).isEqualTo(HttpStatus.OK);
		new JdbcTemplate(this.flywayDataSource)
			.update("UPDATE platform_admins SET active = false WHERE id = ?", adminId);

		assertThat(get("/admin", cookie).response().getStatusCode())
			.as("D-145: revocation must take effect on the next request, not at expiry")
			.isEqualTo(HttpStatus.FOUND);
	}

	// --- helpers ------------------------------------------------------------

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private record Csrf(String name, String value) {
	}

	private String signIn(String phone, String seed, long adminId) {
		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);
		Page loginForm = get("/admin/login", null);
		ResponseEntity<String> afterPassword = post("/admin/login", loginForm.cookie(), loginForm.csrf(),
				"phone", phone, "password", PASSWORD);
		String pending = cookieOf(afterPassword);
		PlatformAdminMfaTestSupport.allowAnotherCode(jdbc, adminId);
		Page challenge = get("/admin/mfa", pending);
		return cookieOf(post("/admin/mfa", pending, challenge.csrf(), "code", code(seed, 0)));
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

	private static String code(String base32Seed, long offset) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(java.time.Instant.now()) + offset);
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
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT status FROM companies WHERE id = ?", String.class, companyId);
	}

	private long createCompany() {
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"INSERT INTO companies (name, phone, active, status) VALUES (?, ?, true, 'active') RETURNING id",
				Long.class, "Flow " + System.nanoTime(), "+90" + System.nanoTime());
	}

	private long createPlatformAdmin(String phone) {
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, true) RETURNING id",
				Long.class, phone, this.passwordEncoder.encode(PASSWORD));
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

	private static String uniquePhone() {
		return "+89" + System.nanoTime();
	}

}
