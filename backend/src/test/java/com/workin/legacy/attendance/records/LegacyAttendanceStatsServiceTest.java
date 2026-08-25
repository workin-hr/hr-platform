package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyAttendanceStatsServiceTest {

	@Test
	void overtimeRequiresFifteenMinuteThresholdOnWorkingDays() {
		assertThat(LegacyAttendanceStatsService.overtimeMinutes(494, 480)).isZero();
		assertThat(LegacyAttendanceStatsService.overtimeMinutes(495, 480)).isEqualTo(15);
		assertThat(LegacyAttendanceStatsService.overtimeMinutes(479, 480)).isZero();
	}

	@Test
	void allPositiveWorkedMinutesAreOvertimeWhenExpectedMinutesAreZero() {
		assertThat(LegacyAttendanceStatsService.overtimeMinutes(0, 0)).isZero();
		assertThat(LegacyAttendanceStatsService.overtimeMinutes(75, 0)).isEqualTo(75);
		assertThat(LegacyAttendanceStatsService.overtimeMinutes(75, -1)).isEqualTo(75);
	}
}
