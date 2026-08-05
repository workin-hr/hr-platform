package com.workin.backend.tenancy;

import java.util.List;

/**
 * Immutable, server-side-validated authorization context --
 * docs/adr/ADR-0010-authorization-model.md Dimension 2, step 5. Built
 * only after {@link TenantContextService} has verified the membership
 * belongs to the authenticated identity, belongs to the requested
 * tenant, and is active. Nothing downstream re-trusts client-supplied
 * claims once this exists.
 */
public record AuthorizationContext(
		Long identityId,
		Long membershipId,
		Long companyId,
		List<TenantRole> roles) {
}
