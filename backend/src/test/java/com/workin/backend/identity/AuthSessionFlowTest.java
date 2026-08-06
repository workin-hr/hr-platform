package com.workin.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.workin.backend.AbstractIntegrationTest;

class AuthSessionFlowTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	private AuthResponse register() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Session Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	@Test
	void loginAndRegisterReturnAnAccessRefreshPair() {
		AuthResponse registered = register();
		assertThat(registered.accessToken()).isNotBlank();
		assertThat(registered.refreshToken()).isNotBlank();
	}

	@Test
	void refreshRotatesTheSessionAndTheOldTokenDies() {
		AuthResponse registered = register();

		ResponseEntity<AuthResponse> refreshed = restTemplate.postForEntity(
				"/api/auth/refresh", new RefreshTokenRequest(registered.refreshToken()), AuthResponse.class);

		assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(refreshed.getBody().accessToken()).isNotBlank();
		assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(registered.refreshToken());
		assertThat(refreshed.getBody().companyId()).isEqualTo(registered.companyId());

		ResponseEntity<String> reuse = restTemplate.postForEntity(
				"/api/auth/refresh", new RefreshTokenRequest(registered.refreshToken()), String.class);
		assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		// Reuse revoked the whole family, so the newest token is dead too.
		ResponseEntity<String> newest = restTemplate.postForEntity(
				"/api/auth/refresh", new RefreshTokenRequest(refreshed.getBody().refreshToken()), String.class);
		assertThat(newest.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void theAccessTokenSidClaimIsTheSessionFamilyAndSurvivesRotation() {
		AuthResponse registered = register();
		String sidAtLogin = jwtService.parseAndValidate(registered.accessToken()).get("sid", String.class);

		AuthResponse refreshed = restTemplate.postForEntity(
				"/api/auth/refresh", new RefreshTokenRequest(registered.refreshToken()), AuthResponse.class).getBody();
		String sidAfterRefresh = jwtService.parseAndValidate(refreshed.accessToken()).get("sid", String.class);

		assertThat(sidAtLogin).isNotBlank();
		assertThat(sidAfterRefresh).isEqualTo(sidAtLogin);
	}

	@Test
	void logoutRevokesTheSessionIsIdempotentAndNeverDeactivatesTheAccount() {
		String phone = uniquePhone();
		AuthResponse registered = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Session Co", phone, "correct horse battery staple"),
				AuthResponse.class).getBody();

		ResponseEntity<Void> logout = restTemplate.postForEntity(
				"/api/auth/logout", new RefreshTokenRequest(registered.refreshToken()), Void.class);
		assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<String> refreshAfterLogout = restTemplate.postForEntity(
				"/api/auth/refresh", new RefreshTokenRequest(registered.refreshToken()), String.class);
		assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		ResponseEntity<Void> secondLogout = restTemplate.postForEntity(
				"/api/auth/logout", new RefreshTokenRequest(registered.refreshToken()), Void.class);
		assertThat(secondLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<Void> unknownLogout = restTemplate.postForEntity(
				"/api/auth/logout", new RefreshTokenRequest("never-issued-token"), Void.class);
		assertThat(unknownLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		// hr-legacy#15 regression guarantee: logout must never deactivate
		// the account -- the user can immediately log back in.
		ResponseEntity<AuthResponse> loginAgain = restTemplate.postForEntity(
				"/api/auth/login",
				new LoginRequest(phone, "correct horse battery staple"),
				AuthResponse.class);
		assertThat(loginAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void eachLoginIsItsOwnSession() {
		String phone = uniquePhone();
		restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Session Co", phone, "correct horse battery staple"),
				AuthResponse.class);
		AuthResponse firstLogin = restTemplate.postForEntity(
				"/api/auth/login", new LoginRequest(phone, "correct horse battery staple"),
				AuthResponse.class).getBody();
		AuthResponse secondLogin = restTemplate.postForEntity(
				"/api/auth/login", new LoginRequest(phone, "correct horse battery staple"),
				AuthResponse.class).getBody();

		// Logging out one session must not kill the other.
		restTemplate.postForEntity("/api/auth/logout", new RefreshTokenRequest(firstLogin.refreshToken()), Void.class);
		ResponseEntity<AuthResponse> refreshSecond = restTemplate.postForEntity(
				"/api/auth/refresh", new RefreshTokenRequest(secondLogin.refreshToken()), AuthResponse.class);
		assertThat(refreshSecond.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private static String uniquePhone() {
		return "+2055" + System.nanoTime() % 100_000_000L;
	}

}
