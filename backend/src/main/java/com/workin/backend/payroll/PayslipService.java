package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;

/**
 * Manual "add/edit one payslip in a draft batch" -- the exact
 * hr-legacy#12 defect site (legacy's payslips/create.php bypassed all
 * shared payroll math and silently dropped base pay for daily-wage
 * employees). Both {@link #create} and {@link #update} here call
 * {@link PayrollCalculationService} exactly like
 * {@link PayrollBatchService#calculate} does -- there is no separate
 * formula in this class, closing that defect by construction.
 *
 * <p>Unlike advances/penalties, MANAGER gets the same company-wide
 * scope as COMPANY_ADMIN/HR here (not branch-limited) --
 * docs/api/existing-endpoint-inventory.md's Payslips section documents
 * no manager branch-scoping for this specific module, unlike
 * penalties.
 */
@Service
public class PayslipService {

	public record AttendanceInput(int daysPresent, int daysAbsent, int daysLeave, BigDecimal overtimeHours) {
	}

	private final PayslipRepository payslipRepository;
	private final PayrollBatchRepository payrollBatchRepository;
	private final EmployeeRepository employeeRepository;
	private final SalaryContractService salaryContractService;
	private final PenaltyRepository penaltyRepository;
	private final AdvanceRepository advanceRepository;
	private final PayrollCalculationService payrollCalculationService;

	public PayslipService(
			PayslipRepository payslipRepository,
			PayrollBatchRepository payrollBatchRepository,
			EmployeeRepository employeeRepository,
			SalaryContractService salaryContractService,
			PenaltyRepository penaltyRepository,
			AdvanceRepository advanceRepository,
			PayrollCalculationService payrollCalculationService) {
		this.payslipRepository = payslipRepository;
		this.payrollBatchRepository = payrollBatchRepository;
		this.employeeRepository = employeeRepository;
		this.salaryContractService = salaryContractService;
		this.penaltyRepository = penaltyRepository;
		this.advanceRepository = advanceRepository;
		this.payrollCalculationService = payrollCalculationService;
	}

	@Transactional
	public Payslip create(AuthorizationContext context, Long batchId, Long employeeId, AttendanceInput attendanceInput) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		PayrollBatch batch = payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Payroll batch " + batchId + " not found"));
		requireDraft(batch);
		Employee employee = employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Employee " + employeeId + " not found"));

