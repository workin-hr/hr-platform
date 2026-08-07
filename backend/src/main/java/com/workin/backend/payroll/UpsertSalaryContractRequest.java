package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * Shared create/update shape -- V9's own append-only-history comment
 * means "update" corrects an existing row, "create" starts a new one,
 * but both write the same field set. {@code housingAllowance} is
 * accepted here (unlike legacy, which hardcoded it to 0 on every write
 * path, hr-legacy#14) per the provisional resolution in
 * docs/bootstrap/open-questions.md ("Payroll Migration") -- still an
 * open product decision, revisit this field's write-access if it goes
 * the other way.
 */
public record UpsertSalaryContractRequest(
		@NotNull SalaryMode salaryMode,
		BigDecimal basicSalary,
		BigDecimal dailyWage,
		BigDecimal housingAllowance,
		BigDecimal transportAllowance,
		BigDecimal foodAllowance,
		BigDecimal riskAllowance,
		BigDecimal incentives,
		BigDecimal insuranceDeduction,
		BigDecimal taxDeduction,
		BigDecimal advancesDeduction,
		BigDecimal fundDeduction,
		BigDecimal penaltyDeduction,
		@NotNull LocalDate effectiveFrom) {
}
