package com.workin.backend.advances;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AdvanceView(
		Long id,
		Long employeeId,
		BigDecimal amount,
		BigDecimal remaining,
		String reason,
		String rejectionReason,
		String status,
		LocalDate requestDate) {

	static AdvanceView of(Advance advance) {
		return new AdvanceView(
				advance.getId(),
				advance.getEmployeeId(),
				advance.getAmount(),
				advance.getRemaining(),
				advance.getReason(),
				advance.getRejectionReason(),
				advance.getStatus().name(),
				advance.getRequestDate());
	}

}
