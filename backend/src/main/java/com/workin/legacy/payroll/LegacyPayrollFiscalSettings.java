package com.workin.legacy.payroll;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyValues;

/**
 * A second bounded, read-only {@code company_settings} reader, admitted for
 * Wave 12.9 the same way D-091 admitted {@code WEEKLY_OFF_DAYS} for Wave
 * 12.6: {@code payroll_fiscal_period_bounds()} needs exactly two more keys
 * -- {@code month_start_day} and {@code month_end_day} -- and nothing else
 * from {@code company_settings}. No endpoint, no write, no settings
 * administration, no other key. Structural, not a convenience: the two
 * admitted keys are the enum, not a caller-supplied string.
 *
 * <p>D-103 is the cautionary evidence for one specific mistake this class
 * must not repeat: {@code setting_definitions.setting_key} is queried by
 * {@code CompanySettingEnum::X->value} -- the enum's lower snake_case
 * backing string -- never its upper-case PHP case name. Both keys below
 * are verified directly against {@code hr-legacy/apis/config/enums.php:117-118}.
 */
@Component
public class LegacyPayrollFiscalSettings {

	/** The only two keys this reader answers for (Wave 12.9). */
	public enum Key {
		/** {@code CompanySettingEnum::MONTH_START_DAY->value}. */
		MONTH_START_DAY("month_start_day"),
		/** {@code CompanySettingEnum::MONTH_END_DAY->value}. */
		MONTH_END_DAY("month_end_day");

		private final String settingKey;

		Key(String settingKey) {
			this.settingKey = settingKey;
		}
	}

	/**
	 * {@code company_setting_selected_values()} ({@code functions.php:892-910}),
	 * identical query and ordering to {@code LegacyWeeklyOffDays}' own copy --
	 * D-091's structural bound keeps each reader to its own admitted key set
	 * rather than exposing a general-purpose settings accessor, so the query
	 * is duplicated here rather than shared.
	 */
	private static final String SELECTED_VALUES = """
			SELECT sav.value
			FROM setting_definitions sd
			INNER JOIN company_settings cs ON cs.setting_definition_id = sd.id AND cs.company_id = ?
			INNER JOIN company_setting_values csv ON csv.company_setting_id = cs.id
			INNER JOIN setting_allowed_values sav ON sav.id = csv.setting_allowed_value_id
			WHERE sd.setting_key = ?
			ORDER BY sav.sort_order ASC, sav.id ASC""";

	private final JdbcTemplate jdbcTemplate;

	public LegacyPayrollFiscalSettings(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * The company's selected values for one admitted key, in the query's
	 * order. A non-positive company returns an empty list without querying
	 * (PHP's own guard); a blank allowed value occupies a sort position and
	 * is filtered out after the query, not by it -- matching
	 * {@code LegacyWeeklyOffDays.forCompany()} exactly.
	 */
	public List<String> forCompany(long companyId, Key key) {
		if (companyId <= 0) {
			return List.of();
		}
		List<String> values = jdbcTemplate.queryForList(
				SELECTED_VALUES, String.class, companyId, key.settingKey);
		return values.stream()
				.map(value -> value == null ? "" : value)
				.filter(value -> !value.isEmpty())
				.toList();
	}

	/**
	 * {@code payroll_fiscal_period_bounds()} ({@code payroll_calculation.php:41-70}),
	 * verbatim including its two independent defaults: an unset or
	 * non-positive start day defaults to 1, an unset or non-positive end day
	 * defaults to the period month's last calendar day (resolved
	 * separately, only once the target month is known) -- not to a shared
	 * default, and not to each other.
	 *
	 * @return {@code [periodFrom, periodTo]}, both {@code yyyy-MM-dd}
	 */
	public String[] fiscalPeriodBounds(long companyId, int year, int month) {
		List<String> startVals = forCompany(companyId, Key.MONTH_START_DAY);
		List<String> endVals = forCompany(companyId, Key.MONTH_END_DAY);
		long startDay = startVals.isEmpty() ? 1 : LegacyValues.toPhpLong(startVals.get(0));
		long endDay = endVals.isEmpty() ? 0 : LegacyValues.toPhpLong(endVals.get(0));
		return computeBounds(year, month, startDay, endDay);
	}

	/**
	 * The pure half of {@code payroll_fiscal_period_bounds()} -- everything
	 * once the two raw setting values are in hand -- kept separate from the
	 * database read so it can be tested without one, the same split
	 * {@link com.workin.legacy.attendance.LegacyWeeklyOffDays} already uses
	 * for {@code isWeeklyRestDay()} versus {@code forCompany()}.
	 *
	 * <p>Two independent defaults: an unset or non-positive start day
	 * defaults to 1, an unset or non-positive end day defaults to the
	 * target month's last calendar day (resolved separately, only once the
	 * target month is known) -- not to a shared default, and not to each
	 * other.
	 *
	 * @return {@code [periodFrom, periodTo]}, both {@code yyyy-MM-dd}
	 */
	static String[] computeBounds(int year, int month, long rawStartDay, long rawEndDay) {
		int clampedMonth = Math.max(1, Math.min(12, month));
		// max(1, min(31, ...)) already floors any non-positive value to 1,
		// exactly like PHP's own clamp -- no separate default branch needed.
		int startDay = clampDay(rawStartDay);

		java.time.YearMonth anchor = java.time.YearMonth.of(year, clampedMonth);
		int lastDom = anchor.lengthOfMonth();
		int endDay = rawEndDay <= 0 ? lastDom : clampDay(rawEndDay);

		if (startDay <= endDay) {
			String from = isoDate(year, clampedMonth, Math.min(startDay, lastDom));
			String to = isoDate(year, clampedMonth, Math.min(endDay, lastDom));
			return new String[] {from, to};
		}

		java.time.YearMonth previous = anchor.minusMonths(1);
		int lastPrev = previous.lengthOfMonth();
		String from = isoDate(previous.getYear(), previous.getMonthValue(), Math.min(startDay, lastPrev));
		String to = isoDate(year, clampedMonth, Math.min(endDay, lastDom));
		return new String[] {from, to};
	}

	/** PHP's {@code max(1, min(31, ...))}, applied identically to both bounds once resolved. */
	private static int clampDay(long value) {
		return (int) Math.max(1, Math.min(31, value));
	}

	private static String isoDate(int year, int month, int day) {
		return java.time.LocalDate.of(year, month, day).toString();
	}
}
