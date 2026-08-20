package com.workin.legacy.organization;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.i18n.ApiException;
import com.workin.legacy.LegacyQueryParameters;
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
	public List<LegacyDepartmentView> list(HttpServletRequest request) {
		LegacyRequestContext context = guard();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		Object rawBranchId = query.value("branch_id");
		String branchIds = LegacyValues.toPhpString(query.value("branch_ids")).trim();
		List<String> ids = branchIds.isEmpty() ? List.of() : Arrays.asList(branchIds.split(","));
		return departmentService.list(context.companyId(), LegacyValues.toPhpLong(rawBranchId), ids);
	}

	@GetMapping("/{id}")
	public LegacyDepartmentView one(@PathVariable long id) {
		LegacyRequestContext context = guard();
		requireId(id);
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
		requireId(id);
		// PHP commits first, then its INNER JOIN response lookup fails for a branchless
		// department. Unwrap outside the service transaction to preserve that 500-after-commit quirk.
		return departmentService.update(context.companyId(), id, body)
				.orElseThrow(() -> new IllegalStateException("legacy department update response has no joined branch"));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		LegacyRequestContext context = guard();
		requireId(id);
		departmentService.delete(context.companyId(), id);
		return ResponseEntity.ok().build();
	}

	private LegacyRequestContext guard() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/**
	 * {@code one.php}/{@code update.php}/{@code delete.php}: {@code $id = (int)($_GET['id'] ?? 0);
	 * if (!$id) fail(ID_REQUIRED);}. Only exact zero is falsy in PHP; a negative id stays truthy and
	 * falls through unchanged to the normal (not-found) lookup, so this must not become {@code id <= 0}.
	 */
	private static void requireId(long id) {
		if (id == 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "id_required");
		}
	}

}
