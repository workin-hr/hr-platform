package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * The attendance page's cells against legacy's own output: each expected value here was printed by
 * the legacy functions themselves, run unchanged under PHP 8.3.
 */
class AttendanceDisplayTest {

	private static final Function<String, String> ARABIC = Map.of("hour_unit", "ساعة", "minute_unit", "دقيقة")::get;

	private static final Function<String, String> ENGLISH = Map.of("hour_unit", "hour", "minute_unit", "minute")::get;

	@Test
	void theDateCarriesLegacysMonthNamesInEitherLanguage() {
		assertThat(AttendanceDisplay.date("2026-03-05 08:07:09", true)).isEqualTo("5 مارس 2026");
		assertThat(AttendanceDisplay.date("2026-03-05 08:07:09", false)).isEqualTo("5 March 2026");
		assertThat(AttendanceDisplay.date("2026-12-31 23:59:59", true)).isEqualTo("31 ديسمبر 2026");
		assertThat(AttendanceDisplay.date("2026-01-09 00:00:00", false)).as("no leading zero").isEqualTo("9 January 2026");
	}

	@Test
	void everyMonthHasLegacysName() {
		String[] arabic = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
			"يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
		for (int month = 1; month <= 12; month++) {
			assertThat(AttendanceDisplay.date("2026-%02d-01 00:00:00".formatted(month), true))
					.isEqualTo("1 " + arabic[month - 1] + " 2026");
		}
	}

	@Test
	void theTimeIsHoursAndMinutesZeroPadded() {
		assertThat(AttendanceDisplay.time("2026-03-05 08:07:09")).isEqualTo("08:07");
		assertThat(AttendanceDisplay.time("2026-03-05 23:59:59")).isEqualTo("23:59");
		assertThat(AttendanceDisplay.time("2026-03-05 00:00:00")).isEqualTo("00:00");
	}

	/** {@code hr_parse_datetime()}'s own reading of what it is given. */
	@Test
	void theCellsReadWhatLegacyReadsAndDashTheRest() {
		assertThat(AttendanceDisplay.time("2026-03-05T08:07:09")).as("T for a space").isEqualTo("08:07");
		assertThat(AttendanceDisplay.time("2026-03-05 08:07")).as("no seconds").isEqualTo("08:07");
		assertThat(AttendanceDisplay.time("2026-03-05 08:07:09.123")).as("a fraction").isEqualTo("08:07");
		assertThat(AttendanceDisplay.date(" 2026-01-09 00:00:00 ", true)).as("trimmed").isEqualTo("9 يناير 2026");
		assertThat(AttendanceDisplay.date("2026-03-05", true)).as("a date alone is not enough").isEqualTo("—");
		assertThat(AttendanceDisplay.dayName("2026-03-05", true)).isEqualTo("—");
		assertThat(AttendanceDisplay.time(null)).isEqualTo("—");
		assertThat(AttendanceDisplay.date("", false)).isEqualTo("—");
		assertThat(AttendanceDisplay.dayName("  ", false)).isEqualTo("—");
	}

	/** Legacy prints "30 November -0001" for the zero date; that is the one place this differs. */
	@Test
	void theZeroDateIsADashRatherThanLegacysRollover() {
		assertThat(AttendanceDisplay.date("0000-00-00 00:00:00", false)).isEqualTo("—");
		assertThat(AttendanceDisplay.dayName("0000-00-00 00:00:00", true)).isEqualTo("—");
		assertThat(AttendanceDisplay.time("0000-00-00 00:00:00")).isEqualTo("—");
	}

	@Test
	void theWeekdayStartsFromSundayAsPhpsWDoes() {
		assertThat(AttendanceDisplay.dayName("2026-03-01 09:00:00", true)).as("a Sunday").isEqualTo("الأحد");
		assertThat(AttendanceDisplay.dayName("2026-03-06 09:00:00", true)).as("a Friday").isEqualTo("الجمعة");
		assertThat(AttendanceDisplay.dayName("2026-03-07 09:00:00", false)).isEqualTo("Saturday");
	}

	@Test
	void workedHoursAreSignedAndZeroPadded() {
		assertThat(AttendanceDisplay.hours(null)).isEqualTo("—");
		assertThat(AttendanceDisplay.hours(0)).isEqualTo("00:00");
		assertThat(AttendanceDisplay.hours(5)).isEqualTo("00:05");
		assertThat(AttendanceDisplay.hours(605)).isEqualTo("10:05");
		assertThat(AttendanceDisplay.hours(6000)).as("past a day").isEqualTo("100:00");
		assertThat(AttendanceDisplay.hours(-1)).isEqualTo("-00:01");
		assertThat(AttendanceDisplay.hours(-61)).isEqualTo("-01:01");
	}

	@Test
	void aDurationIsWordsInLegacysUnits() {
		assertThat(AttendanceDisplay.duration(0, ARABIC)).isEqualTo("0 دقيقة");
		assertThat(AttendanceDisplay.duration(-59, ARABIC)).as("less than nothing is nothing").isEqualTo("0 دقيقة");
		assertThat(AttendanceDisplay.duration(59, ARABIC)).isEqualTo("59 دقيقة");
		assertThat(AttendanceDisplay.duration(60, ARABIC)).isEqualTo("1 ساعة");
		assertThat(AttendanceDisplay.duration(125, ARABIC)).isEqualTo("2 ساعة و 5 دقيقة");
		assertThat(AttendanceDisplay.duration(6000, ARABIC)).isEqualTo("100 ساعة");
		assertThat(AttendanceDisplay.duration(61, ENGLISH)).as("legacy's connective in English too")
				.isEqualTo("1 hour و 1 minute");
	}

	@Test
	void overtimeCountsMinutesAndKeepsTheSignOfAShortfall() {
		assertThat(AttendanceDisplay.overtime(30, ARABIC)).isEqualTo("+30 دقيقة");
		assertThat(AttendanceDisplay.overtime(125, ENGLISH)).as("minutes, not hours").isEqualTo("+125 minute");
		assertThat(AttendanceDisplay.overtime(-30, ARABIC)).as("legacy drops this sign").isEqualTo("-30 دقيقة");
	}

}
