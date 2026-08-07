package com.workin.backend.attendance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.Pattern;

/** Same two-shape carrier as {@link CreateAttendanceRequest}, minus the employee. */
public record UpdateAttendanceRequest(
		Instant checkIn,
		Instant checkOut,
		@Pattern(regexp = "app|excel|qr") String method,
		BigDecimal latitude,
		BigDecimal longitude,
		LocalDate date,
		Long exceptionTypeId) {
}
