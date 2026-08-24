package com.workin.legacy.workforce;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyValues;

/**
 * {@code official_holidays_normalize_dates()} from
 * {@code hr-legacy/apis/helpers/official_holidays_helper.php}.
 *
 * <p>One of exactly <b>two</b> functions that file contributes to Wave 12.5
 * (D-090). The other nine are attendance and payroll consumers, several of
 * which read {@code company_setting_values} -- item 13's table -- and none of
 * them is ported here.
 *
 * <h2>It skips silently</h2>
 * <p>PHP {@code continue}s past anything it cannot use: a blank value, a
 * malformed date, a real date written in the wrong shape. Nothing is reported.
 * So a create carrying {@code ["2026-01-01", "oops"]} produces one holiday and
 * no error at all, and only an <em>entirely</em> unusable list is visible to
 * the caller -- as the empty result its caller then rejects.
 *
 * <h2>The round trip is the validation</h2>
 * <p>{@code createFromFormat('Y-m-d', $v)} is forgiving on its own:
 * {@code 2026-02-30} rolls into March and {@code 2026-1-1} parses happily. The
 * guard is {@code $dt->format('Y-m-d') !== $v}, which rejects anything that
 * does not render back to exactly the string supplied. Reproduced with a strict
 * parse plus the same round-trip comparison, so both of those are skipped here
 * too.
 */
public final class LegacyOfficialHolidayDates {

	/**
	 * The field shape PHP's {@code 'Y-m-d'} accepts, checked before parsing.
	 *
	 * <p>PHP's {@code Y} input field is <b>four unsigned ASCII digits</b>.
	 * Java's {@code uuuu} is a proleptic year and accepts neither the same set
	 * nor a subset: it takes a leading sign and more than four digits, so
	 * {@code -0001-01-01} and {@code +10000-01-01} parse happily. Worse, they
	 * then <em>format back to the same string</em>, so the round-trip check
	 * below cannot see them either -- it is a validity check, not a lexical
	 * one. Without this guard both were accepted and written to a
	 * {@code DATE} column.
	 *
	 * <p>Year {@code 0000} is deliberately allowed: PHP accepts
	 * {@code 0000-01-01}, so a pattern demanding a non-zero year would reject
	 * input legacy takes.
	 */
	private static final Pattern Y_M_D_SHAPE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

	/**
	 * {@code 'Y-m-d'}'s calendar validation, applied after the shape guard.
	 * {@code uuuu} rather than {@code yyyy} because {@link ResolverStyle#STRICT}
	 * rejects {@code yyyy} without an era; the sign and width problems that
	 * choice would otherwise bring are already excluded by
	 * {@link #Y_M_D_SHAPE}.
	 */
	private static final DateTimeFormatter Y_M_D =
			DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

	private LegacyOfficialHolidayDates() {
	}

	/**
	 * @param raw the decoded {@code holiday_dates} value; anything that is not
	 *        a PHP array yields no dates, exactly as {@code foreach} over a
	 *        non-array would
	 * @return the accepted dates as {@code Y-m-d}, first-seen order preserved
	 *         and duplicates collapsed
	 */
	public static List<String> normalize(Object raw) {
		// `$out[$v] = $v` keyed by the normalized string: later duplicates
		// overwrite the same key, so the FIRST position is what survives.
		Map<String, String> out = new LinkedHashMap<>();

		for (Object value : LegacyValues.phpArrayValues(raw)) {
			// `trim((string) $raw)` -- PHP's charlist, so a form feed is NOT
			// stripped and the date it decorates is then rejected by the round
			// trip. Java's String.trim() would have removed it and accepted the
			// date, which is the same defect fixed for the search needle.
			String trimmed = LegacyValues.phpTrim(LegacyValues.toPhpString(value));
			if (trimmed.isEmpty()) {
				continue;
			}
			if (!isExactYmd(trimmed)) {
				continue;
			}
			out.put(trimmed, trimmed);
		}

		return new ArrayList<>(out.values());
	}

	/** {@code $dates} already in list form, for the single-date branches. */
	public static List<String> normalize(Collection<?> values) {
		return normalize((Object) values);
	}

	/**
	 * {@code $dt = createFromFormat('Y-m-d', $v); !$dt || $dt->format('Y-m-d') !== $v}
	 * -- parse, then demand the value render back to itself.
	 */
	private static boolean isExactYmd(String value) {
		// The lexical guard first: it owns PHP's field shape, which the parse
		// cannot express. The strict parse and round trip then own real
		// calendar validity -- 2026-02-30 and 2026-13-01 are correctly shaped
		// and still not dates.
		if (!Y_M_D_SHAPE.matcher(value).matches()) {
			return false;
		}
		try {
			LocalDate parsed = LocalDate.parse(value, Y_M_D);
			return Y_M_D.format(parsed).equals(value);
		} catch (DateTimeParseException ex) {
			return false;
		}
	}

}
