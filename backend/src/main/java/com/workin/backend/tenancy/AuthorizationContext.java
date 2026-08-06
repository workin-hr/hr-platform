package com.workin.backend.tenancy;

import java.util.List;
import java.util.Set;

/**
 * Immutable, server-side-validated authorization context --
 * docs/adr/ADR-0010-authorization-model.md Dimension 2, step 5. Built
 * only after {@link TenantContextService} has verified the membership
 * belongs to the authenticated identity, belongs to the requested
 * tenant, and is active. Nothing downstream re-trusts client-supplied
 * claims once this exists.
 *
 * <p>{@code permissions} is the membership's <em>effective</em>
 * permission set, computed per ADR-0010 Dimension 3's precedence chain
 * inside the same transaction that validated the membership (see
 * {@code PermissionEvaluationService}) -- loaded fresh on every
 * request, never cached across requests (Dimension 5). Permission
 * possession is still not resource scope: holding a permission says
 * nothing about <em>which</em> rows it reaches
 * (docs/architecture/authorization-model.md §3) -- resource-scope
 * evaluation is separate, future work (F-16/F-25).
 */
public record AuthorizationContext(
		Long identityId,
		Long membershipId,
		Long companyId,
		List<TenantRole> roles,
		Set<String> permissions) {

	public boolean hasPermission(String permissionKey) {
		return permissions.contains(permissionKey);
	}

}
