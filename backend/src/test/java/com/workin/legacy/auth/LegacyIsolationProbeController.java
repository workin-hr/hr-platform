package com.workin.legacy.auth;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
class LegacyIsolationProbeController {

	private final LegacyEmployeeRepository legacyEmployeeRepository;

	LegacyIsolationProbeController(LegacyEmployeeRepository legacyEmployeeRepository) {
		this.legacyEmployeeRepository = legacyEmployeeRepository;
	}

	@GetMapping("/api/legacy/test/probe/employee-ids")
	List<Long> employeeIds() {
		return legacyEmployeeRepository.findAll().stream().map(LegacyEmployee::getId).toList();
	}

}
