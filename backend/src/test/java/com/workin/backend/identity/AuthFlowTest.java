package com.workin.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.workin.backend.AbstractIntegrationTest;

class AuthFlowTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void registerIssuesTokenAndTenantContext() {
		ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Acme Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().accessToken()).isNotBlank();
		assertThat(response.getBody().membershipId()).isNotNull();
		assertThat(response.getBody().companyId()).isNotNull();
	}

	@Test
	void registeringTheSamePhoneTwiceIsRejected() {
		String phone = uniquePhone();
		restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Acme Co", phone, "correct horse battery staple"),
				AuthResponse.class);

		ResponseEntity<String> second = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Acme Co Duplicate", phone, "correct horse battery staple"),
				String.class);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void loginWithCorrectCredentialsSucceeds() {
		String phone = uniquePhone();
		restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Acme Co", phone, "correct horse battery staple"),
				AuthResponse.class);

		ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
				"/api/auth/login",
				new LoginRequest(phone, "correct horse battery staple"),
				AuthResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().accessToken()).isNotBlank();
	}

	@Test
	void loginWithWrongPasswordIsRejected() {
		String phone = uniquePhone();
		restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Acme Co", phone, "correct horse battery staple"),
				AuthResponse.class);

		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/auth/login",
				new LoginRequest(phone, "wrong password"),
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	private static String uniquePhone() {
		return "+2010" + System.nanoTime() % 100_000_000L;
	}

}
