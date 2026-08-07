package com.workin.backend.payroll;

import java.math.BigDecimal;

public record UpdatePayslipRequest(int daysPresent, int daysAbsent, int daysLeave, BigDecimal overtimeHours) {
}
