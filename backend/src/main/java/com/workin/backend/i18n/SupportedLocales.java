package com.workin.backend.i18n;

import java.util.List;
import java.util.Locale;

/**
 * The single registry of languages this API serves. Adding a language
 * = add its Locale here + add i18n/messages_<lang>.properties
 * (MessageCatalogSyncTest enforces full key parity). Nothing else
 * changes — the spec's extensibility contract.
 */
public final class SupportedLocales {

	public static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, Locale.forLanguageTag("ar"));

	public static final Locale DEFAULT = Locale.ENGLISH;

	/**
	 * Legacy app_locale()'s param rule, generalized: match by language
	 * prefix against SUPPORTED ("ar", "ar-EG" -> Arabic); anything
	 * unrecognized -> English. For ar/en inputs this is exactly
	 * legacy's behavior.
	 */
	public static Locale fromLangParam(String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT;
		}
		String tag = value.trim().toLowerCase(Locale.ROOT);
		for (Locale supported : SUPPORTED) {
			String language = supported.getLanguage();
			if (tag.equals(language) || tag.startsWith(language + "-") || tag.startsWith(language + "_")) {
				return supported;
			}
		}
		return DEFAULT;
	}

	/** RFC-conformant header matching, restricted to SUPPORTED; malformed -> English. */
	public static Locale fromAcceptLanguage(String header) {
		if (header == null || header.isBlank()) {
			return DEFAULT;
		}
		try {
			Locale matched = Locale.lookup(Locale.LanguageRange.parse(header), SUPPORTED);
			return matched != null ? matched : DEFAULT;
		} catch (IllegalArgumentException malformed) {
			return DEFAULT;
		}
	}

	private SupportedLocales() {
	}

}
