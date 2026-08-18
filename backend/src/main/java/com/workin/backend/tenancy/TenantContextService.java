package com.workin.backend.tenancy;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.authorization.PermissionEvaluationService;

/**
 * Implements docs/adr/ADR-0010-authorization-model.md Dimension 2's
 * required per-request sequence, steps 3-6 (step 1, authentication, has
 * already happened by the time this runs -- see JwtAuthenticationFilter;
 * step 2, resolving the requested tenant_id/membership_id, is the
 * caller's job; step 7, RLS enforcement, happens implicitly once this
 * method returns and the caller's queries run inside the same
 * transaction).
 *
 * <p>The tenant identifier from a request/JWT claim is never trusted
 * without this validation -- {@link #establishContext} always re-derives
 * the membership from the database and cross-checks it against the
 * authenticated identity, never the other way around.
 */
@Profile("!phase1-mysql")
@Service
public class TenantContextService {

	private final TenantMembershipRepository tenantMembershipRepository;
	private final MembershipRoleRepository membershipRoleRepository;
	private final PermissionEvaluationService permissionEvaluationService;
	private final TenantSessionVariable tenantSessionVariable;

	public TenantContextService(
			TenantMembershipRepository tenantMembershipRepository,
			MembershipRoleRepository membershipRoleRepository,
			PermissionEvaluationService permissionEvaluationService,
			TenantSessionVariable tenantSessionVariable) {
		this.tenantMembershipRepository = tenantMembershipRepository;
		this.membershipRoleRepository = membershipRoleRepository;
		this.permissionEvaluationService = permissionEvaluationService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	/**
	 * Steps 2-6 of the Dimension 2 sequence, in one transaction. The
	 * claimed {@code membershipId}/{@code companyId} come from the
	 * request (JWT claims) and are untrusted until this method returns
	 * successfully.
	 *
	 * @throws TenantContextException on every fail-closed condition:
	 *         no tenant selected, membership does not exist for the
	 *         claimed tenant, membership does not belong to the
	 *         authenticated identity, or membership is disabled.
	 */
	@Transactional
	public AuthorizationContext establishContext(Long authenticatedIdentityId, Long claimedMembershipId, Long claimedCompanyId) {
		if (claimedMembershipId == null || claimedCompanyId == null) {
			throw new TenantContextException("No tenant selected: membership_id/tenant_id claim missing");
		}

		// SET LOCAL against the *claimed* tenant first -- this is safe
		// specifically because the very next statement re-derives the
		// membership through RLS scoped to that same claim and then
		// cross-checks every field against the authenticated identity.
		// If the claim was tampered with, either RLS returns zero rows
		// (the real membership belongs to a different company) or the
		// identity-match check below fails -- fail-closed either way.
		tenantSessionVariable.apply(claimedCompanyId);

		TenantMembership membership = tenantMembershipRepository
				.findByIdAndCompanyId(claimedMembershipId, claimedCompanyId)
				.orElseThrow(() -> new TenantContextException(
						"Membership " + claimedMembershipId + " does not exist for tenant " + claimedCompanyId));

		if (!membership.getIdentityId().equals(authenticatedIdentityId)) {
			throw new TenantContextException(
					"Membership " + claimedMembershipId + " does not belong to the authenticated identity");
		}

		if (!membership.isActive()) {
			throw new TenantContextException("Membership " + claimedMembershipId + " is disabled");
		}

		List<TenantRole> roles = membershipRoleRepository.findByMembershipId(membership.getId())
				.stream()
				.map(MembershipRoleAssignment::getRole)
				.toList();

		// Still inside this method's transaction, deliberately: the RLS
		// session variable above is SET LOCAL, and the overrides read in
		// the evaluation is RLS-scoped -- moving this outside the
		// transaction would make overrides invisible (ADR-0010
		// Dimensions 3/5; docs/superpowers/specs/2026-08-06-authorization-runtime-design.md).
		Set<String> permissions = permissionEvaluationService.effectivePermissionsFor(membership.getId(), roles);

		return new AuthorizationContext(
				authenticatedIdentityId, membership.getId(), membership.getCompanyId(), roles, permissions);
	}

}
