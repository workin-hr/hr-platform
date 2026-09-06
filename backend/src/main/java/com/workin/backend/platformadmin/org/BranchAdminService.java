package com.workin.backend.platformadmin.org;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The write half of {@code dashboard/pages/branches/page.php}.
 *
 * <p>Every action goes through {@link #assertWritable}, which is
 * {@code org_verify_post_row()} plus the check PHP performs before it: a POST
 * with no resolvable company is refused outright. That order matters. Legacy
 * checks the company <em>first</em> and redirects with
 * {@code select_company_first}, so an administrator who has not picked a
 * company gets told to, rather than a database error from an insert with
 * {@code company_id = 0}.
 *
 * <h2>Where the tenant boundary is</h2>
 * <p>{@code org_verify_post_row()} only runs for a <b>scoped</b> session, and
 * that is deliberate on legacy's side: the administrator is allowed to edit
 * any company's rows, which is the whole point of the filter. For an owner or
 * HR it is the check that stops a crafted {@code id} in a form post from
 * reaching another company's branch. Reproduced exactly, and named here
 * because "the check is skipped for one audience" is a sentence that needs to
 * be deliberate rather than discovered (<b>R-044</b>).
 */
@Service
@Profile("phase1-mysql")
public class BranchAdminService {

	/** Why an action was refused, so the controller can flash legacy's message. */
	public enum Refusal {

		/** {@code admin_actions_disabled}: the surface flag is off (ADR-0015 prerequisite 7). */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}: no second factor bound to this session (D-152). */
		FACTOR_NOT_BOUND,

		/** {@code select_company_first}: no company resolved for the write. */
		NO_COMPANY,

		/** {@code error_db}: the row is not this session's to touch. */
		FOREIGN_ROW,

		/** {@code branch_qr_invalid_expiry}: absent, unparseable or already past. */
		BAD_EXPIRY
	}

	/** Thrown rather than returned, because every caller's answer is a redirect. */
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

	private final BranchStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	/** {@code bin2hex(random_bytes(16))} -- 16 bytes, so 32 hex characters. */
	private final SecureRandom random = new SecureRandom();

	public BranchAdminService(BranchStore store, PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	/**
	 * The two gates every write passes before legacy's own checks.
	 *
	 * <p>Stricter than the PHP being reproduced, which has neither -- the
	 * dashboard's only gate is the section permission. They are kept because
	 * this surface has them for every other write (D-171, ADR-0015
	 * prerequisites 1 and 7) and an administrator writing <em>inside a
	 * customer's company</em> is at least as sensitive as editing a FAQ. The
	 * cost is that the flag must be turned on deliberately, which is the
	 * default-closed side of the trade.
	 */
	private void gate(boolean factorBound) {
		if (!this.actionsEnabled) {
			throw new RefusedException(Refusal.ACTIONS_DISABLED);
		}
		if (!factorBound) {
			throw new RefusedException(Refusal.FACTOR_NOT_BOUND);
		}
	}

	/**
	 * {@code org_post_company_id()} then {@code org_verify_post_row()}.
	 *
	 * @param postedCompanyId the form's own {@code company_id}, which only an
	 *                        administrator may use -- a scoped session's
	 *                        company is its own and the field is ignored
	 * @return the company the write will be made against
	 */
	private long assertWritable(DashboardSession session, long postedCompanyId, long rowId) {
		long companyId = session.isScopedToOneCompany()
				? session.companyId()
				: (postedCompanyId > 0 ? postedCompanyId : session.companyId());
		if (companyId <= 0) {
			throw new RefusedException(Refusal.NO_COMPANY);
		}
		// Only a scoped session is checked, which is legacy's rule and R-044's
		// deliberate exception: the administrator edits across companies.
		if (session.isScopedToOneCompany() && rowId > 0 && !this.store.belongsTo(rowId, companyId)) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		return companyId;
	}

	/** @return the company the branch was created in, for the redirect's filter */
	@Transactional
	public long add(
			DashboardSession session, long adminId, boolean factorBound, long postedCompanyId,
			String name, String address, String latitude, String longitude, String radius) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, 0L);
		long id = this.store.insert(
				companyId, name.trim(), blankToNull(address),
				Branch.coordinate(latitude), Branch.coordinate(longitude),
				Branch.radiusMeters(radius));
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"branch created in company " + companyId);
		return companyId;
	}

	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long postedCompanyId, String name, String address, String latitude, String longitude,
			String radius, boolean active) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, id);
		this.store.update(
				id, name.trim(), blankToNull(address),
				Branch.coordinate(latitude), Branch.coordinate(longitude),
				Branch.radiusMeters(radius), active);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"branch updated in company " + companyId);
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
				"branch deactivated in company " + companyId);
		return companyId;
	}

	/**
	 * {@code org_branch_generate_qr()}.
	 *
	 * <p>The expiry arrives from a {@code datetime-local} input as
	 * {@code 2026-09-05T23:59}, and legacy normalises it by replacing the
	 * {@code T} with a space before {@code strtotime()}. An expiry at or before
	 * now is refused: a code that is already expired is indistinguishable from
	 * no code at all, and generating one would silently do nothing.
	 */
	@Transactional
	public long generateQr(
			DashboardSession session, long adminId, boolean factorBound, long id,
			long postedCompanyId, String expiresAtInput, LocalDateTime now) {
		gate(factorBound);
		long companyId = assertWritable(session, postedCompanyId, id);
		// The administrator is not checked by assertWritable, so this is where
		// a branch id from another company is still rejected for everyone:
		// org_branch_generate_qr() runs org_assert_company_row() itself,
		// unconditionally, unlike the write actions above.
		if (id <= 0 || !this.store.belongsTo(id, companyId)) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}

		LocalDateTime expires = Branch.parseTimestamp(
				expiresAtInput == null ? "" : expiresAtInput.trim().replace('T', ' '));
		if (expires == null || !expires.isAfter(now)) {
			throw new RefusedException(Refusal.BAD_EXPIRY);
		}

		byte[] bytes = new byte[16];
		this.random.nextBytes(bytes);
		StringBuilder code = new StringBuilder(32);
		for (byte value : bytes) {
			code.append(String.format("%02x", value));
		}
		this.store.storeQr(id, code.toString(),
				expires.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
		// The code itself is not recorded: it is the credential a phone
		// presents to check in, and an audit row is not the place for it.
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"branch check-in code regenerated, expires " + expires);
		return companyId;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long branchId, String detail) {
		this.auditService.recordAction(adminId, type, "branch", String.valueOf(branchId), null, detail);
	}

	/** {@code trim(...) ?: null} -- an empty address is stored as NULL, not ''. */
	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
