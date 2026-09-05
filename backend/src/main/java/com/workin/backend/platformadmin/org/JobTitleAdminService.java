package com.workin.backend.platformadmin.org;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The write half of {@code dashboard/pages/job_titles/page.php}.
 *
 * <p>Two rules this page has that the other org pages do not. The department
 * is <b>optional</b> -- a job title can exist without one -- but when given it
 * must belong to the same company, and that check runs for every audience
 * including the administrator, for the same reason the department page's
 * branch check does: pointing at another company's row is a broken link, not
 * cross-company editing.
 *
 * <p>And {@code work_hours} is required and must be strictly positive. Legacy
 * writes that as {@code $workHours !== null && $workHours > 0} inside the same
 * boolean expression as the insert, so a zero or missing value is simply
 * {@code error_required} with the form re-rendered.
 */
@Service
@Profile("phase1-mysql")
public class JobTitleAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code select_company_first}. */
		NO_COMPANY,

		/** {@code error_db}: the row is not this session's to touch. */
		FOREIGN_ROW,

		/** {@code select_company_first_department}: a department from elsewhere. */
		FOREIGN_DEPARTMENT,

		/** {@code error_required}: an empty name, or work hours that are absent or not positive. */
		INVALID_FIELDS
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

	private final JobTitleStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public JobTitleAdminService(JobTitleStore store, PlatformAdminAuditService auditService,
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
	 * {@code (int) ($_POST['department_id'] ?? 0) ?: null} then the ownership
	 * check.
	 *
	 * <p>The {@code ?:} makes 0 mean "none", so an unselected picker and a
	 * posted zero are the same thing, and neither is checked -- only a real id
	 * is.
	 */
	private Long department(Long posted, long companyId) {
		if (posted == null || posted <= 0) {
			return null;
		}
		if (!this.store.departmentBelongsTo(posted, companyId)) {
			throw new RefusedException(Refusal.FOREIGN_DEPARTMENT);
		}
		return posted;
	}

	private static void assertFields(String name, BigDecimal workHours) {
		if (name.isEmpty() || workHours == null || workHours.signum() <= 0) {
			throw new RefusedException(Refusal.INVALID_FIELDS);
		}
	}

	@Transactional
	public long add(
			DashboardSession session, long adminId, boolean factorBound, long postedCompanyId,
			Long departmentId, String rawName, String rawWorkHours) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, 0L);
		// The department is checked before the name and hours, which is
		// legacy's order: it redirects on a foreign department before it ever
		// looks at the payload.
		Long department = department(departmentId, companyId);
		String name = rawName == null ? "" : rawName.trim();
		BigDecimal workHours = JobTitle.workHours(rawWorkHours);
		assertFields(name, workHours);

		long id = this.store.insert(companyId, department, name, workHours);
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"job title created in company " + companyId);
		return companyId;
	}

	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long postedCompanyId, Long departmentId, String rawName, String rawWorkHours,
			boolean active) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, id);
		Long department = department(departmentId, companyId);
		String name = rawName == null ? "" : rawName.trim();
		BigDecimal workHours = JobTitle.workHours(rawWorkHours);
		assertFields(name, workHours);

		this.store.update(id, department, name, workHours, active);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"job title updated in company " + companyId);
		return companyId;
	}

	@Transactional
	public long delete(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long postedCompanyId) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, id);
		this.store.softDelete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"job title deactivated in company " + companyId);
		return companyId;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "job_title", String.valueOf(id), null, detail);
	}

}
