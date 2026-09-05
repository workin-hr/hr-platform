package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.payroll.LegacyPenaltyDays;

/**
 * The write half of {@code dashboard/pages/penalties/page.php}.
 *
 * <p>The rule that makes this page different: a penalty {@code applied_to_payroll}
 * is <b>frozen</b>. Legacy refuses to edit it, and rightly -- payroll has
 * already deducted against those days, and changing them would leave a payslip
 * that no longer agrees with the row it was computed from. Deleting one is
 * still allowed, which is legacy's choice and not obviously the right one; it
 * is reproduced rather than tightened, because tightening it would refuse an
 * operation operators may rely on and this port is not the place to decide
 * that.
 *
 * <p>R-046: {@code mark_applied} and {@code delete_penalty} wrote by id alone.
 * Both are guarded here.
 */
@Service
@Profile("phase1-mysql")
public class PenaltyAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code error_db}: the row or the employee belongs to another company. */
		FOREIGN_ROW,

		/** {@code error_required}: no employee, no type, or an already-applied row. */
		INVALID,

		/** {@code penalty_days_invalid}: not one of the seven allowed values. */
		BAD_DAYS
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

	private final PenaltyStore store;

	private final PlatformAdminAuditService auditService;

	private final LegacyClock clock;

	private final boolean actionsEnabled;

	public PenaltyAdminService(PenaltyStore store, PlatformAdminAuditService auditService,
			LegacyClock clock,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.clock = clock;
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

	/** The employee must exist and be inside whatever company is in scope. */
	private long assertEmployeeVisible(DashboardSession session, long employeeId) {
		Long company = this.store.employeeCompany(employeeId);
		if (company == null) {
			throw new RefusedException(Refusal.INVALID);
		}
		if (session.isScopedToOneCompany() && company != session.companyId()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		if (!session.isScopedToOneCompany() && session.companyId() > 0
				&& company != session.companyId()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		return company;
	}

	/** {@code dashboard_penalty_days_resolve()}: one of seven values, or refused. */
	private static BigDecimal days(String raw) {
		Double normalized = LegacyPenaltyDays.normalize(phpFloat(raw));
		if (normalized == null) {
			throw new RefusedException(Refusal.BAD_DAYS);
		}
		return BigDecimal.valueOf(normalized);
	}

	@Transactional
	public long add(
			DashboardSession session, long adminId, boolean factorBound, long employeeId,
			String penaltyType, String rawDays, String reason, String penaltyDate) {
		gate(factorBound);
		String type = penaltyType == null ? "" : penaltyType.trim();
		if (employeeId <= 0 || type.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		long companyId = assertEmployeeVisible(session, employeeId);
		BigDecimal penaltyDays = days(rawDays);

		long id = this.store.insert(
				employeeId, type, penaltyDays, blankToNull(reason), dateOrToday(penaltyDate));
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"penalty created for employee " + employeeId + " in company " + companyId);
		return companyId;
	}

	/**
	 * {@code edit_penalty}: refused outright once the row has reached payroll.
	 *
	 * <p>Legacy checks that <b>before</b> anything else about the payload, so
	 * an applied row cannot be edited even with a valid form.
	 */
	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id, long employeeId,
			String penaltyType, String rawDays, String reason, String penaltyDate) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		Boolean applied = this.store.appliedToPayroll(id);
		if (applied == null || applied) {
			throw new RefusedException(Refusal.INVALID);
		}

		String type = penaltyType == null ? "" : penaltyType.trim();
		if (employeeId <= 0 || type.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		// The employee can be changed by the edit, so the new one is checked
		// too -- otherwise an edit could move a penalty to another company.
		assertEmployeeVisible(session, employeeId);
		BigDecimal penaltyDays = days(rawDays);

		this.store.update(
				id, employeeId, type, penaltyDays, blankToNull(reason), dateOrToday(penaltyDate));
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"penalty updated in company " + companyId);
		return companyId;
	}

	@Transactional
	public long markApplied(
			DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.markApplied(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"penalty marked applied to payroll in company " + companyId);
		return companyId;
	}

	@Transactional
	public long delete(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"penalty deleted in company " + companyId);
		return companyId;
	}

	/** {@code $_POST['penalty_date'] ?? date('Y-m-d')}: absent means today. */
	private String dateOrToday(String raw) {
		return raw == null || raw.isBlank() ? this.clock.today().toString() : raw.trim();
	}

	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** {@code (float) $raw}: the leading number, 0 for anything else. */
	private static double phpFloat(String raw) {
		if (raw == null) {
			return 0d;
		}
		String trimmed = raw.trim();
		int end = 0;
		if (end < trimmed.length() && (trimmed.charAt(end) == '+' || trimmed.charAt(end) == '-')) {
			end++;
		}
		while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
			end++;
		}
		if (end < trimmed.length() && trimmed.charAt(end) == '.') {
			end++;
			while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
				end++;
			}
		}
		String number = trimmed.substring(0, end);
		if (number.isEmpty() || "+".equals(number) || "-".equals(number) || ".".equals(number)) {
			return 0d;
		}
		try {
			return Double.parseDouble(number);
		} catch (NumberFormatException ex) {
			return 0d;
		}
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "penalty", String.valueOf(id), null, detail);
	}

}
