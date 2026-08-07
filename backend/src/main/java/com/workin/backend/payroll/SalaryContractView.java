package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalaryContractView(
		Long id,
		Long employeeId,
		SalaryMode salaryMode,
		BigDecimal basicSalary,
		BigDecimal dailyWage,
		BigDecimal housingAllowance,
		BigDecimal transportAllowance,
		BigDecimal foodAllowance,
		BigDecimal riskAllowance,
		BigDecimal incentives,
		BigDecimal insuranceDeduction,
		BigDecimal taxDeduction,
		BigDecimal advancesDeduction,
		BigDecimal fundDeduction,
		BigDecimal penaltyDeduction,
		LocalDate effectiveFrom) {

	static SalaryContractView of(SalaryContract c) {
		return new SalaryContractView(
				c.getId(), c.getEmployeeId(), c.getSalaryMode(), c.getBasicSalary(), c.getDailyWage(),
				c.getHousingAllowance(), c.getTransportAllowance(), c.getFoodAllowance(), c.getRiskAllowance(),
				c.getIncentives(), c.getInsuranceDeduction(), c.getTaxDeduction(), c.getAdvancesDeduction(),
				c.getFundDeduction(), c.getPenaltyDeduction(), c.getEffectiveFrom());
	}

}
