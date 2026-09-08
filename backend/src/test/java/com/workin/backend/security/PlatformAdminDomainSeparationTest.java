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
import com.workin.backend.platformadmin.PlatformAdminAuthResponse;
import com.workin.backend.platformadmin.PlatformAdminLoginRequest;
import com.workin.legacy.auth.LegacyPhpJwtService;

/**
 * Proves docs/architecture/authorization-model.md §8's platform/client
 * structural-separation claim with real requests, not by reading
 * SecurityConfig. A genuine, validly-signed token from one domain must never
 * authenticate a request in the other, regardless of claim content, because
 * each domain's SecurityFilterChain wires only its own JWT filter.
 *
 * <p>The client domain is the frozen PHP API's: its tokens are the PHP-format
 * JWTs {@link LegacyPhpJwtService} issues, presented by the mobile and desktop
 * apps to {@code /apis/**}. There used to be a third domain -- the PostgreSQL
 * tenant API -- and this test used to register a company there to get its
 * token; that domain is gone (ADR-0017), and the separation that remains is
 * the one that matters at cutover.
 */
class PlatformAdminDomainSeparationTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private LegacyPhpJwtService legacyPhpJwtService;

	@Test
	void aGenuineClientTokenIsRejectedByAPlatformAdminRoute() {
		// A well-formed employee token, signed with the real secret: exactly
		// what a phone presents to /apis/**. No row behind it is needed --
		// the point is that the platform chain never decodes it at all.
		String clientToken = legacyPhpJwtService.issueEmployeeToken(1L, 1L, "employee", 0L);

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(clientToken);
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/platform-admin/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

		// 401, not 403: the platform chain never authenticated this token,
		// so there is no principal to deny.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void aGenuinePlatformAdminTokenIsRejectedByAClientRoute() {
		String platformAdminToken = createPlatformAdminAndGetToken();

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(platformAdminToken);
		// A route the frozen API guards inside its controller, as most of
		// /apis/** are: the legacy filter cannot decode a platform-admin
		// token, clears the context, and the guard answers PHP's 401.
		ResponseEntity<String> response = restTemplate.exchange(
				"/apis/api/requests/list.php", HttpMethod.GET, new HttpEntity<>(headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	private String createPlatformAdminAndGetToken() {
		String phone = "+2096" + System.nanoTime() % 100_000_000L;
		new JdbcTemplate(legacyDataSource).update(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, ?)",
				phone, passwordEncoder.encode("correct horse battery staple"), true);
		ResponseEntity<PlatformAdminAuthResponse> response = restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "correct horse battery staple"),
				PlatformAdminAuthResponse.class);
		return response.getBody().accessToken();
	}

}
