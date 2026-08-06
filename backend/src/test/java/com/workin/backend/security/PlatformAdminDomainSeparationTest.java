package com.workin.backend.security;

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
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;
import com.workin.backend.platformadmin.PlatformAdminAuthResponse;
import com.workin.backend.platformadmin.PlatformAdminLoginRequest;

/**
 * Proves docs/architecture/authorization-model.md §8's platform/tenant
 * structural-separation claim with real requests, not just by reading
 * SecurityConfig -- the same regression-test discipline
 * TenantContextIsolationTest already applies to cross-tenant claims. A
 * genuine, validly-signed token from one domain must never authenticate
 * a request in the other, regardless of claim content, because each
 * domain's SecurityFilterChain only wires its own JWT filter.
 */
class PlatformAdminDomainSeparationTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void aGenuineTenantTokenIsRejectedByAPlatformAdminRoute() {
		String tenantToken = registerTenantAndGetToken();

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tenantToken);
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/platform-admin/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aGenuinePlatformAdminTokenIsRejectedByATenantRoute() {
		String platformAdminToken = createPlatformAdminAndGetToken();

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(platformAdminToken);
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	private String registerTenantAndGetToken() {
		String phone = "+2097" + System.nanoTime() % 100_000_000L;
		ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Acme Co", phone, "correct horse battery staple"),
				AuthResponse.class);
		return response.getBody().accessToken();
	}

	private String createPlatformAdminAndGetToken() {
		String phone = "+2096" + System.nanoTime() % 100_000_000L;
		new JdbcTemplate(flywayDataSource).update(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, ?)",
				phone, passwordEncoder.encode("correct horse battery staple"), true);
		ResponseEntity<PlatformAdminAuthResponse> response = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
				PlatformAdminAuthResponse.class);
		return response.getBody().accessToken();
	}

}
