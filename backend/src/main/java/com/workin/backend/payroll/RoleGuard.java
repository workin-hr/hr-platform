package com.workin.backend.payroll;

import java.util.List;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;

/**
 * Role gating only -- the first of the two authorization layers
 * described in docs/migration/payroll-module-execution-plan.md,
 * "Authorization Approach." Deliberately does not attempt fine-grained
 * permission-catalog enforcement (that's F-15/F-17/F-23, tracked
 * separately); this only reproduces the same per-endpoint role
 * requirement legacy already enforced, ported from
 * docs/api/existing-endpoint-inventory.md's per-module tables.
 */
final class RoleGuard {

	private RoleGuard() {
	}

	static void requireAnyRole(AuthorizationContext context, TenantRole... allowed) {
		List<TenantRole> allowedList = List.of(allowed);
		boolean permitted = context.roles().stream().anyMatch(allowedList::contains);
		if (!permitted) {
			throw new PayrollForbiddenException(
					"Membership " + context.membershipId() + " lacks any of the required roles " + allowedList);
		}
	}

}
