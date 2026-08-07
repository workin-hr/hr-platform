package com.workin.backend.payroll;

/**
 * V9__create_salary_contracts.sql's salary_mode CHECK constraint. In
 * {@code DAILY} mode, {@code basicSalary} and the 4 contract allowances
 * (transport/food/risk/incentives) are always zero in favor of
 * {@code dailyWage} -- see {@link SalaryContractService}.
 */
public enum SalaryMode {
	MONTHLY,
	DAILY
}
