package com.workin.legacy.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import com.workin.legacy.LegacyValues;

/**
 * {@code pick_label()} and {@code setting_definition_description_fields()}
 * ({@code helpers/i18n.php:57-81}).
 *
 * <h2>The fallback crosses languages before it reaches the default</h2>
 * <p>{@code $loc === 'ar' ? ($label_ar ?? $label_en) : ($label_en ?? $label_ar)}
 * -- so an Arabic request against a row with no {@code label_ar} answers in
 * <b>English</b> rather than falling straight to the key. The supplied fallback
 * is reached only when <em>both</em> labels are null, or when the chosen one
 * trims to empty.
 *
 * <p>Note the asymmetry between null and blank: the language pick uses
 * {@code ??}, which only skips <em>null</em>, so a present-but-empty
 * {@code label_ar} is chosen for an Arabic request and then trims to empty --
 * landing on the fallback rather than on {@code label_en}. A null and an empty
 * string therefore produce different answers, and the regression pins both.
 */
public final class LegacySettingLabels {

	private LegacySettingLabels() {
	}

	/**
	 * @param locale {@code app_locale()}'s answer, {@code "ar"} or {@code "en"}
	 * @param fallback returned when neither label yields text; may be null
	 */
	public static String pick(String locale, String labelAr, String labelEn, String fallback) {
		String chosen = "ar".equals(locale)
				? (labelAr != null ? labelAr : labelEn)
				: (labelEn != null ? labelEn : labelAr);
		// The emptiness test uses PHP's trim character set (" \t\n\r\0\x0B"),
		// which is narrower than Java's -- a label of a single form feed is
		// non-blank to PHP and is returned unchanged. The returned value is
		// still the untrimmed original, because PHP returns $s, not trim($s).
		if (chosen == null || LegacyValues.phpTrim(chosen).isEmpty()) {
			return fallback;
		}
		return chosen;
	}

	/**
	 * The three description keys a {@code setting_definitions} row contributes.
	 *
	 * <p>Its fallback is {@code null}, not the setting key -- so a definition
	 * with no descriptions carries {@code "description": null} rather than
	 * repeating its key, which is what {@code label} would have done.
	 */
	public static Map<String, Object> descriptionFields(
			String locale, String descriptionAr, String descriptionEn) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("description_ar", descriptionAr);
		out.put("description_en", descriptionEn);
		out.put("description", pick(locale, descriptionAr, descriptionEn, null));
		return out;
	}
}
