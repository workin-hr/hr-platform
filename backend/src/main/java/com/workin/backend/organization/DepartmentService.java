package com.workin.backend.organization;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Module template plus the junction's replace-set semantics: the
 * branch set on create/update replaces department_branches wholesale
 * (each id tenant-validated -> the same 404). Delete clears the
 * aggregate-owned junction rows first; external references
 * (job_titles, employees) still answer 409 via the real FK.
 */
@Service
public class DepartmentService {

	private final DepartmentRepository departmentRepository;
	private final DepartmentBranchRepository departmentBranchRepository;
	private final BranchRepository branchRepository;
	private final EmployeeRepository employeeRepository;
	private final TenantSessionVariable tenantSessionVariable;

	public DepartmentService(
			DepartmentRepository departmentRepository,
			DepartmentBranchRepository departmentBranchRepository,
			BranchRepository branchRepository,
			EmployeeRepository employeeRepository,
			TenantSessionVariable tenantSessionVariable) {
		this.departmentRepository = departmentRepository;
		this.departmentBranchRepository = departmentBranchRepository;
		this.branchRepository = branchRepository;
		this.employeeRepository = employeeRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional(readOnly = true)
	public List<DepartmentView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return departmentRepository.findByCompanyIdOrderById(context.companyId()).stream()
				.map(department -> DepartmentView.of(department, branchIdsOf(department.getId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public Optional<DepartmentView> get(AuthorizationContext context, Long departmentId) {
		tenantSessionVariable.apply(context.companyId());
		return departmentRepository.findByIdAndCompanyId(departmentId, context.companyId())
				.map(department -> DepartmentView.of(department, branchIdsOf(department.getId())));
	}

	@Transactional
	public Optional<DepartmentView> create(AuthorizationContext context, UpsertDepartmentRequest request) {
		tenantSessionVariable.apply(context.companyId());
		if (!referencesResolve(context, request)) {
			return Optional.empty();
		}
		Department department = new Department(context.companyId());
		department.apply(request.name(), request.managerId(), request.isActive());
		departmentRepository.save(department);
		replaceBranchSet(context, department.getId(), request.branchIds());
		return Optional.of(DepartmentView.of(department, branchIdsOf(department.getId())));
	}

	@Transactional
	public Optional<DepartmentView> update(
			AuthorizationContext context, Long departmentId, UpsertDepartmentRequest request) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Department> found = departmentRepository.findByIdAndCompanyId(departmentId, context.companyId());
		if (found.isEmpty() || !referencesResolve(context, request)) {
			return Optional.empty();
		}
		found.get().apply(request.name(), request.managerId(), request.isActive());
		replaceBranchSet(context, departmentId, request.branchIds());
		return Optional.of(DepartmentView.of(found.get(), branchIdsOf(departmentId)));
	}

	@Transactional
	public boolean delete(AuthorizationContext context, Long departmentId) {
		tenantSessionVariable.apply(context.companyId());
		Optional<Department> found = departmentRepository.findByIdAndCompanyId(departmentId, context.companyId());
		if (found.isEmpty()) {
			return false;
		}
		departmentBranchRepository.deleteByDepartmentId(departmentId);
		try {
			departmentRepository.delete(found.get());
			departmentRepository.flush();
		} catch (DataIntegrityViolationException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "department is still referenced", ex);
		}
		return true;
	}

	private boolean referencesResolve(AuthorizationContext context, UpsertDepartmentRequest request) {
		if (request.managerId() != null
				&& employeeRepository.findByIdAndCompanyId(request.managerId(), context.companyId()).isEmpty()) {
			return false;
		}
		for (Long branchId : branchIdsOrEmpty(request)) {
			if (branchRepository.findByIdAndCompanyId(branchId, context.companyId()).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private void replaceBranchSet(AuthorizationContext context, Long departmentId, List<Long> branchIds) {
		departmentBranchRepository.deleteByDepartmentId(departmentId);
		// Flush the removals before inserting: Hibernate's flush ordering
		// executes inserts before deletes, so re-adding a kept branch
		// would otherwise hit the pair-unique constraint.
		departmentBranchRepository.flush();
		for (Long branchId : branchIds != null ? branchIds : List.<Long>of()) {
			departmentBranchRepository.save(new DepartmentBranch(departmentId, branchId, context.companyId()));
		}
	}

	private List<Long> branchIdsOrEmpty(UpsertDepartmentRequest request) {
		return request.branchIds() != null ? request.branchIds() : List.of();
	}

	private List<Long> branchIdsOf(Long departmentId) {
		return departmentBranchRepository.findByDepartmentId(departmentId).stream()
				.map(DepartmentBranch::getBranchId)
				.toList();
	}

}
