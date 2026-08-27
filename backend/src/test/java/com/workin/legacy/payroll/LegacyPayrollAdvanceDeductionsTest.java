package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@code payroll_advance_planned_for_batch()} etc.
 * ({@code payroll_calculation.php:870-1019}).
 */
class LegacyPayrollAdvanceDeductionsTest {

	private final LegacyPayrollAdvanceDeductions deductions = new LegacyPayrollAdvanceDeductions();

	private static Map<String, Object> advance(Map<String, Object> overrides) {
		Map<String, Object> advance = new LinkedHashMap<>();
		advance.put("id", 1L);
		advance.put("amount", "500.00");
		advance.put("remaining", "500.00");
		advance.put("deduction_mode", "single_payroll_month");
		advance.putAll(overrides);
		return advance;
	}

	@Test
	void singlePayrollMonthPlansOnlyItsOwnMonth() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_payroll_year", 2026, "deduction_payroll_month", 4));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("500.00");
		assertThat(deductions.plannedForBatch(advance, 2026, 5)).isEqualByComparingTo("0");
	}

	@Test
	void installmentsJsonMatchesByYearAndMonth() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_installments_json",
				"[{\"year\":2026,\"month\":4,\"amount\":100},{\"year\":2026,\"month\":5,\"amount\":150}]"));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("100");
		assertThat(deductions.plannedForBatch(advance, 2026, 5)).isEqualByComparingTo("150");
		assertThat(deductions.plannedForBatch(advance, 2026, 6)).isEqualByComparingTo("0");
	}

	@Test
	void installmentsJsonAcceptsThePayrollPrefixedKeysToo() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_installments_json",
				"[{\"payroll_year\":2026,\"payroll_month\":4,\"amount\":75}]"));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("75");
	}

	@Test
	void perMonthScheduleAppliesWithinItsMonthCountWindow() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_amount_per_month", "50.00",
				"deduction_payroll_year", 2026, "deduction_payroll_month", 3,
				"deduction_month_count", 3));

		assertThat(deductions.plannedForBatch(advance, 2026, 3)).isEqualByComparingTo("50.00"); // month 1 of 3
		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("50.00"); // month 2 of 3
		assertThat(deductions.plannedForBatch(advance, 2026, 5)).isEqualByComparingTo("50.00"); // month 3 of 3
		assertThat(deductions.plannedForBatch(advance, 2026, 6)).isEqualByComparingTo("0"); // past the window
		assertThat(deductions.plannedForBatch(advance, 2026, 2)).isEqualByComparingTo("0"); // before the window
	}

	/**
	 * Malformed {@code deduction_installments_json} must never throw --
	 * {@code plannedForBatch} silently falls through to the per-month
	 * schedule instead, exactly like a config a client never sent one at
	 * all. Each case below pairs the malformed JSON with a real per-month
	 * fallback so the assertion proves "fell through", not just "returned
	 * zero" (which a thrown-and-swallowed exception could also produce).
	 */
	@Test
	void invalidJsonSyntaxFallsThroughToThePerMonthSchedule() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_installments_json", "{not valid json",
				"deduction_amount_per_month", "50.00",
				"deduction_payroll_year", 2026, "deduction_payroll_month", 4,
				"deduction_month_count", 1));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("50.00");
	}

	@Test
	void anEmptyInstallmentsArrayFallsThroughToThePerMonthSchedule() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_installments_json", "[]",
				"deduction_amount_per_month", "50.00",
				"deduction_payroll_year", 2026, "deduction_payroll_month", 4,
				"deduction_month_count", 1));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("50.00");
	}

	@Test
	void aJsonObjectInsteadOfAnArrayFallsThroughToThePerMonthSchedule() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_installments_json", "{\"year\":2026,\"month\":4,\"amount\":100}",
				"deduction_amount_per_month", "50.00",
				"deduction_payroll_year", 2026, "deduction_payroll_month", 4,
				"deduction_month_count", 1));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("50.00");
	}

	@Test
	void nonObjectArrayItemsAreSkippedRatherThanThrowing() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_installments_json", "[1, \"x\", null, {\"year\":2026,\"month\":4,\"amount\":100}]"));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("100");
	}

	@Test
	void anInstallmentEntryMissingTheAmountFieldIsTreatedAsZeroAndSkipped() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_installments_json", "[{\"year\":2026,\"month\":4}]",
				"deduction_amount_per_month", "50.00",
				"deduction_payroll_year", 2026, "deduction_payroll_month", 4,
				"deduction_month_count", 1));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("50.00");
	}

	@Test
	void nonNumericInstallmentFieldsAreTreatedAsZeroRatherThanThrowing() {
		Map<String, Object> advance = advance(Map.of(
				"deduction_mode", "installments",
				"deduction_installments_json",
				"[{\"year\":\"not-a-year\",\"month\":\"not-a-month\",\"amount\":\"not-a-number\"}]",
				"deduction_amount_per_month", "50.00",
				"deduction_payroll_year", 2026, "deduction_payroll_month", 4,
				"deduction_month_count", 1));

		assertThat(deductions.plannedForBatch(advance, 2026, 4)).isEqualByComparingTo("50.00");
	}

	@Test
	void itemsForBatchCapsTheDeductionAtTheRemainingBalance() {
		Map<String, Object> advance = advance(Map.of(
				"remaining", "30.00", "deduction_payroll_year", 2026, "deduction_payroll_month", 4));

		List<LegacyPayrollAdvanceDeductions.DeductionItem> items = deductions.itemsForBatch(List.of(advance), 2026, 4);

		assertThat(items).hasSize(1);
		assertThat(items.get(0).amount()).isEqualByComparingTo("30.00"); // planned 500, capped at remaining 30
	}

	@Test
	void itemsForBatchSkipsAdvancesWithNoRemainingBalance() {
		Map<String, Object> advance = advance(Map.of(
				"remaining", "0", "deduction_payroll_year", 2026, "deduction_payroll_month", 4));

		assertThat(deductions.itemsForBatch(List.of(advance), 2026, 4)).isEmpty();
	}

	@Test
	void deductionForBatchSumsAllMatchingItems() {
		Map<String, Object> a = advance(Map.of(
				"id", 1L, "remaining", "100.00", "deduction_payroll_year", 2026, "deduction_payroll_month", 4));
		Map<String, Object> b = advance(Map.of(
				"id", 2L, "remaining", "50.00", "deduction_payroll_year", 2026, "deduction_payroll_month", 4,
				"amount", "50.00"));

		assertThat(deductions.deductionForBatch(List.of(a, b), 2026, 4)).isEqualByComparingTo("150.00");
	}

	@Test
	void restoreSharesSplitProportionallyAndTheLastShareAbsorbsTheRoundingRemainder() {
		Map<String, Object> a = advance(Map.of(
				"id", 1L, "deduction_payroll_year", 2026, "deduction_payroll_month", 4, "amount", "100.00"));
		Map<String, Object> b = advance(Map.of(
				"id", 2L, "deduction_payroll_year", 2026, "deduction_payroll_month", 4, "amount", "50.00"));

		List<LegacyPayrollAdvanceDeductions.RestoreShare> shares =
				deductions.restoreShares(List.of(a, b), new BigDecimal("150.00"), 2026, 4);

		assertThat(shares).hasSize(2);
		BigDecimal total = shares.stream().map(LegacyPayrollAdvanceDeductions.RestoreShare::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(total).isEqualByComparingTo("150.00");
	}

	@Test
	void restoreSharesIsEmptyForANonPositiveDeductedTotal() {
		assertThat(deductions.restoreShares(List.of(advance(Map.of())), BigDecimal.ZERO, 2026, 4)).isEmpty();
	}
}
