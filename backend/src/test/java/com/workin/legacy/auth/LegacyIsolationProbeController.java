package com.workin.legacy.auth;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.authorization.LegacyHrPermissionEnforcer;
import com.workin.legacy.authorization.LegacyHrPermissionKey;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeRepository;

/**
 * Test-only. Lives under {@code src/test}, so it never ships in the
 * application jar and cannot be mistaken for a real business endpoint
 * -- punch-list items #11-13 (remapping the built modules, building the
 * 19 missing ones) remain unbuilt. Exists solely so
 * {@link LegacyTenantContextIsolationTest} has a real, tenant-owned
 * resource sitting behind {@code legacySecurityFilterChain} to attack;
 * without it, nothing under {@code /api/legacy/**} requires
 * authentication at all yet.
 *
 * <p>Deliberately does nothing but read every {@code LegacyEmployee}
 * row with no tenant-aware call site anywhere near it -- the same
 * "ordinary repository-shaped code" property
 * {@code com.workin.legacy.TenantBindingEndToEndTest} exercises, now
 * reachable over real HTTP through the real security chain instead of a
 * manually driven filter.
 *
 * <p>{@link #permissionGatedEmployeeIds()} exists for the same reason,
 * one call site later: punch-list item #11 (D-044) needs proof its
 * {@code require()} check actually gates a real HTTP request, and no
 * real legacy-side endpoint calls it yet either.
 */
@RestController
class LegacyIsolationProbeController {

	private final LegacyEmployeeRepository legacyEmployeeRepository;
	private final LegacyHrPermissionEnforcer legacyHrPermissionEnforcer;

	LegacyIsolationProbeController(
			LegacyEmployeeRepository legacyEmployeeRepository,
			LegacyHrPermissionEnforcer legacyHrPermissionEnforcer) {
		this.legacyEmployeeRepository = legacyEmployeeRepository;
		this.legacyHrPermissionEnforcer = legacyHrPermissionEnforcer;
	}

	@GetMapping("/api/legacy/test/probe/employee-ids")
	List<Long> employeeIds() {
		return legacyEmployeeRepository.findAll().stream().map(LegacyEmployee::getId).toList();
	}

	@GetMapping("/api/legacy/test/probe/permission-gated")
	List<Long> permissionGatedEmployeeIds() {
		legacyHrPermissionEnforcer.require(LegacyHrPermissionKey.CAN_EMPLOYEES);
		return legacyEmployeeRepository.findAll().stream().map(LegacyEmployee::getId).toList();
	}

}
