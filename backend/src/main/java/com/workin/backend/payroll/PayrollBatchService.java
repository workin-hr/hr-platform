package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.advances.Advance;
import com.workin.backend.advances.AdvanceRepository;
import com.workin.backend.advances.AdvanceStatus;
import com.workin.backend.companysettings.CompanySettingsService;
import com.workin.backend.companysettings.EffectiveCompanySettings;
import com.workin.backend.employees.Employee;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.penalties.Penalty;
import com.workin.backend.penalties.PenaltyRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * See docs/migration/payroll-module-execution-plan.md's "PayrollBatch
 * And Payslip" section for the full rationale: DB-level (company_id,
 * month, year) uniqueness closes hr-legacy#21's race condition (no
 * app-level pre-check -- a constraint violation is translated to a
 * clean 409 the same way RegistrationService does for duplicate
 * phones); {@link #calculate} is fully @Transactional, closing
 * hr-legacy#22; every employee's payslip goes through
 * {@link PayrollCalculationService}, closing hr-legacy#12/#13.
 *
 * <p><strong>Known, explicit limitations</strong>:
 * <ul>
 *   <li>Period is always calendar-month (1st to last day) --
 *   per-company fiscal-period configuration
 *   (docs/bootstrap/open-questions.md) isn't implementable yet (no
 *   {@code company_settings} module exists).</li>
 *   <li>Attendance figures aren't available yet (attendance module not
 *   built) -- {@link #calculate} assumes full attendance for every
 *   employee rather than fabricating a data source. Re-run once
 *   attendance data exists.</li>
 *   <li>Advance deduction at finalize is a documented v1 heuristic
 *   (oldest APPROVED advance first, see {@code AdvanceRepository}), not
 *   a port of a specific legacy scheduling rule -- V12's deduction_*
 *   scheduling columns remain an open product decision.</li>
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
	private final CompanySettingsService companySettingsService;
	private final TenantSessionVariable tenantSessionVariable;

	public PayrollBatchService(
			PayrollBatchRepository payrollBatchRepository,
			PayslipRepository payslipRepository,
			EmployeeRepository employeeRepository,
			SalaryContractService salaryContractService,
			PenaltyRepository penaltyRepository,
			AdvanceRepository advanceRepository,
			PayrollCalculationService payrollCalculationService,
			CompanySettingsService companySettingsService,
			TenantSessionVariable tenantSessionVariable) {
		this.payrollBatchRepository = payrollBatchRepository;
		this.payslipRepository = payslipRepository;
		this.employeeRepository = employeeRepository;
		this.salaryContractService = salaryContractService;
		this.penaltyRepository = penaltyRepository;
		this.advanceRepository = advanceRepository;
		this.payrollCalculationService = payrollCalculationService;
		this.companySettingsService = companySettingsService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional
	public PayrollBatchView create(AuthorizationContext context, short month, short year) {
		tenantSessionVariable.apply(context.companyId());
		// Legacy payroll_fiscal_period_bounds, ported exactly: start day
		// = setting or 1; end day = setting or the month's last day; each
		// bound capped to its own month's real last day; when start > end
		// the period spans from the previous month's start day (e.g. a
		// 26th -> 25th fiscal month). Unset settings reproduce the
		// calendar month exactly.
		EffectiveCompanySettings settings = companySettingsService.effective(context.companyId());
		YearMonth ym = YearMonth.of(year, month);
		int lastDom = ym.lengthOfMonth();
		int startDay = settings.monthStartDay();
		int endDay = settings.monthEndDay() != null ? settings.monthEndDay() : lastDom;
		LocalDate periodTo = ym.atDay(Math.min(endDay, lastDom));
		LocalDate periodFrom;
		if (startDay <= endDay) {
			periodFrom = ym.atDay(Math.min(startDay, lastDom));
		} else {
			YearMonth previous = ym.minusMonths(1);
			periodFrom = previous.atDay(Math.min(startDay, previous.lengthOfMonth()));
		}
		PayrollBatch batch = new PayrollBatch(context.companyId(), month, year, periodFrom, periodTo);
		try {
			return PayrollBatchView.of(payrollBatchRepository.save(batch));
		} catch (DataIntegrityViolationException ex) {
			// V10's real UNIQUE(company_id, month, year) constraint --
			// no app-level pre-check, see class Javadoc.
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A payroll batch for this month/year already exists", ex);
		}
	}

	@Transactional
	public MutationResult calculate(AuthorizationContext context, Long batchId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<PayrollBatch> found = payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		PayrollBatch batch = found.get();
		if (batch.getStatus() != BatchStatus.DRAFT) {
			return new MutationResult.WrongState();
		}

		payslipRepository.deleteByBatchId(batchId);

		// The period's real inclusive length. getPeriodTo().getDayOfMonth()
		// would only equal this for a calendar month starting on the 1st;
		// for a configured fiscal window (e.g. 26th -> 25th) it is wrong.
		int totalDaysInPeriod = (int) (ChronoUnit.DAYS.between(batch.getPeriodFrom(), batch.getPeriodTo()) + 1);
		List<Employee> activeEmployees = employeeRepository.findByCompanyIdOrderById(context.companyId())
				.stream().filter(Employee::isActive).toList();

		for (Employee employee : activeEmployees) {
			Optional<com.workin.backend.payroll.SalaryContract> contract =
					salaryContractService.findEffectiveContract(employee.getId(), batch.getPeriodTo());
			if (contract.isEmpty()) {
				// No effective contract yet -- skip rather than fail the
				// whole batch, matching legacy's behavior of only paying
				// employees who have a contract.
				continue;
			}

			PayrollCalculationService.AttendanceFigures attendance =
					new PayrollCalculationService.AttendanceFigures(totalDaysInPeriod, 0, 0, BigDecimal.ZERO);
			List<Penalty> unappliedPenalties = penaltyRepository.findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
					employee.getId(), batch.getPeriodFrom(), batch.getPeriodTo(), false);

			PayrollCalculationService.PayslipComputation computation = payrollCalculationService.compute(
					contract.get(), attendance, unappliedPenalties, BigDecimal.ZERO);

			Payslip payslip = new Payslip(batchId, employee.getId(), context.companyId());
			applyComputation(payslip, attendance, computation);
			payslipRepository.save(payslip);
		}

		return new MutationResult.Done(PayrollBatchView.of(batch));
	}

	@Transactional
	public MutationResult finalizeBatch(AuthorizationContext context, Long batchId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<PayrollBatch> found = payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		PayrollBatch batch = found.get();
		if (batch.getStatus() != BatchStatus.DRAFT) {
			return new MutationResult.WrongState();
		}

		for (Payslip payslip : payslipRepository.findByBatchId(batchId)) {
			List<Penalty> unappliedPenalties = penaltyRepository.findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
					payslip.getEmployeeId(), batch.getPeriodFrom(), batch.getPeriodTo(), false);
			unappliedPenalties.forEach(Penalty::markAppliedToPayroll);

			applyAdvanceDeduction(payslip.getEmployeeId(), payslip.getAdvanceDeduction());
		}

		batch.setStatus(BatchStatus.FINALIZED);
		return new MutationResult.Done(PayrollBatchView.of(batch));
	}

	@Transactional
	public MutationResult reopen(AuthorizationContext context, Long batchId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<PayrollBatch> found = payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		PayrollBatch batch = found.get();
		if (batch.getStatus() != BatchStatus.FINALIZED) {
			return new MutationResult.WrongState();
		}

		for (Payslip payslip : payslipRepository.findByBatchId(batchId)) {
			List<Penalty> appliedPenalties = penaltyRepository.findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
					payslip.getEmployeeId(), batch.getPeriodFrom(), batch.getPeriodTo(), true);
			appliedPenalties.forEach(Penalty::revertPayrollApplication);

			restoreAdvanceDeduction(payslip.getEmployeeId(), payslip.getAdvanceDeduction());
		}

		batch.setStatus(BatchStatus.DRAFT);
		return new MutationResult.Done(PayrollBatchView.of(batch));
	}

	@Transactional
	public MutationResult delete(AuthorizationContext context, Long batchId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<PayrollBatch> found = payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (found.get().getStatus() != BatchStatus.DRAFT) {
			return new MutationResult.WrongState();
		}
		payrollBatchRepository.delete(found.get());
		return new MutationResult.Done(null);
	}

	@Transactional
	public MutationResult updatePeriod(AuthorizationContext context, Long batchId, LocalDate periodFrom, LocalDate periodTo) {
		tenantSessionVariable.apply(context.companyId());
		Optional<PayrollBatch> found = payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId());
		if (found.isEmpty()) {
			return new MutationResult.NotFound();
		}
		PayrollBatch batch = found.get();
		if (batch.getStatus() != BatchStatus.DRAFT) {
			return new MutationResult.WrongState();
		}
		batch.setPeriodFrom(periodFrom);
		batch.setPeriodTo(periodTo);
		return new MutationResult.Done(PayrollBatchView.of(batch));
	}

	@Transactional(readOnly = true)
	public Optional<PayrollBatchView> get(AuthorizationContext context, Long batchId) {
		tenantSessionVariable.apply(context.companyId());
		return payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId()).map(PayrollBatchView::of);
	}

	@Transactional(readOnly = true)
	public List<PayrollBatchView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return payrollBatchRepository.findByCompanyId(context.companyId()).stream().map(PayrollBatchView::of).toList();
	}

	/** See class Javadoc's advance-deduction limitation. */
	private void applyAdvanceDeduction(Long employeeId, BigDecimal amountToDeduct) {
		BigDecimal remainingToDeduct = amountToDeduct;
		for (Advance advance : advanceRepository.findByEmployeeIdAndStatusOrderByRequestDateAsc(employeeId, AdvanceStatus.APPROVED)) {
			if (remainingToDeduct.compareTo(BigDecimal.ZERO) <= 0) {
				break;
			}
			BigDecimal applied = remainingToDeduct.min(advance.getRemaining());
			advance.deduct(applied);
			remainingToDeduct = remainingToDeduct.subtract(applied);
		}
	}

	private void restoreAdvanceDeduction(Long employeeId, BigDecimal amountToRestore) {
		BigDecimal remainingToRestore = amountToRestore;
		for (Advance advance : advanceRepository.findByEmployeeIdAndStatusOrderByRequestDateAsc(employeeId, AdvanceStatus.APPROVED)) {
			if (remainingToRestore.compareTo(BigDecimal.ZERO) <= 0) {
				break;
			}
			BigDecimal alreadyDeducted = advance.getAmount().subtract(advance.getRemaining());
			BigDecimal applied = remainingToRestore.min(alreadyDeducted);
			advance.restore(applied);
			remainingToRestore = remainingToRestore.subtract(applied);
		}
	}

	static void applyComputation(
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

	public sealed interface MutationResult {

		record NotFound() implements MutationResult {
		}

		/** Draft-only mutation attempted on a non-draft batch, or vice versa. */
		record WrongState() implements MutationResult {
		}

		record Done(PayrollBatchView view) implements MutationResult {
		}

	}

}
