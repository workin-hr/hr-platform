package com.workin.backend.employees;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.ResourceScopeService;
import com.workin.backend.organization.BranchRepository;
import com.workin.backend.organization.DepartmentRepository;
import com.workin.backend.organization.JobTitleRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * The employees module's application service and the template every
 * future module follows: each {@code @Transactional} method first
 * re-applies the RLS session variable from the already-validated
 * {@link AuthorizationContext} (the authorization interceptor's SET
 * LOCAL died with its own transaction -- see TenantSessionVariable),
 * then queries with an explicit company_id filter on top of RLS
 * (enforcement layers 4 and 5,
 * docs/architecture/authorization-model.md §4 -- neither replaces the
 * other). A cross-tenant id simply finds nothing: the caller cannot
 * distinguish "not yours" from "does not exist" (§8).
 */
@Service
public class EmployeeService {

	private final EmployeeRepository employeeRepository;
	private final BranchRepository branchRepository;
	private final DepartmentRepository departmentRepository;
	private final JobTitleRepository jobTitleRepository;
	private final ResourceScopeService resourceScopeService;
	private final TenantSessionVariable tenantSessionVariable;

	public EmployeeService(
			EmployeeRepository employeeRepository,
			BranchRepository branchRepository,
			DepartmentRepository departmentRepository,
			JobTitleRepository jobTitleRepository,
			ResourceScopeService resourceScopeService,
			TenantSessionVariable tenantSessionVariable) {
		this.employeeRepository = employeeRepository;
		this.branchRepository = branchRepository;
		this.departmentRepository = departmentRepository;
		this.jobTitleRepository = jobTitleRepository;
		this.resourceScopeService = resourceScopeService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional
	public List<EmployeeView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		Set<Long> reach = resourceScopeService.scopedEmployeeIdsOrNull(context);
		return employeeRepository.findByCompanyIdOrderById(context.companyId())
				.stream()
				.filter(e -> reach == null || reach.contains(e.getId()))
				.map(EmployeeView::of)
				.toList();
	}

	@Transactional
	public Optional<EmployeeView> get(AuthorizationContext context, Long employeeId) {
		tenantSessionVariable.apply(context.companyId());
		return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.filter(e -> resourceScopeService.isEmployeeInScope(context, e.getId()))
				.map(EmployeeView::of);
	}

	@Transactional
	public EmployeeView create(AuthorizationContext context, CreateEmployeeRequest request) {
		tenantSessionVariable.apply(context.companyId());
		requireOrgReferences(context, request.branchId(), request.departmentId(), request.jobTitleId());
		try {
			Employee employee = new Employee(
					context.companyId(), request.firstName(), request.lastName(), request.phone());
			employee.place(request.branchId(), request.departmentId(), request.jobTitleId());
			return EmployeeView.of(employeeRepository.save(employee));
		} catch (DataIntegrityViolationException ex) {
			// employees.phone is globally UNIQUE (V8); same clean-409
			// pattern as RegistrationService's registration race.
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone number already in use", ex);
		}
	}

	@Transactional
	public Optional<EmployeeView> updateNames(
			AuthorizationContext context, Long employeeId, UpdateEmployeeRequest request) {
		tenantSessionVariable.apply(context.companyId());
		requireOrgReferences(context, request.branchId(), request.departmentId(), request.jobTitleId());
		return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.filter(employee -> resourceScopeService.isEmployeeInScope(context, employee.getId()))
				.map(employee -> {
					employee.rename(request.firstName(), request.lastName());
					employee.place(request.branchId(), request.departmentId(), request.jobTitleId());
					return EmployeeView.of(employee);
				});
	}

	@Transactional
	public Optional<EmployeeView> updateStatus(AuthorizationContext context, Long employeeId, boolean active) {
		tenantSessionVariable.apply(context.companyId());
		return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.filter(employee -> resourceScopeService.isEmployeeInScope(context, employee.getId()))
				.map(employee -> {
					if (active) {
						employee.activate();
					} else {
						employee.deactivate();
					}
					return EmployeeView.of(employee);
				});
	}

	/**
	 * Each non-null org reference must resolve in-tenant; a miss is the
	 * same 404 as a nonexistent id (F-18's uniform-404 rule, section 8).
	 */
	private void requireOrgReferences(AuthorizationContext context, Long branchId, Long departmentId, Long jobTitleId) {
		boolean resolves = (branchId == null
				|| branchRepository.findByIdAndCompanyId(branchId, context.companyId()).isPresent())
				&& (departmentId == null
						|| departmentRepository.findByIdAndCompanyId(departmentId, context.companyId()).isPresent())
				&& (jobTitleId == null
						|| jobTitleRepository.findByIdAndCompanyId(jobTitleId, context.companyId()).isPresent());
		if (!resolves) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
	}

}
