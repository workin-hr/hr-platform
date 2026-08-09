package com.workin.backend.holidays;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One name applied across a list of dates — legacy's create shape,
 * where a multi-day holiday is several single-day rows sharing a name.
 *
 * <p>Legacy accepts the dates under any of three keys
 * ({@code holiday_dates}, {@code holiday_date}, {@code dates}) and
 * silently drops malformed entries. This takes one list of real dates,
 * so a malformed entry is a framework 400 naming the field rather than
 * a silent omission.
 */
public record CreateHolidaysRequest(
		@NotBlank @Size(max = 150) String name,
		@NotEmpty List<@NotNull LocalDate> holidayDates) {
}
