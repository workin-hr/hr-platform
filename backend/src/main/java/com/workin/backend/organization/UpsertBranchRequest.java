package com.workin.backend.organization;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** radiusMeters null -> 200; isActive null -> true. No qr fields -- view-only. */
public record UpsertBranchRequest(
		@NotBlank String name,
		String address,
		BigDecimal latitude,
		BigDecimal longitude,
		@Positive Integer radiusMeters,
		Boolean isActive) {
}
