package com.workin.backend.organization;

import java.time.LocalTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** isActive null -> true. daysOff keeps legacy's "Fri,Sat" free text. */
public record UpsertShiftRequest(
		@NotBlank String name,
		@NotNull LocalTime startTime,
		@NotNull LocalTime endTime,
		@Size(max = 20) String daysOff,
		Boolean isActive) {
}
