package com.workin.backend.payroll;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code allowances} (schema column name, kept for compatibility with
 * V11) actually holds the housing allowance, not a general bucket --
 * confirmed in docs/legacy/business-rule-extraction.md. Accessed here
 * via {@link #getHousingAllowance()}/{@link #setHousingAllowance} so
 * call sites never have to remember the naming mismatch.
 */
@Entity
@Table(name = "payslips")
public class Payslip {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "batch_id", nullable = false)
	private Long batchId;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "days_present", nullable = false)
	private short daysPresent;

	@Column(name = "days_absent", nullable = false)
	private short daysAbsent;

	@Column(name = "days_leave", nullable = false)
	private short daysLeave;

	@Column(name = "overtime_hours", nullable = false)
	private BigDecimal overtimeHours = BigDecimal.ZERO;

	@Column(name = "basic_salary", nullable = false)
	private BigDecimal basicSalary = BigDecimal.ZERO;

	@Column(name = "allowances", nullable = false)
	private BigDecimal housingAllowance = BigDecimal.ZERO;

	@Column(name = "overtime_pay", nullable = false)
	private BigDecimal overtimePay = BigDecimal.ZERO;

	@Column(name = "penalties_total", nullable = false)
	private BigDecimal penaltiesTotal = BigDecimal.ZERO;

	@Column(name = "advance_deduction", nullable = false)
	private BigDecimal advanceDeduction = BigDecimal.ZERO;

	@Column(name = "other_deductions", nullable = false)
	private BigDecimal otherDeductions = BigDecimal.ZERO;

	@Column(name = "net_salary", nullable = false)
	private BigDecimal netSalary = BigDecimal.ZERO;

	@Column(name = "food_allowance", nullable = false)
	private BigDecimal foodAllowance = BigDecimal.ZERO;

	@Column(name = "risk_allowance", nullable = false)
	private BigDecimal riskAllowance = BigDecimal.ZERO;

	@Column(name = "transport_allowance", nullable = false)
	private BigDecimal transportAllowance = BigDecimal.ZERO;

	@Column(nullable = false)
	private BigDecimal incentives = BigDecimal.ZERO;

	@Column(name = "insurance_deduction", nullable = false)
	private BigDecimal insuranceDeduction = BigDecimal.ZERO;

	@Column(name = "tax_deduction", nullable = false)
	private BigDecimal taxDeduction = BigDecimal.ZERO;

	@Column(name = "advances_deduction", nullable = false)
	private BigDecimal advancesDeduction = BigDecimal.ZERO;

	@Column(name = "fund_deduction", nullable = false)
	private BigDecimal fundDeduction = BigDecimal.ZERO;

	@Column(name = "gross_salary", nullable = false)
	private BigDecimal grossSalary = BigDecimal.ZERO;

	@Column(name = "total_entitlements", nullable = false)
	private BigDecimal totalEntitlements = BigDecimal.ZERO;

	@Column(name = "total_deductions", nullable = false)
	private BigDecimal totalDeductions = BigDecimal.ZERO;

	protected Payslip() {
	}

	public Payslip(Long batchId, Long employeeId, Long companyId) {
		this.batchId = batchId;
		this.employeeId = employeeId;
		this.companyId = companyId;
	}

	public Long getId() {
		return id;
	}

	public Long getBatchId() {
		return batchId;
	}

	public Long getEmployeeId() {
		return employeeId;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public short getDaysPresent() {
		return daysPresent;
	}

	public void setDaysPresent(short daysPresent) {
		this.daysPresent = daysPresent;
	}

	public short getDaysAbsent() {
		return daysAbsent;
	}

	public void setDaysAbsent(short daysAbsent) {
		this.daysAbsent = daysAbsent;
	}

	public short getDaysLeave() {
		return daysLeave;
	}

	public void setDaysLeave(short daysLeave) {
		this.daysLeave = daysLeave;
	}

	public BigDecimal getOvertimeHours() {
		return overtimeHours;
	}

	public void setOvertimeHours(BigDecimal overtimeHours) {
		this.overtimeHours = overtimeHours;
	}

	public BigDecimal getBasicSalary() {
		return basicSalary;
	}

	public void setBasicSalary(BigDecimal basicSalary) {
		this.basicSalary = basicSalary;
	}

	public BigDecimal getHousingAllowance() {
		return housingAllowance;
	}

	public void setHousingAllowance(BigDecimal housingAllowance) {
		this.housingAllowance = housingAllowance;
	}

	public BigDecimal getOvertimePay() {
		return overtimePay;
	}

	public void setOvertimePay(BigDecimal overtimePay) {
		this.overtimePay = overtimePay;
	}

	public BigDecimal getPenaltiesTotal() {
		return penaltiesTotal;
	}

	public void setPenaltiesTotal(BigDecimal penaltiesTotal) {
		this.penaltiesTotal = penaltiesTotal;
	}

	public BigDecimal getAdvanceDeduction() {
		return advanceDeduction;
	}

	public void setAdvanceDeduction(BigDecimal advanceDeduction) {
		this.advanceDeduction = advanceDeduction;
	}

	public BigDecimal getOtherDeductions() {
		return otherDeductions;
	}

	public void setOtherDeductions(BigDecimal otherDeductions) {
		this.otherDeductions = otherDeductions;
	}

	public BigDecimal getNetSalary() {
		return netSalary;
	}

	public void setNetSalary(BigDecimal netSalary) {
		this.netSalary = netSalary;
	}

	public BigDecimal getFoodAllowance() {
		return foodAllowance;
	}

	public void setFoodAllowance(BigDecimal foodAllowance) {
		this.foodAllowance = foodAllowance;
	}

	public BigDecimal getRiskAllowance() {
		return riskAllowance;
	}

	public void setRiskAllowance(BigDecimal riskAllowance) {
		this.riskAllowance = riskAllowance;
	}

	public BigDecimal getTransportAllowance() {
		return transportAllowance;
	}

	public void setTransportAllowance(BigDecimal transportAllowance) {
		this.transportAllowance = transportAllowance;
	}

	public BigDecimal getIncentives() {
		return incentives;
	}

	public void setIncentives(BigDecimal incentives) {
		this.incentives = incentives;
	}

	public BigDecimal getInsuranceDeduction() {
		return insuranceDeduction;
	}

	public void setInsuranceDeduction(BigDecimal insuranceDeduction) {
		this.insuranceDeduction = insuranceDeduction;
	}

	public BigDecimal getTaxDeduction() {
		return taxDeduction;
	}

	public void setTaxDeduction(BigDecimal taxDeduction) {
		this.taxDeduction = taxDeduction;
	}

	public BigDecimal getAdvancesDeduction() {
		return advancesDeduction;
	}

	public void setAdvancesDeduction(BigDecimal advancesDeduction) {
		this.advancesDeduction = advancesDeduction;
	}

	public BigDecimal getFundDeduction() {
		return fundDeduction;
	}

	public void setFundDeduction(BigDecimal fundDeduction) {
		this.fundDeduction = fundDeduction;
	}

	public BigDecimal getGrossSalary() {
		return grossSalary;
	}

	public void setGrossSalary(BigDecimal grossSalary) {
		this.grossSalary = grossSalary;
	}

	public BigDecimal getTotalEntitlements() {
		return totalEntitlements;
	}

	public void setTotalEntitlements(BigDecimal totalEntitlements) {
		this.totalEntitlements = totalEntitlements;
	}

	public BigDecimal getTotalDeductions() {
		return totalDeductions;
	}

	public void setTotalDeductions(BigDecimal totalDeductions) {
		this.totalDeductions = totalDeductions;
	}

}
