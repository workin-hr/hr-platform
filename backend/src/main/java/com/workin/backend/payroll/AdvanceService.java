package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;

/**
 * Direct regression fix for hr-legacy#5: legacy's approve/reject/pay/
 * create/delete on this exact module were missing tenant-isolation
 * checks (docs/security/threat-model.md). Every method here resolves
 * through {@link AdvanceRepository#findByIdAndCompanyId} or a
 * company-scoped employee lookup -- never a raw {@code findById} --
 * per the structural-fix rule in
 * docs/migration/payroll-module-execution-plan.md.
 */
@Service
public class AdvanceService {

	public record CreateFields(
			Long employeeId, // ignored for EMPLOYEE-role callers; required for COMPANY_ADMIN/HR
			BigDecimal amount,
			String reason,
			DeductionMode deductionMode,
			int deductionMonthCount,
			BigDecimal deductionAmountPerMonth,
			Short deductionPayrollYear,
			Short deductionPayrollMonth,
			AdvanceStatus status // only honored for COMPANY_ADMIN/HR; forced PENDING for EMPLOYEE
	) {
	}

	public record UpdateFields(
			BigDecimal amount,
			String reason,
			DeductionMode deductionMode,
			int deductionMonthCount,
			BigDecimal deductionAmountPerMonth,
			Short deductionPayrollYear,
			Short deductionPayrollMonth,
			AdvanceStatus status // ignored for EMPLOYEE callers
	) {
	}

	private final AdvanceRepository advanceRepository;
	private final EmployeeRepository employeeRepository;

	public AdvanceService(AdvanceRepository advanceRepository, EmployeeRepository employeeRepository) {
		this.advanceRepository = advanceRepository;
		this.employeeRepository = employeeRepository;
	}

	@Transactional
	public Advance create(AuthorizationContext context, CreateFields fields) {
		RoleGuard.requireAnyRole(context, TenantRole.EMPLOYEE, TenantRole.COMPANY_ADMIN, TenantRole.HR);

		boolean isSelfService = isEmployeeOnly(context);
		Long employeeId;
		AdvanceStatus initialStatus;
		if (isSelfService) {
			employeeId = resolveOwnEmployee(context).getId();
			initialStatus = AdvanceStatus.PENDING;
		} else {
			if (fields.employeeId() == null) {
				throw new PayrollNotFoundException("employeeId is required");
			}
			// Direct fix for hr-legacy#5's create.php gap: never trust a
			// company/HR-supplied employeeId without re-resolving it
			// through a company-scoped lookup first.
			employeeId = employeeRepository.findByIdAndCompanyId(fields.employeeId(), context.companyId())
					.orElseThrow(() -> new PayrollNotFoundException("Employee " + fields.employeeId() + " not found"))
					.getId();
			// fields.status() is a real AdvanceStatus enum, so an invalid
			// value is already rejected at JSON deserialization -- this
			// closes hr-legacy#5's second finding (unvalidated status on
			// create) by construction, not by an extra runtime check.
			initialStatus = fields.status() != null ? fields.status() : AdvanceStatus.PENDING;
		}

		Advance advance = new Advance(employeeId, context.companyId(), fields.amount(), LocalDate.now());
		advance.setReason(fields.reason());
		advance.setDeductionMode(fields.deductionMode() != null ? fields.deductionMode() : DeductionMode.SINGLE_PAYROLL_MONTH);
		advance.setDeductionMonthCount(fields.deductionMonthCount());
		advance.setDeductionAmountPerMonth(fields.deductionAmountPerMonth());
		advance.setDeductionPayrollYear(fields.deductionPayrollYear());
		advance.setDeductionPayrollMonth(fields.deductionPayrollMonth());
		advance.setStatus(initialStatus);
		return advanceRepository.save(advance);
	}

	@Transactional
	public Advance approve(AuthorizationContext context, Long advanceId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Advance advance = requireOwnedAdvance(context, advanceId);
		advance.setStatus(AdvanceStatus.APPROVED);
		return advanceRepository.save(advance);
	}

