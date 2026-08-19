package com.workin.legacy.organization;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;

/**
 * {@code /api/legacy/departments/**} (Wave 12.3b). All five endpoints use the same
 * COMPANY_ADMIN/HR/MANAGER plus active-company gates, with no {@code hr_permissions} gate (D-057).
 */
@RestController
@RequestMapping("/api/legacy/departments")
public class LegacyDepartmentController {

	private final LegacyDepartmentService departmentService;
	private final LegacyRequestGuard requestGuard;

	public LegacyDepartmentController(LegacyDepartmentService departmentService, LegacyRequestGuard requestGuard) {
		this.departmentService = departmentService;
		this.requestGuard = requestGuard;
	}

	@GetMapping
	public List<LegacyDepartmentView> list(
			@RequestParam(name = "branch_id", required = false) String rawBranchId,
			@RequestParam(name = "branch_ids", required = false) String branchIds) {
		LegacyRequestContext context = guard();
		List<String> ids = branchIds == null || branchIds.trim().isEmpty()
				? List.of() : Arrays.asList(branchIds.trim().split(","));
		return departmentService.list(context.companyId(), LegacyValues.toPhpLong(rawBranchId), ids);
	}

	@GetMapping("/{id}")
	public LegacyDepartmentView one(@PathVariable long id) {
		LegacyRequestContext context = guard();
		return departmentService.one(context.companyId(), id);
	}

	@PostMapping
	public ResponseEntity<LegacyDepartmentView> create(@RequestBody Map<String, Object> body) {
		LegacyRequestContext context = guard();
		return ResponseEntity.status(HttpStatus.CREATED).body(departmentService.create(context.companyId(), body));
	}

	@PutMapping("/{id}")
	public LegacyDepartmentView update(@PathVariable long id, @RequestBody Map<String, Object> body) {
		LegacyRequestContext context = guard();
		// PHP commits first, then its INNER JOIN response lookup fails for a branchless
		// department. Unwrap outside the service transaction to preserve that 500-after-commit quirk.
		return departmentService.update(context.companyId(), id, body)
				.orElseThrow(() -> new IllegalStateException("legacy department update response has no joined branch"));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		LegacyRequestContext context = guard();
		departmentService.delete(context.companyId(), id);
		return ResponseEntity.ok().build();
	}

	private LegacyRequestContext guard() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

}
