package com.workin.backend.penalties;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Zero days stays legal (legacy's schema default is 0.0 -- rejecting
 * it would invent a stricter rule); appliedToPayroll has no field
 * here, deliberately.
 */
public record CreatePenaltyRequest(
		@NotNull Long employeeId,
		@NotBlank String penaltyType,
		@NotNull @PositiveOrZero BigDecimal penaltyDays,
		String reason,
		LocalDate penaltyDate) {
}
