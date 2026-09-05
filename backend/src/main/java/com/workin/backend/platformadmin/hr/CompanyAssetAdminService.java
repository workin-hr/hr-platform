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
 * The write half of {@code dashboard/pages/assets/page.php}.
 *
 * <p>The same shape as the penalties page, one rule lighter: an asset marked
 * returned is <b>frozen</b> against editing, but there is no value whitelist --
 * an asset is a piece of text and a pair of dates.
 *
 * <p>The edit writes {@code company_id} from the chosen employee, so the
 * employee is checked on every write. Without that, changing
 * {@code employee_id} on an edit would carry the row into another company.
 *
 * <p>R-046: {@code mark_returned} and {@code delete_asset} wrote by id alone.
 * Both are guarded here.
 */
@Service
@Profile("phase1-mysql")
public class CompanyAssetAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code error_db}: the row or the employee belongs to another company. */
		FOREIGN_ROW,

		/** {@code error_required}: no employee, no text, or an already-returned row. */
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

	private final CompanyAssetStore store;

	private final PlatformAdminAuditService auditService;

	private final LegacyClock clock;

	private final boolean actionsEnabled;

	public CompanyAssetAdminService(CompanyAssetStore store, PlatformAdminAuditService auditService,
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

	/**
	 * The employee must belong to <b>this</b> company -- the row's, not the
	 * session's. A record never crosses a tenant boundary because someone
	 * changed which employee it points at.
	 */
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
			String assetText, String assetDate, String assetEndDate) {
		gate(factorBound);
		String text = assetText == null ? "" : assetText.trim();
		if (employeeId <= 0 || text.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		long companyId = assertEmployeeVisible(session, employeeId);

		long id = this.store.insert(
				companyId, employeeId, dateOrToday(assetDate), blankToNull(assetEndDate), text);
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"asset assigned to employee " + employeeId + " in company " + companyId);
		return companyId;
	}

	/**
	 * Refused once the asset is back: there is nothing left to correct.
	 *
	 * <h2>The company is immutable</h2>
	 * <p>The edit may reassign the asset to a different employee, but only
	 * within the company that already owns the row -- and the company written
	 * is the row's existing one, not the new employee's.
	 *
	 * <p>Checking the employee against the <em>session</em> instead is not
	 * enough, and that is a hole this had until it was pointed out: an
	 * administrator with no company filter satisfies any session check, so
	 * posting an employee from another company would have carried the asset
	 * there. The row's own company is the only thing that cannot be widened by
	 * how the operator happens to be looking at the page.
	 */
	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id, long employeeId,
			String assetText, String assetDate, String assetEndDate) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		Boolean returned = this.store.isReturned(id);
		if (returned == null || returned) {
			throw new RefusedException(Refusal.INVALID);
		}

		String text = assetText == null ? "" : assetText.trim();
		if (employeeId <= 0 || text.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		assertEmployeeInCompany(employeeId, companyId);

		this.store.update(id, companyId, employeeId,
				dateOrToday(assetDate), blankToNull(assetEndDate), text);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"asset updated in company " + companyId);
		return companyId;
	}

	@Transactional
	public long markReturned(
			DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.markReturned(id, this.clock.today().toString());
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"asset marked returned in company " + companyId);
		return companyId;
	}

	@Transactional
	public long delete(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"asset deleted in company " + companyId);
		return companyId;
	}

	/** {@code $_POST['asset_date'] ?? date('Y-m-d')}: absent means today. */
	private String dateOrToday(String raw) {
		return raw == null || raw.isBlank() ? this.clock.today().toString() : raw.trim();
	}

	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "asset", String.valueOf(id), null, detail);
	}

}
