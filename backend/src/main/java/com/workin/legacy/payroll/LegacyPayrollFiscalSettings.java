package com.workin.legacy.payroll;

import java.time.LocalDate;
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
	 * {@code payroll_fiscal_day_settings()}: the two configured days, clamped.
	 *
	 * <p>The clamping is not symmetric, and the asymmetry is the point. The
	 * start day is forced into 1..31, so an unset or nonsensical value becomes
	 * 1. The end day keeps <b>0</b> as a distinct value meaning "unset", and is
	 * only clamped when it is positive -- callers use that zero to mean "the
	 * month's own last day", which cannot be decided until the month is known.
	 *
	 * @return {@code [startDay, endDay]}, where {@code endDay} may be 0
	 */
	public int[] fiscalDaySettings(long companyId) {
		List<String> startVals = forCompany(companyId, Key.MONTH_START_DAY);
		List<String> endVals = forCompany(companyId, Key.MONTH_END_DAY);
		long rawStart = startVals.isEmpty() ? 1 : LegacyValues.toPhpLong(startVals.get(0));
		long rawEnd = endVals.isEmpty() ? 0 : LegacyValues.toPhpLong(endVals.get(0));
		return new int[] {clampStartDay(rawStart), clampEndDay(rawEnd)};
	}

	/**
	 * {@code employee_row_attach_company_fiscal_month(&$employee, $company_id)}.
	 *
	 * <p>Writes {@code month_start_day} and {@code month_end_day} onto an
	 * employee row so the mobile client can label its attendance screen with
	 * the company's own month boundaries instead of the calendar's.
	 *
	 * <p><b>A non-positive company id writes nothing at all</b>, and the keys
	 * are then absent rather than null -- PHP returns early, and the client
	 * distinguishes an absent key from a null one.
	 *
	 * <p>{@code month_end_day} is written raw, including 0. PHP does not
	 * resolve the "unset means the month's last day" default here, because at
	 * this point there is no month to resolve it against; the caller that
	 * knows the period does that.
	 */
	public void attachCompanyFiscalMonth(java.util.Map<String, Object> employee, long companyId) {
		if (companyId <= 0) {
			return;
		}
		int[] days = fiscalDaySettings(companyId);
		employee.put("month_start_day", (long) days[0]);
		employee.put("month_end_day", (long) days[1]);
	}

	/** {@code max(1, min(31, $start))} -- anything unusable becomes the 1st. */
	static int clampStartDay(long raw) {
		return (int) Math.max(1, Math.min(31, raw));
	}

	/**
	 * {@code $end > 0 ? max(1, min(31, $end)) : $end} -- zero is preserved,
	 * because it means "the period's own last day" and only the caller that
	 * knows the period can resolve it.
	 */
	static int clampEndDay(long raw) {
		return raw > 0 ? (int) Math.max(1, Math.min(31, raw)) : 0;
	}

	/** {@code payroll_fiscal_month_containing_date()}'s answer. */
	public record FiscalMonth(
			int year, int month, String periodFrom, String periodTo, int monthStartDay, int monthEndDay) {
	}

	/**
	 * {@code payroll_fiscal_month_containing_date()}: which fiscal month a date
	 * falls in.
	 *
	 * <p>A fiscal month whose start day is not the 1st spans two calendar
	 * months, so the calendar month a date sits in is <em>not</em> its fiscal
	 * month. The 3rd of March under a start day of 26 belongs to the fiscal
	 * month labelled February. This walks one step in whichever direction the
	 * date falls outside the bounds -- one step is always enough, because a
	 * fiscal month is at most one calendar month long.
	 *
	 * @param ymd the date to place, or null for today
	 */
	public FiscalMonth fiscalMonthContainingDate(long companyId, String ymd, LocalDate today) {
		String date = ymd != null && !ymd.trim().isEmpty() ? ymd.trim() : today.toString();
		LocalDate parsed = LocalDate.parse(date);
		int year = parsed.getYear();
		int month = parsed.getMonthValue();

		String[] bounds = fiscalPeriodBounds(companyId, year, month);
		if (date.compareTo(bounds[0]) < 0) {
			LocalDate previous = parsed.withDayOfMonth(1).minusMonths(1);
			year = previous.getYear();
			month = previous.getMonthValue();
			bounds = fiscalPeriodBounds(companyId, year, month);
		} else if (date.compareTo(bounds[1]) > 0) {
			LocalDate next = parsed.withDayOfMonth(1).plusMonths(1);
			year = next.getYear();
			month = next.getMonthValue();
			bounds = fiscalPeriodBounds(companyId, year, month);
		}

		int[] days = fiscalDaySettings(companyId);
		return new FiscalMonth(year, month, bounds[0], bounds[1], days[0], days[1]);
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
