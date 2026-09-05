package com.workin.backend.platformadmin.hr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The write half of {@code dashboard/pages/workforce_planning/page.php}.
 *
 * <p>The fullest <b>D-176</b> case on this surface, and the one that shows why
 * the invariant is stated the way it is. The table owns its tenant by a
 * {@code company_id} column <i>and</i> points at three rows that each belong to
 * a company. Legacy resolves one company for the whole payload with
 * {@code $resolveWpCompanyId()} -- for an administrator with no filter, the
 * <b>posted</b> value -- writes it into {@code company_id}, and validates all
 * three foreign keys against it. Every part therefore agrees with every other
 * part, and the row lands in another tenant looking entirely native to it
 * (<b>R-047</b>). Nothing dangles, which is exactly what makes it hard to
 * notice afterwards.
 *
 * <p>Here the row's own company is read first and is the only company anything
 * is validated against. The three keys stay editable -- re-pointing a plan
 * within its company is the page's normal use -- but none of them can leave it.
 *
 * <p>Legacy's guard on both write paths is {@code if ($cid > 0 &&
 * $payload['company_id'] !== $cid)}, which does nothing at all when {@code $cid}
 * is zero. That is the unfiltered administrator, and it is the case that
 * matters.
 */
@Service
@Profile("phase1-mysql")
public class WorkforcePlanAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/**
		 * {@code error_required}: no company, a missing branch or job title, or
		 * a foreign key that does not belong to the row's company.
		 */
		INVALID,

		/** {@code error_db}: the row is not this session's to touch. */
		FOREIGN_ROW,

		/**
		 * {@code already_exists}: the company already plans this branch,
		 * department and job title (<b>R-050</b>).
		 */
		DUPLICATE_TARGET
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

	private final WorkforcePlanStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public WorkforcePlanAdminService(
			WorkforcePlanStore store, PlatformAdminAuditService auditService,
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

	/**
	 * {@code hr_verify_post_row('workforce_planning', ...)} -- the guard the
	 * patched legacy page now carries and the unpatched one did not
	 * (<b>R-046</b>). Returns the row's own company, which is the only company
	 * the rest of an edit is allowed to consult.
	 */
	private long assertRowVisible(DashboardSession session, long id) {
		Long owner = this.store.companyOf(id);
		if (owner == null) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		if (session.isScopedToOneCompany()) {
			if (owner != session.companyId()) {
				throw new RefusedException(Refusal.FOREIGN_ROW);
			}
			return owner;
		}
		if (session.companyId() > 0 && owner != session.companyId()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		return owner;
	}

	/**
	 * {@code $resolveWpCompanyId()}. Create path only: a row that does not
	 * exist yet has no tenant to preserve, so the posted company is a genuine
	 * choice rather than a way to move something.
	 */
	private static long companyForCreate(DashboardSession session, long postedCompanyId) {
		if (session.isScopedToOneCompany()) {
			return session.companyId();
		}
		return postedCompanyId > 0 ? postedCompanyId : session.companyId();
	}

	/**
	 * {@code $validateWpPayload()}, with the company fixed by the caller rather
	 * than resolved here.
	 *
	 * <p>The department is the one optional key: zero means "no department" and
	 * legacy's {@code org_department_belongs_to_company()} returns true for it
	 * early. The other two are required and must both be <b>active</b> rows of
	 * this company -- an archived branch is not a valid target even for its
	 * owner.
	 */
	private void assertPayloadWithinCompany(
			long companyId, long branchId, long departmentId, long jobTitleId) {
		if (companyId <= 0 || branchId <= 0 || jobTitleId <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		if (!this.store.branchInCompany(branchId, companyId)
				|| !this.store.departmentInCompany(departmentId, companyId)
				|| !this.store.jobTitleInCompany(jobTitleId, companyId)) {
			throw new RefusedException(Refusal.INVALID);
		}
	}

	/** {@code max(0, ...)}: legacy floors a negative planned count at zero. */
	private static int plannedOf(int rawPlannedCount) {
		return Math.max(0, rawPlannedCount);
	}

	@Transactional
	public long add(
			DashboardSession session, long adminId, boolean factorBound, long postedCompanyId,
			long branchId, long departmentId, long jobTitleId, int rawPlannedCount) {
		gate(factorBound);
		long companyId = companyForCreate(session, postedCompanyId);
		assertPayloadWithinCompany(companyId, branchId, departmentId, jobTitleId);
		assertTargetFree(companyId, branchId, departmentId, jobTitleId, 0);

		long id = this.store.insert(
				companyId, branchId, departmentId, jobTitleId, plannedOf(rawPlannedCount));
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"workforce target planned in company " + companyId);
		return companyId;
	}

	/**
	 * The edit writes the three keys and the planned count. <b>Not</b> the
	 * company: see {@link WorkforcePlanStore#update}.
	 *
	 * <p>Every key is validated against {@code companyId} as read from the
	 * existing row, so a same-company re-point succeeds and a cross-company one
	 * is refused as {@code INVALID} -- there is no posted company in this method
	 * at all, which is the point.
	 */
	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long branchId, long departmentId, long jobTitleId, int rawPlannedCount) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);
		assertPayloadWithinCompany(companyId, branchId, departmentId, jobTitleId);
		assertTargetFree(companyId, branchId, departmentId, jobTitleId, id);

		this.store.update(id, branchId, departmentId, jobTitleId, plannedOf(rawPlannedCount));
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"workforce target updated in company " + companyId);
		return companyId;
	}

	@Transactional
	public long delete(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"workforce target deleted in company " + companyId);
		return companyId;
	}

	/**
	 * <b>R-050</b>: legacy writes straight through the table's unique key and
	 * lets the constraint violation escape as an uncaught {@code PDOException}.
	 * The constraint is still what guarantees uniqueness -- this only turns
	 * that crash into a refusal the page can render.
	 */
	private void assertTargetFree(
			long companyId, long branchId, long departmentId, long jobTitleId, long exceptId) {
		Long existing = this.store.findTarget(companyId, branchId, departmentId, jobTitleId);
		if (existing != null && existing != exceptId) {
			throw new RefusedException(Refusal.DUPLICATE_TARGET);
		}
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(
				adminId, type, "workforce_planning", String.valueOf(id), null, detail);
	}

}
