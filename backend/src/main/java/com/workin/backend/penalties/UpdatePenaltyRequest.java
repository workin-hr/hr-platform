package com.workin.backend.penalties;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdatePenaltyRequest(
		@NotBlank String penaltyType,
		@NotNull @PositiveOrZero BigDecimal penaltyDays,
		String reason,
		LocalDate penaltyDate) {
}
