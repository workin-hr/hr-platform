package com.workin.backend.security;

/**
 * The claims extracted from a validated JWT, before tenant-membership
 * validation. {@code claimedMembershipId}/{@code claimedCompanyId} are
 * exactly that -- claims, not proof -- per
 * docs/adr/ADR-0010-authorization-model.md Dimension 6. Only
 * {@link com.workin.backend.tenancy.TenantContextService#establishContext}
 * turns these into a trusted
 * {@link com.workin.backend.tenancy.AuthorizationContext}.
 *
 * <p>{@code claimedRole}/{@code claimedTokenVersion} (D-049) are always
 * {@code null} for a {@code /api/auth/**} (Postgres-identity) token --
 * {@code JwtService}'s minimal claim set never sets them. Only a legacy
 * token (issued by {@code com.workin.legacy.auth.LegacyLoginController})
 * ever populates them, for {@code com.workin.legacy.auth.LegacyRequestGuard}
 * (P-7/P-8) to read. Adding them here, rather than a parallel
 * legacy-only principal type, keeps one {@code JwtAuthenticationFilter}
 * shared by both security chains (SecurityConfig).
 */
public record AuthenticatedPrincipal(
		Long identityId,
		Long claimedMembershipId,
		Long claimedCompanyId,
		String claimedRole,
		Long claimedTokenVersion) {

	public AuthenticatedPrincipal(Long identityId, Long claimedMembershipId, Long claimedCompanyId) {
		this(identityId, claimedMembershipId, claimedCompanyId, null, null);
	}
}
