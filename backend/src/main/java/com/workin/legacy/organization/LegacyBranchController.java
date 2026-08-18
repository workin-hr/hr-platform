package com.workin.legacy.organization;

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

import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;

/**
 * {@code /api/legacy/branches/**} (Wave 12.3, PR 12.3a). Guard order on
 * every method mirrors legacy's own call sequence exactly: {@code
 * requireAuth([COMPANY_ADMIN, HR, MANAGER])} (P-7 + P-8) then {@code
 * requireCompanyActive} (P-9) -- **all six endpoints, including reads**
 * (D-057: unlike Wave 12.1's ungated list/one, every legacy {@code
 * branches} endpoint role-gates reads too). No {@code
 * LegacyHrPermissionEnforcer} call anywhere (D-057: confirmed negative,
 * no {@code hr_permissions} flag gates this module).
 */
@RestController
@RequestMapping("/api/legacy/branches")
public class LegacyBranchController {

	private final LegacyBranchService legacyBranchService;
	private final LegacyRequestGuard legacyRequestGuard;

	public LegacyBranchController(LegacyBranchService legacyBranchService, LegacyRequestGuard legacyRequestGuard) {
		this.legacyBranchService = legacyBranchService;
		this.legacyRequestGuard = legacyRequestGuard;
	}

	@GetMapping
	public LegacyBranchPage list(
			@RequestParam(required = false) String search,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int limit) {
		LegacyRequestContext context = guard();
		return legacyBranchService.list(context.companyId(), search, page, limit);
	}

	@GetMapping("/{id}")
	public LegacyBranchView one(@PathVariable long id) {
		LegacyRequestContext context = guard();
		return legacyBranchService.one(context.companyId(), id);
	}

	@PostMapping
	public ResponseEntity<LegacyBranchView> create(@RequestBody Map<String, Object> body) {
		LegacyRequestContext context = guard();
		return ResponseEntity.status(HttpStatus.CREATED).body(legacyBranchService.create(context.companyId(), body));
	}

	@PutMapping("/{id}")
	public LegacyBranchView update(@PathVariable long id, @RequestBody Map<String, Object> body) {
		LegacyRequestContext context = guard();
		return legacyBranchService.update(context.companyId(), id, body);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		LegacyRequestContext context = guard();
		legacyBranchService.delete(context.companyId(), id);
		return ResponseEntity.ok().build();
	}

	@PostMapping("/{id}/qr")
	public LegacyBranchView generateQr(@PathVariable long id, @RequestBody Map<String, Object> body) {
		LegacyRequestContext context = guard();
		return legacyBranchService.generateQr(context.companyId(), id, body);
	}

	/** Every endpoint in this module shares this exact gate sequence (D-057). */
	private LegacyRequestContext guard() {
		LegacyRequestContext context = legacyRequestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		legacyRequestGuard.requireCompanyActive(context.companyId());
		return context;
	}

}
