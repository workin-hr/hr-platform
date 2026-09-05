package com.workin.backend.platformadmin.org;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The write half of {@code dashboard/pages/departments/page.php}.
 *
 * <p>Same gates and same tenant rule as {@link BranchAdminService}, plus the
 * one thing this page has that branches does not: a department must be
 * attached to at least one branch, and every branch it names must belong to
 * the same company. That second half is the real check -- without it a form
 * post could attach a department to another company's branch, which is a
 * cross-tenant write dressed as a checkbox.
 *
 * <p>The branch links are written in the same transaction as the department
 * row. Legacy writes them in two untransacted statements, so a failure between
 * them leaves a department with no branches -- exactly the state the
 * validation exists to prevent.
 */
@Service
@Profile("phase1-mysql")
public class DepartmentAdminService {

	/** Why an action was refused. */
	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code select_company_first}. */
		NO_COMPANY,

		/** {@code error_db}: the row is not this session's to touch. */
		FOREIGN_ROW,

		/** {@code error_required}: an empty name. */
		NAME_REQUIRED,

		/** {@code select_at_least_one_branch}: none given, or one from another company. */
		BAD_BRANCHES
	}

	public static class RefusedException extends RuntimeException {

		private final transient Refusal refusal;

		public RefusedException(Refusal refusal) {
			super(refusal.name());
			this.refusal = refusal;
		}

		public Refusal refusal() {
			return this.refusal;
		}
	}

	private final DepartmentStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public DepartmentAdminService(DepartmentStore store, PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	private void gate(boolean factorBound) {
		if (!this.actionsEnabled) {
			throw new RefusedException(Refusal.ACTIONS_DISABLED);
		}
		if (!factorBound) {
			throw new RefusedException(Refusal.FACTOR_NOT_BOUND);
		}
	}

	private long assertWritable(DashboardSession session, long postedCompanyId, long rowId) {
		long companyId = session.isScopedToOneCompany()
				? session.companyId()
				: (postedCompanyId > 0 ? postedCompanyId : session.companyId());
		if (companyId <= 0) {
			throw new RefusedException(Refusal.NO_COMPANY);
		}
		if (session.isScopedToOneCompany() && rowId > 0 && !this.store.belongsTo(rowId, companyId)) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		return companyId;
	}

	/**
	 * {@code org_department_validate_branches_for_company()}: at least one, and
	 * every one of them this company's.
	 *
	 * <p>Checked for <b>every</b> audience, administrator included -- unlike
	 * the row check above. Attaching a department to another company's branch
	 * is not the administrator's cross-company mode, it is a broken link
	 * between two companies' data, and legacy refuses it for everyone.
	 */
	private void assertBranches(List<Long> branchIds, long companyId) {
		if (branchIds.isEmpty()) {
			throw new RefusedException(Refusal.BAD_BRANCHES);
		}
		for (Long branchId : branchIds) {
			if (!this.store.branchBelongsTo(branchId, companyId)) {
				throw new RefusedException(Refusal.BAD_BRANCHES);
			}
		}
	}

	private static String requireName(String raw) {
		String name = raw == null ? "" : raw.trim();
		if (name.isEmpty()) {
			throw new RefusedException(Refusal.NAME_REQUIRED);
		}
		return name;
	}

	@Transactional
	public long add(
			DashboardSession session, long adminId, boolean factorBound, long postedCompanyId,
			String rawName, List<Long> branchIds) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, 0L);
		// The name is checked before the branches, which is legacy's order and
		// therefore which message an operator sees when both are wrong.
		String name = requireName(rawName);
		assertBranches(branchIds, companyId);

		long id = this.store.insert(companyId, name);
		this.store.syncBranches(id, branchIds);
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"department created in company " + companyId + " across " + branchIds.size()
						+ " branch(es)");
		return companyId;
	}

	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long postedCompanyId, String rawName, List<Long> branchIds, boolean active) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, id);
		String name = requireName(rawName);
		assertBranches(branchIds, companyId);

		this.store.update(id, name, active);
		this.store.syncBranches(id, branchIds);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"department updated in company " + companyId);
		return companyId;
	}

	@Transactional
	public long delete(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long postedCompanyId) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, id);
		// The branch links are left alone: deactivating is not detaching, and
		// reactivating the department has to bring its branches back with it.
		this.store.softDelete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"department deactivated in company " + companyId);
		return companyId;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "department", String.valueOf(id), null, detail);
	}

}
