package com.workin.backend.payroll;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

public record CreatePayslipRequest(
		@NotNull Long batchId, @NotNull Long employeeId,
		int daysPresent, int daysAbsent, int daysLeave, BigDecimal overtimeHours) {
}
