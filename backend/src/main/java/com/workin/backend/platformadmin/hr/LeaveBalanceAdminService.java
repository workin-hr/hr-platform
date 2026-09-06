package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The write half of {@code dashboard/pages/leave_balances/page.php}.
 *
 * <h2>This is where R-046 diverges</h2>
 * <p>Legacy checks the employee's company carefully on {@code add_leave} and
 * then writes {@code edit_leave} and {@code delete_leave} with
 * {@code WHERE id = ?} and no check at all -- so any company owner or
 * permitted HR employee could edit or destroy another company's row by posting
 * its id. <b>That is not reproduced.</b> Every action here resolves the row's
 * owning company first and refuses when it is not the one being acted on.
 *
 * <p>Recorded as an explicit exception to the faithful-reproduction rule in
 * <b>R-046</b>, together with the patch that closes the same hole in the PHP.
 * The only behaviour it changes is refusing an operation that should never
 * have succeeded.
 */
@Service
@Profile("phase1-mysql")
public class LeaveBalanceAdminService {

	/** {@code (float) ($_POST['total_days'] ?? 15)} -- the form's own default. */
	private static final BigDecimal DEFAULT_TOTAL_DAYS = new BigDecimal("15");

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code select_company_first}. */
		NO_COMPANY,

		/** {@code error_required}: no employee, or one that does not exist. */
		NO_EMPLOYEE,

		/** {@code error_db}: the row, or the employee, belongs to another company. */
		FOREIGN_ROW
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

	private final LeaveBalanceStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public LeaveBalanceAdminService(LeaveBalanceStore store, PlatformAdminAuditService auditService,
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
	 * R-046's check: the row belongs to the company being acted on.
	 *
	 * <p>The rule is {@code DashboardOrgScope.canOpenRow()}'s, restated for a
	 * row whose company comes from a join rather than a column: a scoped
	 * session may touch only its own, an administrator only what their filter
	 * admits, and an unfiltered administrator anything.
	 */
	private void assertRowVisible(DashboardSession session, long id) {
		Long owner = this.store.companyOf(id);
		if (owner == null) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		// DashboardSession.companyId() is the session's own company when it is
		// scoped and the administrator's current filter otherwise, so one
		// comparison covers both -- with 0 meaning "every company", which only
		// an administrator can hold.
		if (session.isScopedToOneCompany()) {
			if (owner != session.companyId()) {
				throw new RefusedException(Refusal.FOREIGN_ROW);
			}
			return;
		}
		if (session.companyId() > 0 && owner != session.companyId()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
	}

	/**
	 * {@code add_leave}'s three checks, in legacy's own order: the employee
	 * must exist, a scoped session's employee must be its own, and a filtered
	 * administrator's employee must be inside the filter.
	 */
	@Transactional
	public long add(
			DashboardSession session, long adminId, boolean factorBound, long employeeId,
			int year, String rawTotalDays) {
		gate(factorBound);
		if (employeeId <= 0) {
			throw new RefusedException(Refusal.NO_EMPLOYEE);
		}
		Long employeeCompany = this.store.employeeCompany(employeeId);
		if (employeeCompany == null) {
			throw new RefusedException(Refusal.NO_EMPLOYEE);
		}
		if (session.isScopedToOneCompany() && employeeCompany != session.companyId()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		if (!session.isScopedToOneCompany() && session.companyId() > 0
				&& employeeCompany != session.companyId()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}

		long id = this.store.insert(
				employeeId, year, LeaveBalance.days(rawTotalDays, DEFAULT_TOTAL_DAYS));
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"leave balance created for employee " + employeeId + " in company " + employeeCompany);
		return employeeCompany;
	}

	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id,
			String rawTotalDays, String rawUsedDays) {
		gate(factorBound);
		assertRowVisible(session, id);
		Long owner = this.store.companyOf(id);

		this.store.update(id,
				LeaveBalance.days(rawTotalDays, BigDecimal.ZERO),
				LeaveBalance.days(rawUsedDays, BigDecimal.ZERO));
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"leave balance updated in company " + owner);
		return owner == null ? 0L : owner;
	}

	@Transactional
	public long delete(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		assertRowVisible(session, id);
		Long owner = this.store.companyOf(id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"leave balance deleted in company " + owner);
		return owner == null ? 0L : owner;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(
				adminId, type, "leave_balance", String.valueOf(id), null, detail);
	}

}
