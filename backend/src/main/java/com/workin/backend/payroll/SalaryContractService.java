package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;

@Service
public class SalaryContractService {

	/**
	 * Every settable field on a contract, independent of HTTP shape --
	 * see docs/migration/payroll-module-execution-plan.md's
	 * SalaryContract section for the DAILY-mode zeroing rule this
	 * record's fields feed into.
	 */
	public record ContractFields(
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
	}

	private final SalaryContractRepository salaryContractRepository;
	private final EmployeeRepository employeeRepository;

	public SalaryContractService(SalaryContractRepository salaryContractRepository, EmployeeRepository employeeRepository) {
		this.salaryContractRepository = salaryContractRepository;
		this.employeeRepository = employeeRepository;
	}

	@Transactional
	public SalaryContract create(AuthorizationContext context, Long employeeId, ContractFields fields) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Employee " + employeeId + " not found"));

		SalaryContract contract = new SalaryContract(employeeId, context.companyId(), fields.effectiveFrom());
		applyFields(contract, fields);
		return salaryContractRepository.save(contract);
	}

	@Transactional
	public SalaryContract update(AuthorizationContext context, Long contractId, ContractFields fields) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		SalaryContract contract = salaryContractRepository.findByIdAndCompanyId(contractId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Salary contract " + contractId + " not found"));
		applyFields(contract, fields);
		return salaryContractRepository.save(contract);
	}

	@Transactional
	public void delete(AuthorizationContext context, Long contractId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		SalaryContract contract = salaryContractRepository.findByIdAndCompanyId(contractId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Salary contract " + contractId + " not found"));
		salaryContractRepository.delete(contract);
	}

	@Transactional(readOnly = true)
	public SalaryContract findOne(AuthorizationContext context, Long contractId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR, TenantRole.MANAGER);
		return salaryContractRepository.findByIdAndCompanyId(contractId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Salary contract " + contractId + " not found"));
	}

	@Transactional(readOnly = true)
	public List<SalaryContract> listForEmployee(AuthorizationContext context, Long employeeId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR, TenantRole.MANAGER);
		employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Employee " + employeeId + " not found"));
		return salaryContractRepository.findByEmployeeIdAndCompanyIdOrderByEffectiveFromDesc(employeeId, context.companyId());
	}

	/**
	 * Used by {@link PayrollCalculationService} -- the effective contract
	 * for a given period end date, per business-rule extraction. RLS
	 * already scopes this to the caller's company via the active
	 * transaction's session variable, so no explicit companyId filter is
	 * needed here.
	 */
	@Transactional(readOnly = true)
	public SalaryContract findEffectiveContract(Long employeeId, LocalDate periodEnd) {
		return salaryContractRepository
				.findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeId, periodEnd)
				.orElseThrow(() -> new PayrollNotFoundException(
						"No salary contract effective on or before " + periodEnd + " for employee " + employeeId));
	}

	/**
	 * DAILY mode always zeroes basicSalary and the 4 contract allowances
	 * in favor of dailyWage -- V9's own migration comment and
	 * business-rule extraction, "Salary contracts have a daily-wage mode."
	 * Applied on both create and update so the invariant can never drift
	 * between the two entry points.
	 */
	private void applyFields(SalaryContract contract, ContractFields fields) {
		contract.setSalaryMode(fields.salaryMode());
		contract.setEffectiveFrom(fields.effectiveFrom());
		contract.setHousingAllowance(fields.housingAllowance());
		contract.setInsuranceDeduction(fields.insuranceDeduction());
		contract.setTaxDeduction(fields.taxDeduction());
		contract.setAdvancesDeduction(fields.advancesDeduction());
		contract.setFundDeduction(fields.fundDeduction());
		contract.setPenaltyDeduction(fields.penaltyDeduction());

		if (fields.salaryMode() == SalaryMode.DAILY) {
			contract.setBasicSalary(BigDecimal.ZERO);
			contract.setTransportAllowance(BigDecimal.ZERO);
			contract.setFoodAllowance(BigDecimal.ZERO);
			contract.setRiskAllowance(BigDecimal.ZERO);
			contract.setIncentives(BigDecimal.ZERO);
			contract.setDailyWage(fields.dailyWage());
		} else {
			contract.setBasicSalary(fields.basicSalary());
			contract.setTransportAllowance(fields.transportAllowance());
			contract.setFoodAllowance(fields.foodAllowance());
			contract.setRiskAllowance(fields.riskAllowance());
			contract.setIncentives(fields.incentives());
			contract.setDailyWage(null);
		}
	}

}
