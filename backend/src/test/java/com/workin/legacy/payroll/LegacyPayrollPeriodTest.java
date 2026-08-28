package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Measured oracle for {@code payroll_calculation_as_of_date()} and
 * {@code payroll_period_in_progress()} ({@code payroll_calculation.php:219-229}),
 * the clamp every attendance-derived calculation starts from.
 *
 * <p>The cases below are the boundaries, not a happy path: they exist because
 * these three lines were inlined at three call sites before Wave 12.6.6 needed a
 * fourth, and an off-by-one at the period's last day would silently move a day
 * between "present" and "not yet elapsed" in both payroll and the report.
 */
class LegacyPayrollPeriodTest {

	private static final String PERIOD_TO = "2026-08-31";

	@Test
	void todayInsideAnOpenPeriodIsTheAsOfDate() {
		assertThat(LegacyPayrollPeriod.asOfDate("2026-08-15", PERIOD_TO)).isEqualTo("2026-08-15");
	}

	@Test
	void todayAfterThePeriodClampsToThePeriodEnd() {
		assertThat(LegacyPayrollPeriod.asOfDate("2026-09-01", PERIOD_TO)).isEqualTo(PERIOD_TO);
	}

	/** {@code $today <= $period_to}: the boundary day is still "today", not clamped. */
	@Test
	void todayOnTheLastDayOfThePeriodIsTheAsOfDate() {
		assertThat(LegacyPayrollPeriod.asOfDate(PERIOD_TO, PERIOD_TO)).isEqualTo(PERIOD_TO);
	}

	@Test
	void aPeriodEndingTodayIsNotInProgress() {
		assertThat(LegacyPayrollPeriod.inProgress(PERIOD_TO, PERIOD_TO)).isFalse();
	}

	/** {@code $as_of < $period_to} is strict, so one day short is still open. */
	@Test
	void aPeriodEndingTomorrowIsInProgress() {
		assertThat(LegacyPayrollPeriod.inProgress(PERIOD_TO, "2026-08-30")).isTrue();
	}

	@Test
	void aClosedPeriodRangesToItsOwnEndAndAnOpenOneToAsOf() {
		assertThat(LegacyPayrollPeriod.rangeEnd(PERIOD_TO, PERIOD_TO)).isEqualTo(PERIOD_TO);
		assertThat(LegacyPayrollPeriod.rangeEnd(PERIOD_TO, "2026-08-15")).isEqualTo("2026-08-15");
	}

	/**
	 * PHP compares these as strings. Zero-padded {@code Y-m-d} sorts
	 * chronologically, which is what makes that safe -- pinned here because a
	 * later "improvement" to {@code LocalDate} parsing would change how a
	 * malformed bound behaves, not just the comparison.
	 */
	@Test
	void comparisonIsLexicalAcrossMonthAndYearBoundaries() {
		assertThat(LegacyPayrollPeriod.asOfDate("2026-09-01", "2026-08-31")).isEqualTo("2026-08-31");
		assertThat(LegacyPayrollPeriod.asOfDate("2027-01-01", "2026-12-31")).isEqualTo("2026-12-31");
		assertThat(LegacyPayrollPeriod.inProgress("2026-10-01", "2026-09-30")).isTrue();
	}
}
