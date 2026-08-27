package com.workin.legacy.organization;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.i18n.ApiErrorBody;
import com.workin.backend.i18n.ApiException;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/**
 * Test-only Wave 12.3c REST alias retained solely for pre-D-074 regression coverage.
 *
 * <p>{@link LegacyJobTitleService} is shared with the production {@code .php} controller and
 * throws {@link LegacyApiException} (the PHP-envelope exception type, D-107/D-108's fix for the
 * shared service's {@code {field}}-placeholder fidelity). This alias predates that envelope and
 * its own regression suite asserts the platform {@link ApiErrorBody} shape, so it translates
 * locally rather than either reverting the service's exception type or silently changing this
 * suite's contract.
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
	public List<LegacyJobTitleView> list(HttpServletRequest request) {
		LegacyRequestContext context = guard();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		return jobTitleService.list(
				context.companyId(), LegacyValues.toPhpLong(query.value("branch_id")),
				LegacyValues.toPhpLong(query.value("department_id")));
	}

	@GetMapping("/{id}")
	public LegacyJobTitleView one(@PathVariable long id) {
		LegacyRequestContext context = guard();
		requireId(id);
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
		requireId(id);
		return jobTitleService.update(context.companyId(), id, body);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		LegacyRequestContext context = guard();
		requireId(id);
		jobTitleService.delete(context.companyId(), id);
		return ResponseEntity.ok().build();
	}

	private LegacyRequestContext guard() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private static void requireId(long id) {
		if (id == 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "id_required");
		}
	}

	@ExceptionHandler(LegacyApiException.class)
	public ResponseEntity<ApiErrorBody> handleLegacy(LegacyApiException ex) {
		return ResponseEntity.status(ex.getStatus())
				.body(new ApiErrorBody(ex.getMessageKey(), ex.getMessageKey()));
	}
}
