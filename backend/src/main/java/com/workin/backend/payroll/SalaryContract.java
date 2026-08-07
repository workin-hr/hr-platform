package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only version history, matching legacy: "the effective contract
 * for a period" is application-computed as the most recent row with
 * {@code effectiveFrom <=} the period end -- see
 * {@link SalaryContractService#findEffectiveContract}. {@code update}
 * corrects an existing row's mistake (legacy's actual use of its
 * update.php), not a way to record a new compensation period -- create
 * a new row for that instead.
 */
@Entity
@Table(name = "salary_contracts")
public class SalaryContract {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "employee_id", nullable = false)
	private Long employeeId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Enumerated(EnumType.STRING)
	@Column(name = "salary_mode", nullable = false)
	private SalaryMode salaryMode = SalaryMode.MONTHLY;

	@Column(name = "basic_salary", nullable = false)
	private BigDecimal basicSalary = BigDecimal.ZERO;

	@Column(name = "daily_wage")
	private BigDecimal dailyWage;

	// housingAllowance is settable here per the provisional resolution
	// documented in docs/migration/payroll-module-execution-plan.md --
	// still an open product decision
	// (docs/bootstrap/open-questions.md, "Payroll Migration"), unlike
	// legacy which hardcoded this to 0 on every write path
	// (hr-legacy#14). Revisit this field's write-access if that
	// decision goes the other way.
	@Column(name = "housing_allowance", nullable = false)
	private BigDecimal housingAllowance = BigDecimal.ZERO;

	@Column(name = "transport_allowance", nullable = false)
	private BigDecimal transportAllowance = BigDecimal.ZERO;

	@Column(name = "food_allowance", nullable = false)
	private BigDecimal foodAllowance = BigDecimal.ZERO;

	@Column(name = "risk_allowance", nullable = false)
	private BigDecimal riskAllowance = BigDecimal.ZERO;

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

	@Column(name = "penalty_deduction", nullable = false)
	private BigDecimal penaltyDeduction = BigDecimal.ZERO;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	protected SalaryContract() {
	}

	public SalaryContract(Long employeeId, Long companyId, LocalDate effectiveFrom) {
		this.employeeId = employeeId;
		this.companyId = companyId;
		this.effectiveFrom = effectiveFrom;
	}

	public Long getId() {
		return id;
	}

	public Long getEmployeeId() {
		return employeeId;
	}

	public Long getCompanyId() {
		return companyId;
	}

	public SalaryMode getSalaryMode() {
		return salaryMode;
	}

	public void setSalaryMode(SalaryMode salaryMode) {
		this.salaryMode = salaryMode;
	}

	public BigDecimal getBasicSalary() {
		return basicSalary;
	}

	public void setBasicSalary(BigDecimal basicSalary) {
		this.basicSalary = basicSalary;
	}

	public BigDecimal getDailyWage() {
		return dailyWage;
	}

	public void setDailyWage(BigDecimal dailyWage) {
		this.dailyWage = dailyWage;
	}

	public BigDecimal getHousingAllowance() {
		return housingAllowance;
	}

	public void setHousingAllowance(BigDecimal housingAllowance) {
		this.housingAllowance = housingAllowance;
	}

	public BigDecimal getTransportAllowance() {
		return transportAllowance;
	}

	public void setTransportAllowance(BigDecimal transportAllowance) {
		this.transportAllowance = transportAllowance;
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

	public BigDecimal getPenaltyDeduction() {
		return penaltyDeduction;
	}

	public void setPenaltyDeduction(BigDecimal penaltyDeduction) {
		this.penaltyDeduction = penaltyDeduction;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

	public void setEffectiveFrom(LocalDate effectiveFrom) {
		this.effectiveFrom = effectiveFrom;
	}

}
