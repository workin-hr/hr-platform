package com.workin.backend.organization;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** workHours null -> 8.00; isActive null -> true. */
public record UpsertJobTitleRequest(
		@NotBlank String name,
		Long departmentId,
		@Positive BigDecimal workHours,
		Boolean isActive) {
}
