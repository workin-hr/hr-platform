package com.workin.backend.platformadmin.settings;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;

/**
 * The settings page's four write actions
 * ({@code app_content_save()}, {@code setting_templates_edit_definition()},
 * {@code setting_templates_add_option()}, {@code _edit_option()},
 * {@code _delete_option()} and {@code configs_save_from_post()}).
 *
 * <p>Every table here is platform-level, so there is no tenant rule to
 * enforce. What there is instead is a set of integrity rules that are easy to
 * lose in a port and expensive to lose in production: an allowlisted content
 * key, an option value that cannot change once companies depend on it, an
 * option that cannot be deleted while they do, and a definition key that is
 * never rewritten.
 */
@Service
public class SettingsAdminService {

	public enum Refusal {
		ACTIONS_DISABLED,
		FACTOR_NOT_BOUND,
		/** Missing id, absent row, or a blank required field. */
		INVALID,
		/** A content key outside {@code app_content_fixed_keys()}. */
		UNKNOWN_CONTENT_KEY,
		/** An option value longer than 120 characters. */
		VALUE_TOO_LONG,
		/** Another option of the same definition already holds this value. */
		VALUE_EXISTS,
		/** The option is selected by at least one company. */
		OPTION_IN_USE,
	}

	public static class RefusedException extends RuntimeException {

		private final transient Refusal refusal;

		private final transient int usage;

		public RefusedException(Refusal refusal) {
			this(refusal, 0);
		}

		public RefusedException(Refusal refusal, int usage) {
			super(refusal.name());
			this.refusal = refusal;
			this.usage = usage;
		}

		public Refusal refusal() {
			return this.refusal;
		}

		/** Companies using the option, which the refusal message interpolates. */
		public int usage() {
			return this.usage;
		}
	}

	/** {@code mb_strlen($value) > 120}. */
	private static final int VALUE_MAX = 120;

