package com.workin.backend.attendance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Carries both row shapes; {@link AttendanceService} resolves which
 * (exception iff {@code exceptionTypeId} is set) and rejects mixed or
 * incomplete combinations with a 400.
 */
public record CreateAttendanceRequest(
		@NotNull Long employeeId,
		Instant checkIn,
		Instant checkOut,
		@Pattern(regexp = "app|excel|qr") String method,
		BigDecimal latitude,
		BigDecimal longitude,
		LocalDate date,
		Long exceptionTypeId) {
}
