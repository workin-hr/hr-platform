package com.workin.devices.api;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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

/**
 * What a tenant administrator can do with attendance devices: claim a
 * terminal, move or deactivate it, bind PINs to employees, and read what has
 * arrived. {@link DeviceManagementController} authenticates the caller and
 * renders the answer; every rule about what is allowed lives here.
 *
 * <p>The company id is a parameter on every method and a predicate on every
 * query beneath them -- it comes from the caller's validated token, never
 * from a request body, so no argument a client supplies can widen the scope
 * of a read or a write.
 */
@Service
public class DeviceManagementService {

	private static final Pattern PIN = Pattern.compile("^\\d{1,32}$");

	private static final int DEFAULT_PUNCHES_PAGE = 100;

	private static final int MAX_PUNCHES_PAGE = 500;

	/**
	 * @param claimedByCaller true only when the caller's own company owns it;
	 *        a device owned by another company reports false for both fields,
	 *        exactly as one nobody has ever seen
	 */
	public record UnclaimedLookup(String serialNumber, boolean claimedByCaller, boolean seen) {
	}

	public record BoundIdentity(long employeeId, String pin, String cardNo) {
	}

	private final AttendanceDeviceStore devices;
	private final UnclaimedDeviceSightingStore sightings;
	private final EmployeeDeviceIdentityStore identities;
	private final DevicePunchStore punches;
	private final LegacyClock clock;

	public DeviceManagementService(
			AttendanceDeviceStore devices, UnclaimedDeviceSightingStore sightings,
			EmployeeDeviceIdentityStore identities, DevicePunchStore punches, LegacyClock clock) {
		this.devices = devices;
		this.sightings = sightings;
		this.identities = identities;
		this.punches = punches;
		this.clock = clock;
	}

	public List<AttendanceDevice> list(long companyId) {
		return devices.listForCompany(companyId);
	}

