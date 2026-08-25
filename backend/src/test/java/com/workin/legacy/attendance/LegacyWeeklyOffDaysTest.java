package com.workin.legacy.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The pure half of D-091 and the narrow J.2 extraction:
 * {@code payroll_is_weekly_rest_day()}.
 *
 * <p>Every expectation is the measured result of calling the real
 * {@code hr-legacy/apis/helpers/payroll_calculation.php} function under PHP 8.3.
 * The reader's query behaviour is exercised where its consumer is; this class
 * is the value logic, which needs no database.
 */
class LegacyWeeklyOffDaysTest {

	/**
	 * Names, abbreviations and both hamza spellings.
	 *
	 * <p>Case and surrounding whitespace are removed before the lookup, so
	 * {@code "  FRIDAY  "} matches. The Arabic entries are unaffected by
	 * {@code strtolower()} and match through the same table.
	 */
	@ParameterizedTest(name = "[{index}] {0} vs day {1} -> {2}")
	@CsvSource(delimiter = '|', value = {
		"friday      | 5 | true",
		"friday      | 4 | false",
		"Friday      | 5 | true",
		"'  FRIDAY  '| 5 | true",
		"fri         | 5 | true",
		"sat         | 6 | true",
		"sunday      | 0 | true",
		"الجمعة       | 5 | true",
		"الاحد        | 0 | true",
		"الأحد        | 0 | true",
		"الإثنين      | 1 | true",
		"الاثنين      | 1 | true",
		"nope        | 5 | false",
		"''          | 5 | false",
	})
	void dayNamesMatchAsPhpMatchesThem(String value, int dayOfWeek, boolean expected) {
		assertThat(LegacyWeeklyOffDays.isWeeklyRestDay(dayOfWeek, List.of(value)))
				.isEqualTo(expected);
	}

	/**
	 * {@code is_numeric()} is looser than a digit test, and the comparison has
	 * no range check.
	 *
	 * <p>{@code "05"}, {@code "5.0"} and {@code "+5"} are all numeric to PHP and
	 * all cast to 5. And because the test is {@code (int) $v === $day_of_week}
	 * with nothing bounding either side, a setting holding {@code "7"} matches a
	 * caller asking about day 7 -- which is not a weekday. Measured; a port that
	 * validated the range would diverge.
	 */
	@ParameterizedTest(name = "[{index}] {0} vs day {1} -> {2}")
	@CsvSource(delimiter = '|', value = {
		"5     | 5  | true",
		"5     | 4  | false",
		"05    | 5  | true",
		"5.0   | 5  | true",
		"+5    | 5  | true",
		"5.9   | 5  | true",
		"7     | 7  | true",
		"-1    | -1 | true",
	})
	void numericValuesMatchAsPhpMatchesThem(String value, int dayOfWeek, boolean expected) {
		assertThat(LegacyWeeklyOffDays.isWeeklyRestDay(dayOfWeek, List.of(value)))
				.isEqualTo(expected);
	}

	/** No configured rest days is false before anything else is looked at. */
	@Test
	void anEmptyValueListIsNeverARestDay() {
		assertThat(LegacyWeeklyOffDays.isWeeklyRestDay(5, List.of())).isFalse();
		assertThat(LegacyWeeklyOffDays.isWeeklyRestDay(5, null)).isFalse();
	}

	/** Any one matching value is enough. */
	@Test
	void anyMatchingValueInTheListWins() {
		assertThat(LegacyWeeklyOffDays.isWeeklyRestDay(6, List.of("friday", "saturday"))).isTrue();
		assertThat(LegacyWeeklyOffDays.isWeeklyRestDay(3, List.of("friday", "saturday"))).isFalse();
	}

	/** D-091's bound is structural: the reader answers for one key only. */
	@Test
	void theReaderIsBoundToTheOneAdmittedSettingKey() {
		assertThat(LegacyWeeklyOffDays.admittedKeys()).containsExactly("weekly_off_days");
	}

}
