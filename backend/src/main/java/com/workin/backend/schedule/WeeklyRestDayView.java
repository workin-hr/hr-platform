package com.workin.backend.schedule;

/** dayOfWeek keeps legacy's 0=Sunday..6=Saturday wire numbering. */
public record WeeklyRestDayView(int dayOfWeek, String name) {
}
