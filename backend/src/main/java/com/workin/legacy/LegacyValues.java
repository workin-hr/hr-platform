package com.workin.legacy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
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
	 * A JSON scalar converted like PHP's {@code (int)} cast.
	 *
	 * <p>Numbers truncate toward zero, booleans become 1/0, null and malformed or empty strings
	 * become 0, and leading-numeric strings retain their numeric prefix. Non-scalar JSON values
	 * are outside this helper's contract and follow PHP's invalid-input validation path as 0.
	 */
	public static long toPhpLong(Object raw) {
		return phpNumericValue(raw).longValue();
	}

	/**
	 * A JSON scalar converted like PHP's {@code (float)} cast, represented as {@link BigDecimal}
	 * so MariaDB remains the authority for the target column's scale and range normalization.
	 */
	public static BigDecimal toPhpDecimal(Object raw) {
		return phpNumericValue(raw);
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
