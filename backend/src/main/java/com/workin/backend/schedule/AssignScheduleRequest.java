package com.workin.backend.schedule;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** assign_employee_schedule.php's body: one snapshot row per date. */
public record AssignScheduleRequest(@NotNull Long shiftId, @NotEmpty List<@NotNull LocalDate> dates) {
}
