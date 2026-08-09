package com.workin.backend.members;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.authorization.ResourceScope;
import com.workin.backend.authorization.ResourceScopeRepository;
import com.workin.backend.authorization.ResourceScopeType;
import com.workin.backend.i18n.ApiException;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.organization.BranchRepository;
import com.workin.backend.organization.DepartmentRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.MembershipRoleAssignment;
import com.workin.backend.tenancy.MembershipRoleRepository;
import com.workin.backend.tenancy.MembershipStatus;
import com.workin.backend.tenancy.TenantMembership;
import com.workin.backend.tenancy.TenantMembershipRepository;
import com.workin.backend.tenancy.TenantRole;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * The authorization module's administration surface, enforcing
 * docs/architecture/authorization-model.md section 8 inside the
 * request's validated-context transaction:
 *
 * <ul>
 *   <li>Self-mutation is refused flat ({@link MutationResult.SelfMutation})
 *   -- section 8 allows an explicitly approved self-service workflow,
 *   and none exists.</li>
 *   <li>The last ACTIVE COMPANY_ADMIN membership can be lost neither
 *   through role removal nor through disabling
 *   ({@link MutationResult.LastAdmin}).</li>
 *   <li>{@code platform.*} and unknown permission keys are treated as
 *   nonexistent -- the same uniform 404 as a cross-tenant probe, so
 *   the tenant surface reveals nothing about the platform namespace.</li>
 *   <li>"Permitted to administer" (MVP interpretation, recorded in the
 *   slice spec): holding members.manage permits administering every
 *   tenant-namespace key.</li>
 * </ul>
 *
 * <p>Every successful mutation writes one audit row via
 * {@link TenantAuditService}. PermissionEvaluationService recomputes
 * per request, so everything done here bites on the target's very next
 * request with no cache to invalidate.
 */
@Service
public class MemberAdminService {

	private static final String PLATFORM_NAMESPACE_PREFIX = "platform.";

	private final TenantMembershipRepository tenantMembershipRepository;
	private final MembershipRoleRepository membershipRoleRepository;
	private final MembershipPermissionOverrideRepository overrideRepository;
	private final ResourceScopeRepository resourceScopeRepository;
	private final BranchRepository branchRepository;
	private final DepartmentRepository departmentRepository;
	private final TenantAuditService tenantAuditService;
	private final TenantSessionVariable tenantSessionVariable;
	private final EntityManager entityManager;

	public MemberAdminService(
			TenantMembershipRepository tenantMembershipRepository,
			MembershipRoleRepository membershipRoleRepository,
			MembershipPermissionOverrideRepository overrideRepository,
			ResourceScopeRepository resourceScopeRepository,
			BranchRepository branchRepository,
			DepartmentRepository departmentRepository,
			TenantAuditService tenantAuditService,
			TenantSessionVariable tenantSessionVariable,
			EntityManager entityManager) {
		this.tenantMembershipRepository = tenantMembershipRepository;
		this.membershipRoleRepository = membershipRoleRepository;
		this.overrideRepository = overrideRepository;
		this.resourceScopeRepository = resourceScopeRepository;
		this.branchRepository = branchRepository;
		this.departmentRepository = departmentRepository;
		this.tenantAuditService = tenantAuditService;
		this.tenantSessionVariable = tenantSessionVariable;
		this.entityManager = entityManager;
	}

