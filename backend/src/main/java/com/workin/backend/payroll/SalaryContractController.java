package com.workin.backend.payroll;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/**
 * Thin delegation, module template. No dedicated permission key exists
 * for salary contracts specifically in V4's catalog (legacy has no
 * separate can_salary_contracts flag) -- gated by payroll.read/
 * payroll.run, the same as payroll_batches/payslips, consistent with
 * this module group's tight coupling.
 */
@RestController
@RequestMapping("/api/tenant/salary-contracts")
public class SalaryContractController {

	private final SalaryContractService salaryContractService;

	public SalaryContractController(SalaryContractService salaryContractService) {
		this.salaryContractService = salaryContractService;
	}

	@RequiresPermission(PermissionKeys.PAYROLL_READ)
	@GetMapping
	public List<SalaryContractView> list(HttpServletRequest request, @RequestParam Long employeeId) {
		return salaryContractService.listForEmployee(contextFrom(request), employeeId);
	}

	@RequiresPermission(PermissionKeys.PAYROLL_READ)
	@GetMapping("/{contractId}")
	public SalaryContractView get(HttpServletRequest request, @PathVariable Long contractId) {
		return salaryContractService.get(contextFrom(request), contractId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PostMapping
	public ResponseEntity<SalaryContractView> create(
			HttpServletRequest request, @RequestParam Long employeeId,
			@Valid @RequestBody UpsertSalaryContractRequest body) {
		return salaryContractService.create(contextFrom(request), employeeId, body)
				.map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@PutMapping("/{contractId}")
	public SalaryContractView update(
			HttpServletRequest request, @PathVariable Long contractId,
			@Valid @RequestBody UpsertSalaryContractRequest body) {
		return salaryContractService.update(contextFrom(request), contractId, body)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.PAYROLL_RUN)
	@DeleteMapping("/{contractId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long contractId) {
		if (!salaryContractService.delete(contextFrom(request), contractId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
