package com.workin.backend.organization;

import java.time.LocalTime;

public record ShiftView(Long id, String name, LocalTime startTime, LocalTime endTime, String daysOff, boolean isActive) {

	static ShiftView of(Shift s) {
		return new ShiftView(s.getId(), s.getName(), s.getStartTime(), s.getEndTime(), s.getDaysOff(), s.isActive());
	}

}
