package com.workin.backend.requests;

public record RequestTypeView(
		Long id, String name, boolean isActive, boolean deductBalance,
		boolean countsAsPaidLeave, boolean addAttendanceException, Long exceptionTypeId) {

	static RequestTypeView of(RequestType t) {
		return new RequestTypeView(
				t.getId(), t.getName(), t.isActive(), t.isDeductBalance(),
				t.isCountsAsPaidLeave(), t.isAddAttendanceException(), t.getExceptionTypeId());
	}

}
