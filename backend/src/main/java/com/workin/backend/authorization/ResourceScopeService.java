package com.workin.backend.authorization;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;

/**
 * Enforcement boundary 3 (docs/architecture/authorization-model.md
 * section 4): resolves WHICH employees a membership's permissions
 * reach. Per D-029 and its follow-ups the model is role-based
 * fallback: only a <em>pure</em> MANAGER (MANAGER role, and neither
 * COMPANY_ADMIN nor HR -- a company-wide role always trumps) is
 * scope-limited; everyone else keeps company-wide reach. A
 * scope-limited membership with no scope rows reaches zero employees
 * (deny-by-default, matching the permission layer).
 *
 * <p>Like {@link PermissionEvaluationService}, this is not
 * {@code @Transactional} and applies no session variable of its own:
 * it must be called inside the caller's already-validated
 * tenant-scoped transaction, so its reads run under the same RLS
 * context. Nothing is cached across requests (Dimension 5) -- a scope
 * change bites on the very next request.
 */
@Profile("!phase1-mysql")
@Service
public class ResourceScopeService {

	private final EntityManager entityManager;

	public ResourceScopeService(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	public boolean isScopeLimited(AuthorizationContext context) {
		List<TenantRole> roles = context.roles();
		return roles.contains(TenantRole.MANAGER)
				&& !roles.contains(TenantRole.COMPANY_ADMIN)
				&& !roles.contains(TenantRole.HR);
	}

	/**
	 * The scoped employee set = employees whose branch_id is in the
	 * membership's BRANCH scope ids OR whose department_id is in its
	 * DEPARTMENT scope ids. Only meaningful when {@link #isScopeLimited}.
	 */
	@SuppressWarnings("unchecked")
	public Set<Long> reachableEmployeeIds(AuthorizationContext context) {
		List<Long> branchIds = scopeIdsOf(context.membershipId(), ResourceScopeType.BRANCH);
		List<Long> departmentIds = scopeIdsOf(context.membershipId(), ResourceScopeType.DEPARTMENT);
		if (branchIds.isEmpty() && departmentIds.isEmpty()) {
			// Deny-by-default: no scopes -> reaches nobody. Also avoids an
			// invalid empty IN () list.
			return Set.of();
		}

		StringBuilder sql = new StringBuilder("SELECT e.id FROM employees e WHERE e.company_id = :companyId AND (");
		if (!branchIds.isEmpty()) {
			sql.append("e.branch_id IN (:branchIds)");
		}
		if (!departmentIds.isEmpty()) {
			if (!branchIds.isEmpty()) {
				sql.append(" OR ");
			}
			sql.append("e.department_id IN (:departmentIds)");
		}
		sql.append(')');

		var query = entityManager.createNativeQuery(sql.toString(), Long.class)
				.setParameter("companyId", context.companyId());
		if (!branchIds.isEmpty()) {
			query.setParameter("branchIds", branchIds);
		}
		if (!departmentIds.isEmpty()) {
			query.setParameter("departmentIds", departmentIds);
		}
		return new HashSet<>((List<Long>) query.getResultList());
	}

	public boolean canReachEmployee(AuthorizationContext context, Long employeeId) {
		return reachableEmployeeIds(context).contains(employeeId);
	}

	/**
	 * The reusable list-filter primitive: {@code null} means not
	 * scope-limited (company-wide, no filtering), otherwise the reachable
	 * employee set. Callers filter their employee-bound rows by
	 * membership in this set when it is non-null.
	 */
	public Set<Long> scopedEmployeeIdsOrNull(AuthorizationContext context) {
		return isScopeLimited(context) ? reachableEmployeeIds(context) : null;
	}

	/**
	 * The reusable single-row guard: a non-scope-limited caller reaches
	 * every employee; a scope-limited one only those in its scoped set.
	 */
	public boolean isEmployeeInScope(AuthorizationContext context, Long employeeId) {
		return !isScopeLimited(context) || canReachEmployee(context, employeeId);
	}

	@SuppressWarnings("unchecked")
	private List<Long> scopeIdsOf(Long membershipId, ResourceScopeType scopeType) {
		return entityManager.createNativeQuery(
				"SELECT scope_id FROM membership_resource_scopes WHERE membership_id = :membershipId "
						+ "AND scope_type = :scopeType", Long.class)
				.setParameter("membershipId", membershipId)
				.setParameter("scopeType", scopeType.name())
				.getResultList();
	}

}
