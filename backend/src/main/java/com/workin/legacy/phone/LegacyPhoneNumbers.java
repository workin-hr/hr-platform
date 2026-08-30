package com.workin.legacy.phone;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The normalization and validation half of legacy's phone helpers
 * ({@code helpers/phone_validator_helper.php} and the pure parts of
 * {@code helpers/phone_countries_helper.php}).
 *
 * <p>This decides which phone numbers Phase 1 accepts as employee identifiers,
 * so it is a literal port of the PHP graph -- including the parts that look
 * like quirks. It is deliberately <b>not</b> libphonenumber or any other
 * "correct" international validator: legacy's rules are the contract, and a
 * stricter or looser library would silently change which people can log in.
 *
 * <p>Everything that needs a country definition goes through
 * {@link LegacyPhoneCountries}; everything else is static and testable without
 * a database, matching how PHP splits the two files.
 */
@Service
public class LegacyPhoneNumbers {

	/** {@code /^01(0|1|2|5)\d{8}$/} -- the Egyptian local form legacy stores. */
	private static final Pattern EGYPT_LOCAL = Pattern.compile("^01(0|1|2|5)\\d{8}$");

	/** {@code /^1(0|1|2|5)\d{8}$/} -- the same number with the leading zero lost. */
	private static final Pattern EGYPT_NO_LEADING_ZERO = Pattern.compile("^1(0|1|2|5)\\d{8}$");

	/** {@code /^20(1(0|1|2|5)\d{8})$/} -- pasted international without the plus. */
	private static final Pattern EGYPT_INTERNATIONAL = Pattern.compile("^20(1(0|1|2|5)\\d{8})$");

	private static final Pattern SAUDI_LOCAL = Pattern.compile("^05\\d{8}$");
	private static final Pattern SAUDI_NO_LEADING_ZERO = Pattern.compile("^5\\d{8}$");
	private static final Pattern UAE_LOCAL = Pattern.compile("^05(0|2|4|5|6|8)\\d{7}$");
	private static final Pattern UAE_NO_LEADING_ZERO = Pattern.compile("^5(0|2|4|5|6|8)\\d{7}$");

	private static final Pattern NON_DIGITS = Pattern.compile("\\D+");
	private static final Pattern PREFIX_SEPARATORS = Pattern.compile("[\\s,;]+");
	private static final Pattern SCIENTIFIC = Pattern.compile("^\\d+\\.?\\d*E[+-]?\\d+$", Pattern.CASE_INSENSITIVE);
	private static final Pattern TRAILING_ZEROS = Pattern.compile("^\\d+\\.0+$");

	/** The application's own mapper (Jackson 3, already on the classpath). */
	private static final ObjectMapper JSON = new ObjectMapper();

	private final LegacyPhoneCountries countries;

	public LegacyPhoneNumbers(LegacyPhoneCountries countries) {
		this.countries = countries;
	}

	/** {@code phone_digits_only()}: {@code preg_replace('/\D+/', '', $phone)}. */
	public static String digitsOnly(String phone) {
		return phone == null ? "" : NON_DIGITS.matcher(phone).replaceAll("");
	}

