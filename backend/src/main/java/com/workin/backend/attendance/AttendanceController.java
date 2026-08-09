package com.workin.backend.attendance;

import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
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

import com.workin.backend.attendance.AttendanceService.MutationResult;
import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.i18n.ApiException;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/attendance")
public class AttendanceController {

	private final AttendanceService attendanceService;
	private final AttendanceCalendarService attendanceCalendarService;

	public AttendanceController(
			AttendanceService attendanceService, AttendanceCalendarService attendanceCalendarService) {
		this.attendanceService = attendanceService;
		this.attendanceCalendarService = attendanceCalendarService;
	}

	@RequiresPermission(PermissionKeys.ATTENDANCE_READ)
	@GetMapping
	public List<AttendanceView> list(
			HttpServletRequest request,
			@RequestParam(required = false) Long employeeId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return attendanceService.list(contextFrom(request), employeeId, from, to);
	}

	/**
	 * The classified day-by-day view: one row per calendar day in the
	 * range, worked or not. Replaces legacy's three overlapping read
	 * paths (the {@code full_month} branch, {@code fill_days}, and the
	 * plain month scan) with a single range endpoint.
	 *
	 * <p>Not idempotent, deliberately: serving it auto-closes any stale
	 * open punch first, exactly as legacy does before every such read.
	 * A range whose end precedes its start returns an empty list rather
	 * than an error — legacy's behaviour.
	 *
	 * <p>Declared before {@code /{attendanceId}} for readability only;
	 * the two cannot collide, since this pattern has two segments.
	 */
	@RequiresPermission(PermissionKeys.ATTENDANCE_READ)
	@GetMapping("/{employeeId}/calendar")
	public List<CalendarDayView> calendar(
			HttpServletRequest request,
			@PathVariable Long employeeId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return attendanceCalendarService.calendar(contextFrom(request), employeeId, from, to)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.ATTENDANCE_READ)
	@GetMapping("/{attendanceId}")
	public AttendanceView get(HttpServletRequest request, @PathVariable Long attendanceId) {
		return attendanceService.get(contextFrom(request), attendanceId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.ATTENDANCE_CORRECT)
	@PostMapping
	public ResponseEntity<AttendanceView> create(
			HttpServletRequest request, @Valid @RequestBody CreateAttendanceRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(toResponse(attendanceService.create(contextFrom(request), body)));
	}

	@RequiresPermission(PermissionKeys.ATTENDANCE_CORRECT)
	@PutMapping("/{attendanceId}")
	public AttendanceView update(
			HttpServletRequest request, @PathVariable Long attendanceId,
			@Valid @RequestBody UpdateAttendanceRequest body) {
		return toResponse(attendanceService.update(contextFrom(request), attendanceId, body));
	}

	@RequiresPermission(PermissionKeys.ATTENDANCE_CORRECT)
	@DeleteMapping("/{attendanceId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long attendanceId) {
		toResponse(attendanceService.delete(contextFrom(request), attendanceId));
		return ResponseEntity.noContent().build();
	}

	private static AttendanceView toResponse(MutationResult result) {
		return switch (result) {
			case MutationResult.Done done -> done.view();
			case MutationResult.NotFound notFound -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			case MutationResult.InvalidShape invalidShape ->
					throw new ApiException(HttpStatus.BAD_REQUEST, invalidShape.messageKey());
			case MutationResult.GapViolation gapViolation -> throw new ResponseStatusException(HttpStatus.CONFLICT);
		};
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
