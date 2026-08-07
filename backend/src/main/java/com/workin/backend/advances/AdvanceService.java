package com.workin.backend.advances;

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
 * Advances application service by the employees-module template:
 * session variable first, explicit company_id filters on top of RLS,
 * and result objects (never status-code exceptions) so the controller
 * owns HTTP semantics. The employee reference in {@link #create} is
 * resolved tenant-scoped -- a foreign or nonexistent employee is the
 * same empty result (hr-legacy#5's create shape, §8's
 * never-reveal-existence rule).
 */
@Service
public class AdvanceService {

	private final AdvanceRepository advanceRepository;
	private final EmployeeRepository employeeRepository;
	private final ResourceScopeService resourceScopeService;
	private final TenantSessionVariable tenantSessionVariable;

	public AdvanceService(
			AdvanceRepository advanceRepository,
			EmployeeRepository employeeRepository,
			ResourceScopeService resourceScopeService,
			TenantSessionVariable tenantSessionVariable) {
		this.advanceRepository = advanceRepository;
		this.employeeRepository = employeeRepository;
		this.resourceScopeService = resourceScopeService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional
	public List<AdvanceView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		Set<Long> reach = resourceScopeService.scopedEmployeeIdsOrNull(context);
		return advanceRepository.findByCompanyIdOrderById(context.companyId())
				.stream()
				.filter(a -> reach == null || reach.contains(a.getEmployeeId()))
				.map(AdvanceView::of)
				.toList();
	}

	@Transactional
	public Optional<AdvanceView> get(AuthorizationContext context, Long advanceId) {
		tenantSessionVariable.apply(context.companyId());
		return advanceRepository.findByIdAndCompanyId(advanceId, context.companyId())
				.filter(a -> resourceScopeService.isEmployeeInScope(context, a.getEmployeeId()))
				.map(AdvanceView::of);
	}

	@Transactional
	public Optional<AdvanceView> create(AuthorizationContext context, CreateAdvanceRequest request) {
		tenantSessionVariable.apply(context.companyId());
		return employeeRepository.findByIdAndCompanyId(request.employeeId(), context.companyId())
				.filter(employee -> resourceScopeService.isEmployeeInScope(context, employee.getId()))
				.map(employee -> AdvanceView.of(advanceRepository.save(new Advance(
						employee.getId(), context.companyId(), request.amount(), request.reason(),
						LocalDate.now()))));
	}

	@Transactional
	public TransitionResult approve(AuthorizationContext context, Long advanceId) {
		return transition(context, advanceId, Advance::approve);
	}

	@Transactional
	public TransitionResult reject(AuthorizationContext context, Long advanceId, String rejectionReason) {
		return transition(context, advanceId, advance -> advance.reject(rejectionReason));
	}

	private TransitionResult transition(
			AuthorizationContext context, Long advanceId, java.util.function.Consumer<Advance> change) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Advance> found = advanceRepository.findByIdAndCompanyId(advanceId, context.companyId());
		if (found.isEmpty() || !resourceScopeService.isEmployeeInScope(context, found.get().getEmployeeId())) {
			return new TransitionResult.NotFound();
		}
		Advance advance = found.get();
		if (!advance.isPending()) {
			return new TransitionResult.WrongState();
		}
		change.accept(advance);
		return new TransitionResult.Done(AdvanceView.of(advance));
	}

	public sealed interface TransitionResult {

		record NotFound() implements TransitionResult {
		}

		record WrongState() implements TransitionResult {
		}

		record Done(AdvanceView view) implements TransitionResult {
		}

	}

}
