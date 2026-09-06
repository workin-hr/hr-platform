package com.workin.backend.platformadmin.org;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The write half of {@code dashboard/pages/shifts/page.php}.
 *
 * <p>The simplest of the four: the only rule is a non-empty name. The times
 * are stored as posted with no validation at all -- not an omission here, an
 * accurate copy. Legacy defaults them only when the field is <b>absent</b>,
 * and an empty box reaches MariaDB as {@code ''}, which non-strict mode
 * coerces to midnight. Adding a check would refuse rows the live system holds.
 */
@Service
@Profile("phase1-mysql")
public class ShiftAdminService {

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
		NAME_REQUIRED
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

	private final ShiftStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public ShiftAdminService(ShiftStore store, PlatformAdminAuditService auditService,
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
			String rawName, String startTime, String endTime) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, 0L);
		String name = requireName(rawName);

		long id = this.store.insert(companyId, name,
				Shift.timeOr(startTime, "08:00"), Shift.timeOr(endTime, "16:00"));
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"shift created in company " + companyId);
		return companyId;
	}

	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long postedCompanyId, String rawName, String startTime, String endTime, boolean active) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, id);
		String name = requireName(rawName);

		this.store.update(id, name,
				Shift.timeOr(startTime, "08:00"), Shift.timeOr(endTime, "16:00"), active);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"shift updated in company " + companyId);
		return companyId;
	}

	@Transactional
	public long delete(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long postedCompanyId) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, id);
		// Employee assignments point at the shift and are left alone, the same
		// as everywhere else on these pages: deactivating is not unassigning.
		this.store.softDelete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"shift deactivated in company " + companyId);
		return companyId;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "shift", String.valueOf(id), null, detail);
	}

}
