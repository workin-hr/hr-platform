package com.workin.backend.holidays;

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

import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.authorization.RequiresPermission;
import com.workin.backend.tenancy.AuthorizationContext;

/** Thin delegation, module template. */
@RestController
@RequestMapping("/api/tenant/official-holidays")
public class OfficialHolidayController {

	private final OfficialHolidayService holidayService;

	public OfficialHolidayController(OfficialHolidayService holidayService) {
		this.holidayService = holidayService;
	}

	@RequiresPermission(PermissionKeys.HOLIDAYS_READ)
	@GetMapping
	public List<OfficialHolidayView> list(
			HttpServletRequest request,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return holidayService.list(contextFrom(request), from, to);
	}

	@RequiresPermission(PermissionKeys.HOLIDAYS_READ)
	@GetMapping("/{holidayId}")
	public OfficialHolidayView get(HttpServletRequest request, @PathVariable Long holidayId) {
		return holidayService.get(contextFrom(request), holidayId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	/** Upserts each date, matching legacy — an existing date is renamed, not rejected. */
	@RequiresPermission(PermissionKeys.HOLIDAYS_MANAGE)
	@PostMapping
	public ResponseEntity<List<OfficialHolidayView>> create(
			HttpServletRequest request, @Valid @RequestBody CreateHolidaysRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(holidayService.create(contextFrom(request), body));
	}

	@RequiresPermission(PermissionKeys.HOLIDAYS_MANAGE)
	@PutMapping("/{holidayId}")
	public OfficialHolidayView update(
			HttpServletRequest request, @PathVariable Long holidayId,
			@Valid @RequestBody UpdateHolidayRequest body) {
		return holidayService.update(contextFrom(request), holidayId, body)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@RequiresPermission(PermissionKeys.HOLIDAYS_MANAGE)
	@DeleteMapping("/{holidayId}")
	public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long holidayId) {
		if (!holidayService.delete(contextFrom(request), holidayId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}

	private static AuthorizationContext contextFrom(HttpServletRequest request) {
		Object context = request.getAttribute(AuthorizationContext.class.getName());
		if (context == null) {
			throw new IllegalStateException("AuthorizationContext missing -- authorization interceptor not applied");
		}
		return (AuthorizationContext) context;
	}

}
