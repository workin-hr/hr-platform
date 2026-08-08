package com.workin.backend.schedule;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * generate_employee_schedule.php's body. Legacy's optional
 * replace flag is dropped: replace-existing is the only mode its
 * endpoint effectively exposes (spec In section).
 */
public record GenerateScheduleRequest(@NotNull LocalDate from, @NotNull LocalDate to) {
}
