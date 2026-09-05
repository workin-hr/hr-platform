package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

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
	// ---------------- fiscalDaySettings: the asymmetric clamp ----------------

	/**
	 * Start and end are clamped differently, and the difference is load-bearing.
	 * A start day is always a real day, so anything unusable becomes 1. An end
	 * day keeps <b>0</b> as a distinct value meaning "the period's own last
	 * day", which cannot be resolved until the period is known -- flattening it
	 * to 1 or to 31 would silently move every company that leaves it unset.
	 */
	@Test
	void anUnsetEndDayStaysZeroWhileAnUnsetStartDayBecomesOne() {
		assertThat(LegacyPayrollFiscalSettings.clampStartDay(0)).isEqualTo(1);
		assertThat(LegacyPayrollFiscalSettings.clampStartDay(-5)).isEqualTo(1);
		assertThat(LegacyPayrollFiscalSettings.clampStartDay(99)).isEqualTo(31);
		assertThat(LegacyPayrollFiscalSettings.clampStartDay(26)).isEqualTo(26);

		assertThat(LegacyPayrollFiscalSettings.clampEndDay(0))
				.as("0 survives -- it means the month's own last day")
				.isZero();
		assertThat(LegacyPayrollFiscalSettings.clampEndDay(-5)).isZero();
		assertThat(LegacyPayrollFiscalSettings.clampEndDay(99)).isEqualTo(31);
		assertThat(LegacyPayrollFiscalSettings.clampEndDay(25)).isEqualTo(25);
	}

	// ---------------- the two boundary shapes ----------------

	/**
	 * The default: a period that is exactly its calendar month. Everything
	 * about the fiscal change is invisible here, which is the reason the
	 * non-default case below exists -- a regression that only shows on a
	 * shifted boundary would pass every test written against this one.
	 */
	@Test
	void theDefaultPeriodIsTheCalendarMonth() {
		assertThat(LegacyPayrollFiscalSettings.computeBounds(2026, 2, 1, 0))
				.containsExactly("2026-02-01", "2026-02-28");
		assertThat(LegacyPayrollFiscalSettings.computeBounds(2024, 2, 1, 0))
				.as("a leap February ends on the 29th, from the month rather than a constant")
				.containsExactly("2024-02-01", "2024-02-29");
		assertThat(LegacyPayrollFiscalSettings.computeBounds(2026, 4, 1, 0))
				.containsExactly("2026-04-01", "2026-04-30");
	}

	/**
	 * A 26th-to-25th month: the period labelled March 2026 <b>starts in
	 * February</b>. This is the shape every fiscal code path has to survive,
	 * and the one the default configuration hides completely.
	 */
	@Test
	void aTwentySixthToTwentyFifthPeriodStartsInThePreviousCalendarMonth() {
		assertThat(LegacyPayrollFiscalSettings.computeBounds(2026, 3, 26, 25))
				.containsExactly("2026-02-26", "2026-03-25");
		assertThat(LegacyPayrollFiscalSettings.computeBounds(2026, 1, 26, 25))
				.as("January's period reaches back across the year boundary")
				.containsExactly("2025-12-26", "2026-01-25");
		assertThat(LegacyPayrollFiscalSettings.computeBounds(2024, 3, 26, 25))
				.as("and back into a leap February")
				.containsExactly("2024-02-26", "2024-03-25");
	}

	/**
	 * A start day later than the shortest month still resolves: the 31st in a
	 * 30-day month is that month's last day, not an invalid date.
	 */
	@Test
	void aStartDayPastTheEndOfTheMonthIsClampedToTheMonthsLastDay() {
		String[] bounds = LegacyPayrollFiscalSettings.computeBounds(2026, 5, 31, 30);

		assertThat(bounds[0]).startsWith("2026-04-");
		assertThat(java.time.LocalDate.parse(bounds[0]))
				.as("April has 30 days, so a 31st start lands on the 30th")
				.isEqualTo(java.time.LocalDate.of(2026, 4, 30));
	}

	// ---------------- attachCompanyFiscalMonth: absence is the contract ----------------

	/**
	 * A non-positive company id writes <b>nothing</b>. PHP returns early, so
	 * the keys are absent rather than null, and this pins that difference
	 * because it is the kind that survives a refactor unnoticed: a `put(key,
	 * null)` reads as equivalent and is not.
	 *
	 * <p>The mobile client parses both with {@code getIntOrNull} -- no bang, no
	 * default -- so it tolerates either today. That makes this a forward
	 * guarantee rather than a live bug: the day a client adds a default or a
	 * required-key check, absent and null stop meaning the same thing, and the
	 * one PHP produces is absent.
	 */
	@Test
	void aNonPositiveCompanyIdLeavesTheFiscalKeysAbsentRatherThanNull() {
		// Never connected to: the early return happens before any query, and
		// the constructor builds its JdbcTemplate eagerly.
		LegacyPayrollFiscalSettings settings = new LegacyPayrollFiscalSettings(
				new org.springframework.jdbc.datasource.SimpleDriverDataSource());

		for (long companyId : new long[] {0L, -1L}) {
			Map<String, Object> employee = new LinkedHashMap<>();
			employee.put("id", 1L);

			settings.attachCompanyFiscalMonth(employee, companyId);

			assertThat(employee)
					.as("company id %d must write neither key", companyId)
					.doesNotContainKey("month_start_day")
					.doesNotContainKey("month_end_day")
					.containsOnlyKeys("id");
		}
	}

}
