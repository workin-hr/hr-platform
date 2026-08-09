package com.workin.backend.holidays;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code holidayDate} is optional: omitting it keeps the current date,
 * which is legacy's behaviour. Moving a holiday onto a date another row
 * already holds is a 409.
 */
public record UpdateHolidayRequest(
		@NotBlank @Size(max = 150) String name,
		LocalDate holidayDate) {
}
