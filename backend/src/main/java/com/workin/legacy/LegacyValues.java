package com.workin.legacy;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The legacy MySQL contract's value semantics, as pure functions.
 *
 * <p>Phase 1 runs the Java application against the legacy schema
 * unchanged, so three legacy representations have to be read faithfully:
 * {@code tinyint(1)} booleans, {@code '0000-00-00'} zero dates, and
 * lower-snake-case enums. Each is small; each is a place a plausible
 * implementation quietly disagrees with PHP.
 *
 * <p><b>Why these are functions and not {@code AttributeConverter}s.</b>
 * The zero date cannot be converted at the JPA layer at all -- the JDBC
 * driver raises when it reads {@code '0000-00-00'} into a date, before
 * any converter is consulted. The legacy entity therefore carries the
 * raw text and converts here, which is the same rule the ETL already
 * proved: guard the raw value, never cast first and repair after
 * (D-035/A2). Converters wrap these where a converter is safe; the
 * semantics stay in one testable place either way.
 *
 * <p>Everything here is deliberately reachable without a database, so
 * the subtlest part of Phase 1 is provable on a machine with no Docker.
 */
public final class LegacyValues {

	/**
	 * MySQL's zero date, and its datetime form. The only invalid date
	 * pattern observed on the live database
	 * (docs/migration/invalid-date-analysis.md): 22 {@code hire_date}
	 * and 2 {@code birth_date} rows.
	 */
	private static final String ZERO_DATE = "0000-00-00";

	/**
	 * PHP numeric casts consume the numeric prefix of a string instead of requiring the whole
	 * value to be numeric. The exponent is part of the prefix only when it is complete, so
	 * {@code "1efoo"} correctly falls back to the prefix {@code "1"}.
	 */
	private static final Pattern PHP_NUMERIC_PREFIX = Pattern.compile(
			"^\\s*[+-]?(?:(?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?");
	private static final BigDecimal PHP_INT_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
	private static final BigDecimal PHP_INT_MIN = BigDecimal.valueOf(Long.MIN_VALUE);

	private LegacyValues() {
	}

	/**
	 * {@code tinyint(1)} -> boolean, matching PHP's {@code (int) (...) === 1}.
	 *
	 * <p>Strict equality with 1, not {@code != 0}: every call site in
	 * hr-legacy tests these columns that way, so 2 is false there and
	 * has to be false here. Null is false for the same reason -- PHP's
	 * {@code ?? 0} coalesces a missing value to 0 before comparing.
	 */
	public static boolean toBoolean(Integer legacyFlag) {
		return legacyFlag != null && legacyFlag == 1;
	}

	/** Writes stay within the two values legacy uses. */
	public static Integer fromBoolean(boolean value) {
		return value ? 1 : 0;
	}

	/**
	 * A JSON-decoded value converted like PHP's {@code (int)} cast on the legacy 64-bit runtime.
	 *
	 * <p>Numbers truncate toward zero, booleans become 1/0, null and malformed or empty strings
	 * become 0, leading-numeric strings retain their numeric prefix, and PHP arrays become 0/1
	 * according to emptiness. Values outside the signed integer range saturate at the platform
	 * bound; they never use {@link BigDecimal#longValue()}'s wraparound result.
	 */
	public static long toPhpLong(Object raw) {
		BigDecimal value = phpNumericValue(raw);
		if (value.compareTo(PHP_INT_MAX) > 0) {
			return Long.MAX_VALUE;
		}
		if (value.compareTo(PHP_INT_MIN) < 0) {
			return Long.MIN_VALUE;
		}
		return value.longValue();
	}

	/**
	 * A JSON-decoded value converted like PHP's {@code (float)} cast, represented as
	 * {@link BigDecimal} so MariaDB remains the authority for the target column's scale and range
	 * normalization. PHP converts arrays through their 0/1 integer value before converting to
	 * float, so array-shaped JSON follows the same emptiness rule here.
	 */
	public static BigDecimal toPhpDecimal(Object raw) {
		return phpNumericValue(raw);
	}

