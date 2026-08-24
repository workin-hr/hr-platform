package com.workin.legacy.attendance;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyValues;

/**
 * D-091's bounded, read-only {@code company_settings} reader, and the pure
 * rest-day test that consumes it.
 *
 * <h2>Scope is the decision, not a convenience</h2>
 * <p>D-091 admits <b>one</b> call --
 * {@code company_setting_selected_values($company_id, WEEKLY_OFF_DAYS)} -- ahead
 * of item 13, and nothing else from {@code company_settings}. No endpoint, no
 * write, no settings administration, no other key. The key is a constant here
 * rather than a parameter so the bound is structural: this class cannot be
 * asked for a different setting.
 *
 * <p>It is internal to the legacy port and is never exposed on the wire.
 *
 * <h2>The rest-day test is the narrow J.2 extraction</h2>
 * <p>{@code payroll_is_weekly_rest_day()} ({@code payroll_calculation.php:84-118})
 * is pure -- no database, no settings, no holiday helper, no writes -- which is
 * what §G.2 established and what allows it forward while the rest of
 * {@code payroll_calculation} stays blocked. It is ported here, beside its only
 * caller's data source, rather than dragging the whole helper file across a
 * wave boundary.
 */
@Component
public class LegacyWeeklyOffDays {

	/** {@code CompanySettingEnum::WEEKLY_OFF_DAYS->value}. */
	private static final String SETTING_KEY = "WEEKLY_OFF_DAYS";

	/**
	 * {@code company_setting_selected_values()} ({@code functions.php:892-910}).
	 *
	 * <p>The ordering is the query's and is load-bearing for any caller that
	 * looks at the first element: {@code sort_order ASC, id ASC} over
	 * {@code setting_allowed_values}, not insertion order and not the order the
	 * company selected them in.
	 */
	private static final String SELECTED_VALUES = """
			SELECT sav.value
			FROM setting_definitions sd
			INNER JOIN company_settings cs ON cs.setting_definition_id = sd.id AND cs.company_id = ?
			INNER JOIN company_setting_values csv ON csv.company_setting_id = cs.id
			INNER JOIN setting_allowed_values sav ON sav.id = csv.setting_allowed_value_id
			WHERE sd.setting_key = ?
			ORDER BY sav.sort_order ASC, sav.id ASC""";

	/**
	 * {@code payroll_is_weekly_rest_day()}'s day-name table.
	 *
	 * <p>Both hamza spellings of Sunday and Monday are present in the PHP and
	 * are kept: {@code الأحد}/{@code الاحد} and {@code الإثنين}/{@code الاثنين}.
	 */
	private static final java.util.Map<String, Integer> DAY_NAMES = java.util.Map.ofEntries(
			java.util.Map.entry("sunday", 0), java.util.Map.entry("monday", 1),
			java.util.Map.entry("tuesday", 2), java.util.Map.entry("wednesday", 3),
			java.util.Map.entry("thursday", 4), java.util.Map.entry("friday", 5),
			java.util.Map.entry("saturday", 6),
			java.util.Map.entry("sun", 0), java.util.Map.entry("mon", 1),
			java.util.Map.entry("tue", 2), java.util.Map.entry("wed", 3),
			java.util.Map.entry("thu", 4), java.util.Map.entry("fri", 5),
			java.util.Map.entry("sat", 6),
			java.util.Map.entry("الأحد", 0), java.util.Map.entry("الاحد", 0),
			java.util.Map.entry("الإثنين", 1), java.util.Map.entry("الاثنين", 1),
			java.util.Map.entry("الثلاثاء", 2),
			java.util.Map.entry("الأربعاء", 3), java.util.Map.entry("الاربعاء", 3),
			java.util.Map.entry("الخميس", 4),
			java.util.Map.entry("الجمعة", 5),
			java.util.Map.entry("السبت", 6));

	private final JdbcTemplate jdbcTemplate;

	public LegacyWeeklyOffDays(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * The company's selected weekly rest days, in the query's order.
	 *
	 * <p>The guard is PHP's: a non-positive company returns an empty list
	 * without querying. Empty {@code value} strings are removed <b>after</b> the
	 * query rather than by it, so a blank allowed value occupies a sort position
	 * and then disappears -- measured, and the reason the filter is not folded
	 * into the SQL.
	 */
	public List<String> forCompany(long companyId) {
		if (companyId <= 0) {
			return List.of();
		}
		List<String> values = jdbcTemplate.queryForList(
				SELECTED_VALUES, String.class, companyId, SETTING_KEY);
		return values.stream()
				.map(value -> value == null ? "" : value)
				.filter(value -> !value.isEmpty())
				.toList();
	}

	/**
	 * {@code payroll_is_weekly_rest_day($day_of_week, $rest_values)}, verbatim.
	 *
	 * <p>Three details are measured rather than assumed. An empty value list is
	 * false before anything else runs. {@code is_numeric()} is looser than a
	 * digit test, so {@code "05"}, {@code "5.0"} and {@code "+5"} all match day
	 * 5, and the comparison is {@code (int) $v === $day_of_week} with no range
	 * check -- day 7 or -1 match numerically if the setting says so. And the
	 * table is consulted twice per value, once lower-cased and once only
	 * trimmed, which is what lets the Arabic names match: {@code strtolower()}
	 * would otherwise leave them unchanged anyway, but the PHP does both and so
	 * does this.
	 *
	 * @param dayOfWeek 0 = Sunday ... 6 = Saturday
	 */
	public static boolean isWeeklyRestDay(int dayOfWeek, List<String> restValues) {
		if (restValues == null || restValues.isEmpty()) {
			return false;
		}
		for (String raw : restValues) {
			if (raw == null) {
				continue;
			}
			String trimmed = LegacyValues.phpTrim(raw);
			String lowered = trimmed.toLowerCase(Locale.ROOT);
			if (lowered.isEmpty()) {
				continue;
			}
			if (isNumeric(lowered) && phpIntCast(lowered) == dayOfWeek) {
				return true;
			}
			Integer byLowered = DAY_NAMES.get(lowered);
			if (byLowered != null && byLowered == dayOfWeek) {
				return true;
			}
			Integer byTrimmed = DAY_NAMES.get(trimmed);
			if (byTrimmed != null && byTrimmed == dayOfWeek) {
				return true;
			}
		}
		return false;
	}

	/** PHP's {@code is_numeric()} for the decimal forms a setting value can hold. */
	private static boolean isNumeric(String value) {
		return value.matches("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");
	}

	/**
	 * PHP's {@code (int)} cast of an already-numeric string: truncate toward
	 * zero, except that a value which overflows a double -- {@code "1e9999"}
	 * parses to {@link Double#POSITIVE_INFINITY} -- casts to {@code 0}, not
	 * {@link Integer#MAX_VALUE}. PHP documents NaN and both infinities as
	 * always zero on an {@code (int)} cast; Java's narrowing conversion
	 * clamps them to {@code Integer.MAX_VALUE}/{@code MIN_VALUE} instead, so
	 * that case is special-cased here.
	 */
	private static int phpIntCast(String value) {
		double parsed = Double.parseDouble(value);
		if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
			return 0;
		}
		return (int) parsed;
	}

	/** The keys this reader will answer for -- exactly one, by construction (D-091). */
	static Set<String> admittedKeys() {
		return Set.of(SETTING_KEY);
	}

}
