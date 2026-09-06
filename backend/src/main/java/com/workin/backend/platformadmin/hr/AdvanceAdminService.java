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

/**
 * The write half of {@code dashboard/pages/advances/page.php}.
 *
 * <p>The most stateful of the HR pages: an advance carries a {@code remaining}
 * balance that an edit has to adjust rather than overwrite, and three of its
 * six actions are only legal from a particular status.
 *
 * <p>D-176 applies to the edit: the employee may be reassigned, but only
 * within the company that already owns the row -- an advance has no
 * {@code company_id}, so reassigning the employee <em>is</em> moving the debt.
 */
@Service
@Profile("phase1-mysql")
public class AdvanceAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code error_db}: the row or the employee belongs to another company. */
		FOREIGN_ROW,

		/** {@code error_required}: no employee, a non-positive amount, or a settled/decided row. */
		INVALID,

		/** {@code rejection_reason_required}: a rejection must say why. */
		REASON_REQUIRED
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

	private final AdvanceStore store;

	private final PlatformAdminAuditService auditService;

	private final LegacyClock clock;

	private final boolean actionsEnabled;

	public AdvanceAdminService(AdvanceStore store, PlatformAdminAuditService auditService,
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

	/** D-176: against the row's company, which no filter setting can widen. */
	private void assertEmployeeInCompany(long employeeId, long companyId) {
		Long company = this.store.employeeCompany(employeeId);
		if (company == null) {
			throw new RefusedException(Refusal.INVALID);
		}
		if (company != companyId) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
	}

	@Transactional
	public long add(
			DashboardSession session, long adminId, boolean factorBound, long employeeId,
			String rawAmount, String reason, String requestDate) {
		gate(factorBound);
		BigDecimal amount = Advance.amount(rawAmount);
		if (employeeId <= 0 || amount.signum() <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		long companyId = assertEmployeeVisible(session, employeeId);

		long id = this.store.insert(
				employeeId, amount, blankToNull(reason), dateOrToday(requestDate));
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"advance of " + amount + " created for employee " + employeeId
						+ " in company " + companyId);
		return companyId;
	}

	/**
	 * Refused on a settled advance -- rejected, or approved with nothing left
	 * to repay. There is no outstanding amount for a change to mean anything
	 * about.
	 */
	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id, long employeeId,
			String rawAmount, String reason, String requestDate) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		Advance existing = this.store.byId(id);
		if (existing == null || existing.isSettled()) {
			throw new RefusedException(Refusal.INVALID);
		}

		BigDecimal amount = Advance.amount(rawAmount);
		if (employeeId <= 0 || amount.signum() <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		assertEmployeeInCompany(employeeId, companyId);

		BigDecimal remaining = Advance.remainingAfterEdit(
				existing.status(), existing.remaining(), existing.amount(), amount);
		this.store.update(id, employeeId, amount, remaining,
				blankToNull(reason), dateOrToday(requestDate));
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"advance updated to " + amount + " in company " + companyId
						+ ", outstanding " + remaining);
		return companyId;
	}

	/** Only from pending: an advance is decided once. */
	@Transactional
	public long approve(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);
		requirePending(id);

		this.store.approve(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"advance approved in company " + companyId);
		return companyId;
	}

	/** Only from pending, and the reason is not optional. */
	@Transactional
	public long reject(
			DashboardSession session, long adminId, boolean factorBound, long id,
			String rejectionReason) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		// Checked before the status, which is legacy's order: an empty reason
		// on an already-decided advance says the reason is missing.
		String reason = rejectionReason == null ? "" : rejectionReason.trim();
		if (reason.isEmpty()) {
			throw new RefusedException(Refusal.REASON_REQUIRED);
		}
		requirePending(id);

		this.store.reject(id, reason);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"advance rejected in company " + companyId);
		return companyId;
	}

	/**
	 * {@code mark_paid}: approved with nothing outstanding.
	 *
	 * <p>Legacy checks no status at all here, so a pending advance can be
	 * marked paid and becomes approved in the same statement. Reproduced: an
	 * operator recording a repayment made outside the system is the case it
	 * exists for.
	 */
	@Transactional
	public long markPaid(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.markPaid(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"advance marked fully repaid in company " + companyId);
		return companyId;
	}

	@Transactional
	public long delete(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"advance deleted in company " + companyId);
		return companyId;
	}

	private void requirePending(long id) {
		Advance existing = this.store.byId(id);
		if (existing == null || !existing.isPending()) {
			throw new RefusedException(Refusal.INVALID);
		}
	}

	private String dateOrToday(String raw) {
		return raw == null || raw.isBlank() ? this.clock.today().toString() : raw.trim();
	}

	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "advance", String.valueOf(id), null, detail);
	}

}
