package com.workin.backend.platformadmin.hr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;

/**
 * The write half of {@code dashboard/pages/administrative_decisions/page.php}.
 *
 * <p>The page whose visibility guard legacy got right and whose update it did
 * not. {@code $decisionRowAllowed()} is exactly the rule
 * {@code DashboardOrgScope.canOpenRow()} states -- and then the edit writes
 * {@code company_id} from the posted value, so an administrator with no
 * company filter could transfer a decision between tenants (<b>R-047</b>).
 *
 * <p><b>D-176</b> here is the direct half rather than the indirect one: there
 * is no foreign key to validate, only an ownership column to keep out of the
 * update entirely. On the create path the company is still chosen, because a
 * row that does not exist yet has no tenant to preserve.
 */
@Service
@Profile("phase1-mysql")
public class AdministrativeDecisionAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code error_required}: no company chosen, or an empty title or body. */
		INVALID,

		/** {@code error_db}: the row is not this session's to touch. */
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

	private final AdministrativeDecisionStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public AdministrativeDecisionAdminService(
			AdministrativeDecisionStore store, PlatformAdminAuditService auditService,
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

	/** {@code $decisionRowAllowed()}, which is {@code canOpenRow()} by another name. */
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
	 * {@code $resolveDecisionCompanyId()}: a scoped session's own company, or
	 * for an administrator the posted one falling back to the current filter.
	 *
	 * <p>Used on the create path only. On an edit the row already has a tenant
	 * and this value is not consulted.
	 */
	private static long companyForCreate(DashboardSession session, long postedCompanyId) {
		if (session.isScopedToOneCompany()) {
			return session.companyId();
		}
		return postedCompanyId > 0 ? postedCompanyId : session.companyId();
	}

	@Transactional
	public long add(
			DashboardSession session, long adminId, boolean factorBound, long postedCompanyId,
			String rawTitle, String rawBody, boolean active) {
		gate(factorBound);
		long companyId = companyForCreate(session, postedCompanyId);
		String title = rawTitle == null ? "" : rawTitle.trim();
		String body = rawBody == null ? "" : rawBody.trim();
		if (companyId <= 0 || title.isEmpty() || body.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}

		long id = this.store.insert(companyId, title, body, active);
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"administrative decision created in company " + companyId);
		return companyId;
	}

	/**
	 * The edit writes title, body and the flag. <b>Not</b> the company: see
	 * {@link AdministrativeDecisionStore#update}.
	 */
	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, boolean factorBound, long id,
			String rawTitle, String rawBody, boolean active) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		String title = rawTitle == null ? "" : rawTitle.trim();
		String body = rawBody == null ? "" : rawBody.trim();
		if (title.isEmpty() || body.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}

		this.store.update(id, title, body, active);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"administrative decision updated in company " + companyId);
		return companyId;
	}

	@Transactional
	public long delete(DashboardSession session, long adminId, boolean factorBound, long id) {
		gate(factorBound);
		long companyId = assertRowVisible(session, id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"administrative decision deleted in company " + companyId);
		return companyId;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(
				adminId, type, "administrative_decision", String.valueOf(id), null, detail);
	}

}
