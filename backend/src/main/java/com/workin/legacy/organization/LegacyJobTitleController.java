package com.workin.legacy.organization;

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

import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;

/**
 * {@code /api/legacy/job_titles/**} (Wave 12.3c). All five endpoints role-gate reads and writes,
 * require an active company, and deliberately carry no {@code hr_permissions} gate (D-057).
 */
@RestController
@RequestMapping("/api/legacy/job_titles")
public class LegacyJobTitleController {

	private final LegacyJobTitleService jobTitleService;
	private final LegacyRequestGuard requestGuard;

	public LegacyJobTitleController(LegacyJobTitleService jobTitleService, LegacyRequestGuard requestGuard) {
		this.jobTitleService = jobTitleService;
		this.requestGuard = requestGuard;
	}

	@GetMapping
	public List<LegacyJobTitleView> list(
			@RequestParam(name = "branch_id", required = false) Long branchId,
			@RequestParam(name = "department_id", required = false) Long departmentId) {
		LegacyRequestContext context = guard();
		return jobTitleService.list(context.companyId(), branchId, departmentId);
	}

	@GetMapping("/{id}")
	public LegacyJobTitleView one(@PathVariable long id) {
		LegacyRequestContext context = guard();
		return jobTitleService.one(context.companyId(), id);
	}

	@PostMapping
	public ResponseEntity<LegacyJobTitleView> create(@RequestBody Map<String, Object> body) {
		LegacyRequestContext context = guard();
		return ResponseEntity.status(HttpStatus.CREATED).body(jobTitleService.create(context.companyId(), body));
	}

	@PutMapping("/{id}")
	public LegacyJobTitleView update(@PathVariable long id, @RequestBody Map<String, Object> body) {
		LegacyRequestContext context = guard();
		return jobTitleService.update(context.companyId(), id, body);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		LegacyRequestContext context = guard();
		jobTitleService.delete(context.companyId(), id);
		return ResponseEntity.ok().build();
	}

	private LegacyRequestContext guard() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

}
