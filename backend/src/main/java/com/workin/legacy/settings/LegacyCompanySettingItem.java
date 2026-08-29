package com.workin.legacy.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The company-setting item shape, which {@code list.php}, {@code one.php},
 * {@code create.php} and {@code update.php} each build independently in PHP --
 * {@code build_company_setting_item()} is literally duplicated between
 * {@code create.php} and {@code update.php}, and {@code list.php} and
 * {@code one.php} inline the same seventeen keys again.
 *
 * <p>Four copies that must agree is the shape the change-propagation rule
 * exists to prevent, so the port has one. The key order below is the order all
 * four PHP copies build, and it is part of the wire contract.
 *
 * <h2>{@code company_setting_id} is 0, not null, when nothing is set</h2>
 * <p>Every copy casts with {@code (int) (... ?? 0)}, so a definition a company
 * has never touched still carries the key with a zero. {@code updated_at} in
 * the same row is {@code null} instead. Two absent-value conventions in
 * adjacent keys of the same object.
 */
public final class LegacyCompanySettingItem {

	private LegacyCompanySettingItem() {
	}

	/** One selectable or selected value, as the {@code options}/{@code selected} arrays carry it. */
	public static Map<String, Object> option(String locale, String value, String labelAr, String labelEn) {
		String text = value == null ? "" : value;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("value", text);
		out.put("label", LegacySettingLabels.pick(locale, labelAr, labelEn, text));
		out.put("label_ar", labelAr);
		out.put("label_en", labelEn);
		return out;
	}

	public static Map<String, Object> of(
			String locale, LegacySettingsStore.Definition definition,
			long companySettingId, String updatedAt,
			List<LegacySettingsStore.AllowedValue> options,
			List<LegacySettingsStore.AllowedValue> selected) {

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("setting_definition_id", definition.id());
		out.put("company_setting_id", companySettingId);
		out.put("setting_key", definition.settingKey());
		out.put("label", LegacySettingLabels.pick(
				locale, definition.labelAr(), definition.labelEn(), definition.settingKey()));
		out.put("label_ar", definition.labelAr());
		out.put("label_en", definition.labelEn());
		out.putAll(LegacySettingLabels.descriptionFields(
				locale, definition.descriptionAr(), definition.descriptionEn()));
		out.put("icon_data", definition.iconData());
		out.put("is_multi", definition.isMulti());
		out.put("is_required", definition.isRequired());
		out.put("sort_order", definition.sortOrder());
		out.put("options", shape(locale, options));
		out.put("selected", shape(locale, selected));
		out.put("updated_at", updatedAt);
		return out;
	}

	private static List<Map<String, Object>> shape(
			String locale, List<LegacySettingsStore.AllowedValue> rows) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (LegacySettingsStore.AllowedValue row : rows) {
			out.add(option(locale, row.value(), row.labelAr(), row.labelEn()));
		}
		return out;
	}
}
