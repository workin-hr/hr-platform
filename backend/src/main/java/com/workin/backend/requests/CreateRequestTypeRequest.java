package com.workin.backend.requests;

import jakarta.validation.constraints.NotBlank;

/** Null toggles take V25's column defaults (true/false/true/false). */
public record CreateRequestTypeRequest(
		@NotBlank String name,
		Boolean isActive,
		Boolean deductBalance,
		Boolean countsAsPaidLeave,
		Boolean addAttendanceException,
		Long exceptionTypeId) {
}