	@Transactional
	public Advance reject(AuthorizationContext context, Long advanceId, String rejectionReason) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Advance advance = requireOwnedAdvance(context, advanceId);
		advance.setStatus(AdvanceStatus.REJECTED);
		advance.setRejectionReason(rejectionReason);
		return advanceRepository.save(advance);
	}

	@Transactional
	public Advance pay(AuthorizationContext context, Long advanceId, BigDecimal paymentAmount) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Advance advance = requireOwnedAdvance(context, advanceId);
		BigDecimal newRemaining = advance.getRemaining().subtract(paymentAmount);
		if (newRemaining.compareTo(BigDecimal.ZERO) < 0) {
			throw new IllegalArgumentException("Payment of " + paymentAmount + " exceeds remaining balance " + advance.getRemaining());
		}
		advance.setRemaining(newRemaining);
		return advanceRepository.save(advance);
	}

	@Transactional
	public void delete(AuthorizationContext context, Long advanceId) {
		RoleGuard.requireAnyRole(context, TenantRole.EMPLOYEE, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Advance advance = requireOwnedAdvance(context, advanceId);
		if (isEmployeeOnly(context)) {
			Employee self = resolveOwnEmployee(context);
			if (!advance.getEmployeeId().equals(self.getId())) {
				// Same shape as any other cross-tenant miss: hide
				// existence rather than distinguish "not yours" from
				// "doesn't exist."
				throw new PayrollNotFoundException("Advance " + advanceId + " not found");
			}
		}
		if (advance.getStatus() != AdvanceStatus.PENDING) {
			throw new IllegalArgumentException("Only PENDING advances can be deleted");
		}
		advanceRepository.delete(advance);
	}

	@Transactional
	public Advance update(AuthorizationContext context, Long advanceId, UpdateFields fields) {
		RoleGuard.requireAnyRole(context, TenantRole.EMPLOYEE, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Advance advance = requireOwnedAdvance(context, advanceId);

		if (isEmployeeOnly(context)) {
			Employee self = resolveOwnEmployee(context);
			if (!advance.getEmployeeId().equals(self.getId())) {
				throw new PayrollNotFoundException("Advance " + advanceId + " not found");
			}
			if (advance.getStatus() != AdvanceStatus.PENDING) {
				throw new IllegalArgumentException("Only PENDING advances can be edited by the requesting employee");
			}
			// Employees may edit only amount/reason on their own pending
			// advance -- everything else on the request is ignored, not
			// silently accepted.
			if (fields.amount() != null) {
				advance.setAmount(fields.amount());
				advance.setRemaining(fields.amount());
			}
			advance.setReason(fields.reason());
			return advanceRepository.save(advance);
		}

		if (fields.amount() != null) {
			advance.setAmount(fields.amount());
		}
		advance.setReason(fields.reason());
		if (fields.deductionMode() != null) {
			advance.setDeductionMode(fields.deductionMode());
		}
		advance.setDeductionMonthCount(fields.deductionMonthCount());
		advance.setDeductionAmountPerMonth(fields.deductionAmountPerMonth());
		advance.setDeductionPayrollYear(fields.deductionPayrollYear());
		advance.setDeductionPayrollMonth(fields.deductionPayrollMonth());
		if (fields.status() != null) {
			advance.setStatus(fields.status());
		}
		return advanceRepository.save(advance);
	}

	@Transactional(readOnly = true)
	public Advance findOne(AuthorizationContext context, Long advanceId) {
		RoleGuard.requireAnyRole(context, TenantRole.EMPLOYEE, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Advance advance = requireOwnedAdvance(context, advanceId);
		if (isEmployeeOnly(context)) {
			Employee self = resolveOwnEmployee(context);
			if (!advance.getEmployeeId().equals(self.getId())) {
				throw new PayrollNotFoundException("Advance " + advanceId + " not found");
			}
		}
		return advance;
	}

	@Transactional(readOnly = true)
	public List<Advance> list(AuthorizationContext context) {
		RoleGuard.requireAnyRole(context, TenantRole.EMPLOYEE, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		if (isEmployeeOnly(context)) {
			Employee self = resolveOwnEmployee(context);
			return advanceRepository.findByCompanyIdAndEmployeeId(context.companyId(), self.getId());
		}
		return advanceRepository.findByCompanyId(context.companyId());
	}

	private Advance requireOwnedAdvance(AuthorizationContext context, Long advanceId) {
		return advanceRepository.findByIdAndCompanyId(advanceId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Advance " + advanceId + " not found"));
	}

	private Employee resolveOwnEmployee(AuthorizationContext context) {
		return employeeRepository.findByIdentityIdAndCompanyId(context.identityId(), context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("No employee record linked to this identity in this company"));
	}

	private boolean isEmployeeOnly(AuthorizationContext context) {
		return context.roles().contains(TenantRole.EMPLOYEE)
				&& !context.roles().contains(TenantRole.COMPANY_ADMIN)
				&& !context.roles().contains(TenantRole.HR);
	}

}