	@Transactional(readOnly = true)
	public Optional<List<ResourceScopeView>> listScopes(AuthorizationContext context, Long membershipId) {
		tenantSessionVariable.apply(context.companyId());
		return tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId())
				.map(membership -> resourceScopeRepository.findByMembershipId(membershipId).stream()
						.map(scope -> new ResourceScopeView(scope.getScopeType(), scope.getScopeId()))
						.toList());
	}

	@Transactional
	public MutationResult assignScope(AuthorizationContext context, Long membershipId, AssignScopeRequest request) {
		tenantSessionVariable.apply(context.companyId());
		if (tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId()).isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (membershipId.equals(context.membershipId())) {
			return new MutationResult.SelfMutation();
		}
		if (!scopeTargetResolves(context, request.scopeType(), request.scopeId())) {
			return new MutationResult.NotFound();
		}
		if (resourceScopeRepository.findByMembershipIdAndScopeTypeAndScopeId(
				membershipId, request.scopeType(), request.scopeId()).isPresent()) {
			return new MutationResult.Duplicate();
		}
		try {
			resourceScopeRepository.save(
					new ResourceScope(membershipId, context.companyId(), request.scopeType(), request.scopeId()));
		} catch (DataIntegrityViolationException ex) {
			throw duplicateConflict(ex);
		}
		tenantAuditService.record(context, membershipId, "SCOPE_ASSIGNED", null,
				request.scopeType() + ":" + request.scopeId());
		return new MutationResult.Done();
	}

	@Transactional
	public MutationResult revokeScope(
			AuthorizationContext context, Long membershipId, ResourceScopeType scopeType, Long scopeId) {
		tenantSessionVariable.apply(context.companyId());
		if (tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId()).isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (membershipId.equals(context.membershipId())) {
			return new MutationResult.SelfMutation();
		}
		Optional<ResourceScope> scope =
				resourceScopeRepository.findByMembershipIdAndScopeTypeAndScopeId(membershipId, scopeType, scopeId);
		if (scope.isEmpty()) {
			return new MutationResult.NotFound();
		}
		resourceScopeRepository.delete(scope.get());
		tenantAuditService.record(context, membershipId, "SCOPE_REVOKED", null, scopeType + ":" + scopeId);
		return new MutationResult.Done();
	}

	private boolean scopeTargetResolves(AuthorizationContext context, ResourceScopeType scopeType, Long scopeId) {
		return switch (scopeType) {
			case BRANCH -> branchRepository.findByIdAndCompanyId(scopeId, context.companyId()).isPresent();
			case DEPARTMENT -> departmentRepository.findByIdAndCompanyId(scopeId, context.companyId()).isPresent();
		};
	}

	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public List<MemberView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		Map<Long, List<TenantRole>> rolesByMembership = rolesByMembership(context.companyId());
		List<Object[]> rows = entityManager.createNativeQuery(
				"SELECT m.id, i.phone, m.status FROM tenant_memberships m "
						+ "JOIN identities i ON i.id = m.identity_id "
						+ "WHERE m.company_id = :companyId ORDER BY m.id")
				.setParameter("companyId", context.companyId())
				.getResultList();
		return rows.stream()
				.map(row -> new MemberView(
						((Number) row[0]).longValue(), (String) row[1],
						rolesByMembership.getOrDefault(((Number) row[0]).longValue(), List.of()),
						MembershipStatus.valueOf((String) row[2])))
				.toList();
	}

	@Transactional(readOnly = true)
	public Optional<MemberDetailView> get(AuthorizationContext context, Long membershipId) {
		tenantSessionVariable.apply(context.companyId());
		return tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId())
				.map(membership -> {
					String phone = (String) entityManager.createNativeQuery(
							"SELECT i.phone FROM identities i JOIN tenant_memberships m ON m.identity_id = i.id "
									+ "WHERE m.id = :membershipId")
							.setParameter("membershipId", membershipId)
							.getSingleResult();
					List<TenantRole> roles = membershipRoleRepository.findByMembershipId(membershipId).stream()
							.map(MembershipRoleAssignment::getRole)
							.toList();
					return new MemberDetailView(
							membership.getId(), phone, roles, membership.getStatus(), overridesOf(membershipId));
				});
	}

	@Transactional
	public MutationResult assignRole(AuthorizationContext context, Long membershipId, TenantRole role) {
		tenantSessionVariable.apply(context.companyId());
		Optional<TenantMembership> target = tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId());
		if (target.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (membershipId.equals(context.membershipId())) {
			return new MutationResult.SelfMutation();
		}
		if (membershipRoleRepository.findByMembershipIdAndRole(membershipId, role).isPresent()) {
			return new MutationResult.Duplicate();
		}
		try {
			membershipRoleRepository.save(new MembershipRoleAssignment(membershipId, context.companyId(), role));
		} catch (DataIntegrityViolationException ex) {
			throw duplicateConflict(ex);
		}
		tenantAuditService.record(context, membershipId, "ROLE_ASSIGNED", null, role.name());
		return new MutationResult.Done();
	}

	@Transactional
	public MutationResult removeRole(AuthorizationContext context, Long membershipId, TenantRole role) {
		tenantSessionVariable.apply(context.companyId());
		Optional<TenantMembership> target = tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId());
		if (target.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (membershipId.equals(context.membershipId())) {
			return new MutationResult.SelfMutation();
		}
		Optional<MembershipRoleAssignment> assignment = membershipRoleRepository.findByMembershipIdAndRole(membershipId, role);
		if (assignment.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (role == TenantRole.COMPANY_ADMIN) {
			lockActiveCompanyAdmins(context.companyId());
			if (countOtherActiveCompanyAdmins(context.companyId(), membershipId) == 0) {
				return new MutationResult.LastAdmin();
			}
		}
		membershipRoleRepository.delete(assignment.get());
		tenantAuditService.record(context, membershipId, "ROLE_REMOVED", null, role.name());
		return new MutationResult.Done();
	}

	@Transactional
	public MutationResult grantOverride(AuthorizationContext context, Long membershipId, UpsertOverrideRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Optional<TenantMembership> target = tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId());
		if (target.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (membershipId.equals(context.membershipId())) {
			return new MutationResult.SelfMutation();
		}
		Optional<Long> permissionId = tenantPermissionId(request.permissionKey());
		if (permissionId.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (overrideRepository.findByMembershipIdAndPermissionId(membershipId, permissionId.get()).isPresent()) {
			return new MutationResult.Duplicate();
		}
		try {
			overrideRepository.save(new MembershipPermissionOverride(
					membershipId, context.companyId(), permissionId.get(), request.effect()));
		} catch (DataIntegrityViolationException ex) {
			throw duplicateConflict(ex);
		}
		tenantAuditService.record(context, membershipId, "OVERRIDE_GRANTED", request.permissionKey(),
				request.effect().name());
		return new MutationResult.Done();
	}

	@Transactional
	public MutationResult revokeOverride(AuthorizationContext context, Long membershipId, String permissionKey) {
		tenantSessionVariable.apply(context.companyId());
		Optional<TenantMembership> target = tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId());
		if (target.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (membershipId.equals(context.membershipId())) {
			return new MutationResult.SelfMutation();
		}
		Optional<Long> permissionId = tenantPermissionId(permissionKey);
		if (permissionId.isEmpty()) {
			return new MutationResult.NotFound();
		}
		Optional<MembershipPermissionOverride> override =
				overrideRepository.findByMembershipIdAndPermissionId(membershipId, permissionId.get());
		if (override.isEmpty()) {
			return new MutationResult.NotFound();
		}
		overrideRepository.delete(override.get());
		tenantAuditService.record(context, membershipId, "OVERRIDE_REVOKED", permissionKey, null);
		return new MutationResult.Done();
	}

	@Transactional
	public MutationResult updateStatus(AuthorizationContext context, Long membershipId, MembershipStatus newStatus) {
		tenantSessionVariable.apply(context.companyId());
		Optional<TenantMembership> found = tenantMembershipRepository.findByIdAndCompanyId(membershipId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (membershipId.equals(context.membershipId())) {
			return new MutationResult.SelfMutation();
		}
		TenantMembership target = found.get();
		if (target.getStatus() == newStatus) {
			// Idempotent no-op: no state change, no audit row.
			return new MutationResult.Done();
		}
		if (newStatus == MembershipStatus.DISABLED
				&& membershipRoleRepository.findByMembershipIdAndRole(membershipId, TenantRole.COMPANY_ADMIN).isPresent()) {
			lockActiveCompanyAdmins(context.companyId());
			if (countOtherActiveCompanyAdmins(context.companyId(), membershipId) == 0) {
				return new MutationResult.LastAdmin();
			}
		}
		MembershipStatus before = target.getStatus();
		if (newStatus == MembershipStatus.DISABLED) {
			target.disable();
			tenantAuditService.record(context, membershipId, "MEMBERSHIP_DISABLED", null, "was " + before);
		} else {
			target.activate();
			tenantAuditService.record(context, membershipId, "MEMBERSHIP_REACTIVATED", null, "was " + before);
		}
		return new MutationResult.Done();
	}

	private Map<Long, List<TenantRole>> rolesByMembership(Long companyId) {
		Map<Long, List<TenantRole>> result = new HashMap<>();
		for (MembershipRoleAssignment assignment : membershipRoleRepository.findByCompanyId(companyId)) {
			result.computeIfAbsent(assignment.getMembershipId(), id -> new java.util.ArrayList<>())
					.add(assignment.getRole());
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private List<OverrideView> overridesOf(Long membershipId) {
		List<Object[]> rows = entityManager.createNativeQuery(
				"SELECT p.permission_key, o.effect FROM membership_permission_overrides o "
						+ "JOIN permissions p ON p.id = o.permission_id "
						+ "WHERE o.membership_id = :membershipId ORDER BY o.id")
				.setParameter("membershipId", membershipId)
				.getResultList();
		return rows.stream()
				.map(row -> new OverrideView((String) row[0], OverrideEffect.valueOf((String) row[1])))
				.toList();
	}

	/**
	 * Resolves a permission key to its catalog id, treating the
	 * platform namespace and unknown keys identically as absent --
	 * section 8's structural separation, presented as the same 404.
	 */
	@SuppressWarnings("unchecked")
	private Optional<Long> tenantPermissionId(String permissionKey) {
		if (permissionKey.startsWith(PLATFORM_NAMESPACE_PREFIX)) {
			return Optional.empty();
		}
		List<Number> ids = entityManager.createNativeQuery(
				"SELECT id FROM permissions WHERE permission_key = :key")
				.setParameter("key", permissionKey)
				.getResultList();
		return ids.stream().findFirst().map(Number::longValue);
	}

	/**
	 * A concurrent duplicate raced past the {@code isPresent()} pre-check
	 * and tripped the row's UNIQUE constraint. Thrown (not returned as
	 * {@link MutationResult.Duplicate}) because the failed INSERT has
	 * already marked the transaction rollback-only -- returning normally
	 * would make Spring attempt a commit and surface
	 * {@code UnexpectedRollbackException} as a 500. Same 409 the
	 * pre-check answers; mirrors {@code PayrollBatchService.create}.
	 */
	private static ApiException duplicateConflict(DataIntegrityViolationException ex) {
		return new ApiException(HttpStatus.CONFLICT, MessageKeys.MEMBERS_ALREADY_PRESENT, ex);
	}

	/**
	 * Serializes the last-admin guard against concurrent role-removals
	 * and disables. Under READ COMMITTED a plain COUNT lets two
	 * transactions each see the other as the remaining admin and both
	 * proceed, dropping the company to zero COMPANY_ADMINs. Both guarded
	 * paths first take a row lock over the full active-COMPANY_ADMIN set
	 * (ordered by membership id so concurrent callers acquire it in the
	 * same order and cannot deadlock), so the second call blocks until
	 * the first commits and then counts the committed state.
	 */
	private void lockActiveCompanyAdmins(Long companyId) {
		entityManager.createNativeQuery(
				"SELECT mr.id FROM membership_roles mr "
						+ "JOIN tenant_memberships tm ON tm.id = mr.membership_id "
						+ "WHERE mr.company_id = :companyId AND mr.role = 'COMPANY_ADMIN' "
						+ "AND tm.status = 'ACTIVE' ORDER BY mr.membership_id FOR UPDATE")
				.setParameter("companyId", companyId)
				.getResultList();
	}

	private long countOtherActiveCompanyAdmins(Long companyId, Long excludedMembershipId) {
		Number count = (Number) entityManager.createNativeQuery(
				"SELECT COUNT(*) FROM membership_roles mr "
						+ "JOIN tenant_memberships tm ON tm.id = mr.membership_id "
						+ "WHERE mr.company_id = :companyId AND mr.role = 'COMPANY_ADMIN' "
						+ "AND tm.status = 'ACTIVE' AND mr.membership_id <> :excluded")
				.setParameter("companyId", companyId)
				.setParameter("excluded", excludedMembershipId)
				.getSingleResult();
		return count.longValue();
	}

	public sealed interface MutationResult {

		record NotFound() implements MutationResult {
		}

		/** Section 8: a user cannot change their own role, permissions, or status. */
		record SelfMutation() implements MutationResult {
		}

		/** Section 8: the last active COMPANY_ADMIN cannot be removed or disabled. */
		record LastAdmin() implements MutationResult {
		}

		record Duplicate() implements MutationResult {
		}

		record Done() implements MutationResult {
		}

	}

}
