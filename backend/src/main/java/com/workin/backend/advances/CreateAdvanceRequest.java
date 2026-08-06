package com.workin.backend.advances;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateAdvanceRequest(
		@NotNull Long employeeId,
		@NotNull @Positive BigDecimal amount,
		String reason) {
}
