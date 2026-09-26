package com.workin.legacy.phone;

import java.text.Normalizer;
import java.util.Optional;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.workin.legacy.LegacyValues;

/**
 * The one place a phone number is parsed, validated and canonicalised
 * (ADR-0020, D-291). Every registration, login, lookup, uniqueness check,
 * import, update, OTP and login-throttle path goes through {@link #parse}.
 *
 * <p>Validity is Google libphonenumber's metadata and nothing else: no
 * per-country pattern is written in this repository. A number is identified
 * by its E.164 form, so {@code 01012345678}, {@code (010) 1234-5678},
 * {@code +20 10 1234 5678} and {@code 0020 10 1234 5678} are one number.
 *
 * <p>How the country is decided, in order:
 * <ol>
 * <li>input written internationally -- a leading {@code +} or {@code 00} --
 *     carries its own country, and any context is ignored;</li>
 * <li>otherwise the explicit context, a dial code such as {@code +966} (a
 *     request's or a stored row's {@code country_code});</li>
 * <li>otherwise Egypt, the product's home region.</li>
 * </ol>
 * A context that maps to no libphonenumber region makes a national number
 * invalid rather than falling back: the number's country is then unknown, and
 * guessing one is exactly what this class exists not to do.
 *
 * <p>Before parsing, NFKC is applied and every decimal digit (Unicode category
 * Nd -- Arabic-Indic, Persian, fullwidth ...) becomes its ASCII digit. Three
 * things are refused outright even though the library would read them:
 * letters (it would read a vanity number, {@code 010ABCDEFGH}, as digits),
 * characters that are numbers without being decimal digits (circled, dingbat,
 * superscript -- NFKC would otherwise turn {@code ⓪①} into {@code 01}), and
 * an extension. An identity is a whole number written with digits.
 */
public final class CanonicalPhones {

	/** Egypt: the region a national number with no other context belongs to. */
	public static final String DEFAULT_REGION = "EG";

	private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

	/** libphonenumber's own region for "no region": international input only. */
	private static final String UNKNOWN_REGION = "ZZ";

	private CanonicalPhones() {
	}

	/**
	 * @param raw the value as received -- a JSON string or number, read with
	 *        PHP's {@code (string)} cast so a numeric phone reads as PHP would
	 * @param countryContext a dial code ({@code +20}, {@code 20}, {@code 0020})
	 *        or null/blank for Egypt
	 * @return the number, or empty when it is not a valid phone number
	 */
	public static Optional<CanonicalPhone> parse(Object raw, String countryContext) {
		String text;
		try {
			text = LegacyValues.toPhpString(raw);
		} catch (RuntimeException ex) {
			return Optional.empty();
		}
		Optional<String> folded = fold(text);
		if (folded.isEmpty()) {
			return Optional.empty();
		}
		String input = folded.get();
		int start = firstDialable(input);
		if (start < 0) {
			return Optional.empty();
		}
		String context = countryCodeAsRead(countryContext);
		String region;
		if (input.charAt(start) == '+') {
			region = UNKNOWN_REGION;
		} else if (input.startsWith("00", start)) {
			input = input.substring(0, start) + "+" + input.substring(start + 2);
			region = UNKNOWN_REGION;
		} else if (context == null || context.isEmpty()) {
			region = DEFAULT_REGION;
		} else {
			Optional<String> contextRegion = regionForDialCode(context);
			if (contextRegion.isEmpty()) {
				return Optional.empty();
			}
			region = contextRegion.get();
		}
		PhoneNumber number;
		try {
			number = UTIL.parse(input, region);
		} catch (NumberParseException ex) {
			return Optional.empty();
		}
		if (number.hasExtension() || !UTIL.isValidNumber(number)) {
			return Optional.empty();
		}
		String numberRegion = UTIL.getRegionCodeForNumber(number);
		if (numberRegion == null) {
			return Optional.empty();
		}
		PhoneNumberType type = UTIL.getNumberType(number);
		return Optional.of(new CanonicalPhone(
				UTIL.format(number, PhoneNumberFormat.E164),
				number.getCountryCode(),
				numberRegion,
				UTIL.getNationalSignificantNumber(number),
				asciiDigits(UTIL.format(number, PhoneNumberFormat.NATIONAL)),
				type == PhoneNumberType.MOBILE || type == PhoneNumberType.FIXED_LINE_OR_MOBILE));
	}

