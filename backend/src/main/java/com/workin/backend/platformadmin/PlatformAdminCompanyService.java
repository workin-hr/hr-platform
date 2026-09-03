package com.workin.backend.platformadmin;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.stepup.PlatformAdminStepUpService;

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
	private final PlatformAdminStepUpService stepUpService;
	private final PlatformAdminAuditService auditService;
	private final boolean actionsEnabled;

	public PlatformAdminCompanyService(
			PlatformAdminCompanyDirectory companies,
			PlatformAdminStepUpService stepUpService,
			PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.companies = companies;
		this.stepUpService = stepUpService;
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
		SECOND_FACTOR_NOT_BOUND,
		STEP_UP_REJECTED,
		NO_SUCH_COMPANY,
	}

	/**
	 * Applies a lifecycle change to one company.
	 *
	 * @param factorBound whether the caller's session has a bound second factor
	 * @param approvalId the step-up approval minted for this exact request
	 * @param reason the operator's note, part of the approval's digest
	 */
	@Transactional
	public Outcome apply(long platformAdminId, boolean factorBound, String action,
			long companyId, String reason, String approvalId) {
		if (!this.actionsEnabled) {
			return Outcome.SURFACE_DISABLED;
		}
		// D-152: an administrator whose factor is not bound cannot perform a
		// destructive operation. Existing rows migrate unbound, so this is not
		// a theoretical state.
		if (!factorBound) {
			return Outcome.SECOND_FACTOR_NOT_BOUND;
		}

		PlatformAdminStepUpService.Request request = request(action, companyId, reason);
		// Consumed inside this transaction: an action that rolls back must not
		// leave its approval spent, and a spent approval with no action would be
		// worse.
		if (!this.stepUpService.consume(platformAdminId, approvalId, request)) {
			return Outcome.STEP_UP_REJECTED;
		}

		if (!this.companies.updateStatus(companyId, statusFor(action))) {
			// Rolls back, taking the approval's consumption with it, so a
			// mistyped id does not burn the operator's step-up.
			throw new CompanyNotFoundException(companyId);
		}
		this.auditService.recordAction(platformAdminId, auditTypeFor(action),
				TARGET_TYPE, String.valueOf(companyId), approvalId, reason);
		return Outcome.DONE;
	}

	/**
	 * The canonical request an approval must be minted against.
	 *
	 * <p>Built here, from the same inputs the action uses, so the digest the
	 * approval carries and the digest checked at consumption cannot drift: a
	 * second construction site is how "bound to the request" quietly becomes
	 * "bound to whatever the caller said the request was".
	 */
	public PlatformAdminStepUpService.Request request(String action, long companyId, String reason) {
		return new PlatformAdminStepUpService.Request(action, TARGET_TYPE,
				String.valueOf(companyId), List.of(reason == null ? "" : reason));
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
	public static class CompanyNotFoundException extends RuntimeException {

		public CompanyNotFoundException(long companyId) {
			super("no company " + companyId);
		}

	}

}