	private final SettingsAdminStore store;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public SettingsAdminService(
			SettingsAdminStore store, PlatformAdminAuditService auditService,
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

	/**
	 * {@code save_content}.
	 *
	 * <p>The key is checked against the catalogue before anything is written.
	 * That allowlist is the only thing stopping an arbitrary content key being
	 * created by a posted field, and legacy returns false rather than
	 * inserting.
	 */
	@Transactional
	public void saveContent(long adminId, boolean factorBound,
			String key, String valueAr, String valueEn) {
		gate(factorBound);
		if (key == null || !SettingsCatalog.APP_CONTENT_KEYS.contains(key)) {
			throw new RefusedException(Refusal.UNKNOWN_CONTENT_KEY);
		}
		this.store.saveAppContent(key, nullToEmpty(valueAr), nullToEmpty(valueEn));
		audit(adminId, PlatformAdminAuditEventType.CONTENT_UPDATED, "app_content", key,
				"app content saved");
	}

	/** {@code edit_definition}: labels required, descriptions nullable, key untouched. */
	@Transactional
	public void editDefinition(long adminId, boolean factorBound, long id,
			String labelAr, String labelEn, String descriptionAr, String descriptionEn,
			int sortOrder) {
		gate(factorBound);
		if (id <= 0 || !this.store.definitionExists(id)) {
			throw new RefusedException(Refusal.INVALID);
		}
		String ar = trim(labelAr);
		String en = trim(labelEn);
		if (ar.isEmpty() || en.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		this.store.updateDefinition(id, ar, en,
				blankToNull(descriptionAr), blankToNull(descriptionEn), sortOrder);
		audit(adminId, PlatformAdminAuditEventType.CONTENT_UPDATED, "setting_definitions",
				String.valueOf(id), "definition relabelled");
	}

	/** {@code add_option}. */
	@Transactional
	public long addOption(long adminId, boolean factorBound, long definitionId,
			String value, String labelAr, String labelEn, int sortOrder) {
		gate(factorBound);
		String checked = validateOptionValue(definitionId, value, 0);
		long id = this.store.insertOption(definitionId, checked,
				blankToNull(labelAr), blankToNull(labelEn), sortOrder);
		audit(adminId, PlatformAdminAuditEventType.CONTENT_CREATED, "setting_allowed_values",
				String.valueOf(id), "option added to definition " + definitionId);
		return id;
	}

	/**
	 * {@code edit_option}.
	 *
	 * <p>The value-pinning rule: when companies have selected the option, the
	 * posted value is replaced by the stored one <em>before</em> validation and
	 * again before the update, so an edit can never change a code other systems
	 * are already keyed on. Labels and sort order remain editable, which is the
	 * point -- the option can be renamed without being re-coded.
	 */
	@Transactional
	public void editOption(long adminId, boolean factorBound, long id,
			String value, String labelAr, String labelEn, int sortOrder) {
		gate(factorBound);
		if (id <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		SettingTemplate.Option existing = this.store.option(id);
		if (existing == null) {
			throw new RefusedException(Refusal.INVALID);
		}
		String effective = existing.inUse() ? existing.value() : value;
		String checked = validateOptionValue(existing.definitionId(), effective, id);
		this.store.updateOption(id, existing.inUse() ? existing.value() : checked,
				blankToNull(labelAr), blankToNull(labelEn), sortOrder);
		audit(adminId, PlatformAdminAuditEventType.CONTENT_UPDATED, "setting_allowed_values",
				String.valueOf(id), existing.inUse()
						? "option relabelled, value pinned by " + existing.companiesUsing()
								+ " companies"
						: "option updated");
	}

	/** {@code delete_option}, refused while any company depends on it. */
	@Transactional
	public void deleteOption(long adminId, boolean factorBound, long id) {
		gate(factorBound);
		if (id <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		SettingTemplate.Option existing = this.store.option(id);
		if (existing == null) {
			throw new RefusedException(Refusal.INVALID);
		}
		int usage = this.store.optionUsage(id);
		if (usage > 0) {
			throw new RefusedException(Refusal.OPTION_IN_USE, usage);
		}
		this.store.deleteOption(id);
		audit(adminId, PlatformAdminAuditEventType.CONTENT_DELETED, "setting_allowed_values",
				String.valueOf(id), "option deleted");
	}

	/**
	 * {@code configs_save_from_post()}.
	 *
	 * <p>Writes <b>every</b> defined key, using the empty string for one the
	 * form did not send. That is how an unchecked box becomes {@code false},
	 * and it also means a partial post blanks the keys it omits -- reproduced,
	 * because the page always submits the whole tab and a caller that does not
	 * is not a case legacy protects against either.
	 */
	@Transactional
	public void saveConfigs(long adminId, boolean factorBound, Map<String, String> posted) {
		gate(factorBound);
		SettingsCatalog.CONFIGS.forEach((key, definition) -> this.store.saveConfig(
				key, ConfigValues.normalize(definition.type(), posted.get(key))));
		audit(adminId, PlatformAdminAuditEventType.CONTENT_UPDATED, "configs", "*",
				"system configuration saved");
	}

	private String validateOptionValue(long definitionId, String value, long excludeId) {
		if (definitionId <= 0 || !this.store.definitionExists(definitionId)) {
			throw new RefusedException(Refusal.INVALID);
		}
		String trimmed = trim(value);
		if (trimmed.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		// mb_strlen counts characters, so an emoji is one. String.length()
		// counts UTF-16 units and would call it two.
		if (trimmed.codePointCount(0, trimmed.length()) > VALUE_MAX) {
			throw new RefusedException(Refusal.VALUE_TOO_LONG);
		}
		if (this.store.optionValueTaken(definitionId, trimmed, excludeId)) {
			throw new RefusedException(Refusal.VALUE_EXISTS);
		}
		return trimmed;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, String target,
			String targetId, String detail) {
		this.auditService.recordAction(adminId, type, target, targetId, null, detail);
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private static String blankToNull(String value) {
		String trimmed = trim(value);
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

}
