package com.workin.backend.requests;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record RequestView(
		Long id, Long employeeId, Long requestTypeId, LocalDate fromDate, LocalDate toDate,
		LocalTime fromTime, LocalTime toTime, String notes, RequestStatus status,
		String reply, Long approverMembershipId, Instant decidedAt) {

	static RequestView of(LeaveRequest r) {
		return new RequestView(
				r.getId(), r.getEmployeeId(), r.getRequestTypeId(), r.getFromDate(), r.getToDate(),
				r.getFromTime(), r.getToTime(), r.getNotes(), r.getStatus(),
				r.getReply(), r.getApproverMembershipId(), r.getDecidedAt());
	}

}
