package com.workin.backend.platformadmin.hr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardAccess;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * Accepting and rejecting one join request
 * ({@code home_handle_join_post()} and the two functions behind it).
 *
 * <p>Legacy gets the tenant rule right here, which is worth saying because so
 * many of its sibling pages do not: {@code home_can_manage_join_employee()}
 * loads the row first and compares the session's company against <b>the row's
 * own</b> {@code company_id}, refusing a row that has none. That is D-176's
 * second half, and it is reproduced rather than replaced.
 *
 * <p><b>Rejecting deletes the employee.</b> Both this surface and
 * {@code company_join_requests/reject.php} do it, so {@code rejected} is a
 * status the column allows and nothing ever writes. The only thing standing
 * between the reject button and a working employee's record is the pending
 * check, which is therefore not a formality.
 */
@Service
public class JoinRequestAdminService {

	public enum Refusal {
		/** The surface-wide write switch is off. */
		ACTIONS_DISABLED,
		/** No second factor bound to this administrator. */
		/** No such row, not an employee, or another company's. */
		FOREIGN_ROW,
		/**
		 * Already accepted, so there is nothing to decide.
		 *
		 * <p>Separate from {@code FOREIGN_ROW} on purpose: on the reject path
		 * this is the difference between refusing a decided request and
		 * deleting a working employee.
		 */
		NOT_PENDING,
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

	private final JoinRequestStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public JoinRequestAdminService(
			JoinRequestStore store, PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	private void gate() {
		if (!this.actionsEnabled) {
			throw new RefusedException(Refusal.ACTIONS_DISABLED);
		}
	}

	/**
	 * {@code home_can_manage_join_employee()}: the permission, then the row's
	 * own company.
	 *
	 * <p>The zero-company refusal is legacy's and is kept. An employee row
	 * with no company cannot be shown to belong to the session's, so the
	 * safe answer is no -- including for an administrator, who is otherwise
	 * allowed across companies.
	 */
	private JoinRequest visible(DashboardSession session, long id) {
		if (!DashboardAccess.canViewHrSection(session, "employees")) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		JoinRequest row = this.store.find(id);
		if (row == null || row.companyId() <= 0) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		if (session.isAdmin()) {
			return row;
		}
		if (!session.isScopedToOneCompany() || session.companyId() != row.companyId()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		return row;
	}

	private JoinRequest pending(DashboardSession session, long id) {
		JoinRequest row = visible(session, id);
		if (!row.pending()) {
			throw new RefusedException(Refusal.NOT_PENDING);
		}
		return row;
	}

	/** {@code accept_join}: the request is approved and the employee activated. */
	@Transactional
	public long accept(DashboardSession session, long adminId, long id) {
		gate();
		JoinRequest row = pending(session, id);
		this.store.accept(row.id());
		// The generic ORG_* types, as the other HR services use. A pair of
		// join-request-specific values would widen an enum the audit column
		// constrains, for a distinction the detail line already carries.
		this.auditService.recordAction(
				adminId, PlatformAdminAuditEventType.ORG_UPDATED, "employees",
				String.valueOf(row.id()),
				"join request accepted for company " + row.companyId());
		return row.id();
	}

	/**
	 * {@code reject_join}: the employee row is deleted.
	 *
	 * <p>Audited before the delete rather than after, because afterwards there
	 * is no row to describe.
	 */
	@Transactional
	public long reject(DashboardSession session, long adminId, long id) {
		gate();
		JoinRequest row = pending(session, id);
		this.auditService.recordAction(
				adminId, PlatformAdminAuditEventType.ORG_DELETED, "employees",
				String.valueOf(row.id()),
				"join request rejected, employee row deleted, company " + row.companyId());
		this.store.reject(row.id());
		return row.id();
	}

}
