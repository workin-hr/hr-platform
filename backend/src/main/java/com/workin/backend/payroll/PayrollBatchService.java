package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;

/**
 * See docs/migration/payroll-module-execution-plan.md's "PayrollBatch
 * And Payslip" section for the full rationale behind every fix here:
 * DB-level (company_id, month, year) uniqueness closes hr-legacy#21's
 * race condition (no app-level pre-check needed, the constraint does
 * the work); {@link #calculate} is fully @Transactional, closing
 * hr-legacy#22; every employee's payslip goes through
 * {@link PayrollCalculationService}, closing hr-legacy#12/#13.
 *
 * <p><strong>Known limitations, explicit, not silent</strong>:
 * <ul>
 *   <li>Period is always calendar-month (1st to last day) --
 *   per-company fiscal-period configuration is an open decision
 *   (docs/bootstrap/open-questions.md) not yet implementable (no
 *   {@code company_settings} module exists yet).</li>
 *   <li>Attendance figures are not available yet (attendance module
 *   not built) -- {@link #calculate} assumes full attendance (every
 *   calendar day present, zero absence, zero overtime) for every
 *   employee rather than fabricating a data source. Re-run
 *   {@code calculate} once attendance data is wired in.</li>
 *   <li>Finalize/reopen match penalties and advances to a batch by
 *   period/date rather than a stored per-batch ledger (penalties carry
 *   no batch_id in this schema; advances are matched by
 *   deductionPayrollYear/Month equality, the mechanism V12 was
 *   designed for). Reopen restores advance {@code remaining} by adding
 *   back up to {@code deductionAmountPerMonth}, capped at the original
 *   {@code amount} -- an approximation where the exact
 *   originally-deducted figure isn't separately stored.</li>
 * </ul>
 */
@Service
public class PayrollBatchService {

	private final PayrollBatchRepository payrollBatchRepository;
	private final PayslipRepository payslipRepository;
	private final EmployeeRepository employeeRepository;
	private final SalaryContractService salaryContractService;
	private final PenaltyRepository penaltyRepository;
	private final AdvanceRepository advanceRepository;
	private final PayrollCalculationService payrollCalculationService;

	public PayrollBatchService(
			PayrollBatchRepository payrollBatchRepository,
			PayslipRepository payslipRepository,
			EmployeeRepository employeeRepository,
			SalaryContractService salaryContractService,
			PenaltyRepository penaltyRepository,
			AdvanceRepository advanceRepository,
			PayrollCalculationService payrollCalculationService) {
		this.payrollBatchRepository = payrollBatchRepository;
		this.payslipRepository = payslipRepository;
		this.employeeRepository = employeeRepository;
		this.salaryContractService = salaryContractService;
		this.penaltyRepository = penaltyRepository;
		this.advanceRepository = advanceRepository;
		this.payrollCalculationService = payrollCalculationService;
	}

