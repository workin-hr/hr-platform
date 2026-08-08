package com.workin.backend.schedule;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/schedules")
public class ScheduleController {

	private final ScheduleService scheduleService;

	public ScheduleController(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@RequiresPermission(PermissionKeys.SCHEDULES_READ)
	@GetMapping("/{employeeId}/monthly")
	public MonthlyOverviewView monthly(
			HttpServletRequest request, @PathVariable Long employeeId,
			@RequestParam int year, @RequestParam int month) {
		return scheduleService.monthlyOverview(contextFrom(request), employeeId, year, month)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.SCHEDULES_MANAGE)
	@PostMapping("/{employeeId}/assign")
	public ResponseEntity<Void> assign(
			HttpServletRequest request, @PathVariable Long employeeId,
			@Valid @RequestBody AssignScheduleRequest body) {
		if (!scheduleService.assign(contextFrom(request), employeeId, body)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

	@RequiresPermission(PermissionKeys.SCHEDULES_MANAGE)
	@PostMapping("/{employeeId}/generate")
	public GenerateResultView generate(
			HttpServletRequest request, @PathVariable Long employeeId,
			@Valid @RequestBody GenerateScheduleRequest body) {
		return scheduleService.generate(contextFrom(request), employeeId, body)
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
