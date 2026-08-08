package com.workin.backend.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One calendar day. id is the employee_schedules row id for manual
 * rows, null for computed-from-shift rows (recorded normalization:
 * legacy returns 0 there).
 */
public record ScheduleDayView(
		Long id, LocalDate scheduleDate, String name,
		LocalTime startTime, LocalTime endTime, String exception) {
}
