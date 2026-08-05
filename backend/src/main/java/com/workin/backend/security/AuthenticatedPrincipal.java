package com.workin.backend.security;

/**
 * The claims extracted from a validated JWT, before tenant-membership
 * validation. {@code claimedMembershipId}/{@code claimedCompanyId} are
 * exactly that -- claims, not proof -- per
 * docs/adr/ADR-0010-authorization-model.md Dimension 6. Only
 * {@link com.workin.backend.tenancy.TenantContextService#establishContext}
 * turns these into a trusted
 * {@link com.workin.backend.tenancy.AuthorizationContext}.
 */
public record AuthenticatedPrincipal(
		Long identityId,
		Long claimedMembershipId,
		Long claimedCompanyId) {
}