	/**
	 * Registers a serial to a branch of this company.
	 *
	 * <p>Claiming is first-come and global, which is a known limitation
	 * (R-041): the protocol has no proof of possession, so this is a
	 * supervised-pilot arrangement and production is to move allocation to
	 * platform staff (D-157).
	 */
	public AttendanceDevice claim(long companyId, long actorEmployeeId, Map<String, Object> body) {
		String serialNumber = requiredSerialNumber(body.get("serial_number"));
		String name = requiredText(body, "name", "devices.name_required", 255);
		long branchId = requireOwnBranch(companyId, body.get("branch_id"));
		String zone = zoneOrDefault(body.get("device_time_zone"));
		Optional<Long> id = devices.claim(
				companyId, branchId, DeviceVendor.ZKTECO.code(), serialNumber, name, zone,
				actorEmployeeId > 0 ? actorEmployeeId : null, clock.now());
		if (id.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "devices.serial_already_claimed");
		}
		sightings.forget(serialNumber);
		return require(companyId, id.get());
	}

	public AttendanceDevice update(long companyId, long id, Map<String, Object> body) {
		require(companyId, id);
		String name = body.containsKey("name") ? requiredText(body, "name", "devices.name_required", 255) : null;
		Long branchId = body.containsKey("branch_id") ? requireOwnBranch(companyId, body.get("branch_id")) : null;
		String zone = body.containsKey("device_time_zone") ? zoneOrDefault(body.get("device_time_zone")) : null;
		Boolean active = body.containsKey("is_active") ? truthy(body.get("is_active")) : null;
		if (name == null && branchId == null && zone == null && active == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.nothing_to_update");
		}
		devices.update(companyId, id, name, branchId, zone, active, clock.now());
		return require(companyId, id);
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
	public UnclaimedLookup lookupUnclaimed(long companyId, String serialNumber) {
		if (serialNumber == null || serialNumber.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.serial_number_query_required");
		}
		String serial = requiredSerialNumber(serialNumber);
		Optional<AttendanceDevice> registered = devices.findBySerial(serial);
		boolean ownedByCaller = registered.isPresent() && registered.get().companyId() == companyId;
		boolean seen = ownedByCaller || (registered.isEmpty() && sightings.lookup(serial).isPresent());
		return new UnclaimedLookup(serial, ownedByCaller, seen);
	}

	public List<Map<String, Object>> identities(long companyId) {
		return identities.listForCompany(companyId);
	}

	public BoundIdentity bindIdentity(long companyId, Map<String, Object> body) {
		long employeeId = asLong(body.get("employee_id"));
		if (employeeId <= 0 || !identities.employeeBelongsToCompany(companyId, employeeId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "devices.employee_not_found");
		}
		String pin = requiredText(body, "pin", "devices.pin_required", 32);
		if (!PIN.matcher(pin).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.pin_invalid");
		}
		String cardNo = DeviceInput.bounded(asText(body.get("card_no")), 32);
		switch (identities.bind(companyId, employeeId, pin, cardNo, clock.now())) {
			case PIN_TAKEN -> throw new ApiException(HttpStatus.CONFLICT, "devices.pin_already_bound");
			case EMPLOYEE_ALREADY_BOUND -> throw new ApiException(HttpStatus.CONFLICT, "devices.employee_already_bound");
			case BOUND -> { }
		}
		return new BoundIdentity(employeeId, pin, cardNo);
	}

	/** Shadow-mode visibility: the raw punches, newest first, inside one company only. */
	public List<Map<String, Object>> punches(long companyId, Long deviceId, String state, Integer limit) {
		int page = limit == null || limit <= 0 ? DEFAULT_PUNCHES_PAGE : Math.min(limit, MAX_PUNCHES_PAGE);
		String stateFilter = state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT);
		return punches.recentForCompany(companyId, deviceId, stateFilter, page);
	}

	private AttendanceDevice require(long companyId, long id) {
		return devices.findForCompany(companyId, id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "devices.not_found"));
	}

	private long requireOwnBranch(long companyId, Object rawBranchId) {
		long branchId = asLong(rawBranchId);
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
	 * Absent, the platform's own runtime offset (D-099) -- the assumption the
	 * legacy DATETIME columns already make about every other timestamp.
	 *
	 * <p>Whole hours only. The handshake tells the terminal its zone as an
	 * integer number of hours, which is all the protocol field expresses, so
	 * a half-hour zone would be delivered rounded and the device's clock --
	 * and therefore every punch it reports, stored verbatim -- would be thirty
	 * minutes out. Refusing the claim is the honest answer until a real
	 * terminal shows what that field accepts (the hardware checklist).
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
		if (!isWholeHourYearRound(zone)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.time_zone_not_whole_hour");
		}
		return zone.getId();
	}

	/**
	 * Every offset the zone will use, not just today's.
	 *
	 * <p>Checking the current offset alone would accept a zone that is whole
	 * hours in one season and fractional in another -- {@code
	 * Australia/Lord_Howe} shifts by thirty minutes -- and the terminal would
	 * silently be sent a rounded zone for half the year, with every punch it
	 * then reports wrong by that much.
	 */
	static boolean isWholeHourYearRound(ZoneId zone) {
		ZoneRules rules = zone.getRules();
		Instant cursor = Instant.now();
		if (rules.getOffset(cursor).getTotalSeconds() % 3600 != 0) {
			return false;
		}
		// Two years covers every regular transition, including zones that
		// change their rules annually.
		Instant horizon = cursor.plus(Duration.ofDays(730));
		for (ZoneOffsetTransition transition = rules.nextTransition(cursor);
				transition != null && transition.getInstant().isBefore(horizon);
				transition = rules.nextTransition(transition.getInstant())) {
			if (transition.getOffsetAfter().getTotalSeconds() % 3600 != 0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A serial has to be one a device could actually present -- the same rule
	 * the receiver applies.
	 *
	 * <p>Whitespace is stripped; the value is <b>not</b> truncated. Shortening
	 * an identifier to fit would register a device under a serial no terminal
	 * will ever send: the real one is rejected by the receiver as overlong, so
	 * the claim is permanently dead, and the prefix it reserved may belong to
	 * another device.
	 */
	private static String requiredSerialNumber(Object raw) {
		String text = asText(raw);
		String serial = text == null ? null : text.strip();
		if (serial == null || serial.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.serial_number_required");
		}
		if (!DeviceInput.isValidSerialNumber(serial)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.serial_number_invalid");
		}
		return serial;
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

	private static long asLong(Object raw) {
		if (raw instanceof Number number) {
			return number.longValue();
		}
		try {
			return raw == null ? 0L : Long.parseLong(String.valueOf(raw).strip());
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
		String text = raw == null ? "" : String.valueOf(raw).strip().toLowerCase(Locale.ROOT);
		return "1".equals(text) || "true".equals(text);
	}

}
