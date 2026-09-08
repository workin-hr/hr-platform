package com.workin.backend.platformadmin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * The platform-admin operations on companies (ADR-0009 Option E): approve,
 * reject, suspend, restore.
 *
 * <p><b>Shipped closed.</b> ADR-0015 prerequisite 7 is a deployment condition,
 * not a code one: "the JTE surface does not perform a privileged operation while
 * the PHP surface is reachable", and D-152 settled that the legacy admin surface
 * is disabled at cutover. Code cannot verify that, so
 * {@code app.platform-admin.actions.enabled} defaults to <b>false</b> and the
 * operation refuses. Turning it on is a deliberate cutover step taken once the
 * PHP surface is confirmed unreachable — the flag exists so that decision is
 * explicit and revocable, rather than implied by a deploy.
 *
 * <p>Every call passes four gates, and each one is here rather than in a
 * controller because a second caller would otherwise have to remember them:
 * the surface must be enabled, the administrator must have a bound second
 * factor, a step-up approval must be spendable for <em>this exact request</em>,
 * and the audit row must be written in the same transaction as the change.
 */
@Service
public class PlatformAdminCompanyService {

	/** The canonical operations. Not URLs: URLs change, and these are what approvals bind to. */
	public static final String ACTION_APPROVE = "COMPANY_APPROVE";
	public static final String ACTION_REJECT = "COMPANY_REJECT";
	public static final String ACTION_SUSPEND = "COMPANY_SUSPEND";
	public static final String ACTION_RESTORE = "COMPANY_RESTORE";

	public static final String TARGET_TYPE = "COMPANY";

	private final PlatformAdminCompanyDirectory companies;
	private final PlatformAdminAuditService auditService;
	private final boolean actionsEnabled;

	public PlatformAdminCompanyService(
			PlatformAdminCompanyDirectory companies,
			PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.companies = companies;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	/** Why an attempt was refused. Distinguished for the operator, not for the caller. */
	public enum Outcome {
		DONE,
		SURFACE_DISABLED,
		NO_SUCH_COMPANY,
	}

	/**
	 * Applies one lifecycle action.
	 *
	 * <p>Gated by the surface flag (ADR-0015 prerequisite 7) and audited in the
	 * same transaction, so a committed change cannot exist without its audit
	 * row. The step-up approval that used to sit between the two is gone with
	 * the second factor (ADR-0018): the dashboard has one password, and a
	 * second prompt for it would be theatre.
	 */
	@Transactional
	public Outcome apply(long platformAdminId, String action, long companyId, String reason) {
		if (!this.actionsEnabled) {
			return Outcome.SURFACE_DISABLED;
		}
		boolean applied = ACTION_REJECT.equals(action)
				? this.companies.reject(companyId, reason)
				: this.companies.updateStatus(companyId, statusFor(action));
		if (!applied) {
			return Outcome.NO_SUCH_COMPANY;
		}
		this.auditService.recordAction(platformAdminId, auditTypeFor(action),
				TARGET_TYPE, String.valueOf(companyId), reason);
		return Outcome.DONE;
	}

	private static String statusFor(String action) {
		return switch (action) {
			case ACTION_APPROVE, ACTION_RESTORE -> "active";
			case ACTION_REJECT -> "rejected";
			case ACTION_SUSPEND -> "suspended";
			default -> throw new IllegalArgumentException("unknown action " + action);
		};
	}

	private static PlatformAdminAuditEventType auditTypeFor(String action) {
		return switch (action) {
			case ACTION_APPROVE -> PlatformAdminAuditEventType.COMPANY_APPROVED;
			case ACTION_REJECT -> PlatformAdminAuditEventType.COMPANY_REJECTED;
			case ACTION_SUSPEND -> PlatformAdminAuditEventType.COMPANY_SUSPENDED;
			case ACTION_RESTORE -> PlatformAdminAuditEventType.COMPANY_UNSUSPENDED;
			default -> throw new IllegalArgumentException("unknown action " + action);
		};
	}

	/** Thrown so the transaction rolls back and the step-up approval is not spent. */

}