	@Transactional
	public PayrollBatch create(AuthorizationContext context, short month, short year) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		YearMonth ym = YearMonth.of(year, month);
		PayrollBatch batch = new PayrollBatch(
				context.companyId(), month, year, ym.atDay(1), ym.atEndOfMonth());
		// Uniqueness enforced by V10's real DB constraint -- no
		// app-level pre-check, see class Javadoc.
		return payrollBatchRepository.save(batch);
	}

	@Transactional
	public PayrollBatch calculate(AuthorizationContext context, Long batchId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		PayrollBatch batch = requireOwnedBatch(context, batchId);
		requireDraft(batch);

		payslipRepository.deleteByBatchId(batchId);

		int totalDaysInPeriod = batch.getPeriodTo().getDayOfMonth();
		List<Employee> activeEmployees = employeeRepository.findByCompanyIdAndActiveTrue(context.companyId());

		for (Employee employee : activeEmployees) {
			SalaryContract contract;
			try {
				contract = salaryContractService.findEffectiveContract(employee.getId(), batch.getPeriodTo());
			} catch (PayrollNotFoundException noContract) {
				// No effective contract for this employee yet -- skip
				// rather than fail the whole batch, matching legacy's
				// behavior of only paying employees who have a contract.
				continue;
			}

			PayrollCalculationService.AttendanceFigures attendance =
					new PayrollCalculationService.AttendanceFigures(totalDaysInPeriod, 0, 0, BigDecimal.ZERO);

			List<Penalty> unappliedPenalties = penaltyRepository.findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
					employee.getId(), batch.getPeriodFrom(), batch.getPeriodTo(), false);

			BigDecimal advanceDeduction = deductibleAdvanceAmount(employee.getId(), batch.getMonth(), batch.getYear());

			PayrollCalculationService.PayslipComputation computation =
					payrollCalculationService.compute(contract, attendance, unappliedPenalties, advanceDeduction);

			Payslip payslip = new Payslip(batchId, employee.getId(), context.companyId());
			applyComputation(payslip, attendance, computation);
			payslipRepository.save(payslip);
		}

		return batch;
	}

	@Transactional
	public PayrollBatch finalizeBatch(AuthorizationContext context, Long batchId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		PayrollBatch batch = requireOwnedBatch(context, batchId);
		requireDraft(batch);

		for (Payslip payslip : payslipRepository.findByBatchId(batchId)) {
			List<Penalty> unappliedPenalties = penaltyRepository.findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
					payslip.getEmployeeId(), batch.getPeriodFrom(), batch.getPeriodTo(), false);
			unappliedPenalties.forEach(p -> p.setAppliedToPayroll(true));
			penaltyRepository.saveAll(unappliedPenalties);

			for (Advance advance : deductibleAdvances(payslip.getEmployeeId(), batch.getMonth(), batch.getYear())) {
				BigDecimal deduction = advance.getDeductionAmountPerMonth() != null
						? advance.getDeductionAmountPerMonth()
						: BigDecimal.ZERO;
				BigDecimal actual = deduction.min(advance.getRemaining());
				advance.setRemaining(advance.getRemaining().subtract(actual));
				advanceRepository.save(advance);
			}
		}

		batch.setStatus(BatchStatus.FINALIZED);
		return payrollBatchRepository.save(batch);
	}

	@Transactional
	public PayrollBatch reopen(AuthorizationContext context, Long batchId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		PayrollBatch batch = requireOwnedBatch(context, batchId);
		if (batch.getStatus() != BatchStatus.FINALIZED) {
			throw new IllegalArgumentException("Batch " + batchId + " is not finalized");
		}

		for (Payslip payslip : payslipRepository.findByBatchId(batchId)) {
			List<Penalty> appliedPenalties = penaltyRepository.findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
					payslip.getEmployeeId(), batch.getPeriodFrom(), batch.getPeriodTo(), true);
			appliedPenalties.forEach(p -> p.setAppliedToPayroll(false));
			penaltyRepository.saveAll(appliedPenalties);

			for (Advance advance : deductibleAdvances(payslip.getEmployeeId(), batch.getMonth(), batch.getYear())) {
				BigDecimal deduction = advance.getDeductionAmountPerMonth() != null
						? advance.getDeductionAmountPerMonth()
						: BigDecimal.ZERO;
				BigDecimal restored = advance.getRemaining().add(deduction).min(advance.getAmount());
				advance.setRemaining(restored);
				advanceRepository.save(advance);
			}
		}

		batch.setStatus(BatchStatus.DRAFT);
		return payrollBatchRepository.save(batch);
	}

	@Transactional
	public void delete(AuthorizationContext context, Long batchId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		PayrollBatch batch = requireOwnedBatch(context, batchId);
		requireDraft(batch);
		payrollBatchRepository.delete(batch);
	}

	@Transactional
	public PayrollBatch updatePeriod(AuthorizationContext context, Long batchId, LocalDate periodFrom, LocalDate periodTo) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		PayrollBatch batch = requireOwnedBatch(context, batchId);
		requireDraft(batch);
		batch.setPeriodFrom(periodFrom);
		batch.setPeriodTo(periodTo);
		return payrollBatchRepository.save(batch);
	}

	@Transactional(readOnly = true)
	public PayrollBatch findOne(AuthorizationContext context, Long batchId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		return requireOwnedBatch(context, batchId);
	}

	@Transactional(readOnly = true)
	public List<PayrollBatch> list(AuthorizationContext context) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		return payrollBatchRepository.findByCompanyId(context.companyId());
	}

	private BigDecimal deductibleAdvanceAmount(Long employeeId, short month, short year) {
		return deductibleAdvances(employeeId, month, year).stream()
				.map(a -> {
					BigDecimal deduction = a.getDeductionAmountPerMonth() != null ? a.getDeductionAmountPerMonth() : BigDecimal.ZERO;
					return deduction.min(a.getRemaining());
				})
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private List<Advance> deductibleAdvances(Long employeeId, short month, short year) {
		return advanceRepository.findByEmployeeIdAndStatusAndDeductionPayrollYearAndDeductionPayrollMonth(
				employeeId, AdvanceStatus.APPROVED, year, month);
	}

	private void applyComputation(
			Payslip payslip, PayrollCalculationService.AttendanceFigures attendance,
			PayrollCalculationService.PayslipComputation computation) {
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

	private PayrollBatch requireOwnedBatch(AuthorizationContext context, Long batchId) {
		return payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Payroll batch " + batchId + " not found"));
	}

	private void requireDraft(PayrollBatch batch) {
		if (batch.getStatus() != BatchStatus.DRAFT) {
			throw new IllegalArgumentException("Payroll batch " + batch.getId() + " is not a draft");
		}
	}

}
