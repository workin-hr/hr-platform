package com.workin.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.JwtService;
import com.workin.backend.identity.RegisterCompanyRequest;
import com.workin.backend.tenancy.IdentityMembershipIndexService.MembershipSummary;

/**
 * IdentityMembershipIndexService deliberately queries through the
 * superuser (Flyway) DataSource, not the RLS-scoped {@code app_runtime}
 * one -- see its class Javadoc for why. That makes it the one place in
 * this application where tenant isolation depends entirely on the
 * query's own {@code WHERE identity_id = ?}, not on Postgres RLS.
 * SuperuserStartupCheck does not and cannot cover this DataSource, since
 * using it here is intentional, not a misconfiguration.
 *
 * <p>This test is the regression guard for that specific safety
 * invariant: it must never be possible for one identity's lookup to
 * return another identity's, or another company's, membership rows. If
 * a future edit to the query (or a copy of this pattern into a new
 * service) drops or weakens the identity filter, this test is what
 * should catch it.
 */
class IdentityMembershipIndexServiceTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private IdentityMembershipIndexService membershipIndexService;

	@Autowired
	private JwtService jwtService;

	@Test
	void lookupForOneIdentityNeverReturnsAnotherIdentitysMembership() {
		AuthResponse companyA = register("Company A");
		AuthResponse companyB = register("Company B");

		Long identityIdA = extractIdentityIdFrom(companyA);

		List<MembershipSummary> membershipsForA = membershipIndexService.findMembershipsForIdentity(identityIdA);

		assertThat(membershipsForA).hasSize(1);
		assertThat(membershipsForA.get(0).membershipId()).isEqualTo(companyA.membershipId());
		assertThat(membershipsForA.get(0).companyId()).isEqualTo(companyA.companyId());
		assertThat(membershipsForA)
				.extracting(MembershipSummary::membershipId)
				.doesNotContain(companyB.membershipId());
	}

	@Test
	void lookupForAnUnknownIdentityIdReturnsNothing() {
		List<MembershipSummary> memberships = membershipIndexService.findMembershipsForIdentity(Long.MAX_VALUE);

		assertThat(memberships).isEmpty();
	}

	private AuthResponse register(String name) {
		String phone = "+2012" + System.nanoTime() % 100_000_000L;
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
