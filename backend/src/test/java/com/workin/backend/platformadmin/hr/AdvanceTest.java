package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * {@link Advance}'s balance arithmetic, which is the only real computation on
 * the advances page and the one place an edit can quietly lose money.
 *
 * <p>Unit-tested rather than only through the page, because the interesting
 * inputs are combinations of four numbers and driving them over HTTP would
 * prove the same thing far more slowly.
 */
class AdvanceTest {

	private static BigDecimal remaining(String status, String was, String oldAmount, String newAmount) {
		return Advance.remainingAfterEdit(status, new BigDecimal(was), new BigDecimal(oldAmount),
				new BigDecimal(newAmount));
	}

	@Test
	void aPendingAdvanceTracksTheAmountExactly() {
		// Nothing repaid yet, so the balance is simply the new amount --
		// whether that is more or less than before.
		assertThat(remaining("pending", "1000", "1000", "1500")).isEqualByComparingTo("1500");
		assertThat(remaining("pending", "1000", "1000", "400")).isEqualByComparingTo("400");
	}

	@Test
	void anApprovedAdvanceKeepsWhatWasAlreadyRepaid() {
		// 1000 advanced, 400 repaid, so 600 outstanding. Raising it to 1200
		// adds the 200 difference and leaves the repayment intact.
		assertThat(remaining("approved", "600", "1000", "1200")).isEqualByComparingTo("800");
	}

	@Test
	void reducingBelowWhatWasRepaidClearsTheDebtRatherThanGoingNegative() {
		// 1000 advanced, 400 repaid, reduced to 300: 600 + (300 - 1000) = -100,
		// floored to 0. Without the floor the employee becomes a creditor.
		assertThat(remaining("approved", "600", "1000", "300")).isEqualByComparingTo("0");
	}

	@Test
	void theBalanceNeverExceedsTheAmountAdvanced() {
		// A correction that would leave more outstanding than was ever lent.
		// 1000 advanced, none repaid, corrected down to 800 then back up: the
		// cap is what stops 1000 outstanding on an 800 advance.
		assertThat(remaining("approved", "1000", "800", "900")).isEqualByComparingTo("900");
	}

	@Test
	void anUntouchedAmountLeavesTheBalanceWhereItWas() {
		// Editing the reason or the date must not move the money.
		assertThat(remaining("approved", "600", "1000", "1000")).isEqualByComparingTo("600");
	}

	@Test
	void aZeroBalanceOnAnApprovedAdvanceMeansFullyRepaid() {
		// Established from the schema and the data, not from PHP's `??`.
		// `remaining` is `decimal(10,2) NOT NULL` with no default, and in the
		// production copy all 25 rows with `remaining = 0` are `approved` --
		// none pending, none rejected. So zero on an approved advance is
		// "nothing left to repay", and an edit that leaves the amount alone
		// must not resurrect the debt.
		assertThat(remaining("approved", "0", "1000", "1000")).isEqualByComparingTo("0");
		// Raising the amount on a repaid advance owes the difference, and only
		// the difference.
		assertThat(remaining("approved", "0", "1000", "1200")).isEqualByComparingTo("200");
	}

	@Test
	void aNullBalanceCannotOccurAndIsMerelyToleratedAsZero() {
		// NOT the same claim as the test above. A null does not *mean* fully
		// repaid -- it cannot arise at all: the column is NOT NULL and the one
		// insert path always sets it. This mirrors PHP's `?? 0`, which guards a
		// missing array key rather than encoding a state.
		assertThat(Advance.remainingAfterEdit("approved", null, new BigDecimal("1000"),
				new BigDecimal("1200"))).isEqualByComparingTo("200");
	}

	@Test
	void aSettledAdvanceIsOneNoEditCanMeanAnythingAbout() {
		assertThat(settled("rejected", "500")).as("rejected is finished").isTrue();
		assertThat(settled("approved", "0")).as("fully repaid is finished").isTrue();
		assertThat(settled("approved", "250")).as("still owing is not").isFalse();
		assertThat(settled("pending", "0"))
				.as("pending with a zero balance is still open -- nothing was advanced yet")
				.isFalse();
	}

	private static boolean settled(String status, String remaining) {
		return new Advance(1L, 2L, 3L, null, "E1", "Someone", new BigDecimal("500"),
				new BigDecimal(remaining), null, null, status, "2026-03-02", "2026-03-02")
				.isSettled();
	}

	@Test
	void theAmountParserTakesTheLeadingNumberTheWayPhpDoes() {
		assertThat(Advance.amount("1500.50")).isEqualByComparingTo("1500.50");
		assertThat(Advance.amount("1500abc")).as("(float) takes the leading number")
				.isEqualByComparingTo("1500");
		assertThat(Advance.amount("abc")).isEqualByComparingTo("0");
		assertThat(Advance.amount("")).isEqualByComparingTo("0");
		assertThat(Advance.amount(null)).isEqualByComparingTo("0");
		assertThat(Advance.amount("-50")).as("negative parses, and the caller refuses it")
				.isEqualByComparingTo("-50");
	}

}
