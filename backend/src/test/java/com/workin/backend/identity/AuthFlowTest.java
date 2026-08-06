package com.workin.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;

class AuthFlowTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private JwtService jwtService;

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
	void registeringWithAPasswordShorterThanTheMinimumIsRejected() {
		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Acme Co", uniquePhone(), "short1"),
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void concurrentRegistrationsForTheSamePhoneNeverProduceAServerError() throws InterruptedException {
		// The existsByPhone() precheck in RegistrationService only closes
		// the common case -- it cannot close the race window between two
		// concurrent requests for the same phone. This fires several
		// registrations at the same phone number at once so at least one
		// is expected to lose that race and hit the companies.phone/
		// identities.phone UNIQUE constraint directly. That must still
		// surface as a clean 409, never an uncaught 500.
		String phone = uniquePhone();
		int attempts = 6;
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(attempts);
		List<HttpStatusCode> statuses = new CopyOnWriteArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		try {
			for (int i = 0; i < attempts; i++) {
				pool.submit(() -> {
					try {
						startGate.await();
						ResponseEntity<String> response = restTemplate.postForEntity(
								"/api/auth/register",
								new RegisterCompanyRequest("Acme Co", phone, "correct horse battery staple"),
								String.class);
						statuses.add(response.getStatusCode());
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					} finally {
						doneLatch.countDown();
					}
				});
			}
			startGate.countDown();
			assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdown();
		}

		assertThat(statuses).hasSize(attempts);
		assertThat(statuses).doesNotContain(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(statuses).containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT);
		assertThat(statuses).contains(HttpStatus.CREATED);
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

	@Test
	void loginWithMultipleActiveMembershipsRequiresExplicitTenantSelection() {
		String primaryPhone = uniquePhone();
		ResponseEntity<AuthResponse> primaryRegistration = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Primary Company", primaryPhone, "correct horse battery staple"),
				AuthResponse.class);
		ResponseEntity<AuthResponse> secondaryRegistration = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Secondary Company", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class);

		Long primaryIdentityId = Long.valueOf(
				jwtService.parseAndValidate(primaryRegistration.getBody().accessToken()).getSubject());
		new JdbcTemplate(flywayDataSource).update(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE')",
				primaryIdentityId,
				secondaryRegistration.getBody().companyId());

		ResponseEntity<String> loginResponse = restTemplate.postForEntity(
				"/api/auth/login",
				new LoginRequest(primaryPhone, "correct horse battery staple"),
				String.class);

		assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	private static String uniquePhone() {
		return "+2010" + System.nanoTime() % 100_000_000L;
	}

}
