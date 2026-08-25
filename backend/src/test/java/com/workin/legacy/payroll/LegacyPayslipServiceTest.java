package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * {@code payroll_elapsed_calendar_days()}/{@code payroll_attendance_salary_base()}
 * ({@code payroll_calculation.php:234-275}), the in-progress-period proration
 * {@code payroll_enrich_payslip_row()} uses -- reachable without a database,
 * and otherwise untested since every {@code LegacyPayslipEndToEndTest} fixture
 * uses a closed 2020 period.
 */
class LegacyPayslipServiceTest {

	@Test
	void elapsedCalendarDaysIsInclusiveOfBothEndpoints() {
		assertThat(LegacyPayslipService.elapsedCalendarDays("2020-01-01", "2020-01-05", "2020-01-01")).isEqualTo(1);
		assertThat(LegacyPayslipService.elapsedCalendarDays("2020-01-01", "2020-01-05", "2020-01-03")).isEqualTo(3);
		assertThat(LegacyPayslipService.elapsedCalendarDays("2020-01-01", "2020-01-05", "2020-01-05")).isEqualTo(5);
	}

	@Test
	void elapsedCalendarDaysCapsAtPeriodToAndFloorsAtZeroBeforePeriodFrom() {
		// as_of past period_to: end clamps to period_to (the "closed period" case never reaches here
		// in practice since attendanceSalaryBase short-circuits first, but the helper itself is total).
		assertThat(LegacyPayslipService.elapsedCalendarDays("2020-01-01", "2020-01-05", "2020-01-09")).isEqualTo(5);
		assertThat(LegacyPayslipService.elapsedCalendarDays("2020-01-05", "2020-01-09", "2020-01-01")).isEqualTo(0);
	}

	@Test
	void elapsedCalendarDaysIsZeroForBlankPeriodBounds() {
		assertThat(LegacyPayslipService.elapsedCalendarDays("", "2020-01-05", "2020-01-01")).isZero();
		assertThat(LegacyPayslipService.elapsedCalendarDays("2020-01-01", "", "2020-01-01")).isZero();
	}

	@Test
	void attendanceSalaryBaseReturnsFullGrossOncePeriodIsClosed() {
		BigDecimal base = LegacyPayslipService.attendanceSalaryBase(
				new BigDecimal("3000.00"), new BigDecimal("100.00"), "2020-01-01", "2020-01-05", "2020-01-05");
		assertThat(base).isEqualByComparingTo("3000.00");

		// as_of past period_to is also "closed" -- the >= guard, not strict equality.
		BigDecimal past = LegacyPayslipService.attendanceSalaryBase(
				new BigDecimal("3000.00"), new BigDecimal("100.00"), "2020-01-01", "2020-01-05", "2020-01-09");
		assertThat(past).isEqualByComparingTo("3000.00");
	}

	@Test
	void attendanceSalaryBaseIsDayRateTimesElapsedDaysWhileInProgress() {
		BigDecimal base = LegacyPayslipService.attendanceSalaryBase(
				new BigDecimal("3000.00"), new BigDecimal("100.00"), "2020-01-01", "2020-01-05", "2020-01-03");
		// Three elapsed days (Jan 1-3 inclusive) at the 100.00 day rate -- not the full 3000 gross.
		assertThat(base).isEqualByComparingTo("300.00");
	}
}
