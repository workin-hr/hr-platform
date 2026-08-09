package com.workin.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.JwtService;
import com.workin.backend.identity.RegisterCompanyRequest;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Proves docs/adr/ADR-0010-authorization-model.md Dimension 2's core
 * guarantee: a token's {@code membership_id}/{@code tenant_id} claims
 * are never trusted without server-side re-validation. This is the
 * exact bug shape found in 15 hr-legacy files
 * (hr-legacy#2/#3/#5/#6) -- modeled here against the new platform's own
 * mechanism, not the legacy one.
 */
class TenantContextIsolationTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	private final SecretKey testSigningKey = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes());

	@Test
	void aTokenClaimingAnotherIdentitysMembershipIsRejected() {
		AuthResponse companyA = register("Company A");
		AuthResponse companyB = register("Company B");

		// A token that is validly signed and belongs to a real,
		// authenticated identity (company A's), but whose membership/
		// tenant claims point at company B -- exactly what an attacker
		// tampering with a JWT, or a bug that trusts the claim directly,
		// would produce. This must never be treated as proof of
		// membership -- TenantContextService re-derives and cross-checks
		// against the database on every request.
		String forgedToken = jwtService.issueAccessToken(
				extractIdentityIdFrom(companyA), companyB.membershipId(), companyB.companyId(),
				java.util.UUID.randomUUID().toString());

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(forgedToken);
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void aSignedTokenMissingMembershipClaimFailsClosedInsteadOfCrashing() {
		AuthResponse companyA = register("Company A");

		String malformedToken = Jwts.builder()
				.subject(String.valueOf(extractIdentityIdFrom(companyA)))
				.claim("tenant_id", companyA.companyId())
				.issuer("workin-backend")
				.audience().add("workin-clients").and()
				.signWith(testSigningKey)
				.compact();

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(malformedToken);
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

		// 401, not 403: an unusable token leaves the request
		// unauthenticated, and there is no principal to deny. The old 403
		// came from Spring Security's fallback entry point, not from a
		// deliberate choice (issue #70). Fail-closed behaviour is
		// unchanged -- the token still reaches nothing.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void aSignedTokenWithTheWrongAudienceIsRejected() {
		AuthResponse companyA = register("Company A");

		String wrongAudienceToken = Jwts.builder()
				.subject(String.valueOf(extractIdentityIdFrom(companyA)))
				.claim("membership_id", companyA.membershipId())
				.claim("tenant_id", companyA.companyId())
				.issuer("workin-backend")
				.audience().add("unexpected-audience").and()
				.signWith(testSigningKey)
				.compact();

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(wrongAudienceToken);
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);

		// 401, not 403: an unusable token leaves the request
		// unauthenticated, and there is no principal to deny. The old 403
		// came from Spring Security's fallback entry point, not from a
		// deliberate choice (issue #70). Fail-closed behaviour is
		// unchanged -- the token still reaches nothing.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void aGenuineTokenCanReadItsOwnTenantContext() {
		AuthResponse companyA = register("Company A");

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(companyA.accessToken());
		ResponseEntity<TenantController.MembershipView> response = restTemplate.exchange(
				"/api/tenant/me", HttpMethod.GET, new HttpEntity<>(headers), TenantController.MembershipView.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().membershipId()).isEqualTo(companyA.membershipId());
		assertThat(response.getBody().companyId()).isEqualTo(companyA.companyId());
	}

	private AuthResponse register(String name) {
		String phone = "+2010" + System.nanoTime() % 100_000_000L;
		ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest(name, phone, "correct horse battery staple"),
				AuthResponse.class);
		return response.getBody();
	}

	private Long extractIdentityIdFrom(AuthResponse authResponse) {
		return Long.valueOf(jwtService.parseAndValidate(authResponse.accessToken()).getSubject());
	}

}
