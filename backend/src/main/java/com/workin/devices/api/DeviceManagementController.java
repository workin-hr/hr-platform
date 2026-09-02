package com.workin.devices.api;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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

import com.workin.backend.i18n.ApiException;
import com.workin.devices.DeviceInput;
import com.workin.devices.DeviceVendor;
import com.workin.devices.identity.EmployeeDeviceIdentityStore;
import com.workin.devices.ingest.DevicePunchStore;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.devices.registry.AttendanceDeviceStore;
import com.workin.devices.registry.UnclaimedDeviceSightingStore;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyRuntimeOffset;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;

/**
 * The tenant surface for attendance devices (Q6, D-156): claim a terminal
 * by serial number, bind PINs to employees, and see what has arrived.
 *
 * <p>Authenticated with the legacy PHP JWT through the legacy chain
 * ({@code SecurityConfig}), then {@link LegacyRequestGuard#requireAuth} for
 * role ({@code company_admin}, {@code hr}) and {@code requireCompanyActive},
 * exactly the guard order every other tenant write reproduces. The company
 * comes from the validated context and is a predicate on every query; a
 * caller can neither name another tenant's branch nor read its devices.
 *
 * <p>Not a PHP parity route: it renders the platform {@code {code,message}}
 * error body via {@code ApiExceptionHandler}, snake_case JSON, no envelope.
 */
@RestController
@RequestMapping(path = "/api/v1/devices", produces = MediaType.APPLICATION_JSON_VALUE)
public class DeviceManagementController {

	private static final Pattern PIN = Pattern.compile("^\\d{1,32}$");
	private static final int MAX_PUNCHES_PAGE = 500;

	private final LegacyRequestGuard guard;
	private final AttendanceDeviceStore devices;
	private final UnclaimedDeviceSightingStore sightings;
	private final EmployeeDeviceIdentityStore identities;
	private final DevicePunchStore punches;
	private final LegacyClock clock;

	public DeviceManagementController(
			LegacyRequestGuard guard, AttendanceDeviceStore devices, UnclaimedDeviceSightingStore sightings,
			EmployeeDeviceIdentityStore identities, DevicePunchStore punches, LegacyClock clock) {
		this.guard = guard;
		this.devices = devices;
		this.sightings = sightings;
		this.identities = identities;
		this.punches = punches;
		this.clock = clock;
	}

	@GetMapping
	public Map<String, Object> list() {
		LegacyRequestContext context = administrative();
		return Map.of("devices", devices.listForCompany(context.companyId()).stream().map(DeviceManagementController::view).toList());
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> claim(@RequestBody Map<String, Object> body) {
		LegacyRequestContext context = administrative();
		String serialNumber = requiredSerialNumber(body.get("serial_number"));
		String name = requiredText(body, "name", "devices.name_required", 255);
		long branchId = requireOwnBranch(context.companyId(), body.get("branch_id"));
		String zone = zoneOrDefault(body.get("device_time_zone"));
		LocalDateTime now = clock.now();
		Optional<Long> id = devices.claim(
				context.companyId(), branchId, DeviceVendor.ZKTECO.code(), serialNumber, name, zone,
				context.employeeId() > 0 ? context.employeeId() : null, now);
		if (id.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "devices.serial_already_claimed");
		}
		sightings.forget(serialNumber);
		return ResponseEntity.status(HttpStatus.CREATED).body(view(devices.findForCompany(context.companyId(), id.get()).orElseThrow()));
	}