	/**
	 * {@code phone_excel_cell_to_raw()}: spreadsheet cells arrive as floats,
	 * scientific notation or a trailing {@code .0}, and all three have to become
	 * the digit string a human typed.
	 *
	 * <p>Java sees the same shapes through Jackson: an integral JSON number is
	 * an {@code Integer}/{@code Long}, a fractional one a {@code Double}, so the
	 * type branches are kept rather than collapsed into {@code toString()}.
	 */
	public static String excelCellToRaw(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
			return value.toString();
		}
		if (value instanceof Float || value instanceof Double) {
			// sprintf('%.0f', $value) -- no exponent, no decimal point.
			return new BigDecimal(value.toString()).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
		}
		if (value instanceof BigDecimal decimal) {
			return decimal.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
		}
		String raw = value.toString().trim();
		if (raw.isEmpty()) {
			return "";
		}
		if (SCIENTIFIC.matcher(raw).matches()) {
			return new BigDecimal(raw).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
		}
		if (TRAILING_ZEROS.matcher(raw).matches()) {
			return raw.substring(0, raw.indexOf('.'));
		}
		return raw;
	}

	/**
	 * {@code phone_country_decode_prefixes()}: {@code json_decode($raw, true)}
	 * first, then a {@code [\s,;]+} split of whatever else the column held,
	 * then digits-only, blanks dropped, duplicates removed, order preserved.
	 *
	 * <p>Decoding uses the application's own Jackson mapper rather than a
	 * hand-written parser, because PHP's decoder is a real JSON decoder: a
	 * comma inside a string ({@code ["01,0"]}) and an escape sequence
	 * ({@code ["010"]}) both have to survive it, and a split
	 * on {@code ,} silently corrupts the first into two prefixes.
	 *
	 * <p><b>A JSON object root takes the array branch, not the fallback.</b>
	 * {@code json_decode($raw, true)} turns an object into an associative array
	 * and {@code is_array()} is then true, so PHP iterates the object's
	 * <em>values</em>. Verified against PHP 8.3: {@code {"a":"01,0"}} yields
	 * {@code ["010"]}, where a delimiter split would have yielded
	 * {@code ["01","0"]}. Only a root that decodes to a scalar or fails to
	 * decode reaches the split.
	 *
	 * <p>Element coercion is PHP's {@code (string)} cast, which is what
	 * {@link LegacyValues#toPhpString} already reproduces: numbers stringify,
	 * {@code true} becomes {@code "1"}, {@code false} and {@code null} become
	 * the empty string, and a nested array or object becomes {@code "Array"} --
	 * contributing no digits, so {@code [{"prefix":"010"},["011"]]} decodes to
	 * nothing at all.
	 */
	public static List<String> decodePrefixes(Object raw) {
		if (raw == null || "".equals(raw)) {
			return List.of();
		}
		List<String> items;
		if (raw instanceof Iterable<?> iterable) {
			items = new ArrayList<>();
			iterable.forEach(item -> items.add(LegacyValues.toPhpString(item)));
		} else {
			String text = raw.toString();
			items = decodeJsonArrayOrObject(text).orElseGet(() -> {
				List<String> split = new ArrayList<>();
				for (String part : PREFIX_SEPARATORS.split(text)) {
					if (!part.isEmpty()) {
						split.add(part);
					}
				}
				return split;
			});
		}
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (String item : items) {
			String digits = digitsOnly(item);
			if (!digits.isEmpty()) {
				unique.add(digits);
			}
		}
		return List.copyOf(unique);
	}

	/** {@code phone_country_normalize_dial_code()}: {@code 20} and {@code 020} both become {@code +20}. */
	public static String normalizeDialCode(String countryCode) {
		String code = countryCode == null ? "" : countryCode.trim();
		if (code.isEmpty()) {
			return "";
		}
		if (!code.startsWith("+")) {
			String digits = digitsOnly(code);
			if (!digits.isEmpty()) {
				code = "+" + digits;
			}
		}
		return code;
	}

	/**
	 * {@code phone_lookup_variants()}: the forms the same Egyptian number can
	 * already be stored in. Used only for the global uniqueness precheck, never
	 * for storage.
	 */
	public static List<String> lookupVariants(String phone) {
		String digits = digitsOnly(phone == null ? "" : phone.trim());
		if (digits.isEmpty()) {
			return List.of();
		}
		LinkedHashSet<String> variants = new LinkedHashSet<>();
		variants.add(digits);
		if (EGYPT_LOCAL.matcher(digits).matches()) {
			variants.add(digits.substring(1));
			variants.add("20" + digits.substring(1));
		} else if (EGYPT_NO_LEADING_ZERO.matcher(digits).matches()) {
			variants.add("0" + digits);
			variants.add("20" + digits);
		} else {
			java.util.regex.Matcher international = EGYPT_INTERNATIONAL.matcher(digits);
			if (international.matches()) {
				variants.add("0" + international.group(1));
				variants.add(international.group(1));
			}
		}
		return List.copyOf(variants);
	}

	/**
	 * {@code phone_digits_sql_expr()}: the stored value with {@code + - space (
	 * )} removed, so a formatted number still matches a variant.
	 */
	public static String digitsSqlExpression(String column) {
		return "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(TRIM(COALESCE(" + column
				+ ", '')), '+', ''), '-', ''), ' ', ''), '(', ''), ')', '')";
	}

	/**
	 * {@code phone_sql_match_clause()} ({@code phone_validator_helper.php:114-125}):
	 * a {@code WHERE} fragment matching any stored formatting of the same
	 * number, and the values to bind with it.
	 *
	 * <p>A phone with no digits produces the literal {@code "0=1"} and no
	 * binds, so a query built from it matches nothing rather than everything.
	 * That guard is the whole reason this returns a fragment instead of a list
	 * of variants for the caller to splice.
	 */
	public static MatchClause sqlMatchClause(String column, String phone) {
		List<String> variants = lookupVariants(phone);
		if (variants.isEmpty()) {
			return new MatchClause("0=1", List.of());
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(variants.size(), "?"));
		return new MatchClause(digitsSqlExpression(column) + " IN (" + placeholders + ")", variants);
	}

	/** The fragment and its binds, kept together so they cannot drift apart. */
	public record MatchClause(String sql, List<String> binds) {
	}

	/**
	 * {@code phones_are_equivalent()}: true when the two numbers share any
	 * lookup variant. Either side having no digits is false, so an empty phone
	 * is not equivalent to another empty phone.
	 */
	public static boolean areEquivalent(String phoneA, String phoneB) {
		List<String> a = lookupVariants(phoneA);
		List<String> b = lookupVariants(phoneB);
		if (a.isEmpty() || b.isEmpty()) {
			return false;
		}
		for (String candidate : a) {
			if (b.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

	/** {@code phone_country_resolve_code()}: a known dial code, else the first configured one, else {@code +20}. */
	public String resolveCode(String countryCode) {
		String code = normalizeDialCode(countryCode);
		List<String> codes = countries.dialCodes();
		if (codes.isEmpty()) {
			return "+20";
		}
		if (!code.isEmpty() && codes.contains(code)) {
			return code;
		}
		return codes.get(0);
	}

	/**
	 * {@code phone_country_normalize_local()}: the canonical local form legacy
	 * stores.
	 *
	 * <p>Order matters and is preserved: strip the dial prefix from a pasted
	 * international number first (only when what remains is longer than seven
	 * digits), then apply Egypt's hard-coded rule, and only then consult the
	 * configured length and prefixes for the missing-leading-zero case. Egypt
	 * never reaches the table -- {@code +20} is special-cased in PHP, which is
	 * why an odd {@code phone_countries} row cannot break Egyptian numbers.
	 */
	public String normalizeLocal(String countryCode, Object localPhone) {
		String digits = digitsOnly(excelCellToRaw(localPhone));
		if (digits.isEmpty()) {
			return "";
		}
		String code = normalizeDialCode(countryCode);
		String dialDigits = digitsOnly(code);
		if (!dialDigits.isEmpty() && digits.startsWith(dialDigits)
				&& digits.length() > dialDigits.length() + 7) {
			digits = digits.substring(dialDigits.length());
		}
		if ("+20".equals(code) || "20".equals(dialDigits)) {
			if (EGYPT_LOCAL.matcher(digits).matches()) {
				return digits;
			}
			if (EGYPT_NO_LEADING_ZERO.matcher(digits).matches()) {
				return "0" + digits;
			}
			return digits;
		}
		Optional<LegacyPhoneCountry> row = countries.find(code.isEmpty() ? countries.defaultCode() : code);
		if (row.isEmpty()) {
			return digits;
		}
		int length = row.get().phoneLength();
		List<String> prefixes = decodePrefixes(row.get().phonePrefixes());
		if (length > 0 && digits.length() == length) {
			return digits;
		}
		if (length > 0 && digits.length() == length - 1) {
			for (String prefix : prefixes) {
				if (prefix.isEmpty() || !prefix.startsWith("0")) {
					continue;
				}
				String withoutZero = prefix.substring(1);
				if (!withoutZero.isEmpty() && digits.startsWith(withoutZero)) {
					return "0" + digits;
				}
			}
		}
		return digits;
	}

	/**
	 * {@code phone_country_is_valid_local()}. Egypt is decided by the regex
	 * alone ("regardless of DB prefix quirks"); an unknown country falls back to
	 * {@code phone_is_valid_local_legacy()}'s three hard-coded rules and rejects
	 * everything else; a configured country checks length, then prefixes with
	 * both leading-zero spellings accepted.
	 */
	public boolean isValidLocal(String countryCode, Object localPhone) {
		String code = normalizeDialCode(countryCode);
		String digits = normalizeLocal(code, localPhone);
		if (digits.isEmpty()) {
			return false;
		}
		if ("+20".equals(code)) {
			return EGYPT_LOCAL.matcher(digits).matches();
		}
		Optional<LegacyPhoneCountry> row = countries.find(code);
		if (row.isEmpty()) {
			return isValidLocalLegacy(code, digits);
		}
		int length = row.get().phoneLength();
		if (length > 0 && digits.length() != length) {
			return false;
		}
		List<String> prefixes = decodePrefixes(row.get().phonePrefixes());
		if (prefixes.isEmpty()) {
			return true;
		}
		for (String prefix : prefixes) {
			if (prefix.isEmpty()) {
				continue;
			}
			if (digits.startsWith(prefix)) {
				return true;
			}
			// The column may hold 10... while normalization produced 010...
			if (!prefix.startsWith("0") && digits.startsWith("0" + prefix)) {
				return true;
			}
			if (prefix.startsWith("0") && digits.startsWith(prefix.substring(1))) {
				return true;
			}
		}
		return false;
	}

	/** {@code phone_is_valid_local_legacy()}: the pre-table rules, still the fallback for an unknown country. */
	static boolean isValidLocalLegacy(String countryCode, String digits) {
		return switch (countryCode == null ? "" : countryCode) {
			case "+20" -> EGYPT_LOCAL.matcher(digits).matches() || EGYPT_NO_LEADING_ZERO.matcher(digits).matches();
			case "+966" -> SAUDI_LOCAL.matcher(digits).matches() || SAUDI_NO_LEADING_ZERO.matcher(digits).matches();
			case "+971" -> UAE_LOCAL.matcher(digits).matches() || UAE_NO_LEADING_ZERO.matcher(digits).matches();
			default -> false;
		};
	}

	/**
	 * {@code json_decode($raw, true)} plus {@code is_array($decoded)}.
	 *
	 * <p>Returns the values to iterate when the root decodes to a JSON array or
	 * object (both are PHP arrays under {@code assoc = true}), and nothing when
	 * it decodes to a scalar or does not decode at all -- the two cases that
	 * send PHP to the delimiter split.
	 */
	private static Optional<List<String>> decodeJsonArrayOrObject(String text) {
		Object decoded;
		try {
			decoded = JSON.readValue(text, Object.class);
		} catch (JacksonException ex) {
			return Optional.empty();
		}
		Collection<?> values;
		if (decoded instanceof List<?> list) {
			values = list;
		} else if (decoded instanceof Map<?, ?> map) {
			values = map.values();
		} else {
			return Optional.empty();
		}
		List<String> items = new ArrayList<>();
		for (Object value : values) {
			items.add(LegacyValues.toPhpString(value));
		}
		return Optional.of(items);
	}

}
