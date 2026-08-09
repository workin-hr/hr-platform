package com.workin.backend.holidays;

import java.time.LocalDate;

/** One holiday as the API returns it. */
public record OfficialHolidayView(Long id, String name, LocalDate holidayDate) {

	public static OfficialHolidayView of(OfficialHoliday holiday) {
		return new OfficialHolidayView(holiday.getId(), holiday.getName(), holiday.getHolidayDate());
	}

}
