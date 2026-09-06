package com.workin.backend.platformadmin.hr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The write half of {@code dashboard/pages/complaints/page.php}.
 *
 * <p>D-176 does not bite here: no write touches {@code company_id} or
 * {@code employee_id}, and there is no editable foreign key. What this page has
 * instead is a rule about <b>source</b> -- a company-scoped session may change
 * the status only of complaints its <em>employees</em> raised, not of the ones
 * the company raised to the platform.
 *
 * <p>That rule is on {@code set_status} alone. The same session may still
 * <em>reply to</em> and <em>delete</em> a {@code company_support} complaint,
 * which is legacy's asymmetry and is reproduced: the three actions are checked
 * separately in the PHP and only one of them looks at the source.
 */
@Service
@Profile("phase1-mysql")
public class ComplaintAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code error_db}: another company's complaint, or one this session may not restatus. */
		FOREIGN_ROW,

		/** {@code error_required}: a status outside the three the column allows. */
		INVALID_STATUS
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

	private final ComplaintStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public ComplaintAdminService(ComplaintStore store, PlatformAdminAuditService auditService,
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
	 * The visibility check, with this table's own wrinkle: {@code company_id}
	 * is nullable here.
	 *
	 * <p>A complaint with no company belongs to no tenant, so a scoped session
	 * can never reach it and an administrator filtered to a company cannot
	 * either. Only an unfiltered administrator can, which is the same answer
	 * {@code canOpenRow()} gives for every other table -- reached by a
	 * different route because the column can be null.
	 */
	private Complaint assertVisible(DashboardSession session, long id) {
		Complaint row = this.store.find(id);
		if (row == null) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		Long owner = row.companyId();
		if (session.isScopedToOneCompany()) {
			if (owner == null || owner != session.companyId()) {
				throw new RefusedException(Refusal.FOREIGN_ROW);
			}
			return row;
		}
		if (session.companyId() > 0 && (owner == null || owner != session.companyId())) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		return row;
	}

	/**
	 * {@code reply}: the answer plus a status.
	 *
	 * <p>Legacy writes {@code $_POST['status'] ?? 'pending'} unvalidated. The
	 * column is {@code enum('pending','done','closed')} and production runs a
	 * non-strict {@code sql_mode}, so an unrecognised value is stored as the
	 * <b>empty string</b> -- measured, not assumed. A complaint with no status
	 * renders as nothing and matches no filter, so the port validates here as
	 * {@code set_status} already does (<b>R-048</b>).
	 */
	@Transactional
	public long reply(
			DashboardSession session, long adminId, boolean factorBound, long id, String reply,
			String status) {
		gate(factorBound);
		Complaint row = assertVisible(session, id);

		String resolved = status == null || status.isBlank() ? "pending" : status.trim();
		if (!Complaint.isValidStatus(resolved)) {
			throw new RefusedException(Refusal.INVALID_STATUS);
		}

		this.store.reply(id, blankToNull(reply), resolved);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"complaint answered in company " + row.companyId());
		return row.companyId() == null ? 0L : row.companyId();
	}

	/**
	 * {@code set_status}: the one action that looks at {@code source}.
	 *
	 * <p>A company-scoped session may restatus only its employees' complaints.
	 * The ones it raised to the platform are the platform's to move, which is
	 * why an administrator is not held to the same test.
	 */
	@Transactional
	public long setStatus(
			DashboardSession session, long adminId, boolean factorBound, long id, String status) {
		gate(factorBound);
		if (!Complaint.isValidStatus(status)) {
			throw new RefusedException(Refusal.INVALID_STATUS);
		}
		Complaint row = assertVisible(session, id);
		if (session.isScopedToOneCompany() && !row.fromEmployee()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}

		this.store.setStatus(id, status);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"complaint status set to " + status + " in company " + row.companyId());
		return row.companyId() == null ? 0L : row.companyId();
	}

	@Transactional
	public long delete(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		Complaint row = assertVisible(session, id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"complaint deleted in company " + row.companyId());
		return row.companyId() == null ? 0L : row.companyId();
	}

	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "complaint", String.valueOf(id), null, detail);
	}

}
