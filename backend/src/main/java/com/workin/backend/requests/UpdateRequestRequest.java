package com.workin.backend.requests;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;

public record UpdateRequestRequest(
		@NotNull Long requestTypeId,
		@NotNull LocalDate fromDate,
		@NotNull LocalDate toDate,
		LocalTime fromTime,
		LocalTime toTime,
		String notes) {
}