	/**
	 * A {@code country_code} as every phone rule reads it: PHP's
	 * {@code trim()} characters (NUL and vertical tab among them) and Unicode
	 * whitespace removed from both ends, or null for null. The comparison that
	 * decides whether a written code changes a number, the value such a write
	 * stores, and every reading of a stored code go through this one method,
	 * so a code cannot be judged unchanged by one rule and unreadable by
	 * another.
	 */
	public static String countryCodeAsRead(Object countryCode) {
		if (countryCode == null) {
			return null;
		}
		String text = LegacyValues.toPhpString(countryCode);
		int start = 0;
		int end = text.length();
		while (start < end && trimmed(text.charAt(start))) {
			start++;
		}
		while (end > start && trimmed(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(start, end);
	}

	/**
	 * The canonical {@code +<cc>} a code names -- {@code 966},
	 * {@code +0000000966} and {@code "+966\0"} are all {@code +966} -- or
	 * empty when it names no country with national numbers.
	 */
	public static Optional<String> canonicalDialCode(Object countryCode) {
		return regionForDialCode(countryCodeAsRead(countryCode))
				.map(region -> "+" + UTIL.getCountryCodeForRegion(region));
	}

	private static boolean trimmed(char c) {
		// PHP's trim() set is " \t\n\r\0\x0B"; all but NUL are Java whitespace.
		return c == '\0' || Character.isWhitespace(c);
	}

	/**
	 * The region a dial code names: {@code +966}, {@code 966} and
	 * {@code 00966} all give {@code SA}; {@code +1} gives the main region of
	 * its plan. Empty for a blank code or one the metadata does not know.
	 */
	public static Optional<String> regionForDialCode(String dialCode) {
		if (dialCode == null) {
			return Optional.empty();
		}
		String digits = countryCodeAsRead(dialCode);
		if (digits.startsWith("+")) {
			digits = digits.substring(1);
		}
		while (digits.startsWith("0")) {
			digits = digits.substring(1);
		}
		if (digits.isEmpty() || digits.length() > 3 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
			return Optional.empty();
		}
		String region = UTIL.getRegionCodeForCountryCode(Integer.parseInt(digits));
		// "001" is the metadata's non-geographic entity (+800, +882 ...): no
		// national numbers exist under it.
		if (UNKNOWN_REGION.equals(region) || "001".equals(region)) {
			return Optional.empty();
		}
		return Optional.of(region);
	}

	/**
	 * NFKC, then every decimal digit as ASCII; empty when the text holds a
	 * letter or a non-decimal number character, before or after folding.
	 */
	static Optional<String> fold(String text) {
		if (containsRefused(text)) {
			return Optional.empty();
		}
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
		if (containsRefused(normalized)) {
			return Optional.empty();
		}
		StringBuilder folded = new StringBuilder(normalized.length());
		for (int index = 0; index < normalized.length(); ) {
			int codePoint = normalized.codePointAt(index);
			index += Character.charCount(codePoint);
			if (Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER) {
				int digit = Character.digit(codePoint, 10);
				if (digit < 0) {
					return Optional.empty();
				}
				folded.append((char) ('0' + digit));
			} else {
				folded.appendCodePoint(codePoint);
			}
		}
		return Optional.of(folded.toString());
	}

	private static boolean containsRefused(String text) {
		return text.codePoints().anyMatch(codePoint -> Character.isLetter(codePoint)
				|| Character.getType(codePoint) == Character.OTHER_NUMBER
				|| Character.getType(codePoint) == Character.LETTER_NUMBER);
	}

	/** The index of the first {@code +} or digit, or -1. */
	private static int firstDialable(String input) {
		for (int index = 0; index < input.length(); index++) {
			char character = input.charAt(index);
			if (character == '+' || (character >= '0' && character <= '9')) {
				return index;
			}
		}
		return -1;
	}

	/** The ASCII digits of {@code text}, in order. */
	public static String asciiDigits(String text) {
		StringBuilder digits = new StringBuilder(text.length());
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (character >= '0' && character <= '9') {
				digits.append(character);
			}
		}
		return digits.toString();
	}
}
