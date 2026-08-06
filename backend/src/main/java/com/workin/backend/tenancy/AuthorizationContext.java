package com.workin.backend.tenancy;

import java.util.List;

/**
 * Immutable, server-side-validated authorization context --
 * docs/adr/ADR-0010-authorization-model.md Dimension 2, step 5. Built
 * only after {@link TenantContextService} has verified the membership
 * belongs to the authenticated identity, belongs to the requested
 * tenant, and is active. Nothing downstream re-trusts client-supplied
 * claims once this exists.
 *
 * <p>{@code roles} carries this membership's assigned {@link TenantRole}s
 * only. Role-to-permission enforcement (V4's {@code role_permissions}
 * catalog, Dimension 3) is not wired to any endpoint yet -- this slice
 * validates authentication and tenant membership, not permissions. Do
 * not treat a non-empty {@code roles} list as proof that a caller has
 * been checked against any specific permission.
 */
public record AuthorizationContext(
		Long identityId,
		Long membershipId,
		Long companyId,
		List<TenantRole> roles) {
}