		SalaryContract contract = salaryContractService.findEffectiveContract(employee.getId(), batch.getPeriodTo());
		Payslip payslip = new Payslip(batchId, employee.getId(), context.companyId());
		recompute(payslip, batch, contract, attendanceInput);
		// (batch_id, employee_id) uniqueness is real (V11) -- a
		// duplicate becomes a clean 409 via PayrollExceptionHandler, no
		// app-level pre-check needed.
		return payslipRepository.save(payslip);
	}

	@Transactional
	public Payslip update(AuthorizationContext context, Long payslipId, AttendanceInput attendanceInput) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Payslip payslip = payslipRepository.findByIdAndCompanyId(payslipId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Payslip " + payslipId + " not found"));
		PayrollBatch batch = payrollBatchRepository.findByIdAndCompanyId(payslip.getBatchId(), context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Payroll batch " + payslip.getBatchId() + " not found"));
		requireDraft(batch);
		SalaryContract contract = salaryContractService.findEffectiveContract(payslip.getEmployeeId(), batch.getPeriodTo());
		recompute(payslip, batch, contract, attendanceInput);
		return payslipRepository.save(payslip);
	}

	@Transactional
	public void delete(AuthorizationContext context, Long payslipId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Payslip payslip = payslipRepository.findByIdAndCompanyId(payslipId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Payslip " + payslipId + " not found"));
		PayrollBatch batch = payrollBatchRepository.findByIdAndCompanyId(payslip.getBatchId(), context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Payroll batch " + payslip.getBatchId() + " not found"));
		requireDraft(batch);
		payslipRepository.delete(payslip);
	}

	@Transactional(readOnly = true)
	public Payslip findOne(AuthorizationContext context, Long payslipId) {
		RoleGuard.requireAnyRole(context, TenantRole.EMPLOYEE, TenantRole.COMPANY_ADMIN, TenantRole.HR, TenantRole.MANAGER);
		Payslip payslip = payslipRepository.findByIdAndCompanyId(payslipId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Payslip " + payslipId + " not found"));
		if (isEmployeeOnly(context)) {
			Employee self = resolveOwnEmployee(context);
			if (!payslip.getEmployeeId().equals(self.getId())) {
				throw new PayrollNotFoundException("Payslip " + payslipId + " not found");
			}
		}
		return payslip;
	}

	@Transactional(readOnly = true)
	public List<Payslip> list(AuthorizationContext context) {
		RoleGuard.requireAnyRole(context, TenantRole.EMPLOYEE, TenantRole.COMPANY_ADMIN, TenantRole.HR, TenantRole.MANAGER);
		if (isEmployeeOnly(context)) {
			Employee self = resolveOwnEmployee(context);
			return payslipRepository.findByCompanyIdAndEmployeeId(context.companyId(), self.getId());
		}
		return payslipRepository.findByCompanyId(context.companyId());
	}

	private void recompute(Payslip payslip, PayrollBatch batch, SalaryContract contract, AttendanceInput attendanceInput) {
		PayrollCalculationService.AttendanceFigures attendance = new PayrollCalculationService.AttendanceFigures(
				attendanceInput.daysPresent(), attendanceInput.daysAbsent(), attendanceInput.daysLeave(),
				attendanceInput.overtimeHours() != null ? attendanceInput.overtimeHours() : BigDecimal.ZERO);

		List<Penalty> unappliedPenalties = penaltyRepository.findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
				payslip.getEmployeeId(), batch.getPeriodFrom(), batch.getPeriodTo(), false);

		BigDecimal advanceDeduction = advanceRepository
				.findByEmployeeIdAndStatusAndDeductionPayrollYearAndDeductionPayrollMonth(
						payslip.getEmployeeId(), AdvanceStatus.APPROVED, batch.getYear(), batch.getMonth())
				.stream()
				.map(a -> {
					BigDecimal deduction = a.getDeductionAmountPerMonth() != null ? a.getDeductionAmountPerMonth() : BigDecimal.ZERO;
					return deduction.min(a.getRemaining());
				})
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		PayrollCalculationService.PayslipComputation computation =
				payrollCalculationService.compute(contract, attendance, unappliedPenalties, advanceDeduction);

		payslip.setDaysPresent((short) attendance.daysPresent());
		payslip.setDaysAbsent((short) attendance.daysAbsent());
		payslip.setDaysLeave((short) attendance.daysLeave());
		payslip.setOvertimeHours(attendance.overtimeHours());
		payslip.setBasicSalary(computation.basicSalary());
		payslip.setHousingAllowance(computation.allowances());
		payslip.setOvertimePay(computation.overtimePay());
		payslip.setFoodAllowance(computation.foodAllowance());
		payslip.setRiskAllowance(computation.riskAllowance());
		payslip.setTransportAllowance(computation.transportAllowance());
		payslip.setIncentives(computation.incentives());
		payslip.setPenaltiesTotal(computation.penaltiesTotal());
		payslip.setAdvanceDeduction(computation.advanceDeduction());
		payslip.setOtherDeductions(computation.otherDeductions());
		payslip.setGrossSalary(computation.grossSalary());
		payslip.setTotalEntitlements(computation.totalEntitlements());
		payslip.setTotalDeductions(computation.totalDeductions());
		payslip.setNetSalary(computation.netSalary());
	}

	private void requireDraft(PayrollBatch batch) {
		if (batch.getStatus() != BatchStatus.DRAFT) {
			throw new IllegalArgumentException("Payroll batch " + batch.getId() + " is not a draft");
		}
	}

	private Employee resolveOwnEmployee(AuthorizationContext context) {
		return employeeRepository.findByIdentityIdAndCompanyId(context.identityId(), context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("No employee record linked to this identity in this company"));
	}

	private boolean isEmployeeOnly(AuthorizationContext context) {
		return context.roles().contains(TenantRole.EMPLOYEE)
				&& !context.roles().contains(TenantRole.COMPANY_ADMIN)
				&& !context.roles().contains(TenantRole.HR)
				&& !context.roles().contains(TenantRole.MANAGER);
	}

}
