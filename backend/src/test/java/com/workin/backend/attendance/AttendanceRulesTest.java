package com.workin.backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.workin.backend.organization.ShiftTimes;

/**
 * The pure rules, pinned without a database. Each case is a legacy
 * behaviour that the calendar engine depends on being reproduced
 * exactly — including the two that are defects rather than rules, which
 * are asserted here so a later "cleanup" cannot change them silently.
 */
class AttendanceRulesTest {

	@Test
	void shiftDurationHandlesOvernightAndDegenerateWindows() {
		assertThat(ShiftTimes.durationMinutes(LocalTime.of(9, 0), LocalTime.of(17, 0))).isEqualTo(480);
		// Overnight: (1440 - start) + end.
		assertThat(ShiftTimes.durationMinutes(LocalTime.of(22, 0), LocalTime.of(6, 0))).isEqualTo(480);
		// A 24-hour shift is inexpressible in legacy -- equal bounds are 0.
		assertThat(ShiftTimes.durationMinutes(LocalTime.of(9, 0), LocalTime.of(9, 0))).isZero();
		assertThat(ShiftTimes.durationMinutes(null, LocalTime.of(9, 0))).isNull();
		assertThat(ShiftTimes.durationMinutesOrZero(null, null)).isZero();
	}

	@Test
	void shiftDurationDiscardsSecondsTheWayLegacyDoes() {
		// Legacy's regex captures the seconds group and never reads it.
		assertThat(ShiftTimes.durationMinutes(LocalTime.of(9, 0, 59), LocalTime.of(17, 0, 59))).isEqualTo(480);
	}

	@Test
	void syntheticIdIsNegativeStableAndDateEncoded() {
		long id = AttendanceRules.syntheticRowId(42L, LocalDate.of(2026, 8, 9));

		assertThat(id).isEqualTo(-4_220_260_809L);
		assertThat(id).isEqualTo(AttendanceRules.syntheticRowId(42L, LocalDate.of(2026, 8, 9)));
		assertThat(id).isNotEqualTo(AttendanceRules.syntheticRowId(42L, LocalDate.of(2026, 8, 10)));
	}

	@Test
	void exceptionOnlyRowNeedsAnExceptionMidnightAndNoCheckOut() {
		Instant midnight = Instant.parse("2026-03-02T00:00:00Z");
		Instant morning = Instant.parse("2026-03-02T09:00:00Z");

		assertThat(AttendanceRules.isExceptionOnlyRow(midnight, null, 7L)).isTrue();
		assertThat(AttendanceRules.isExceptionOnlyRow(morning, null, 7L)).isFalse();
		assertThat(AttendanceRules.isExceptionOnlyRow(midnight, morning, 7L)).isFalse();
		assertThat(AttendanceRules.isExceptionOnlyRow(midnight, null, null)).isFalse();
		assertThat(AttendanceRules.isExceptionOnlyRow(midnight, null, 0L)).isFalse();
	}

	@Test
	void displayDurationAppliesTheTwoHourDeductionToASinglePunch() {
		Instant in = Instant.parse("2026-03-02T09:00:00Z");
		Instant out = Instant.parse("2026-03-02T17:00:00Z");

		assertThat(AttendanceRules.displayDurationMinutes(in, out, 480)).isEqualTo(480);
		assertThat(AttendanceRules.displayDurationMinutes(in, null, 480)).isEqualTo(360);
		assertThat(AttendanceRules.displayDurationMinutes(null, null, 480)).isZero();
		// Floored, never negative -- a short expected day cannot go under zero.
		assertThat(AttendanceRules.displayDurationMinutes(in, null, 60)).isZero();
		// A reversed pair clamps rather than reporting negative time.
		assertThat(AttendanceRules.displayDurationMinutes(out, in, 480)).isZero();
	}

}