	@PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> update(@PathVariable("id") long id, @RequestBody Map<String, Object> body) {
		LegacyRequestContext context = administrative();
		devices.findForCompany(context.companyId(), id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "devices.not_found"));
		String name = body.containsKey("name") ? requiredText(body, "name", "devices.name_required", 255) : null;
		// device_time_zone is validated the same way on update as on claim.
		Long branchId = body.containsKey("branch_id") ? requireOwnBranch(context.companyId(), body.get("branch_id")) : null;
		String zone = body.containsKey("device_time_zone") ? zoneOrDefault(body.get("device_time_zone")) : null;
		Boolean active = body.containsKey("is_active") ? truthy(body.get("is_active")) : null;
		if (name == null && branchId == null && zone == null && active == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.nothing_to_update");
		}
		devices.update(context.companyId(), id, name, branchId, zone, active, clock.now());
		return view(devices.findForCompany(context.companyId(), id).orElseThrow());
	}

	/**
	 * "Has the terminal with this serial reached the platform yet?" -- the
	 * step before claiming one.
	 *
	 * <h2>Exact serial, and nothing about anyone else's devices</h2>
	 * <p>There is no list form: an unclaimed serial belongs to no tenant, so
	 * listing them would hand every company a directory of every other
	 * company's hardware. For the same reason the answer carries no
	 * timestamps, address, push version or device type, and a serial already
	 * claimed by <em>another</em> company answers exactly as one that was
	 * never seen -- a caller can learn about a device only while it is
	 * unowned, or once it is their own.
	 *
	 * <p>Reading the registry first also makes a stale sighting row harmless:
	 * the claim that deletes one is not in the same transaction as the insert
	 * that registers the device, so a row can outlive its own claim.
	 */
	@GetMapping("/unclaimed")
	public Map<String, Object> unclaimed(@RequestParam(name = "serial_number", required = false) String serialNumber) {
		LegacyRequestContext context = administrative();
		if (serialNumber == null || serialNumber.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.serial_number_query_required");
		}
		String serial = requiredSerialNumber(serialNumber);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("serial_number", serial);
		Optional<AttendanceDevice> registered = devices.findBySerial(serial);
		boolean ownedByCaller = registered.isPresent() && registered.get().companyId() == context.companyId();
		result.put("claimed", ownedByCaller);
		result.put("seen", ownedByCaller || (registered.isEmpty() && sightings.lookup(serial).isPresent()));
		return result;
	}

	@GetMapping("/identities")
	public Map<String, Object> identities() {
		LegacyRequestContext context = administrative();
		return Map.of("identities", identities.listForCompany(context.companyId()));
	}

	@PutMapping(path = "/identities", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> bindIdentity(@RequestBody Map<String, Object> body) {
		LegacyRequestContext context = administrative();
		Object rawEmployee = body.get("employee_id");
		long employeeId = rawEmployee instanceof Number number ? number.longValue() : parseLongOrZero(rawEmployee);
		if (employeeId <= 0 || !identities.employeeBelongsToCompany(context.companyId(), employeeId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "devices.employee_not_found");
		}
		String pin = requiredText(body, "pin", "devices.pin_required", 32);
		if (!PIN.matcher(pin).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.pin_invalid");
		}
		String cardNo = DeviceInput.bounded(asText(body.get("card_no")), 32);
		switch (identities.bind(context.companyId(), employeeId, pin, cardNo, clock.now())) {
			case PIN_TAKEN -> throw new ApiException(HttpStatus.CONFLICT, "devices.pin_already_bound");
			case EMPLOYEE_ALREADY_BOUND -> throw new ApiException(HttpStatus.CONFLICT, "devices.employee_already_bound");
			case BOUND -> { }
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("employee_id", employeeId);
		result.put("pin", pin);
		result.put("card_no", cardNo);
		return result;
	}

	/** Shadow-mode visibility: the raw punches, newest first, inside the caller's company only. */
	@GetMapping("/punches")
	public Map<String, Object> punches(
			@RequestParam(name = "device_id", required = false) Long deviceId,
			@RequestParam(name = "state", required = false) String state,
			@RequestParam(name = "limit", required = false) Integer limit) {
		LegacyRequestContext context = administrative();
		int page = limit == null || limit <= 0 ? 100 : Math.min(limit, MAX_PUNCHES_PAGE);
		String stateFilter = state == null || state.isBlank() ? null : state.strip().toUpperCase(java.util.Locale.ROOT);
		return Map.of("punches", punches.recentForCompany(context.companyId(), deviceId, stateFilter, page));
	}

	private LegacyRequestContext administrative() {
		LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private long requireOwnBranch(long companyId, Object rawBranchId) {
		long branchId = rawBranchId instanceof Number number ? number.longValue() : parseLongOrZero(rawBranchId);
		Long owner = branchId <= 0 ? null : devices.branchCompanyId(branchId);
		// A foreign branch and a missing branch answer identically: the caller
		// learns nothing about whether the id exists in another tenant.
		if (owner == null || owner != companyId) {
			throw new ApiException(HttpStatus.NOT_FOUND, "devices.branch_not_found");
		}
		return branchId;
	}

	/**
	 * The zone the device's clock is set to, as an IANA id or fixed offset.
	 * Absent, the platform's own runtime offset (D-099) -- the assumption
	 * the legacy DATETIME columns already make about every other timestamp.
	 *
	 * <p>Whole hours only. The handshake tells the terminal its zone as an
	 * integer number of hours, which is all the protocol field expresses, so
	 * a half-hour zone would be delivered rounded and the device's clock --
	 * and therefore every punch it reports, stored verbatim -- would be
	 * thirty minutes out. Refusing the claim is the honest answer until a
	 * real terminal shows what that field accepts (the hardware checklist).
	 */
	private String zoneOrDefault(Object raw) {
		String text = DeviceInput.bounded(asText(raw), 64);
		if (text == null) {
			return LegacyRuntimeOffset.zoneId(clock.offset());
		}
		ZoneId zone;
		try {
			zone = ZoneId.of(text);
		} catch (DateTimeException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.time_zone_invalid");
		}
		if (zone.getRules().getOffset(java.time.Instant.now()).getTotalSeconds() % 3600 != 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.time_zone_not_whole_hour");
		}
		return zone.getId();
	}

	/** A serial has to be one a device could actually present -- same rule the receiver applies. */
	private static String requiredSerialNumber(Object raw) {
		String serial = DeviceInput.bounded(asText(raw), 64);
		if (serial == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.serial_number_required");
		}
		if (!DeviceInput.isValidSerialNumber(serial)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.serial_number_invalid");
		}
		return serial;
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
		view.put("created_at", device.createdAt());
		return view;
	}

	private static String requiredText(Map<String, Object> body, String field, String messageKey, int max) {
		String text = DeviceInput.bounded(asText(body.get(field)), max);
		if (text == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, messageKey);
		}
		return text;
	}

	private static String asText(Object raw) {
		return raw == null ? null : String.valueOf(raw);
	}

	private static long parseLongOrZero(Object raw) {
		if (raw == null) {
			return 0L;
		}
		try {
			return Long.parseLong(String.valueOf(raw).strip());
		} catch (NumberFormatException ex) {
			return 0L;
		}
	}

	private static Boolean truthy(Object raw) {
		if (raw instanceof Boolean bool) {
			return bool;
		}
		if (raw instanceof Number number) {
			return number.longValue() != 0L;
		}
		String text = raw == null ? "" : String.valueOf(raw).strip().toLowerCase(java.util.Locale.ROOT);
		return "1".equals(text) || "true".equals(text);
	}

}
