package com.workin.backend.penalties;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.authorization.ResourceScopeService;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Penalties application service by the module template. Beyond CRUD it
 * enforces V13's documented immutability rule: a penalty with
 * applied_to_payroll = true answers Locked (409) to update and delete
 * -- the payroll-applied financial record is frozen on this surface.
 */
@Service
public class PenaltyService {

	private final PenaltyRepository penaltyRepository;
	private final EmployeeRepository employeeRepository;
	private final ResourceScopeService resourceScopeService;
	private final TenantSessionVariable tenantSessionVariable;

	public PenaltyService(
			PenaltyRepository penaltyRepository,
			EmployeeRepository employeeRepository,
			ResourceScopeService resourceScopeService,
			TenantSessionVariable tenantSessionVariable) {
		this.penaltyRepository = penaltyRepository;
		this.employeeRepository = employeeRepository;
		this.resourceScopeService = resourceScopeService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional
	public List<PenaltyView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		Set<Long> reach = resourceScopeService.scopedEmployeeIdsOrNull(context);
		return penaltyRepository.findByCompanyIdOrderById(context.companyId())
				.stream()
				.filter(p -> reach == null || reach.contains(p.getEmployeeId()))
				.map(PenaltyView::of)
				.toList();
	}

	@Transactional
	public Optional<PenaltyView> get(AuthorizationContext context, Long penaltyId) {
		tenantSessionVariable.apply(context.companyId());
		return penaltyRepository.findByIdAndCompanyId(penaltyId, context.companyId())
				.filter(p -> resourceScopeService.isEmployeeInScope(context, p.getEmployeeId()))
				.map(PenaltyView::of);
	}

	@Transactional
	public Optional<PenaltyView> create(AuthorizationContext context, CreatePenaltyRequest request) {
		tenantSessionVariable.apply(context.companyId());
		return employeeRepository.findByIdAndCompanyId(request.employeeId(), context.companyId())
				.filter(employee -> resourceScopeService.isEmployeeInScope(context, employee.getId()))
				.map(employee -> PenaltyView.of(penaltyRepository.save(new Penalty(
						employee.getId(), context.companyId(), request.penaltyType(), request.penaltyDays(),
						request.reason(),
						request.penaltyDate() != null ? request.penaltyDate() : LocalDate.now()))));
	}

	@Transactional
	public MutationResult update(AuthorizationContext context, Long penaltyId, UpdatePenaltyRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Penalty> found = penaltyRepository.findByIdAndCompanyId(penaltyId, context.companyId());
		if (found.isEmpty() || !resourceScopeService.isEmployeeInScope(context, found.get().getEmployeeId())) {
			return new MutationResult.NotFound();
		}
		Penalty penalty = found.get();
		if (penalty.isAppliedToPayroll()) {
			return new MutationResult.Locked();
		}
		penalty.update(request.penaltyType(), request.penaltyDays(), request.reason(), request.penaltyDate());
		return new MutationResult.Done(PenaltyView.of(penalty));
	}

	@Transactional
	public MutationResult delete(AuthorizationContext context, Long penaltyId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Penalty> found = penaltyRepository.findByIdAndCompanyId(penaltyId, context.companyId());
		if (found.isEmpty() || !resourceScopeService.isEmployeeInScope(context, found.get().getEmployeeId())) {
			return new MutationResult.NotFound();
		}
		Penalty penalty = found.get();
		if (penalty.isAppliedToPayroll()) {
			return new MutationResult.Locked();
		}
		penaltyRepository.delete(penalty);
		return new MutationResult.Done(null);
	}

	public sealed interface MutationResult {

		record NotFound() implements MutationResult {
		}

		record Locked() implements MutationResult {
		}

		record Done(PenaltyView view) implements MutationResult {
		}

	}

}
