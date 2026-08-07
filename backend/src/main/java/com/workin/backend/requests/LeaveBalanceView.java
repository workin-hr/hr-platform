package com.workin.backend.requests;

import java.math.BigDecimal;

public record LeaveBalanceView(
		Long id, Long employeeId, Short year, Short periodFromMonth, Short periodToMonth,
		BigDecimal monthlyCapDays, BigDecimal totalDays, BigDecimal usedDays, BigDecimal remainingDays) {

	static LeaveBalanceView of(LeaveBalance b) {
		// Computed with the DB generated column's exact expression rather
		// than read from the entity field: JPA does not re-read generated
		// columns after a flush, so the mapped field goes stale on update.
		return new LeaveBalanceView(
				b.getId(), b.getEmployeeId(), b.getYear(), b.getPeriodFromMonth(), b.getPeriodToMonth(),
				b.getMonthlyCapDays(), b.getTotalDays(), b.getUsedDays(),
				b.getTotalDays().subtract(b.getUsedDays()));
	}

}
