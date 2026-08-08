package com.workin.backend.schedule;

import java.util.List;

/**
 * schedule_month_overview's shape. officialHolidays is a declared stub
 * (always empty) until the holidays module lands -- spec Out item.
 */
public record MonthlyOverviewView(
		ShiftSummaryView shift, List<WeeklyRestDayView> weeklyRestDays,
		List<HolidayView> officialHolidays, List<ScheduleDayView> days) {
}
