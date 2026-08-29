package com.workin.legacy.phone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code phone_country_public_row()} ({@code phone_countries_helper.php:282-299})
 * -- the wire shape {@code phone_countries/list.php} returns.
 *
 * <h2>The language comes from the raw header, not from {@code app_locale()}</h2>
 * <p>{@code phone_countries_public_rows(null)} passes a null language, so
 * {@code phone_country_localized_name()} falls back to
 * {@code $_SERVER['HTTP_ACCEPT_LANGUAGE'] ?? 'ar'} and tests it with
 * {@code str_starts_with($lang, 'en')} after lowercasing and trimming. That is
 * <b>not</b> the same rule the rest of the API uses: {@code Accept-Language:
 * ar,en;q=0.8} is Arabic here because the string starts with {@code ar}, and a
 * missing header is Arabic rather than the platform default. Reproduced exactly
 * -- routing this through the shared locale resolver would change which name a
 * client receives.
 *
 * <h2>The fallback chain has a quirk worth keeping</h2>
 * <p>PHP is {@code trim($chosen) ?: trim($row['name_ar'] ?? $row['name_en'] ?? '')}.
 * The {@code ?:} fires on any falsy value, so an <em>empty</em> localized name
 * falls back -- and the fallback prefers {@code name_ar} whichever language was
 * asked for. An English request against a row with a blank {@code name_en}
 * therefore answers in Arabic, not with an empty string.
 *
 * <p>Note also that {@code $row['name_ar'] ?? $row['name_en']} is a
 * <em>null</em> coalesce, not a falsy one: a present-but-empty {@code name_ar}
 * wins over a populated {@code name_en}, and the whole expression is then
 * empty. Both branches are reproduced below.
 */
public final class LegacyPhoneCountryPublicRow {

	private LegacyPhoneCountryPublicRow() {
	}

	/**
	 * @param acceptLanguage the raw {@code Accept-Language} header, or null
	 *     when the client sent none
	 */
	public static Map<String, Object> of(LegacyPhoneCountry row, String acceptLanguage) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", row.id());
		out.put("country_code", text(row.countryCode()));
		out.put("name", localizedName(row, acceptLanguage));
		out.put("name_ar", text(row.nameAr()));
		out.put("name_en", text(row.nameEn()));
		out.put("flag_emoji", text(row.flagEmoji()));
		out.put("phone_length", row.phoneLength());
		out.put("phone_prefixes", LegacyPhoneNumbers.decodePrefixes(row.phonePrefixes()));
		out.put("example_number", exampleNumber(row));
		out.put("sort_order", row.sortOrder());
		out.put("is_active", row.isActive());
		return out;
	}

	/** {@code phone_country_localized_name($row, null)}. */
	static String localizedName(LegacyPhoneCountry row, String acceptLanguage) {
		String language = (acceptLanguage == null ? "ar" : acceptLanguage).trim().toLowerCase(Locale.ROOT);
		boolean english = language.startsWith("en");

		String chosen = text(english ? row.nameEn() : row.nameAr()).trim();
		if (!chosen.isEmpty()) {
			return chosen;
		}
		// `$row['name_ar'] ?? $row['name_en']` -- null-coalescing, so a
		// present-but-empty name_ar shadows name_en entirely.
		String fallback = row.nameAr() != null ? row.nameAr() : text(row.nameEn());
		return fallback.trim();
	}

	/**
	 * {@code phone_country_example_number()}: the first prefix padded with
	 * zeroes to the country's length, or all zeroes when there is no prefix.
	 *
	 * <p>{@code max(1, ...)} means a zero or negative {@code phone_length}
	 * still yields one character, and {@code max(0, $length - strlen($prefix))}
	 * means a prefix longer than the length is returned unpadded rather than
	 * truncated -- so the result can be longer than {@code phone_length}.
	 */
	static String exampleNumber(LegacyPhoneCountry row) {
		List<String> prefixes = LegacyPhoneNumbers.decodePrefixes(row.phonePrefixes());
		int length = Math.max(1, row.phoneLength());
		String prefix = prefixes.isEmpty() ? "" : prefixes.get(0);
		if (prefix.isEmpty()) {
			return "0".repeat(length);
		}
		return prefix + "0".repeat(Math.max(0, length - prefix.length()));
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
