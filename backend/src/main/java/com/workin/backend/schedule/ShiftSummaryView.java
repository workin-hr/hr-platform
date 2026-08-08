package com.workin.backend.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftSummaryView(
		Long shiftId, String name, LocalTime startTime, LocalTime endTime,
		LocalDate effectiveFrom, LocalDate effectiveTo) {
}
