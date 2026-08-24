package com.workin.legacy;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code parse_ymd_config_date()}
 * ({@code hr-legacy/apis/helpers/configs_helper.php:41-55}).
 *
 * <h2>It rolls; it does not validate</h2>
 * <p>The helper calls {@code DateTimeImmutable::createFromFormat()} with
 * {@code '!Y-m-d'} and then {@code '!Y-n-j'}, and <b>never inspects
 * {@code getLastErrors()}</b>. An out-of-range month or day therefore produces
 * an object rather than {@code false}, and the object carries a rolled date.
 * Measured under PHP 8.3:
 *
 * <ul>
 * <li>{@code 2026-02-30} is <b>2026-03-02</b>, not null;</li>
 * <li>{@code 2026-13-01} is <b>2027-01-01</b>, not null;</li>
 * <li>{@code 2026-00-00} is <b>2025-11-30</b> -- zero is a legal component that
 *     rolls backwards;</li>
 * <li>{@code 0000-00-00} is <b>-0001-11-30</b>, a negative year.</li>
 * </ul>
 *
 * <p>So a strict {@link LocalDate#parse} would answer null where PHP answers a
 * date, and the one caller -- the attendance Excel import's availability gate --
 * would then refuse an import PHP admits. This class rolls.
 *
 * <h2>What it still rejects</h2>
 * <p>"Trailing data" is an <em>error</em> rather than a warning, so
 * {@code createFromFormat} does return {@code false} for it and the whole
 * grammar is anchored: {@code 2026-01-15abc}, {@code 2026-01-15 10:00} and
 * {@code 2026/01/15} are all null. Measured, not assumed -- the distinction
 * between a rejected error and an ignored warning is the entire behaviour here.
 *
 * <p>The accepted shape is the union of the two formats: {@code Y} takes one to
 * four digits, and the month and day take one or two each, because {@code Y-n-j}
 * covers what {@code Y-m-d} does not. {@code 26-01-15} is therefore year
 * <b>26</b>, not 2026, and {@code 12026-01-15} is null.
 */
public final class LegacyPhpConfigDate {

	/** {@code '!Y-m-d'} unioned with {@code '!Y-n-j'}, anchored because trailing data is an error. */
	private static final Pattern YMD = Pattern.compile("^(\\d{1,4})-(\\d{1,2})-(\\d{1,2})$");

	private LegacyPhpConfigDate() {
	}

	/**
	 * The date the helper resolves to, or {@code null} where it returns null.
	 *
	 * @param raw the config value; {@code trim()}ed first, exactly as PHP does,
	 *        so {@code "  2026-01-15  "} parses
	 */
	public static LocalDate parse(String raw) {
		String value = LegacyValues.phpTrim(raw);
		if (value.isEmpty()) {
			return null;
		}
		Matcher matcher = YMD.matcher(value);
		if (!matcher.matches()) {
			return null;
		}
		int year = Integer.parseInt(matcher.group(1));
		int month = Integer.parseInt(matcher.group(2));
		int day = Integer.parseInt(matcher.group(3));
		// The roll: start at 1 January of the stated year and step by the
		// stated month and day, which is what PHP's date arithmetic does for
		// out-of-range components -- including component zero, which steps
		// backwards. Year 0 is ISO's proleptic 0 (1 BC), matching PHP's
		// `0000` and its `-0001` result for `0000-00-00`.
		return LocalDate.of(year, 1, 1).plusMonths(month - 1L).plusDays(day - 1L);
	}

	/**
	 * {@code $dt->format('j/n/Y')} -- the display form the
	 * {@code attendance_excel_import_not_yet_available} message substitutes into
	 * its {@code {date}} placeholder.
	 *
	 * <p>{@code j} and {@code n} drop leading zeros; {@code Y} does not, and PHP
	 * pads a negative year as {@code -0001} rather than {@code -001}.
	 */
	public static String formatDayMonthYear(LocalDate date) {
		int year = date.getYear();
		String yearText = year < 0
				? "-" + String.format("%04d", -year)
				: String.format("%04d", year);
		return date.getDayOfMonth() + "/" + date.getMonthValue() + "/" + yearText;
	}

}
