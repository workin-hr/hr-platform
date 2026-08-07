package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.AuthorizationContextResolver;

/**
 * Class-level {@code @Transactional} -- see {@code AdvanceController}'s
 * Javadoc for why resolving the tenant context and the subsequent
 * service call must share one transaction.
 */
@RestController
@Transactional
public class SalaryContractController {

	public record UpsertSalaryContractRequest(
			@NotNull SalaryMode salaryMode,
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
			@NotNull LocalDate effectiveFrom) {

		SalaryContractService.ContractFields toFields() {
			return new SalaryContractService.ContractFields(
					salaryMode,
					zeroIfNull(basicSalary),
					dailyWage,
					zeroIfNull(housingAllowance),
					zeroIfNull(transportAllowance),
					zeroIfNull(foodAllowance),
					zeroIfNull(riskAllowance),
					zeroIfNull(incentives),
					zeroIfNull(insuranceDeduction),
					zeroIfNull(taxDeduction),
					zeroIfNull(advancesDeduction),
					zeroIfNull(fundDeduction),
					zeroIfNull(penaltyDeduction),
					effectiveFrom);
		}

		private static BigDecimal zeroIfNull(BigDecimal value) {
			return value == null ? BigDecimal.ZERO : value;
		}
	}

	public record SalaryContractResponse(
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

		static SalaryContractResponse from(SalaryContract c) {
			return new SalaryContractResponse(
					c.getId(), c.getEmployeeId(), c.getSalaryMode(), c.getBasicSalary(), c.getDailyWage(),
					c.getHousingAllowance(), c.getTransportAllowance(), c.getFoodAllowance(), c.getRiskAllowance(),
					c.getIncentives(), c.getInsuranceDeduction(), c.getTaxDeduction(), c.getAdvancesDeduction(),
					c.getFundDeduction(), c.getPenaltyDeduction(), c.getEffectiveFrom());
		}
	}

	private final SalaryContractService salaryContractService;
	private final AuthorizationContextResolver authorizationContextResolver;

	public SalaryContractController(
			SalaryContractService salaryContractService, AuthorizationContextResolver authorizationContextResolver) {
		this.salaryContractService = salaryContractService;
		this.authorizationContextResolver = authorizationContextResolver;
	}

	@PostMapping("/api/payroll/salary-contracts")
	public ResponseEntity<SalaryContractResponse> create(
			@RequestParam Long employeeId, @Valid @RequestBody UpsertSalaryContractRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		SalaryContract created = salaryContractService.create(context, employeeId, request.toFields());
		return ResponseEntity.status(HttpStatus.CREATED).body(SalaryContractResponse.from(created));
	}

	@PutMapping("/api/payroll/salary-contracts/{id}")
	public SalaryContractResponse update(@PathVariable Long id, @Valid @RequestBody UpsertSalaryContractRequest request) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return SalaryContractResponse.from(salaryContractService.update(context, id, request.toFields()));
	}

	@DeleteMapping("/api/payroll/salary-contracts/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		salaryContractService.delete(context, id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/payroll/salary-contracts/{id}")
	public SalaryContractResponse one(@PathVariable Long id) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return SalaryContractResponse.from(salaryContractService.findOne(context, id));
	}

	@GetMapping("/api/payroll/salary-contracts")
	public List<SalaryContractResponse> list(@RequestParam Long employeeId) {
		AuthorizationContext context = authorizationContextResolver.resolve();
		return salaryContractService.listForEmployee(context, employeeId).stream()
				.map(SalaryContractResponse::from)
				.toList();
	}

}
