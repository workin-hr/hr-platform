package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Measured oracle for {@code data_export_helper.php}'s cell formatters.
 *
 * <p>Each case is a quirk that would look like a formatting nicety to remove:
 * an em dash where a blank cell would seem tidier, {@code 00:00} in one
 * duration column and an empty string in another, a date that returns its own
 * unparseable input rather than failing, and a style precedence that is only
 * visible when two flags are true at once.
 */
class LegacyAttendanceExportFormatTest {

	private static final LocalDate TODAY = LocalDate.parse("2026-08-28");

	@Test
	void attendanceDateIsNumericDayMonthYear() {
		assertThat(LegacyAttendanceExportFormat.attendanceDate("2026-03-07", TODAY)).isEqualTo("07/03/2026");
	}

	/** {@code strtotime} failing returns the raw input, not an empty cell. */
	@Test
	void anUnparseableAttendanceDateComesBackUnchanged() {
		assertThat(LegacyAttendanceExportFormat.attendanceDate("not-a-date", TODAY)).isEqualTo("not-a-date");
	}

	@Test
	void weekdayNamesFollowPhpsSundayZeroIndexInBothLocales() {
		// 2026-03-01 is a Sunday, 2026-03-07 a Saturday.
		assertThat(LegacyAttendanceExportFormat.weekdayName("2026-03-01", TODAY, false)).isEqualTo("Sunday");
		assertThat(LegacyAttendanceExportFormat.weekdayName("2026-03-07", TODAY, false)).isEqualTo("Saturday");
		assertThat(LegacyAttendanceExportFormat.weekdayName("2026-03-01", TODAY, true)).isEqualTo("الأحد");
		assertThat(LegacyAttendanceExportFormat.weekdayName("2026-03-07", TODAY, true)).isEqualTo("السبت");
	}

	@Test
	void anUnparseableWeekdayIsAnEmptyStringNotTheInput() {
		assertThat(LegacyAttendanceExportFormat.weekdayName("not-a-date", TODAY, false)).isEmpty();
	}

	/** Null, blank and unparseable all render the em dash -- never an empty cell. */
	@Test
	void aMissingClockIsAnEmDash() {
		assertThat(LegacyAttendanceExportFormat.attendanceClock(null, TODAY)).isEqualTo("—");
		assertThat(LegacyAttendanceExportFormat.attendanceClock("   ", TODAY)).isEqualTo("—");
		assertThat(LegacyAttendanceExportFormat.attendanceClock("not-a-time", TODAY)).isEqualTo("—");
	}

	@Test
	void aPresentClockIsZeroPaddedHoursAndMinutes() {
		assertThat(LegacyAttendanceExportFormat.attendanceClock("2026-03-02 09:05:00", TODAY)).isEqualTo("09:05");
		assertThat(LegacyAttendanceExportFormat.attendanceClock("2026-03-02 17:00:00", TODAY)).isEqualTo("17:00");
	}

	/**
	 * The day-level duration has three outcomes, not two, and the difference
	 * between the last two is the whole reason the {@code incomplete} flag is
	 * threaded through the row builder.
	 */
	@Test
	void theDayLevelDurationDistinguishesIncompleteFromZero() {
		assertThat(LegacyAttendanceExportFormat.attendanceDuration(485, false)).isEqualTo("08:05");
		assertThat(LegacyAttendanceExportFormat.attendanceDuration(0, true)).isEqualTo("—");
		assertThat(LegacyAttendanceExportFormat.attendanceDuration(0, false)).isEmpty();
	}

	/** The overall column says {@code 00:00} where the day-level one says em dash or nothing. */
	@Test
	void theOverallDurationIsNeverBlank() {
		assertThat(LegacyAttendanceExportFormat.overallDuration(485)).isEqualTo("08:05");
		assertThat(LegacyAttendanceExportFormat.overallDuration(0)).isEqualTo("00:00");
		assertThat(LegacyAttendanceExportFormat.overallDuration(-30)).isEqualTo("00:00");
	}

	@Test
	void durationsPastTwentyFourHoursKeepCountingRatherThanWrapping() {
		assertThat(LegacyAttendanceExportFormat.overallDuration(1500)).isEqualTo("25:00");
		assertThat(LegacyAttendanceExportFormat.attendanceDuration(1500, false)).isEqualTo("25:00");
	}

	/** Missing wins over rest/holiday, which wins over a complete punch pair. */
	@Test
	void rowStylePrecedenceIsMissingThenRestThenComplete() {
		assertThat(LegacyAttendanceExportFormat.rowStyle(true, true, true, true, true))
				.as("a missing day is red even when it is also a holiday")
				.isEqualTo(LegacyAttendanceExportFormat.STYLE_BODY_RED);
		assertThat(LegacyAttendanceExportFormat.rowStyle(false, true, false, true, true))
				.as("a rest day is white even with a complete punch pair")
				.isEqualTo(LegacyAttendanceExportFormat.STYLE_BODY_WHITE);
		assertThat(LegacyAttendanceExportFormat.rowStyle(false, false, false, true, true))
				.isEqualTo(LegacyAttendanceExportFormat.STYLE_BODY_GREEN);
		assertThat(LegacyAttendanceExportFormat.rowStyle(false, false, false, true, false))
				.as("a half-punched ordinary day is white, not green")
				.isEqualTo(LegacyAttendanceExportFormat.STYLE_BODY_WHITE);
	}

	@Test
	void cellStylesRepeatEachRowsStyleAcrossEveryColumnPastTheHeader() {
		Map<Integer, Map<Integer, Integer>> styles = LegacyAttendanceExportFormat.rowCellStyles(
				List.of(LegacyAttendanceExportFormat.STYLE_BODY_GREEN,
						LegacyAttendanceExportFormat.STYLE_BODY_RED), 3, 1);

		assertThat(styles.keySet()).as("one header row offsets the data rows to 1 and 2").containsExactly(1, 2);
		assertThat(styles.get(1)).containsOnlyKeys(0, 1, 2)
				.containsValues(LegacyAttendanceExportFormat.STYLE_BODY_GREEN);
		assertThat(styles.get(2)).containsValues(LegacyAttendanceExportFormat.STYLE_BODY_RED);
	}

	@Test
	void theTwoSheetsDeclareTheirOwnColumnCounts() {
		assertThat(LegacyAttendanceExportFormat.OVERALL_HEADER_KEYS).hasSize(13).doesNotHaveDuplicates();
		assertThat(LegacyAttendanceExportFormat.FINGERPRINTS_HEADER_KEYS).hasSize(9).doesNotHaveDuplicates();
	}
}
