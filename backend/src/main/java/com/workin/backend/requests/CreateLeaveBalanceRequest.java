package com.workin.backend.requests;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateLeaveBalanceRequest(
		@NotNull Long employeeId,
		@NotNull Short year,
		@NotNull @PositiveOrZero BigDecimal totalDays,
		Short periodFromMonth,
		Short periodToMonth,
		BigDecimal monthlyCapDays) {
}
