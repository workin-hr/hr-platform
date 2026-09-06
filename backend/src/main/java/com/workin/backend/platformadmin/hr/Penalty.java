package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;

/**
 * A row of {@code penalties} as the dashboard's list and form need it
 * ({@code dashboard/pages/penalties}).
 *
 * @param appliedToPayroll once true the row is <b>frozen</b>: legacy refuses
 *                         to edit it, because payroll has already deducted
 *                         against it and changing the days would silently
 *                         disagree with a payslip already issued
 */
public record Penalty(
		long id, long employeeId, long companyId, String companyName, String employeeCode,
		String employeeName, String penaltyType, BigDecimal penaltyDays, String reason,
		String penaltyDate, boolean appliedToPayroll, String createdAt) {

	public String daysDisplay() {
		return this.penaltyDays == null ? "0" : this.penaltyDays.stripTrailingZeros().toPlainString();
	}

	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

}
