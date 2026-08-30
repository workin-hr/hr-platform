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

import com.workin.backend.AbstractIntegrationTest;

/**
 * There is no register endpoint for platform admins (see
 * PlatformAdminBootstrap), so tests create the fixture admin directly
 * against the real database, the same way AuthFlowTest's
 * multi-membership test inserts a fixture membership row directly.
 */
class PlatformAdminAuthFlowTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void loginWithCorrectCredentialsSucceeds() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, "correct horse battery staple", true);

		ResponseEntity<PlatformAdminAuthResponse> response = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
				PlatformAdminAuthResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().accessToken()).isNotBlank();
		assertThat(response.getBody().refreshToken()).isNotBlank();
		assertThat(response.getBody().platformAdminId()).isNotNull();
	}

	@Test
	void loginWithWrongPasswordIsRejected() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, "correct horse battery staple", true);

		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "wrong password"),
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void loginAsAnInactiveAdminIsRejected() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, "correct horse battery staple", false);

		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void meReturnsTheAuthenticatedAdminForAGenuineToken() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, "correct horse battery staple", true);
		String token = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
				PlatformAdminAuthResponse.class).getBody().accessToken();

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		ResponseEntity<PlatformAdminController.PlatformAdminView> response = restTemplate.exchange(
				"/api/platform-admin/me",
				HttpMethod.GET,
				new HttpEntity<>(headers),
				PlatformAdminController.PlatformAdminView.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().phone()).isEqualTo(phone);
	}

	/**
	 * R-026. Deactivation is the control an operator reaches for when someone
	 * must lose access <em>now</em> -- a departure, a suspected compromise.
	 *
	 * <p>Scoped to what exists: the only authenticated route on this surface
	 * today is {@code GET /api/platform-admin/me}, so what the defect realised
	 * was continued identity disclosure. The operations this surface is for --
	 * company suspension and deletion -- are not built, and the defect mattered
	 * because the first one would have inherited it silently.
	 *
	 * <p>Before this was fixed, {@code PlatformAdminAuthenticationFilter} built
	 * its principal from the JWT subject alone and never loaded the row, so a
	 * deactivated admin kept working tokens for the remainder of the access-token
	 * TTL. {@code PlatformAdminSessionService} refuses to rotate a deactivated
	 * admin's refresh token, which bounded the window to 15 minutes but did not
	 * close it.
	 */
	@Test
	void aTokenIssuedBeforeDeactivationStopsWorkingImmediately() {
		String phone = uniquePhone();
		createPlatformAdmin(phone, "correct horse battery staple", true);
		String token = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
				PlatformAdminAuthResponse.class).getBody().accessToken();

		assertThat(meWith(token).getStatusCode())
				.as("the token works while the admin is active")
				.isEqualTo(HttpStatus.OK);

		new JdbcTemplate(flywayDataSource).update(
				"UPDATE platform_admins SET active = false WHERE phone = ?", phone);

		assertThat(meWith(token).getStatusCode())
				.as("the same unexpired token must stop working the moment the admin is deactivated")
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	private ResponseEntity<PlatformAdminController.PlatformAdminView> meWith(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return restTemplate.exchange(
				"/api/platform-admin/me",
				HttpMethod.GET,
				new HttpEntity<>(headers),
				PlatformAdminController.PlatformAdminView.class);
	}

	private void createPlatformAdmin(String phone, String password, boolean active) {
		new JdbcTemplate(flywayDataSource).update(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, ?)",
				phone, passwordEncoder.encode(password), active);
	}

	private static String uniquePhone() {
		return "+2098" + System.nanoTime() % 100_000_000L;
	}

}
