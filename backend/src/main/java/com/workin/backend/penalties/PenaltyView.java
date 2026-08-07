package com.workin.backend.penalties;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PenaltyView(
		Long id,
		Long employeeId,
		String penaltyType,
		BigDecimal penaltyDays,
		String reason,
		LocalDate penaltyDate,
		boolean appliedToPayroll) {

	static PenaltyView of(Penalty penalty) {
		return new PenaltyView(
				penalty.getId(),
				penalty.getEmployeeId(),
				penalty.getPenaltyType(),
				penalty.getPenaltyDays(),
				penalty.getReason(),
				penalty.getPenaltyDate(),
				penalty.isAppliedToPayroll());
	}

}
