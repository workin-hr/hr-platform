package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.authorization.ResourceScopeService;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.penalties.Penalty;
import com.workin.backend.penalties.PenaltyRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Manual "add/edit one payslip in a draft batch" -- the exact
 * hr-legacy#12 defect site (legacy's payslips/create.php bypassed all
 * shared payroll math and silently dropped base pay for daily-wage
 * employees). Both {@link #create} and {@link #update} call
 * {@link PayrollCalculationService} exactly like
 * {@link PayrollBatchService#calculate} does -- no separate formula
 * exists in this class, closing that defect by construction.
 *
 * <p>Admin/HR-only for this slice, matching the employees/advances/
 * penalties modules' own established pattern of deferring
 * EMPLOYEE-role self-service (e.g. "view my own payslips") to a later
 * slice -- consistent with the fact that {@code employees} carries no
 * identity link yet anywhere in this codebase.
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
	private final PayrollCalculationService payrollCalculationService;
	private final WorkHoursResolver workHoursResolver;
	private final TenantSessionVariable tenantSessionVariable;

	private final ResourceScopeService resourceScopeService;

	public PayslipService(
			PayslipRepository payslipRepository,
			PayrollBatchRepository payrollBatchRepository,
			EmployeeRepository employeeRepository,
			SalaryContractService salaryContractService,
			PenaltyRepository penaltyRepository,
			PayrollCalculationService payrollCalculationService,
			WorkHoursResolver workHoursResolver,
			ResourceScopeService resourceScopeService,
			TenantSessionVariable tenantSessionVariable) {
		this.payslipRepository = payslipRepository;
		this.payrollBatchRepository = payrollBatchRepository;
		this.employeeRepository = employeeRepository;
		this.salaryContractService = salaryContractService;
		this.penaltyRepository = penaltyRepository;
		this.payrollCalculationService = payrollCalculationService;
		this.workHoursResolver = workHoursResolver;
		this.resourceScopeService = resourceScopeService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional
	public MutationResult create(AuthorizationContext context, Long batchId, Long employeeId, AttendanceInput attendanceInput) {
		tenantSessionVariable.apply(context.companyId());
		Optional<PayrollBatch> batch = payrollBatchRepository.findByIdAndCompanyId(batchId, context.companyId());
		if (batch.isEmpty()) {
			return new MutationResult.NotFound();
		}
		if (batch.get().getStatus() != BatchStatus.DRAFT) {
			return new MutationResult.WrongState();
		}
		var employee = employeeRepository.findByIdAndCompanyId(employeeId, context.companyId());
		if (employee.isEmpty() || !resourceScopeService.isEmployeeInScope(context, employeeId)) {
			return new MutationResult.NotFound();
		}
		Optional<SalaryContract> contract = salaryContractService.findEffectiveContract(employee.get().getId(), batch.get().getPeriodTo());
		if (contract.isEmpty()) {
			return new MutationResult.NotFound();
		}

		Payslip payslip = new Payslip(batchId, employee.get().getId(), context.companyId());
		recompute(payslip, batch.get(), contract.get(), attendanceInput);
		try {
			// (batch_id, employee_id) uniqueness is real (V11) -- a
			// duplicate becomes a clean 409, no app-level pre-check.
			return new MutationResult.Done(PayslipView.of(payslipRepository.save(payslip)));
		} catch (DataIntegrityViolationException ex) {
			return new MutationResult.WrongState();
		}
	}

	@Transactional
	public MutationResult update(AuthorizationContext context, Long payslipId, AttendanceInput attendanceInput) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Payslip> payslip = payslipRepository.findByIdAndCompanyId(payslipId, context.companyId());
		if (payslip.isEmpty() || !resourceScopeService.isEmployeeInScope(context, payslip.get().getEmployeeId())) {
			return new MutationResult.NotFound();
		}
		Optional<PayrollBatch> batch = payrollBatchRepository.findByIdAndCompanyId(payslip.get().getBatchId(), context.companyId());
		if (batch.isEmpty() || batch.get().getStatus() != BatchStatus.DRAFT) {
			return new MutationResult.WrongState();
		}
		Optional<SalaryContract> contract =
				salaryContractService.findEffectiveContract(payslip.get().getEmployeeId(), batch.get().getPeriodTo());
		if (contract.isEmpty()) {
			return new MutationResult.NotFound();
		}
		recompute(payslip.get(), batch.get(), contract.get(), attendanceInput);
		return new MutationResult.Done(PayslipView.of(payslip.get()));
	}

	@Transactional
	public MutationResult delete(AuthorizationContext context, Long payslipId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Payslip> payslip = payslipRepository.findByIdAndCompanyId(payslipId, context.companyId());
		if (payslip.isEmpty() || !resourceScopeService.isEmployeeInScope(context, payslip.get().getEmployeeId())) {
			return new MutationResult.NotFound();
		}
		Optional<PayrollBatch> batch = payrollBatchRepository.findByIdAndCompanyId(payslip.get().getBatchId(), context.companyId());
		if (batch.isEmpty() || batch.get().getStatus() != BatchStatus.DRAFT) {
			return new MutationResult.WrongState();
		}
		payslipRepository.delete(payslip.get());
		return new MutationResult.Done(null);
	}

	@Transactional(readOnly = true)
	public Optional<PayslipView> get(AuthorizationContext context, Long payslipId) {
		tenantSessionVariable.apply(context.companyId());
		return payslipRepository.findByIdAndCompanyId(payslipId, context.companyId())
				.filter(p -> resourceScopeService.isEmployeeInScope(context, p.getEmployeeId()))
				.map(PayslipView::of);
	}

	@Transactional(readOnly = true)
	public List<PayslipView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		Set<Long> reach = resourceScopeService.scopedEmployeeIdsOrNull(context);
		return payslipRepository.findByCompanyId(context.companyId()).stream()
				.filter(p -> reach == null || reach.contains(p.getEmployeeId()))
				.map(PayslipView::of).toList();
	}

	private void recompute(Payslip payslip, PayrollBatch batch, SalaryContract contract, AttendanceInput attendanceInput) {
		// The manual-entry surface supplies overtime directly rather than
		// deriving it, so total worked hours are synthesised as "the
		// expected hours for the entered present days, plus the entered
		// overtime". The computation then re-derives exactly the overtime
		// that was typed in, and HR's override survives the port.
		BigDecimal workHoursPerDay = workHoursResolver.forEmployee(payslip.getCompanyId(), payslip.getEmployeeId());
		BigDecimal enteredOvertime =
				attendanceInput.overtimeHours() != null ? attendanceInput.overtimeHours() : BigDecimal.ZERO;
		BigDecimal totalWorkedHours = workHoursPerDay
				.multiply(BigDecimal.valueOf(attendanceInput.daysPresent()))
				.add(enteredOvertime);
		PayrollCalculationService.AttendanceFigures attendance = new PayrollCalculationService.AttendanceFigures(
				attendanceInput.daysPresent(), totalWorkedHours,
				attendanceInput.daysAbsent(), attendanceInput.daysLeave(),
				0, 0, workHoursPerDay);

		List<Penalty> unappliedPenalties = penaltyRepository.findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
				payslip.getEmployeeId(), batch.getPeriodFrom(), batch.getPeriodTo(), false);

		PayrollCalculationService.PayslipComputation computation =
				payrollCalculationService.compute(contract, attendance, unappliedPenalties, BigDecimal.ZERO,
						PayrollCalculationService.PeriodProgress.complete(),
						PayrollCalculationService.OvertimePolicy.standard());

		PayrollBatchService.applyComputation(payslip, attendance, computation);
	}

	public sealed interface MutationResult {

		record NotFound() implements MutationResult {
		}

		/** Batch is not a draft, or a duplicate (batch_id, employee_id) payslip already exists. */
		record WrongState() implements MutationResult {
		}

		record Done(PayslipView view) implements MutationResult {
		}

	}

}
