package com.workin.devices.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.workin.devices.api.DeviceManagementService.BoundIdentity;
import com.workin.devices.api.DeviceManagementService.UnclaimedLookup;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;

/**
 * The tenant surface for attendance devices (Q6, D-156): claim a terminal,
 * bind PINs to employees, and see what has arrived.
 *
 * <p>This class owns authentication and presentation only -- the guard order
 * and the JSON shape. What a caller is allowed to do, and what each rule
 * means, belongs to {@link DeviceManagementService}.
 *
 * <p>Authenticated with the legacy PHP JWT through the legacy chain
 * ({@code SecurityConfig}), then {@link LegacyRequestGuard#requireAuth} for
 * role ({@code company_admin}, {@code hr}) and {@code requireCompanyActive} --
 * exactly the guard order every other tenant write reproduces. The company
 * comes from the validated context, so a caller can neither name another
 * tenant's branch nor read its devices.
 *
 * <p>Not a PHP parity route: it renders the platform {@code {code,message}}
 * error body via {@code ApiExceptionHandler}, snake_case JSON, no envelope.
 */
@RestController
@RequestMapping(path = "/api/v1/devices", produces = MediaType.APPLICATION_JSON_VALUE)
public class DeviceManagementController {

	private final LegacyRequestGuard guard;
	private final DeviceManagementService service;

	public DeviceManagementController(LegacyRequestGuard guard, DeviceManagementService service) {
		this.guard = guard;
		this.service = service;
	}

	@GetMapping
	public Map<String, Object> list() {
		LegacyRequestContext context = administrative();
		return Map.of("devices", service.list(context.companyId()).stream().map(DeviceManagementController::view).toList());
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> claim(@RequestBody Map<String, Object> body) {
		LegacyRequestContext context = administrative();
		AttendanceDevice claimed = service.claim(context.companyId(), context.employeeId(), body);
		return ResponseEntity.status(HttpStatus.CREATED).body(view(claimed));
	}

	@PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> update(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
		LegacyRequestContext context = administrative();
		return view(service.update(context.companyId(), id, body));
	}

	/** Has the terminal with this exact serial reached the platform yet? */
	@GetMapping("/unclaimed")
	public Map<String, Object> unclaimed(@RequestParam(name = "serial_number", required = false) String serialNumber) {
		LegacyRequestContext context = administrative();
		UnclaimedLookup lookup = service.lookupUnclaimed(context.companyId(), serialNumber);
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("serial_number", lookup.serialNumber());
		view.put("claimed", lookup.claimedByCaller());
		view.put("seen", lookup.seen());
		return view;
	}

	@GetMapping("/identities")
	public Map<String, Object> identities() {
		LegacyRequestContext context = administrative();
		return Map.of("identities", service.identities(context.companyId()));
	}

	@PutMapping(path = "/identities", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> bindIdentity(@RequestBody Map<String, Object> body) {
		LegacyRequestContext context = administrative();
		BoundIdentity bound = service.bindIdentity(context.companyId(), body);
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("employee_id", bound.employeeId());
		view.put("pin", bound.pin());
		view.put("card_no", bound.cardNo());
		return view;
	}

	/** Shadow-mode visibility: the raw punches, newest first, inside the caller's company only. */
	@GetMapping("/punches")
	public Map<String, Object> punches(
			@RequestParam(name = "device_id", required = false) Long deviceId,
			@RequestParam(name = "state", required = false) String state,
			@RequestParam(name = "limit", required = false) Integer limit) {
		LegacyRequestContext context = administrative();
		return Map.of("punches", service.punches(context.companyId(), deviceId, state, limit));
	}

	private LegacyRequestContext administrative() {
		LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	static Map<String, Object> view(AttendanceDevice device) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", device.id());
		view.put("serial_number", device.serialNumber());
		view.put("name", device.name());
		view.put("branch_id", device.branchId());
		view.put("vendor", device.vendor());
		view.put("model", device.model());
		view.put("firmware", device.firmware());
		view.put("push_version", device.pushVersion());
		view.put("device_time_zone", device.deviceTimeZone());
		view.put("is_active", device.active());
		view.put("last_seen_at", device.lastSeenAt());
		view.put("last_handshake_at", device.lastHandshakeAt());
		view.put("last_seen_ip", device.lastSeenIp());
		// Recorded from the device and never returned to it (D-158); surfaced
		// here so "diagnostic" means an operator can actually read it.
		view.put("last_attlog_stamp", device.lastAttlogStamp());
		view.put("created_at", device.createdAt());
		return view;
	}
}
