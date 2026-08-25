package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyAttendanceWorkedMinutesTest {

	@Test
	void completePunchUsesRawTimestampDiffAndNeverGoesNegative() {
		assertThat(LegacyAttendanceWorkedMinutes.displayDuration(true, true, 487, 480)).isEqualTo(487);
		assertThat(LegacyAttendanceWorkedMinutes.displayDuration(true, true, -12, 480)).isZero();
	}

	@Test
	void exactlyOnePunchUsesExpectedMinusTwoHours() {
		assertThat(LegacyAttendanceWorkedMinutes.displayDuration(true, false, 0, 480)).isEqualTo(360);
		assertThat(LegacyAttendanceWorkedMinutes.displayDuration(false, true, 0, 90)).isZero();
	}

	@Test
	void exceptionOnlyMeansPositiveExceptionMidnightAndNoCheckout() {
		assertThat(LegacyAttendanceWorkedMinutes.isExceptionOnly("2026-08-25 00:00:00", null, 7)).isTrue();
		assertThat(LegacyAttendanceWorkedMinutes.isExceptionOnly("2026-08-25 00:01:00", null, 7)).isFalse();
		assertThat(LegacyAttendanceWorkedMinutes.isExceptionOnly("2026-08-25 00:00:00", "2026-08-25 08:00:00", 7)).isFalse();
		assertThat(LegacyAttendanceWorkedMinutes.isExceptionOnly("2026-08-25 00:00:00", null, 0)).isFalse();
	}

	@Test
	void shiftClockNormalizationMatchesPhpHelperGrammar() {
		assertThat(LegacyAttendanceWorkedMinutes.normalizeClock("9:05")).isEqualTo("09:05:00");
		assertThat(LegacyAttendanceWorkedMinutes.normalizeClock(" 23:59:58 ")).isEqualTo("23:59:58");
		assertThat(LegacyAttendanceWorkedMinutes.normalizeClock("24:00")).isNull();
		assertThat(LegacyAttendanceWorkedMinutes.normalizeClock("9am")).isNull();
	}
}
