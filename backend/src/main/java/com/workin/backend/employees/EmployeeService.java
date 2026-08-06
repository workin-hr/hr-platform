package com.workin.backend.employees;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
	private final TenantSessionVariable tenantSessionVariable;

	public EmployeeService(EmployeeRepository employeeRepository, TenantSessionVariable tenantSessionVariable) {
		this.employeeRepository = employeeRepository;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	@Transactional
	public List<EmployeeView> list(AuthorizationContext context) {
		tenantSessionVariable.apply(context.companyId());
		return employeeRepository.findByCompanyIdOrderById(context.companyId())
				.stream()
				.map(EmployeeView::of)
				.toList();
	}

	@Transactional
	public Optional<EmployeeView> get(AuthorizationContext context, Long employeeId) {
		tenantSessionVariable.apply(context.companyId());
		return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.map(EmployeeView::of);
	}

	@Transactional
	public EmployeeView create(AuthorizationContext context, CreateEmployeeRequest request) {
		tenantSessionVariable.apply(context.companyId());
		try {
			Employee employee = employeeRepository.save(new Employee(
					context.companyId(), request.firstName(), request.lastName(), request.phone()));
			return EmployeeView.of(employee);
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
		return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.map(employee -> {
					employee.rename(request.firstName(), request.lastName());
					return EmployeeView.of(employee);
				});
	}

}
