package com.workin.backend.platformadmin.content;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;

/**
 * The write side of {@code phone_countries} -- the capability that existed
 * only in the PHP dashboard until ADR-0016.
 *
 * <p>Three gates, matching {@code PlatformAdminCompanyService} minus
 * step-up: the surface flag, a bound second factor, and an audit row
 * written in the same transaction as the change. See {@code
 * PlatformAdminAuditEventType.CONTENT_CREATED} for why step-up itself is
 * not required here.
 */
@Service
@Profile("phase1-mysql")
public class PhoneCountryAdminService {

	/** The audit row's {@code target_type}: the table, so one triple of event types serves every content page. */
	static final String TARGET_TYPE = "PHONE_COUNTRY";

	public enum Outcome {
		DONE,
		SURFACE_DISABLED,
		SECOND_FACTOR_NOT_BOUND,
		INVALID,
		DUPLICATE_COUNTRY_CODE,
		NOT_FOUND
	}

	/** @param errorKey the message key to render, or null when {@link #outcome} is {@code DONE} */
	public record Result(Outcome outcome, String errorKey) {

		public boolean ok() {
			return this.outcome == Outcome.DONE;
		}
	}

	private final PhoneCountryStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public PhoneCountryAdminService(PhoneCountryStore store, PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	public List<PhoneCountry> list() {
		return this.store.list();
	}

	@Transactional
	public Result create(long platformAdminId, boolean factorBound, PhoneCountryForm.Result form) {
		Result gate = gate(factorBound, form);
		if (gate != null) {
			return gate;
		}
		PhoneCountry country = form.country();
		if (this.store.countryCodeTaken(country.countryCode(), null)) {
			return new Result(Outcome.DUPLICATE_COUNTRY_CODE, "already_exists");
		}
		this.store.insert(country);
		audit(platformAdminId, PlatformAdminAuditEventType.CONTENT_CREATED, country.countryCode());
		return new Result(Outcome.DONE, null);
	}

	@Transactional
	public Result update(long platformAdminId, boolean factorBound, long id, PhoneCountryForm.Result form) {
		Result gate = gate(factorBound, form);
		if (gate != null) {
			return gate;
		}
		if (this.store.find(id).isEmpty()) {
			return new Result(Outcome.NOT_FOUND, "error_not_found");
		}
		PhoneCountry country = form.country();
		if (this.store.countryCodeTaken(country.countryCode(), id)) {
			return new Result(Outcome.DUPLICATE_COUNTRY_CODE, "already_exists");
		}
		this.store.update(id, country);
		audit(platformAdminId, PlatformAdminAuditEventType.CONTENT_UPDATED, String.valueOf(id));
		return new Result(Outcome.DONE, null);
	}

	@Transactional
	public Result delete(long platformAdminId, boolean factorBound, long id) {
		if (!this.actionsEnabled) {
			return new Result(Outcome.SURFACE_DISABLED, "admin_actions_disabled");
		}
		if (!factorBound) {
			return new Result(Outcome.SECOND_FACTOR_NOT_BOUND, "mfa_required_for_actions");
		}
		if (this.store.find(id).isEmpty()) {
			return new Result(Outcome.NOT_FOUND, "error_not_found");
		}
		this.store.delete(id);
		audit(platformAdminId, PlatformAdminAuditEventType.CONTENT_DELETED, String.valueOf(id));
		return new Result(Outcome.DONE, null);
	}

	/** @return the refusal, or null when the caller may proceed */
	private Result gate(boolean factorBound, PhoneCountryForm.Result form) {
		if (!this.actionsEnabled) {
			return new Result(Outcome.SURFACE_DISABLED, "admin_actions_disabled");
		}
		if (!factorBound) {
			return new Result(Outcome.SECOND_FACTOR_NOT_BOUND, "mfa_required_for_actions");
		}
		if (!form.ok()) {
			return new Result(Outcome.INVALID, form.errorKey());
		}
		return null;
	}

	private void audit(long platformAdminId, PlatformAdminAuditEventType type, String targetId) {
		this.auditService.recordAction(platformAdminId, type, TARGET_TYPE, targetId, null, null);
	}

}
