package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantRole;

/**
 * Legacy's penalties module was the one confirmed-correct tenant-scoping
 * model in the payroll group (docs/api/existing-endpoint-inventory.md)
 * -- this service reproduces that discipline, not a defect list to fix.
 *
 * <p><strong>MANAGER branch-scoping is deliberately not implemented
 * yet.</strong> Legacy scopes MANAGER to their own branch via
 * {@code branches}, which does not exist in this schema yet (lowest
 * priority group per docs/migration/migration-strategy-and-sequencing.md).
 * Per the open decision recorded in docs/bootstrap/open-questions.md
 * ("Payroll Migration"), this implementation excludes MANAGER from all
 * penalty endpoints for now rather than silently widening it to
 * company-wide access -- revisit once branches exists (tracked by F-25).
 */
@Service
public class PenaltyService {

	public record PenaltyFields(String penaltyType, BigDecimal penaltyDays, String reason, LocalDate penaltyDate) {
	}

	private final PenaltyRepository penaltyRepository;
	private final EmployeeRepository employeeRepository;

	public PenaltyService(PenaltyRepository penaltyRepository, EmployeeRepository employeeRepository) {
		this.penaltyRepository = penaltyRepository;
		this.employeeRepository = employeeRepository;
	}

	@Transactional
	public Penalty create(AuthorizationContext context, Long employeeId, PenaltyFields fields) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Employee " + employeeId + " not found"));
		Penalty penalty = new Penalty(employeeId, context.companyId(), fields.penaltyType(), fields.penaltyDays(), fields.penaltyDate());
		penalty.setReason(fields.reason());
		return penaltyRepository.save(penalty);
	}

	@Transactional
	public Penalty update(AuthorizationContext context, Long penaltyId, PenaltyFields fields) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Penalty penalty = requireOwnedPenalty(context, penaltyId);
		if (penalty.isAppliedToPayroll()) {
			throw new IllegalArgumentException("Penalty " + penaltyId + " has already been applied to payroll and cannot be edited");
		}
		penalty.setPenaltyType(fields.penaltyType());
		penalty.setPenaltyDays(fields.penaltyDays());
		penalty.setReason(fields.reason());
		penalty.setPenaltyDate(fields.penaltyDate());
		return penaltyRepository.save(penalty);
	}

	@Transactional
	public void delete(AuthorizationContext context, Long penaltyId) {
		RoleGuard.requireAnyRole(context, TenantRole.COMPANY_ADMIN, TenantRole.HR);
		Penalty penalty = requireOwnedPenalty(context, penaltyId);
		if (penalty.isAppliedToPayroll()) {
			throw new IllegalArgumentException("Penalty " + penaltyId + " has already been applied to payroll and cannot be deleted");
		}
		penaltyRepository.delete(penalty);
	}

	@Transactional(readOnly = true)
	public Penalty findOne(AuthorizationContext context, Long penaltyId) {
		requireCompanyOrEmployeeRole(context);
		Penalty penalty = requireOwnedPenalty(context, penaltyId);
		if (isEmployeeOnly(context)) {
			Employee self = resolveOwnEmployee(context);
			if (!penalty.getEmployeeId().equals(self.getId())) {
				throw new PayrollNotFoundException("Penalty " + penaltyId + " not found");
			}
		}
		return penalty;
	}

	@Transactional(readOnly = true)
	public List<Penalty> list(AuthorizationContext context) {
		requireCompanyOrEmployeeRole(context);
		if (isEmployeeOnly(context)) {
			Employee self = resolveOwnEmployee(context);
			return penaltyRepository.findByCompanyIdAndEmployeeId(context.companyId(), self.getId());
		}
		return penaltyRepository.findByCompanyId(context.companyId());
	}

	private Penalty requireOwnedPenalty(AuthorizationContext context, Long penaltyId) {
		return penaltyRepository.findByIdAndCompanyId(penaltyId, context.companyId())
				.orElseThrow(() -> new PayrollNotFoundException("Penalty " + penaltyId + " not found"));
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

	private void requireCompanyOrEmployeeRole(AuthorizationContext context) {
		// MANAGER excluded here deliberately -- see class Javadoc.
		RoleGuard.requireAnyRole(context, TenantRole.EMPLOYEE, TenantRole.COMPANY_ADMIN, TenantRole.HR);
	}

}
