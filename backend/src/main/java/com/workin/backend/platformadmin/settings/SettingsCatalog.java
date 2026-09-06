package com.workin.backend.platformadmin.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two catalogues the settings page is built from, both hard-coded in
 * legacy and both reproduced as constants here
 * ({@code app_content_helper.php}, {@code configs_helper.php}).
 *
 * <p>These are not rows. {@code app_content_fixed_keys()} and
 * {@code configs_definitions()} return literals in PHP, and the tables behind
 * them hold only values -- so a key that is not in this file cannot be written
 * even if it exists in the database, which is the property
 * {@code app_content_save()} depends on for its allowlist.
 */
public final class SettingsCatalog {

	private SettingsCatalog() {
	}

	/**
	 * {@code app_content_fixed_keys()}: the only three keys the content tab may
	 * write. The allowlist is the guard -- {@code app_content_save()} returns
	 * false for anything else rather than creating a row.
	 */
	public static final List<String> APP_CONTENT_KEYS =
			List.of("compliance", "how_to_use", "terms_and_conditions");

	/** {@code app_content_definitions()}: labels and icon, both languages. */
	public record ContentDefinition(String key, String labelAr, String labelEn, String icon) {
	}

	public static final Map<String, ContentDefinition> APP_CONTENT = Map.of(
			"compliance",
			new ContentDefinition("compliance", "سياسة الامتثال", "Compliance policy", "shield"),
			"how_to_use",
			new ContentDefinition("how_to_use", "كيفية الاستخدام", "How to use", "guide"),
			"terms_and_conditions",
			new ContentDefinition("terms_and_conditions", "الشروط والأحكام",
					"Terms & conditions", "document"));

	/**
	 * One system configuration key.
	 *
	 * @param defaultValue what {@code configs_load_map()} seeds when the row is
	 *     absent -- {@code ''} for most, {@code "true"} for the two export
	 *     toggles, which therefore default to on
	 */
	public record ConfigDefinition(
			String key, String type, String group, String labelKey, String hintKey,
			String defaultValue) {
	}

	public static final String GROUP_VERSIONS = "versions";

	public static final String GROUP_MAINTENANCE = "maintenance";

	public static final String GROUP_FEATURES = "features";

	public static final String GROUP_GENERAL = "general";

	/** {@code configs_group_order()}: the order the tab renders groups in. */
	public static final List<String> GROUP_ORDER =
			List.of(GROUP_VERSIONS, GROUP_MAINTENANCE, GROUP_FEATURES, GROUP_GENERAL);

	private static final List<String> PLATFORMS = List.of("android", "ios", "windows", "mac");

	/**
	 * {@code configs_definitions()}, in its original order: the per-platform
	 * pairs first, then the singletons.
	 */
	public static final Map<String, ConfigDefinition> CONFIGS = configs();

	private static Map<String, ConfigDefinition> configs() {
		Map<String, ConfigDefinition> defs = new LinkedHashMap<>();
		for (String platform : PLATFORMS) {
			put(defs, "min_" + platform + "_build_number", "number", GROUP_VERSIONS,
					"config_min_" + platform + "_build", null, "");
			put(defs, platform + "_app_under_maintenance", "boolean", GROUP_MAINTENANCE,
					"config_" + platform + "_maintenance", null, "");
		}
		put(defs, "website_url", "url", GROUP_GENERAL, "config_website_url", null, "");
		put(defs, "show_banners", "boolean", GROUP_FEATURES, "config_show_banners", null, "");
		put(defs, "show_compliance", "boolean", GROUP_FEATURES, "config_show_compliance", null, "");
		put(defs, "show_export_fingerprints_sheet", "boolean", GROUP_FEATURES,
				"config_show_export_fingerprints_sheet",
				"config_show_export_fingerprints_sheet_hint", "true");
		put(defs, "show_export_overall_sheet", "boolean", GROUP_FEATURES,
				"config_show_export_overall_sheet", "config_show_export_overall_sheet_hint", "true");
		put(defs, "is_daylight_saving", "boolean", GROUP_FEATURES,
				"config_is_daylight_saving", "config_is_daylight_saving_hint", "");
		put(defs, "attendance_excel_import_available_from", "date", GROUP_FEATURES,
				"config_attendance_excel_import_from",
				"config_attendance_excel_import_from_hint", "");
		put(defs, "support_whatsapp", "text", GROUP_GENERAL,
				"config_support_whatsapp", "config_support_whatsapp_hint", "");
		return java.util.Collections.unmodifiableMap(defs);
	}

	private static void put(Map<String, ConfigDefinition> defs, String key, String type,
			String group, String labelKey, String hintKey, String defaultValue) {
		defs.put(key, new ConfigDefinition(key, type, group, labelKey, hintKey, defaultValue));
	}

	/** {@code configs_group_label()}. */
	public static String groupLabelKey(String group) {
		return switch (group) {
			case GROUP_VERSIONS -> "config_group_versions";
			case GROUP_MAINTENANCE -> "config_group_maintenance";
			case GROUP_FEATURES -> "config_group_features";
			default -> "config_group_general";
		};
	}

}
