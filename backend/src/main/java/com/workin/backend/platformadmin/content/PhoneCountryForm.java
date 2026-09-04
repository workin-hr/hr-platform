package com.workin.backend.platformadmin.content;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates a submitted phone-country row, reproducing
 * {@code dashboard_phone_country_validate_post()} rule for rule.
 *
 * <p>Separate from the store and the controller because these rules are
 * the interesting part: they are what stops a bad dial code reaching a
 * table every mobile client reads at startup. A rule that lives in a
 * controller method is a rule nothing tests directly.
 */
public final class PhoneCountryForm {

	/** {@code /^\+\d{1,4}$/} -- a leading plus and up to four digits, nothing else. */
	private static final Pattern COUNTRY_CODE = Pattern.compile("^\\+\\d{1,4}$");

	private static final int MIN_PHONE_LENGTH = 4;

	private static final int MAX_PHONE_LENGTH = 15;

	/** @param errorKey a message key, or null when {@link #country} is present */
	public record Result(PhoneCountry country, String errorKey) {

		public boolean ok() {
			return this.country != null;
		}

		static Result rejected(String errorKey) {
			return new Result(null, errorKey);
		}
	}

	private PhoneCountryForm() {
	}

	public static Result validate(
			String countryCode, String nameAr, String nameEn, String flagEmoji,
			String phoneLength, String prefixes, boolean active, String sortOrder) {

		String code = trimToEmpty(countryCode);
		if (!COUNTRY_CODE.matcher(code).matches()) {
			return Result.rejected("error_invalid_country");
		}

		String ar = trimToEmpty(nameAr);
		String en = trimToEmpty(nameEn);
		if (ar.isEmpty() || en.isEmpty()) {
			return Result.rejected("error_required");
		}

		int length = parseInt(phoneLength, 0);
		if (length < MIN_PHONE_LENGTH || length > MAX_PHONE_LENGTH) {
			return Result.rejected("phone_length_invalid");
		}

		List<String> parsed = parsePrefixes(prefixes);
		for (String prefix : parsed) {
			// A prefix as long as the whole number would match every number
			// in the country, which is the same as having no rule at all.
			if (prefix.length() >= length) {
				return Result.rejected("phone_prefix_invalid");
			}
		}

		return new Result(new PhoneCountry(0L, code, ar, en, trimToEmpty(flagEmoji),
				length, parsed, active, parseInt(sortOrder, 0)), null);
	}

	/**
	 * {@code phone_country_decode_prefixes()} on the input side: the form
	 * field is free text, so commas, whitespace and newlines all separate.
	 */
	static List<String> parsePrefixes(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		List<String> parsed = new ArrayList<>();
		for (String part : raw.split("[,\\s]+")) {
			String value = part.trim();
			if (!value.isEmpty() && !parsed.contains(value)) {
				parsed.add(value);
			}
		}
		return List.copyOf(parsed);
	}

	private static String trimToEmpty(String value) {
		return value == null ? "" : value.trim();
	}

	private static int parseInt(String value, int fallback) {
		try {
			return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

}
