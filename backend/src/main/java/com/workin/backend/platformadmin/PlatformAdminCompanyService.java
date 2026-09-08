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
	private final com.workin.legacy.phone.LegacyPhoneNumbers phoneNumbers;
	private final com.workin.legacy.uploads.LegacyFileUploads uploads;
	private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
	private final boolean actionsEnabled;

	public PlatformAdminCompanyService(
			PlatformAdminCompanyDirectory companies,
			PlatformAdminAuditService auditService,
			com.workin.legacy.phone.LegacyPhoneNumbers phoneNumbers,
			com.workin.legacy.uploads.LegacyFileUploads uploads,
			org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.companies = companies;
		this.auditService = auditService;
		this.phoneNumbers = phoneNumbers;
		this.uploads = uploads;
		this.passwordEncoder = passwordEncoder;
		this.actionsEnabled = actionsEnabled;
	}

	/** @param errorKey null when it succeeded */
	public record Saved(boolean ok, String errorKey) {
	}

	/**
	 * {@code company_admin_create()}. The rules that need the database run
	 * here, in the PHP's order and between the same pure ones: the phone's
	 * uniqueness after the ids are known to be positive, the three lookups
	 * after that, and the logo last of all.
	 *
	 * <p>Creating a company provisions a login, so this is audited like any
	 * other administrative write and refuses when the surface is closed.
	 */
	@Transactional
	public Saved create(long platformAdminId, CompanyForm.CompanyWrite write,
			org.springframework.web.multipart.MultipartFile logo,
			org.springframework.web.multipart.MultipartFile commercialReg) {
		if (!this.actionsEnabled) {
			return new Saved(false, "admin_actions_disabled");
		}
		if (this.companies.phoneTaken(write.phone(), 0L)) {
			return new Saved(false, "error_phone_registered");
		}
		if (!this.companies.lookupsExist(write.activityId(), write.titleId(), write.sizeId())) {
			return new Saved(false, "error_required");
		}
		String logoUrl = this.uploads.store(logo, "logos");
		if (logoUrl == null) {
			return new Saved(false, "error_logo_required");
		}
		String commercialUrl = this.uploads.store(commercialReg, "commercial");

		long companyId = this.companies.create(write,
				this.passwordEncoder.encode(write.password()), logoUrl, commercialUrl);
		this.auditService.recordAction(platformAdminId,
				PlatformAdminAuditEventType.COMPANY_CREATED, TARGET_TYPE,
				String.valueOf(companyId), write.companyName());
		return new Saved(true, null);
	}

	/** {@code company_admin_update()}, in the same order. */
	@Transactional
	public Saved update(long platformAdminId, long companyId, CompanyForm.CompanyWrite write,
			org.springframework.web.multipart.MultipartFile logo,
			org.springframework.web.multipart.MultipartFile commercialReg) {
		if (!this.actionsEnabled) {
			return new Saved(false, "admin_actions_disabled");
		}
		java.util.Optional<PlatformAdminCompanyDirectory.StoredFiles> stored =
				this.companies.storedFiles(companyId);
		if (stored.isEmpty()) {
			return new Saved(false, "no_data");
		}
		if (write.companyCode() != null
				&& this.companies.companyCodeTaken(write.companyCode(), companyId)) {
			return new Saved(false, "company_code_taken");
		}
		if (this.companies.phoneTaken(write.phone(), companyId)) {
			return new Saved(false, "error_phone_registered");
		}
		if (!this.companies.lookupsExist(write.activityId(), write.titleId(), write.sizeId())) {
			return new Saved(false, "error_required");
		}
		// An upload replaces the stored file; no upload keeps it. A company with
		// neither is refused, which is how legacy stops an edit clearing a logo
		// that was required to create the row.
		String logoUrl = firstNonNull(this.uploads.store(logo, "logos"), stored.get().logoUrl());
		if (logoUrl == null || logoUrl.isBlank()) {
			return new Saved(false, "error_logo_required");
		}
		String commercialUrl = firstNonNull(this.uploads.store(commercialReg, "commercial"),
				stored.get().commercialRegUrl());

		this.companies.update(companyId, write,
				write.password() == null ? null : this.passwordEncoder.encode(write.password()),
				logoUrl, commercialUrl);
		this.auditService.recordAction(platformAdminId,
				PlatformAdminAuditEventType.COMPANY_UPDATED, TARGET_TYPE,
				String.valueOf(companyId), write.companyName());
		return new Saved(true, null);
	}

	private static String firstNonNull(String uploaded, String stored) {
		return uploaded != null && !uploaded.isBlank() ? uploaded : stored;
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
