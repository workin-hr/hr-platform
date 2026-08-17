package com.workin.legacy.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.tenancy.NoTenantScopeException;
import com.workin.legacy.TenantFilterActivator;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeRepository;

/**
 * Legacy's counterpart to {@code TenantContextService} (ADR-0012 / D-041,
 * mandatory part 2): the authenticated context is the only source of a
 * tenant id, and a claimed one is never trusted until this re-derives it
 * from the database and cross-checks it against the authenticated
 * identity — never the other way around.
 *
 * <p>{@code TenantScopeFilter}'s resolver contract requires exactly
 * this: it "derives the id from the authenticated principal", not from a
 * claim taken at face value. A resolver that read a JWT's
 * {@code tenant_id} claim and entered it directly would satisfy the
 * filter's type signature while reintroducing the trust bug
 * {@code TenantContextIsolationTest} exists to catch on the PostgreSQL
 * side — a validly-signed token whose tenant claim does not match what
 * the authenticated identity actually owns must still be refused.
 *
 * <p>Legacy has no separate membership row: one {@code employees} row
 * per employee-per-company is the identity <em>and</em> the membership.
 * So re-deriving "the membership" is re-deriving the employee row by the
 * claimed id, then checking two things a tampered or stale claim could
 * get wrong independently — that the row belongs to the authenticated
 * identity, and that it belongs to the claimed company — rather than one
 * combined check that would conflate the two failure reasons.
 *
 * <p>This runs before any {@link com.workin.backend.tenancy.TenantScope}
 * is established — establishing it is this class's whole job — so the
 * lookup must use {@link TenantFilterActivator#deactivateForPreTenantLookup()}
 * exactly as the pre-tenant phone lookup does. Without it, the
 * NO_TENANT sentinel {@code TenantAwareJpaTransactionManager} binds to
 * every fresh transaction would make the re-derivation query itself
 * return zero rows, unconditionally.
 */
@Service
public class LegacyTenantContextService {

	private final LegacyEmployeeRepository legacyEmployeeRepository;
	private final TenantFilterActivator tenantFilterActivator;

	public LegacyTenantContextService(
			LegacyEmployeeRepository legacyEmployeeRepository,
			TenantFilterActivator tenantFilterActivator) {
		this.legacyEmployeeRepository = legacyEmployeeRepository;
		this.tenantFilterActivator = tenantFilterActivator;
	}

	/**
	 * Re-derives {@code claimedEmployeeId}'s tenant from the database and
	 * cross-checks it, rather than trusting {@code claimedCompanyId}.
	 *
	 * @param authenticatedIdentityId the identity the JWT's signature
	 *        actually vouches for (the {@code sub}/{@code identityId}
	 *        claim) — ground truth, not re-derived
	 * @param claimedEmployeeId the {@code membership_id} claim. For a
	 *        legacy identity this is the same value as
	 *        {@code authenticatedIdentityId} by construction at issuance
	 *        (D-042/decision: legacy has no separate membership concept),
	 *        but it is still validated independently here rather than
	 *        assumed equal, because the whole point is to not trust
	 *        claims a bug or staleness could have made diverge
	 * @param claimedCompanyId the {@code tenant_id} claim — the value
	 *        this method exists to refuse to trust blindly
	 * @return the re-derived company id, never the claimed one
	 * @throws NoTenantScopeException if no such employee exists, if the
	 *         employee does not belong to the authenticated identity, or
	 *         if the employee does not belong to the claimed company
	 */
	@Transactional
	public long validate(long authenticatedIdentityId, long claimedEmployeeId, long claimedCompanyId) {
		tenantFilterActivator.deactivateForPreTenantLookup();

		LegacyEmployee employee = legacyEmployeeRepository.findById(claimedEmployeeId)
				.orElseThrow(() -> new NoTenantScopeException(
						"Refusing to establish tenant scope: no legacy employee " + claimedEmployeeId + " exists"));

		if (!employee.getId().equals(authenticatedIdentityId)) {
			throw new NoTenantScopeException(
					"Refusing to establish tenant scope: employee " + claimedEmployeeId
							+ " does not belong to the authenticated identity " + authenticatedIdentityId);
		}

		if (!employee.getCompanyId().equals(claimedCompanyId)) {
			throw new NoTenantScopeException(
					"Refusing to establish tenant scope: employee " + claimedEmployeeId
							+ " does not belong to claimed company " + claimedCompanyId);
		}

		return employee.getCompanyId();
	}

}
