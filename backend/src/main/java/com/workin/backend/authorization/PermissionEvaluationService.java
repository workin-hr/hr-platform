package com.workin.backend.authorization;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;

import com.workin.backend.tenancy.TenantRole;

/**
 * Computes a membership's effective permission set per ADR-0010
 * Dimension 3's precedence chain: explicit deny -> explicit allow ->
 * role-granted -> deny by default. Implemented as
 * effective = (roleGranted ∪ explicitAllows) − explicitDenies, which
 * is the same ordering (denies subtract last; absence of any rule
 * yields nothing).
 *
 * <p>Must be called inside the caller's tenant-scoped transaction
 * (TenantContextService.establishContext): membership_permission_overrides
 * is RLS-protected, and the SET LOCAL session variable lives only on
 * that transaction's connection -- which is why this service shares the
 * transactional EntityManager instead of using its own DataSource. Per
 * Dimension 5 nothing here is cached: every request recomputes, so any
 * role/override change is live on the very next request by
 * construction.
 */
@Service
public class PermissionEvaluationService {

	private final EntityManager entityManager;

	public PermissionEvaluationService(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@SuppressWarnings("unchecked")
	public Set<String> effectivePermissionsFor(Long membershipId, List<TenantRole> roles) {
		Set<String> effective = new HashSet<>();

		if (!roles.isEmpty()) {
			List<String> roleGranted = entityManager.createNativeQuery(
					"SELECT p.permission_key FROM role_permissions rp "
							+ "JOIN permissions p ON p.id = rp.permission_id "
							+ "WHERE rp.role IN (:roles)",
					String.class)
					.setParameter("roles", roles.stream().map(Enum::name).toList())
					.getResultList();
			effective.addAll(roleGranted);
		}

		List<Object[]> overrides = entityManager.createNativeQuery(
				"SELECT p.permission_key, o.effect FROM membership_permission_overrides o "
						+ "JOIN permissions p ON p.id = o.permission_id "
						+ "WHERE o.membership_id = :membershipId")
				.setParameter("membershipId", membershipId)
				.getResultList();
		for (Object[] override : overrides) {
			if ("ALLOW".equals(override[1])) {
				effective.add((String) override[0]);
			}
		}
		for (Object[] override : overrides) {
			if ("DENY".equals(override[1])) {
				effective.remove((String) override[0]);
			}
		}

		return Set.copyOf(effective);
	}

}
