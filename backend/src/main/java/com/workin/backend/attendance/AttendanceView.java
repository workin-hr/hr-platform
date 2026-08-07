package com.workin.backend.attendance;

import java.math.BigDecimal;
import java.time.Instant;

public record AttendanceView(
		Long id, Long employeeId, Instant checkIn, Instant checkOut, String method,
		BigDecimal latitude, BigDecimal longitude, Long exceptionTypeId) {

	static AttendanceView of(Attendance a) {
		return new AttendanceView(
				a.getId(), a.getEmployeeId(), a.getCheckIn(), a.getCheckOut(), a.getMethod(),
				a.getLatitude(), a.getLongitude(), a.getExceptionTypeId());
	}

}
