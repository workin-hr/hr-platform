package com.workin.legacy.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * The pure halves of {@code turnover_helper.php}: the rate arithmetic and the
 * three-month step. Both are worth pinning without a database because both have
 * a boundary that a reasonable "cleanup" would move.
 */
class LegacyTurnoverTest {

	@Test
	void theRateIsDeparturesOverTheAverageOfOpeningAndClosingHeadcount() {
		// start 100, +20 hires, -10 departures => end 110, average 105
		assertThat(LegacyTurnover.rateFromCounts(100, 20, 10)).isEqualTo(9.52);
	}

	@Test
	void aPeriodWithNoHeadcountAtAllIsZeroRatherThanADivisionByZero() {
		assertThat(LegacyTurnover.rateFromCounts(0, 0, 0)).isEqualTo(0.0);
	}

	/**
	 * The closing headcount is floored at zero, which <b>raises</b> the rate
	 * rather than lowering it: without the floor the average would be negative
	 * and the percentage would come back negative too.
	 */
	@Test
	void moreDeparturesThanHeadcountFloorsTheClosingCountAndInflatesTheRate() {
		// start 10, no hires, 20 departures => end floored to 0, average 5
		assertThat(LegacyTurnover.rateFromCounts(10, 0, 20)).isEqualTo(400.0);
	}

	@Test
	void theRateIsRoundedToTwoDecimals() {
		// 1 / 1.5 * 100 = 66.666...
		assertThat(LegacyTurnover.rateFromCounts(2, 0, 1)).isEqualTo(66.67);
	}

	/**
	 * {@code strtotime('-3 months')} keeps the day and lets it roll into the
	 * next month; {@link LocalDate#minusMonths} clamps to the month's last day
	 * instead. The two disagree by up to three days, which moves the cohort
	 * window's start and therefore who is counted.
	 */
	@Test
	void theThreeMonthStepRollsTheDayOverflowRatherThanClampingIt() {
		assertThat(LegacyTurnover.minusThreeMonthsPhpStyle(LocalDate.of(2026, 5, 31)))
				.as("31 May - 3 months is 31 February, which PHP resolves to 3 March")
				.isEqualTo(LocalDate.of(2026, 3, 3));
		assertThat(LocalDate.of(2026, 5, 31).minusMonths(3))
				.as("and this is what Java would have done instead")
				.isEqualTo(LocalDate.of(2026, 2, 28));
	}

	@Test
	void theThreeMonthStepIsExactWhenTheTargetMonthIsLongEnough() {
		assertThat(LegacyTurnover.minusThreeMonthsPhpStyle(LocalDate.of(2026, 8, 29)))
				.isEqualTo(LocalDate.of(2026, 5, 29));
		assertThat(LegacyTurnover.minusThreeMonthsPhpStyle(LocalDate.of(2026, 1, 15)))
				.as("crossing the year boundary")
				.isEqualTo(LocalDate.of(2025, 10, 15));
	}

	/** A leap February still rolls, just one day less far. */
	@Test
	void theThreeMonthStepRollsCorrectlyIntoALeapFebruary() {
		assertThat(LegacyTurnover.minusThreeMonthsPhpStyle(LocalDate.of(2024, 5, 31)))
				.isEqualTo(LocalDate.of(2024, 3, 2));
	}
}
