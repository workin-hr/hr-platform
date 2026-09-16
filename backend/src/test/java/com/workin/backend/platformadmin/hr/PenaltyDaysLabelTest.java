package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * {@code dashboard_penalty_days_option_label()}: each expected value is what the legacy function
 * printed, run unchanged under PHP 8.3.
 */
class PenaltyDaysLabelTest {

	private static final Function<String, String> ARABIC = Map.of("penalty_quarter_day", "ربع يوم",
			"penalty_half_day", "نص يوم", "penalty_one_day", "يوم", "penalty_days_unit", "أيام")::get;

	private static final Function<String, String> ENGLISH = Map.of("penalty_quarter_day", "Quarter day",
			"penalty_half_day", "Half day", "penalty_one_day", "1 day", "penalty_days_unit", "days")::get;

	@Test
	void aQuarterAHalfAndOneDayHaveTheirOwnWords() {
		assertThat(Penalty.daysLabel(0.25, ARABIC)).isEqualTo("ربع يوم");
		assertThat(Penalty.daysLabel(0.5, ARABIC)).isEqualTo("نص يوم");
		assertThat(Penalty.daysLabel(1.0, ARABIC)).isEqualTo("يوم");
		assertThat(Penalty.daysLabel(1.0, ENGLISH)).isEqualTo("1 day");
	}

	@Test
	void theWordsAllowLegacysTolerance() {
		assertThat(Penalty.daysLabel(0.2505, ARABIC)).isEqualTo("ربع يوم");
		assertThat(Penalty.daysLabel(0.2495, ENGLISH)).isEqualTo("Quarter day");
	}

	@Test
	void anyOtherWholeNumberIsCountedInDaysZeroIncluded() {
		assertThat(Penalty.daysLabel(2.0, ARABIC)).isEqualTo("2 أيام");
		assertThat(Penalty.daysLabel(10.0, ENGLISH)).isEqualTo("10 days");
		assertThat(Penalty.daysLabel(0.0, ARABIC)).isEqualTo("0 أيام");
	}

	@Test
	void anythingElseIsTheBareNumber() {
		assertThat(Penalty.daysLabel(0.75, ARABIC)).isEqualTo("0.75");
		assertThat(Penalty.daysLabel(1.5, ENGLISH)).isEqualTo("1.5");
		assertThat(Penalty.daysLabel(0.1, ARABIC)).isEqualTo("0.1");
	}

	@Test
	void theRowReadsItsStoredDecimalAndTheFormKeepsTheNumber() {
		Penalty row = new Penalty(1, 1, 1, "Co", "A1", "Aya", "T", new BigDecimal("2.0"), null,
				"2026-03-02", false, "2026-03-02 09:00:00");
		assertThat(row.daysLabel(ARABIC)).isEqualTo("2 أيام");
		assertThat(row.daysDisplay()).as("what the edit form posts back").isEqualTo("2");
	}

}
