package com.workin.backend.companysettings;

import java.math.BigDecimal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Every field nullable -- null means "unset this". Range validation
 * is a recorded normalization: legacy silently clamps out-of-range
 * day values, the new surface rejects them.
 */
public record UpdateCompanySettingsRequest(
		@Min(1) @Max(31) Short monthStartDay,
		@Min(1) @Max(31) Short monthEndDay,
		@Size(max = 60) String weeklyOffDays,
		@PositiveOrZero BigDecimal overtimeRate,
		@PositiveOrZero BigDecimal monthlyLeaveAccrual,
		Boolean payOvertime) {
}
