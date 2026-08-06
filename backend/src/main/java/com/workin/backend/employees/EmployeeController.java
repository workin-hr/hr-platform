package com.workin.backend.employees;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/**
 * Thin delegation only (ADR-0010 Dimension 4: controllers never
 * reproduce business checks): the authorization interceptor has
 * already validated the membership, checked the declared permission,
 * and stashed the validated {@link AuthorizationContext}; the service
 * re-applies tenant scope inside its own transaction.
 */
@RestController
@RequestMapping("/api/tenant/employees")
public class EmployeeController {

	private final EmployeeService employeeService;

	public EmployeeController(EmployeeService employeeService) {
		this.employeeService = employeeService;
	}

	@RequiresPermission(PermissionKeys.EMPLOYEES_READ)
	@GetMapping
	public List<EmployeeView> list(HttpServletRequest request) {
		return employeeService.list(contextFrom(request));
	}

	@RequiresPermission(PermissionKeys.EMPLOYEES_READ)
	@GetMapping("/{employeeId}")
	public EmployeeView get(HttpServletRequest request, @PathVariable Long employeeId) {
		return employeeService.get(contextFrom(request), employeeId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.EMPLOYEES_MANAGE)
	@PostMapping
	public ResponseEntity<EmployeeView> create(
			HttpServletRequest request, @Valid @RequestBody CreateEmployeeRequest body) {
		EmployeeView created = employeeService.create(contextFrom(request), body);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@RequiresPermission(PermissionKeys.EMPLOYEES_MANAGE)
	@PutMapping("/{employeeId}")
	public EmployeeView updateNames(
			HttpServletRequest request, @PathVariable Long employeeId,
			@Valid @RequestBody UpdateEmployeeRequest body) {
		return employeeService.updateNames(contextFrom(request), employeeId, body)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			// The interceptor always stashes the context for
			// @RequiresPermission handlers -- absence is a wiring bug,
			// not a caller error, and must fail loudly.
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
