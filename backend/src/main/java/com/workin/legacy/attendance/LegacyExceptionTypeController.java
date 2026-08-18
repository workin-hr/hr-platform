package com.workin.legacy.attendance;

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
import com.workin.legacy.authorization.LegacyHrPermissionEnforcer;
import com.workin.legacy.authorization.LegacyHrPermissionKey;
import com.workin.legacy.employees.LegacyEmployee;

/**
 * {@code /api/legacy/attendance_exception_types/**} (Wave 12.1, PR 12.1):
 * the first real legacy business endpoint, closing PR #101's
 * probe-controller evidence gap (spec §9). Guard order on every method
 * mirrors legacy's own call sequence exactly: {@code requireAuth} (P-7 +
 * P-8), {@code requireCompanyActive} (P-9), then -- writes only -- {@code
 * can_company_settings} (P-3, D-045). List/one carry no permission gate
 * on purpose; adding one would be authorization legacy does not have.
 */
@RestController
@RequestMapping("/api/legacy/attendance_exception_types")
public class LegacyExceptionTypeController {

	private final LegacyExceptionTypeService legacyExceptionTypeService;
	private final LegacyRequestGuard legacyRequestGuard;
	private final LegacyHrPermissionEnforcer legacyHrPermissionEnforcer;

	public LegacyExceptionTypeController(
			LegacyExceptionTypeService legacyExceptionTypeService,
			LegacyRequestGuard legacyRequestGuard,
			LegacyHrPermissionEnforcer legacyHrPermissionEnforcer) {
		this.legacyExceptionTypeService = legacyExceptionTypeService;
		this.legacyRequestGuard = legacyRequestGuard;
		this.legacyHrPermissionEnforcer = legacyHrPermissionEnforcer;
	}

	@GetMapping
	public LegacyExceptionTypePage list(
			@RequestParam(required = false) Integer isActive,
			@RequestParam(required = false) String search,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int limit) {
		LegacyRequestContext context = legacyRequestGuard.requireAuth();
		legacyRequestGuard.requireCompanyActive(context.companyId());
		return legacyExceptionTypeService.list(context.companyId(), context.role(), isActive, search, page, limit);
	}

	@GetMapping("/{id}")
	public LegacyExceptionTypeView one(@PathVariable long id) {
		LegacyRequestContext context = legacyRequestGuard.requireAuth();
		legacyRequestGuard.requireCompanyActive(context.companyId());
		return legacyExceptionTypeService.one(context.companyId(), id);
	}

	@PostMapping
	public ResponseEntity<LegacyExceptionTypeView> create(@RequestBody Map<String, Object> body) {
		LegacyRequestContext context = writeGuard();
		String name = (String) body.get("name");
		Boolean isActive = toBoolean(body.get("isActive"));
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(legacyExceptionTypeService.create(context.companyId(), name, isActive));
	}

	@PutMapping("/{id}")
	public LegacyExceptionTypeView update(@PathVariable long id, @RequestBody Map<String, Object> body) {
		LegacyRequestContext context = writeGuard();
		return legacyExceptionTypeService.update(context.companyId(), id, body);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		LegacyRequestContext context = writeGuard();
		legacyExceptionTypeService.delete(context.companyId(), id);
		return ResponseEntity.ok().build();
	}

	/** create/update/delete share this exact gate sequence. */
	private LegacyRequestContext writeGuard() {
		LegacyRequestContext context = legacyRequestGuard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		legacyRequestGuard.requireCompanyActive(context.companyId());
		legacyHrPermissionEnforcer.require(LegacyHrPermissionKey.CAN_COMPANY_SETTINGS);
		return context;
	}

	private static Boolean toBoolean(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Boolean bool) {
			return bool;
		}
		return "1".equals(String.valueOf(raw)) || "true".equalsIgnoreCase(String.valueOf(raw));
	}

}
