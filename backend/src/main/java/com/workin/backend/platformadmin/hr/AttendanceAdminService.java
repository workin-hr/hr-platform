package com.workin.backend.platformadmin.hr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;
import com.workin.legacy.LegacyPhpStrtotime;

/**
 * The write half of {@code dashboard/pages/attendance/page.php}.
 *
 * <p>Attendance rows carry no {@code company_id}; ownership arrives through the
 * employee. Legacy resolves it the same way and, since `505004f`, guards every
 * action here with {@code hr_verify_post_row('attendance', ...)} -- the R-059
 * fix. This port does not have to diverge to be safe, which is worth stating
 * because the payroll page next door still does.
 *
 * <p><b>D-176 holds by construction, not vacuously.</b> {@code edit_attendance}
 * never writes {@code employee_id}, so the column that decides ownership is not
 * reachable from an edit. {@code exception_type_id} <em>is</em> editable and
 * decides nothing about ownership, but it names a row that belongs to a
 * company, so it is checked against the company of the row already stored --
 * never against anything the request supplied.
 */
@Service
public class AttendanceAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,


		/**
		 * {@code error_db}: the row, the employee, or the exception type belongs
		 * to another company. Legacy flashes exactly this for all three.
		 */
		FOREIGN_ROW,

		/** {@code error_required}: no employee, or an unusable date range. */
		INVALID
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

	private final AttendanceStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public AttendanceAdminService(
			AttendanceStore store, PlatformAdminAuditService auditService,
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

	/** {@code hr_verify_post_row('attendance', $id, $cid)} -- R-046's shape. */
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
	 * The company a new row would belong to.
	 *
	 * <p>Legacy's own resolution order, kept: a company-scoped session gets its
	 * own company; otherwise the active filter; otherwise the employee's. That
	 * last arm makes the ownership check tautological for an unscoped
	 * administrator with no filter -- which is R-044's intentional
	 * cross-company reach, not a hole. A scoped session never reaches it.
	 */
	private long resolveAddCompany(DashboardSession session, long employeeId) {
		if (session.isScopedToOneCompany()) {
			return session.companyId();
		}
		if (session.companyId() > 0) {
			return session.companyId();
		}
		Long owner = this.store.companyOfEmployee(employeeId);
		return owner == null ? 0L : owner;
	}

	private void assertExceptionTypeVisible(Long exceptionTypeId, long companyId) {
		if (exceptionTypeId == null) {
			return;
		}
		if (!this.store.exceptionTypeBelongsToCompany(exceptionTypeId, companyId)) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
	}

	/** {@code $exceptionTypeId = (int) ($_POST['exception_type_id'] ?? 0) ?: null}. */
	public static Long exceptionTypeOrNull(long raw) {
		return raw > 0 ? raw : null;
	}

	@Transactional
	public long add(DashboardSession session, long adminId, long employeeId, String checkIn, String checkOut, Long exceptionTypeId) {
		gate();
		// `if ($addEid)` -- legacy flashes error_required and writes nothing.
		if (employeeId <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		long companyId = resolveAddCompany(session, employeeId);
		Long owner = this.store.companyOfEmployee(employeeId);
		if (owner == null || companyId <= 0 || owner != companyId) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		assertExceptionTypeVisible(exceptionTypeId, companyId);

		this.store.insert(employeeId, checkIn, blankToNull(checkOut), exceptionTypeId);
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, employeeId,
				"attendance added for employee " + employeeId + " in company " + companyId);
		return companyId;
	}

	@Transactional
	public long saveEdit(DashboardSession session, long adminId, long id, String checkIn, String checkOut, Long exceptionTypeId) {
		gate();
		if (id <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		long owner = assertRowVisible(session, id);
		// The stored row's company, never one the request named -- D-176(b).
		assertExceptionTypeVisible(exceptionTypeId, owner);

		this.store.update(id, checkIn, blankToNull(checkOut), exceptionTypeId);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"attendance " + id + " edited in company " + owner);
		return owner;
	}

	@Transactional
	public long delete(DashboardSession session, long adminId, long id) {
		gate();
		if (id <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		long owner = assertRowVisible(session, id);
		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"attendance " + id + " deleted in company " + owner);
		return owner;
	}

	/** What {@code delete_range} reports back: the count, and the company it ran in. */
	public record RangeDeletion(long companyId, int deleted) {
	}

	/**
	 * {@code delete_range}.
	 *
	 * <p>Legacy parses both bounds with {@code strtotime()} and refuses when
	 * either fails, then normalises to {@code Y-m-d} and refuses a reversed
	 * range -- so "31 January 2020" is accepted and a reversed pair is not.
	 * A company-scoped session's company always wins over the posted one.
	 */
	@Transactional
	public RangeDeletion deleteRange(DashboardSession session, long adminId, long postedCompanyId, String from, String to, String today) {
		gate();
		long companyId = session.isScopedToOneCompany()
				? session.companyId()
				: (postedCompanyId > 0 ? postedCompanyId : session.companyId());

		java.time.LocalDate fromDate = LegacyPhpStrtotime.dateOf(from, java.time.LocalDate.parse(today));
		java.time.LocalDate toDate = LegacyPhpStrtotime.dateOf(to, java.time.LocalDate.parse(today));
		if (companyId <= 0 || fromDate == null || toDate == null) {
			throw new RefusedException(Refusal.INVALID);
		}
		if (fromDate.isAfter(toDate)) {
			throw new RefusedException(Refusal.INVALID);
		}

		String fromYmd = fromDate.toString();
		String toYmd = toDate.toString();
		// Counted before the delete, because that is the number legacy reports.
		int deleted = this.store.countInRange(companyId, fromYmd, toYmd);
		if (deleted > 0) {
			this.store.deleteRange(companyId, fromYmd, toYmd);
		}
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, companyId,
				"attendance range " + fromYmd + ".." + toYmd + " deleted in company " + companyId
						+ " (" + deleted + " rows)");
		return new RangeDeletion(companyId, deleted);
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "attendance", String.valueOf(id), detail);
	}

	/** `$_POST['check_out'] ?: null` -- an empty string stores NULL, not ''. */
	private static String blankToNull(String raw) {
		return raw == null || raw.isEmpty() ? null : raw;
	}

}
