package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Salary contracts application service, module template (see
 * PenaltyService). Permission gating lives on the controller's
 * {@code @RequiresPermission} annotations, not here -- this service
 * only applies tenant scope and the DAILY-mode zeroing invariant
 * (V9's migration comment; business-rule-extraction.md, "Salary
 * contracts have a daily-wage mode").
 */
@Service
public class SalaryContractService {

	private final SalaryContractRepository salaryContractRepository;
	private final EmployeeRepository employeeRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public SalaryContractService(
			SalaryContractRepository salaryContractRepository,
			EmployeeRepository employeeRepository,
			TenantSessionVariable tenantSessionVariable) {
		this.salaryContractRepository = salaryContractRepository;
		this.employeeRepository = employeeRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional
	public List<SalaryContractView> listForEmployee(AuthorizationContext context, Long employeeId) {
		tenantSessionVariable.apply(context.companyId());
		return salaryContractRepository.findByEmployeeIdAndCompanyIdOrderByEffectiveFromDesc(employeeId, context.companyId())
				.stream()
				.map(SalaryContractView::of)
				.toList();
	}

	@Transactional
	public Optional<SalaryContractView> get(AuthorizationContext context, Long contractId) {
		tenantSessionVariable.apply(context.companyId());
		return salaryContractRepository.findByIdAndCompanyId(contractId, context.companyId())
				.map(SalaryContractView::of);
	}

	@Transactional
	public Optional<SalaryContractView> create(AuthorizationContext context, Long employeeId, UpsertSalaryContractRequest request) {
		tenantSessionVariable.apply(context.companyId());
		return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.map(employee -> {
					SalaryContract contract = new SalaryContract(employee.getId(), context.companyId(), request.effectiveFrom());
					applyFields(contract, request);
					return SalaryContractView.of(salaryContractRepository.save(contract));
				});
	}

	@Transactional
	public Optional<SalaryContractView> update(AuthorizationContext context, Long contractId, UpsertSalaryContractRequest request) {
		tenantSessionVariable.apply(context.companyId());
		return salaryContractRepository.findByIdAndCompanyId(contractId, context.companyId())
				.map(contract -> {
					applyFields(contract, request);
					return SalaryContractView.of(contract);
				});
	}

	@Transactional
	public boolean delete(AuthorizationContext context, Long contractId) {
		tenantSessionVariable.apply(context.companyId());
		return salaryContractRepository.findByIdAndCompanyId(contractId, context.companyId())
				.map(contract -> {
					salaryContractRepository.delete(contract);
					return true;
				})
				.orElse(false);
	}

	/**
	 * Used by {@link PayrollCalculationService} callers -- the effective
	 * contract for a given period end date (business-rule extraction).
	 * Callers must have already applied tenant scope in the same
	 * transaction (RLS then confines this lookup to that company).
	 */
	@Transactional(readOnly = true)
	public Optional<SalaryContract> findEffectiveContract(Long employeeId, LocalDate periodEnd) {
		return salaryContractRepository
				.findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeId, periodEnd);
	}

	private void applyFields(SalaryContract contract, UpsertSalaryContractRequest request) {
		contract.setSalaryMode(request.salaryMode());
		contract.setEffectiveFrom(request.effectiveFrom());
		contract.setHousingAllowance(zeroIfNull(request.housingAllowance()));
		contract.setInsuranceDeduction(zeroIfNull(request.insuranceDeduction()));
		contract.setTaxDeduction(zeroIfNull(request.taxDeduction()));
		contract.setAdvancesDeduction(zeroIfNull(request.advancesDeduction()));
		contract.setFundDeduction(zeroIfNull(request.fundDeduction()));
		contract.setPenaltyDeduction(zeroIfNull(request.penaltyDeduction()));

		if (request.salaryMode() == SalaryMode.DAILY) {
			contract.setBasicSalary(BigDecimal.ZERO);
			contract.setTransportAllowance(BigDecimal.ZERO);
			contract.setFoodAllowance(BigDecimal.ZERO);
			contract.setRiskAllowance(BigDecimal.ZERO);
			contract.setIncentives(BigDecimal.ZERO);
			contract.setDailyWage(request.dailyWage());
		} else {
			contract.setBasicSalary(zeroIfNull(request.basicSalary()));
			contract.setTransportAllowance(zeroIfNull(request.transportAllowance()));
			contract.setFoodAllowance(zeroIfNull(request.foodAllowance()));
			contract.setRiskAllowance(zeroIfNull(request.riskAllowance()));
			contract.setIncentives(zeroIfNull(request.incentives()));
			contract.setDailyWage(null);
		}
	}

	private static BigDecimal zeroIfNull(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

}
