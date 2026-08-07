package com.workin.backend.payroll;

import java.math.BigDecimal;

public record PayslipView(
		Long id, Long batchId, Long employeeId, int daysPresent, int daysAbsent, int daysLeave,
		BigDecimal overtimeHours, BigDecimal basicSalary, BigDecimal housingAllowance, BigDecimal overtimePay,
		BigDecimal foodAllowance, BigDecimal riskAllowance, BigDecimal transportAllowance, BigDecimal incentives,
		BigDecimal penaltiesTotal, BigDecimal advanceDeduction, BigDecimal otherDeductions,
		BigDecimal grossSalary, BigDecimal totalEntitlements, BigDecimal totalDeductions, BigDecimal netSalary) {

	static PayslipView of(Payslip p) {
		return new PayslipView(
				p.getId(), p.getBatchId(), p.getEmployeeId(), p.getDaysPresent(), p.getDaysAbsent(), p.getDaysLeave(),
				p.getOvertimeHours(), p.getBasicSalary(), p.getHousingAllowance(), p.getOvertimePay(),
				p.getFoodAllowance(), p.getRiskAllowance(), p.getTransportAllowance(), p.getIncentives(),
				p.getPenaltiesTotal(), p.getAdvanceDeduction(), p.getOtherDeductions(),
				p.getGrossSalary(), p.getTotalEntitlements(), p.getTotalDeductions(), p.getNetSalary());
	}

}