	/**
	 * A JSON-decoded value converted like PHP's explicit {@code (string)} cast.
	 *
	 * <p>Null and false become the empty string, true becomes {@code "1"}, strings remain
	 * unchanged, and JSON arrays/objects become {@code "Array"}. PHP also raises an
	 * {@code E_WARNING} for that last conversion; D-068 explicitly treats warning display as
	 * diagnostic behavior outside the Phase-1 business response contract while preserving the
	 * converted value. Floating-point formatting follows the legacy runtime's default 14-digit
	 * precision instead of Java collection or number {@code toString()} behavior.
	 */
	public static String toPhpString(Object raw) {
		if (raw == null || Boolean.FALSE.equals(raw)) {
			return "";
		}
		if (Boolean.TRUE.equals(raw)) {
			return "1";
		}
		if (raw instanceof CharSequence sequence) {
			return sequence.toString();
		}
		if (raw instanceof Collection<?> || raw instanceof Map<?, ?> || raw.getClass().isArray()) {
			return "Array";
		}
		if (raw instanceof BigInteger integer) {
			if (integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0
					&& integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0) {
				return integer.toString();
			}
			return phpFloatString(integer.doubleValue());
		}
		if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
			return raw.toString();
		}
		if (raw instanceof Number number) {
			return phpFloatString(number.doubleValue());
		}
		throw new LegacyValueException(
				"Unsupported JSON-decoded value for PHP string conversion: " + raw.getClass().getName());
	}

	/**
	 * PHP {@code empty(...)} for request values produced by {@code json_decode(..., true)}.
	 *
	 * <p>Only null, false, numeric zero, the exact strings {@code ""}/{@code "0"}, and empty PHP
	 * arrays are empty. JSON arrays and objects arrive in Java as collections and maps; Java arrays
	 * are also supported so the shared compatibility function is complete at its public boundary.
	 */
	/**
	 * {@code filter_var($value, FILTER_VALIDATE_BOOLEAN)}.
	 *
	 * <p>Not a cast and not {@code empty()}: the filter recognises a fixed set
	 * of spellings, case-insensitively and after trimming --
	 * {@code 1 true on yes} are true, {@code 0 false off no ""} are false, and
	 * anything else (including {@code 2}, {@code "y"} and {@code "TRUE!"}) is
	 * unrecognised, which without {@code FILTER_NULL_ON_FAILURE} also means
	 * false. {@code employees/delete.php} reads {@code cascade} this way, so
	 * {@code ?cascade=on} really does destroy an employee's history.
	 */
	public static boolean toPhpFilterBoolean(Object raw) {
		if (raw == null) {
			return false;
		}
		if (raw instanceof Boolean flag) {
			return flag;
		}
		if (raw instanceof Number number) {
			return number.doubleValue() == 1.0d;
		}
		String value = toPhpString(raw).trim().toLowerCase(java.util.Locale.ROOT);
		return "1".equals(value) || "true".equals(value) || "on".equals(value) || "yes".equals(value);
	}

	public static boolean isPhpEmpty(Object raw) {
		if (raw == null || Boolean.FALSE.equals(raw)) {
			return true;
		}
		if (raw instanceof Number number) {
			return isNumericZero(number);
		}
		if (raw instanceof CharSequence sequence) {
			return sequence.isEmpty() || "0".contentEquals(sequence);
		}
		if (raw instanceof Collection<?> collection) {
			return collection.isEmpty();
		}
		if (raw instanceof Map<?, ?> map) {
			return map.isEmpty();
		}
		return raw.getClass().isArray() && Array.getLength(raw) == 0;
	}

	/**
	 * Values yielded by PHP {@code foreach} over an array decoded from JSON.
	 *
	 * <p>PHP associative arrays iterate their values, not their keys. Jackson represents the same
	 * JSON object as a map, so returning {@link Map#values()} preserves {@code normalize_id_list()}
	 * behavior. A non-array value produces the same empty input that PHP's normalizer returns.
	 */
	public static Collection<?> phpArrayValues(Object raw) {
		if (raw instanceof Collection<?> collection) {
			return collection;
		}
		if (raw instanceof Map<?, ?> map) {
			return map.values();
		}
		if (raw != null && raw.getClass().isArray()) {
			int length = Array.getLength(raw);
			List<Object> values = new ArrayList<>(length);
			for (int index = 0; index < length; index++) {
				values.add(Array.get(raw, index));
			}
			return values;
		}
		return List.of();
	}

	private static BigDecimal phpNumericValue(Object raw) {
		if (raw == null) {
			return BigDecimal.ZERO;
		}
		if (raw instanceof BigDecimal decimal) {
			return decimal;
		}
		if (raw instanceof Number number) {
			try {
				return new BigDecimal(String.valueOf(number));
			} catch (NumberFormatException ex) {
				return BigDecimal.ZERO;
			}
		}
		if (raw instanceof Boolean bool) {
			return bool ? BigDecimal.ONE : BigDecimal.ZERO;
		}
		if (raw instanceof Collection<?> || raw instanceof Map<?, ?> || raw.getClass().isArray()) {
			return isPhpEmpty(raw) ? BigDecimal.ZERO : BigDecimal.ONE;
		}
		if (!(raw instanceof CharSequence sequence)) {
			return BigDecimal.ZERO;
		}

		Matcher matcher = PHP_NUMERIC_PREFIX.matcher(sequence);
		if (!matcher.find()) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(matcher.group().trim());
		} catch (NumberFormatException ex) {
			return BigDecimal.ZERO;
		}
	}

	private static boolean isNumericZero(Number number) {
		try {
			return new BigDecimal(String.valueOf(number)).signum() == 0;
		} catch (NumberFormatException ex) {
			// PHP treats NAN/INF as non-empty; finite JSON numbers never reach this fallback.
			return number.doubleValue() == 0d;
		}
	}

	private static String phpFloatString(double value) {
		if (Double.isNaN(value)) {
			return "NAN";
		}
		if (Double.isInfinite(value)) {
			return value > 0 ? "INF" : "-INF";
		}

		String formatted = String.format(Locale.ROOT, "%.14G", value);
		int exponentMarker = formatted.indexOf('E');
		if (exponentMarker < 0) {
			return trimFractionZeros(formatted, false);
		}

		String mantissa = trimFractionZeros(formatted.substring(0, exponentMarker), true);
		int exponent = Integer.parseInt(formatted.substring(exponentMarker + 1));
		return mantissa + "E" + (exponent >= 0 ? "+" : "") + exponent;
	}

	private static String trimFractionZeros(String value, boolean retainScientificFraction) {
		if (!value.contains(".")) {
			return retainScientificFraction ? value + ".0" : value;
		}
		int end = value.length();
		while (end > 0 && value.charAt(end - 1) == '0') {
			end--;
		}
		if (end > 0 && value.charAt(end - 1) == '.') {
			return retainScientificFraction ? value.substring(0, end) + "0" : value.substring(0, end - 1);
		}
		return value.substring(0, end);
	}

	/**
	 * Legacy date text -> {@link LocalDate}, with the zero date read as
	 * "no date recorded" -- which is what the column means, and what
	 * D-036 decided for these columns rather than inventing a date.
	 *
	 * <p>Accepts the datetime form too, because the same rule applies to
	 * legacy {@code datetime} columns and truncating to the date half is
	 * what reading a date out of one means.
	 *
	 * @throws LegacyValueException for any other unparseable value. This
	 *         is not defensive vagueness: the live probe found only the
	 *         all-zero form on these columns, so a partial zero date
	 *         ({@code '2020-00-00'}) is an unknown defect. Nulling it
	 *         would turn that into a silently wrong hire date; failing
	 *         means somebody finds out.
	 */
	public static LocalDate toDate(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty() || trimmed.startsWith(ZERO_DATE)) {
			return null;
		}
		String datePart = trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed;
		try {
			return LocalDate.parse(datePart);
		} catch (DateTimeParseException ex) {
			throw new LegacyValueException(
					"Unreadable legacy date '" + raw + "'. Only '" + ZERO_DATE
							+ "' is a known-invalid value on these columns; this is not it.");
		}
	}

	/** Never emits a zero date: absent stays absent. */
	public static String fromDate(LocalDate value) {
		return value == null ? null : value.toString();
	}

	/**
	 * Legacy enum text -> Java enum constant, 1:1 by name.
	 *
	 * <p>Legacy spells them lower snake case
	 * ({@code enum('company_admin','hr','manager','employee')}); Java
	 * spells them upper. Mapping by name and not by ordinal is
	 * deliberate -- ordinals would silently re-point if either side ever
	 * reordered -- and it is a mapping, never a collapse, the same rule
	 * D-036 set for {@code gender}.
	 *
	 * <p>{@code ''} is what MySQL stores for an invalid enum value in
	 * non-strict mode, so it means "no value" and reads as null.
	 *
	 * @throws LegacyValueException for a value legacy has and Java does
	 *         not -- schema drift, which should surface rather than read
	 *         as unset.
	 */
	public static <E extends Enum<E>> E toEnum(Class<E> type, String legacyValue) {
		if (legacyValue == null || legacyValue.trim().isEmpty()) {
			return null;
		}
		String constant = legacyValue.trim().toUpperCase(Locale.ROOT);
		try {
			return Enum.valueOf(type, constant);
		} catch (IllegalArgumentException ex) {
			throw new LegacyValueException(
					"Legacy " + type.getSimpleName() + " value '" + legacyValue
							+ "' has no counterpart in Java. The legacy enum has gained a value,"
							+ " or this mapping is wrong.");
		}
	}

	/** The legacy spelling, for writes. */
	public static String fromEnum(Enum<?> value) {
		return value == null ? null : value.name().toLowerCase(Locale.ROOT);
	}

}
