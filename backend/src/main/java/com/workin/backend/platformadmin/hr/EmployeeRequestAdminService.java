package com.workin.backend.platformadmin.hr;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;
import com.workin.legacy.LegacyClock;

/**
 * The write half of {@code dashboard/pages/requests/page.php}, reproducing
 * {@code request_actions_dashboard.php}.
 *
 * <h2>Why this is not {@code LegacyRequestApprovalService}</h2>
 * <p>The API has an approval service for the same table and it looks reusable:
 * it takes a company id, scopes the fetch by it, deducts leave and writes
 * attendance exceptions. It is <b>not</b> reused, and the reason is one line
 * of it -- {@code pushDelivery.sendToEmployee(...)}.
 *
 * <p>{@code request_approve()} (the API's) notifies the employee's phone.
 * {@code dashboard_request_approve()} (this one) does not: there is no push,
 * no notification row, nothing. They are two different functions in two
 * different files that happen to write the same table. Reusing the API's here
 * would send a real push to a real employee on a path that has never sent one
 * -- and on a box holding a copy of production, to a real person.
 *
 * <p>The other difference is smaller and points the same way: the API throws
 * on an insufficient balance, while the dashboard returns false and the page
 * flashes {@code insufficient_leave_balance} with nothing written.
 *
 * <h2>R-046</h2>
 * <p>Legacy scopes {@code approve} properly and leaves {@code reject} and
 * {@code delete} writing by id alone. Both are guarded here.
 */
@Service
@Profile("phase1-mysql")
public class EmployeeRequestAdminService {

	/** {@code REQ_PENDING}, {@code REQ_APPROVED}, {@code REQ_REJECTED}. */
	private static final String PENDING = "pending";

	private static final String APPROVED = "approved";

	private static final String REJECTED = "rejected";

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code error_db}: the row belongs to another company, or is gone. */
		FOREIGN_ROW,

		/** {@code insufficient_leave_balance}: the type deducts and the balance will not cover it. */
		INSUFFICIENT_BALANCE,

		/** {@code error_required}: already decided, so there is nothing to approve. */
		NOT_PENDING
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

	private final EmployeeRequestStore store;

	private final PlatformAdminAuditService auditService;

	private final LegacyClock clock;

	private final boolean actionsEnabled;

	public EmployeeRequestAdminService(
			EmployeeRequestStore store, PlatformAdminAuditService auditService, LegacyClock clock,
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

	/** R-046's check, in the shape {@code DashboardOrgScope.canOpenRow()} states. */
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
	 * {@code dashboard_request_approve()}: check the balance <em>before</em>
	 * deciding, then decide, then apply the type's side effects.
	 *
	 * <p>The order matters. A request whose type deducts leave and whose
	 * employee cannot cover it is refused with nothing written -- not approved
	 * and then failed halfway.
	 */
	@Transactional
	public long approve(
			DashboardSession session, long adminId, boolean factorBound, long id, String reply) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		EmployeeRequest request = this.store.forApproval(id, companyId);
		if (request == null) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		if (!PENDING.equals(request.status())) {
			throw new RefusedException(Refusal.NOT_PENDING);
		}

		int days = EmployeeRequest.inclusiveDays(request.fromDate(), request.toDate());
		int year = EmployeeRequest.yearOf(request.fromDate());
		if (request.deductBalance() && insufficient(request.employeeId(), days, year)) {
			throw new RefusedException(Refusal.INSUFFICIENT_BALANCE);
		}

		this.store.decide(id, APPROVED, blankToNull(reply), now());
		applySideEffects(request, companyId, days, year);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"request approved in company " + companyId);
		return companyId;
	}

	/**
	 * {@code dashboard_request_insufficient_leave_balance()}: an employee with
	 * <b>no balance row for the year</b> is never insufficient.
	 *
	 * <p>That is not an oversight to correct. A missing row means the year was
	 * never granted, and the deduction below creates it -- so refusing here
	 * would make the first leave request of a new year impossible to approve.
	 */
	private boolean insufficient(long employeeId, int days, int year) {
		if (!this.store.leaveBalanceExists(employeeId, year)) {
			return false;
		}
		return this.store.availableLeaveDays(employeeId, year) < days;
	}

	/** {@code dashboard_request_apply_approval_side_effects()}. */
	private void applySideEffects(EmployeeRequest request, long companyId, int days, int year) {
		if (request.employeeId() <= 0
				|| request.fromDate() == null || request.fromDate().isBlank()
				|| request.toDate() == null || request.toDate().isBlank()) {
			return;
		}
		if (request.deductBalance()) {
			this.store.applyLeaveDeduction(request.employeeId(), days, year);
		}
		if (request.addAttendanceException()) {
			applyAttendanceExceptions(request, companyId);
		}
	}

	/**
	 * {@code dashboard_request_apply_attendance_exceptions()}: one row per day
	 * of the span, skipping days the employee already has attendance on.
	 *
	 * <p>A company with no active exception type writes nothing at all rather
	 * than failing -- the resolver returns 0 and this returns.
	 */
	private void applyAttendanceExceptions(EmployeeRequest request, long companyId) {
		long typeId = this.store.resolveExceptionType(companyId, request.exceptionTypeId());
		if (typeId <= 0) {
			return;
		}
		LocalDate start = EmployeeRequest.date(request.fromDate());
		LocalDate end = EmployeeRequest.date(request.toDate());
		if (start == null || end == null || end.isBefore(start)) {
			return;
		}
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			String date = day.toString();
			if (this.store.attendanceExistsOn(request.employeeId(), date)) {
				continue;
			}
			this.store.insertAttendanceException(request.employeeId(), date, typeId);
		}
	}

	/**
	 * {@code reject}: a status change and a reply, with <b>no</b> side effects
	 * -- nothing to deduct, nothing to write.
	 *
	 * <p>Unguarded in legacy (R-046); guarded here.
	 */
	@Transactional
	public long reject(
			DashboardSession session, long adminId, boolean factorBound, long id, String reply) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.decide(id, REJECTED, blankToNull(reply), now());
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"request rejected in company " + companyId);
		return companyId;
	}

	/**
	 * {@code delete}: a hard delete, and unguarded in legacy (R-046).
	 *
	 * <p>Note what it does <em>not</em> undo. A request approved with a
	 * deducting type has already added to {@code used_days} and may have
	 * written attendance rows; deleting the request leaves both. That is
	 * legacy's behaviour and it is the reason an unguarded delete here was
	 * worth more than the row it destroyed.
	 */
	@Transactional
	public long delete(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"request deleted in company " + companyId);
		return companyId;
	}

	private String now() {
		return this.clock.now().format(
				java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	/** {@code $comment !== '' ? $comment : null}. */
	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "request", String.valueOf(id), null, detail);
	}

}
