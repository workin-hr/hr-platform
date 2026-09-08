package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.AbstractIntegrationTest;

class PlatformAdminSessionFlowTest extends AbstractIntegrationTest {


	@Autowired
	private com.workin.backend.platformadmin.mfa.PlatformAdminMfaService mfaServiceForTests;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PlatformAdminSessionService platformAdminSessionService;

	private PlatformAdminAuthResponse loginNewAdmin() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, "correct horse battery staple");
		return restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple", codeFor(phone)),
				PlatformAdminAuthResponse.class).getBody();
	}

	@Test
	void loginReturnsAnAccessRefreshPair() {
		PlatformAdminAuthResponse response = loginNewAdmin();
		assertThat(response.accessToken()).isNotBlank();
		assertThat(response.refreshToken()).isNotBlank();
	}

	@Test
	void refreshRotatesAndReuseRevokesTheFamily() {
		PlatformAdminAuthResponse login = loginNewAdmin();

		ResponseEntity<PlatformAdminAuthResponse> refreshed = restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(login.refreshToken()),
				PlatformAdminAuthResponse.class);
		assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(login.refreshToken());

		ResponseEntity<String> reuse = restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(login.refreshToken()),
				String.class);
		assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		ResponseEntity<String> newest = restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(refreshed.getBody().refreshToken()),
				String.class);
		assertThat(newest.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/**
	 * R-027, platform-admin half. {@link #logoutRevokesTheSessionAndIsIdempotent()}
	 * proves logout kills the <em>refresh</em> token; this one proves it also
	 * stops the access token already in the caller's hands, which it previously
	 * did not.
	 */
	@Test
	void logoutAlsoStopsTheAccessTokenImmediately() {
		PlatformAdminAuthResponse login = loginNewAdmin();

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(login.accessToken());
		HttpEntity<Void> authed = new HttpEntity<>(headers);

		assertThat(restTemplate.exchange("/api/platform-admin/me", HttpMethod.GET, authed, String.class)
				.getStatusCode())
				.as("the access token works before logout")
				.isEqualTo(HttpStatus.OK);

		restTemplate.postForEntity(
				"/api/platform-admin/logout",
				new PlatformAdminRefreshTokenRequest(login.refreshToken()),
				Void.class);

		assertThat(restTemplate.exchange("/api/platform-admin/me", HttpMethod.GET, authed, String.class)
				.getStatusCode())
				.as("the same unexpired access token must stop working once the session is revoked")
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void logoutRevokesTheSessionAndIsIdempotent() {
		PlatformAdminAuthResponse login = loginNewAdmin();

		ResponseEntity<Void> logout = restTemplate.postForEntity(
				"/api/platform-admin/logout",
				new PlatformAdminRefreshTokenRequest(login.refreshToken()),
				Void.class);
		assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<String> refreshAfterLogout = restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(login.refreshToken()),
				String.class);
		assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		ResponseEntity<Void> secondLogout = restTemplate.postForEntity(
				"/api/platform-admin/logout",
				new PlatformAdminRefreshTokenRequest(login.refreshToken()),
				Void.class);
		assertThat(secondLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	void rotationFailsClosedWhenTheAdminIsDeactivated() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, "correct horse battery staple");
		PlatformAdminAuthResponse login = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple", codeFor(phone)),
				PlatformAdminAuthResponse.class).getBody();
		new JdbcTemplate(legacyDataSource).update(
				"UPDATE platform_admins SET active = FALSE WHERE phone = ?", phone);

		ResponseEntity<String> refresh = restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(login.refreshToken()),
				String.class);
		assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void revokeAllForPlatformAdminKillsEverySession() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, "correct horse battery staple");
		PlatformAdminAuthResponse first = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple", codeFor(phone)),
				PlatformAdminAuthResponse.class).getBody();
		PlatformAdminMfaTestSupport.allowAnotherCode(
				new JdbcTemplate(legacyDataSource), first.platformAdminId());
		PlatformAdminAuthResponse second = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple", codeFor(phone)),
				PlatformAdminAuthResponse.class).getBody();

		platformAdminSessionService.revokeAllForPlatformAdmin(first.platformAdminId());

		for (String refreshToken : new String[] { first.refreshToken(), second.refreshToken() }) {
			ResponseEntity<String> refresh = restTemplate.postForEntity(
					"/api/platform-admin/refresh",
					new PlatformAdminRefreshTokenRequest(refreshToken),
					String.class);
			assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	private void createPlatformAdmin(String phone, String password) {
		Long id = new JdbcTemplate(legacyDataSource).queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, TRUE) RETURNING id",
				Long.class, phone, passwordEncoder.encode(password));
		// ADR-0015 prerequisite 8: the bearer surface refuses an administrator
		// with no bound factor, so a fixture that intends to log in must enrol.
		this.seeds.put(phone, PlatformAdminMfaTestSupport.enrol(this.mfaServiceForTests, id));
	}


	/** Seeds of the administrators this test enrolled, by phone. */
	private final java.util.Map<String, String> seeds = new java.util.HashMap<>();

	private String codeFor(String phone) {
		return PlatformAdminMfaTestSupport.freshCode(this.seeds.get(phone));
	}

	private static String uniquePhone() {
		return "+2066" + System.nanoTime() % 100_000_000L;
	}

}
