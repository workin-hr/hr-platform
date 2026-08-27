package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The pure half of D-104's reader: {@code payroll_fiscal_period_bounds()}'s
 * arithmetic once the two raw setting values are already resolved. The
 * database query itself follows {@code LegacyWeeklyOffDaysTest}'s own
 * precedent -- exercised where its consumer is, not here.
 */
class LegacyPayrollFiscalSettingsTest {

	@Test
	void noSettingsDefaultToTheFullCalendarMonth() {
		String[] bounds = LegacyPayrollFiscalSettings.computeBounds(2026, 4, 0, 0);
		assertThat(bounds).containsExactly("2026-04-01", "2026-04-30");
	}

	@Test
	void anExplicitStartAtOrBeforeEndStaysWithinTheSameMonth() {
		String[] bounds = LegacyPayrollFiscalSettings.computeBounds(2026, 4, 5, 25);
		assertThat(bounds).containsExactly("2026-04-05", "2026-04-25");
	}

	/**
	 * Start after end wraps the period start into the previous calendar
	 * month -- a 26th-to-25th fiscal month, for example.
	 */
	@Test
	void aStartAfterEndWrapsIntoThePreviousMonth() {
		String[] bounds = LegacyPayrollFiscalSettings.computeBounds(2026, 4, 26, 25);
		assertThat(bounds).containsExactly("2026-03-26", "2026-04-25");
	}

	/** February (28 days in 2026, not a leap year) clamps a 30 to the real last day. */
	@Test
	void aDayBeyondTheMonthsLengthClampsToTheLastCalendarDay() {
		String[] bounds = LegacyPayrollFiscalSettings.computeBounds(2026, 2, 1, 30);
		assertThat(bounds).containsExactly("2026-02-01", "2026-02-28");
	}

	/**
	 * The wrapped previous month clamps to its own length, independently of
	 * the target month's -- wrapping March 31 into February (28 days in
	 * 2026, not a leap year) must land on the 28th, not overflow into March.
	 */
	@Test
	void theWrappedPreviousMonthClampsToItsOwnLastDayToo() {
		String[] bounds = LegacyPayrollFiscalSettings.computeBounds(2026, 3, 31, 1);
		assertThat(bounds).containsExactly("2026-02-28", "2026-03-01");
	}

	@Test
	void negativeOrOutOfRangeRawValuesClampRatherThanThrow() {
		String[] bounds = LegacyPayrollFiscalSettings.computeBounds(2026, 4, -5, 99);
		// start clamps to 1 (max(1, min(31, -5))), end clamps to 31 then to April's 30.
		assertThat(bounds).containsExactly("2026-04-01", "2026-04-30");
	}

	@Test
	void monthIsClampedToTheValidOneToTwelveRange() {
		String[] bounds = LegacyPayrollFiscalSettings.computeBounds(2026, 13, 0, 0);
		assertThat(bounds).containsExactly("2026-12-01", "2026-12-31");
	}
}
