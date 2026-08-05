package com.workin.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * F-13 / ADR-0002 Decision condition 3, implemented for real: proves
 * Postgres RLS's fail-closed design actually holds when the tenant
 * session variable is never set -- not merely assumed to hold because
 * every other test happens to set it first. This is the exact test the
 * H2 spike's RLS arm was missing
 * (docs/migration/technical-spike-plan.md's "Test Coverage Gap").
 *
 * <p>The row genuinely exists (created via the real registration
 * endpoint, in its own transaction). This test's own transaction never
 * calls {@link TenantContextService#establishContext} or otherwise sets
 * {@code app.current_company_id} -- a fresh Spring-test transaction gets
 * a connection with no session variable set (SET LOCAL is
 * transaction-scoped and resets automatically), so this genuinely
 * exercises the unset-variable path, not a leftover value from a prior
 * test.
 */
class RlsFailClosedTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private TenantMembershipRepository tenantMembershipRepository;

	@Test
	@Transactional
	void unscopedReadOfARealRowReturnsNothingWhenNoTenantIsSelected() {
		String phone = "+2010" + System.nanoTime() % 100_000_000L;
		AuthResponse registered = restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Real Company", phone, "correct horse battery staple"),
				AuthResponse.class).getBody();

		// No SET LOCAL app.current_company_id has run in this
		// transaction -- fail-closed means zero rows, not an error and
		// not the real row, even though it genuinely exists and even
		// though we already know its exact id.
		var result = tenantMembershipRepository.findByIdAndCompanyId(
				registered.membershipId(), registered.companyId());

		assertThat(result).isEmpty();
	}

}
