package com.workin.devices.zkteco;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.workin.devices.DeviceInput;
import com.workin.devices.DeviceVendor;
import com.workin.devices.QueryParameters;
import com.workin.devices.ingest.DeviceOperationLogStore;
import com.workin.devices.ingest.DevicePunchIngestionService;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.devices.registry.AttendanceDeviceStore;
import com.workin.devices.registry.UnclaimedDeviceSightingStore;
import com.workin.legacy.LegacyClock;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The ZKTeco ADMS / PUSH SDK receiver -- the Part B adapter (D-156). Four
 * device-facing endpoints, all device-initiated, all plain text.
 *
 * <h2>Trust model</h2>
 * <p>The serial number in the query string is an identifier, not a
 * credential. It is validated for shape, then resolved against the registry;
 * the registry row supplies the tenant and branch, and nothing the payload
 * asserts about itself is believed. An unclaimed <em>or deactivated</em>
 * serial receives a neutral handshake carrying no stamp and no zone, is
 * recorded as a sighting, and has every upload refused, so it keeps
 * buffering and nothing of it is stored.
 *
 * <p>Three consequences of "unauthenticated" are handled explicitly rather
 * than left to chance: a serial is bounded and shape-checked <em>before</em>
 * it can create a row (the legacy database is non-strict and would truncate
 * an over-long one into a collision); every value echoed into the handshake
 * or into a metric tag comes from a closed set, never from the request; and
 * anything logged is control-character-stripped and bounded, so a caller
 * cannot forge or flood log lines.
 *
 * <p>Exists only when {@code app.devices.ingest.enabled=true}: default
 * closed, together with its security chain.
 */
@RestController
@RequestMapping(path = "/iclock", produces = MediaType.TEXT_PLAIN_VALUE)
@ConditionalOnProperty(name = "app.devices.ingest.enabled", havingValue = "true")
public class ZkTecoAdmsController {

	private static final Logger LOG = LoggerFactory.getLogger(ZkTecoAdmsController.class);

	static final String VENDOR = DeviceVendor.ZKTECO.code();
	static final String OK = "OK";
	static final String UNREGISTERED = "ERROR: device is not registered";
	static final String INVALID_SERIAL = "ERROR: invalid SN";

	/**
	 * The upload tables this receiver recognises. A {@code table} value
	 * outside the set is tagged {@code other} rather than used verbatim: the
	 * parameter is caller-controlled, and a metric tag built from it is an
	 * unbounded series count in the registry -- memory growth reachable by
	 * anyone who knows one serial.
	 */
	private static final Set<String> KNOWN_TABLES = Set.of(
			"ATTLOG", "OPERLOG", "OPTIONS", "ATTPHOTO", "FINGERTMP", "BIODATA", "USERINFO", "USERPIC");

	private static final String OTHER_TABLE = "other";

	private final AttendanceDeviceStore devices;
	private final UnclaimedDeviceSightingStore sightings;
	private final DevicePunchIngestionService ingestion;
	private final DeviceOperationLogStore operationLogs;
	private final LegacyClock clock;
	private final MeterRegistry meters;
	private final int maxBodyBytes;

	public ZkTecoAdmsController(
			AttendanceDeviceStore devices, UnclaimedDeviceSightingStore sightings,
			DevicePunchIngestionService ingestion, DeviceOperationLogStore operationLogs, LegacyClock clock,
			MeterRegistry meters, @Value("${app.devices.ingest.max-body-bytes}") int maxBodyBytes) {
		this.devices = devices;
		this.sightings = sightings;
		this.ingestion = ingestion;
		this.operationLogs = operationLogs;
		this.clock = clock;
		this.meters = meters;
		this.maxBodyBytes = maxBodyBytes;
	}

	/** {@code GET /iclock/cdata?SN=..&options=all&pushver=..&DeviceType=..} */
	@GetMapping("/cdata")
	public ResponseEntity<String> handshake(
			@RequestParam("SN") String serialNumber,
			@RequestParam(name = "pushver", required = false) String pushVersion,
			@RequestParam(name = "DeviceType", required = false) String deviceType,
			HttpServletRequest request) {
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return invalidSerial(serialNumber);
		}
		LocalDateTime now = clock.now();
		Optional<AttendanceDevice> device = devices.findBySerial(serialNumber);
		Optional<AttendanceDevice> configured = device.filter(AttendanceDevice::active);
		if (device.isPresent()) {
			// A deactivated device is still recorded as seen -- an operator
			// needs to know it is out there knocking -- but it is configured
			// like an unknown one below, so deactivating really does stop
			// every flow of information, not only uploads.
			devices.recordHandshake(
					device.get().id(), request.getRemoteAddr(),
					configured.isPresent() ? DeviceInput.bounded(pushVersion, 32) : null, now);
		} else {
			sightings.record(serialNumber, request.getRemoteAddr(),
					DeviceInput.bounded(pushVersion, 32), DeviceInput.bounded(deviceType, 64), now);
			meters.counter("devices.unclaimed.hits", "vendor", VENDOR).increment();
		}
		return ResponseEntity.ok(ZkTecoHandshake.response(serialNumber, configured, Instant.now()));
	}

	/**
	 * {@code POST /iclock/cdata?SN=..&table=ATTLOG|OPERLOG|options|...&Stamp=..}
	 *
	 * <p>Parameters come from {@link QueryParameters}, not {@code @RequestParam}:
	 * on a POST the latter reads the servlet parameter map, which for a
	 * form-urlencoded content type is built by consuming the body -- and this
	 * handler's body is the punch batch.
	 */
	@PostMapping("/cdata")
	public ResponseEntity<String> upload(HttpServletRequest request) throws IOException {
		Map<String, String> query = QueryParameters.parse(request.getQueryString());
		String serialNumber = query.get("SN");
		String table = query.get("table");
		String stamp = query.get("Stamp");
		if (serialNumber == null) {
			return missingSerial();
		}
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return invalidSerial(serialNumber);
		}
		String body = readBody(request);
		if (body == null) {
			return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("ERROR: body too large");
		}
		LocalDateTime now = clock.now();
		Optional<AttendanceDevice> found = devices.findBySerial(serialNumber);
		if (found.isEmpty()) {
			sightings.record(serialNumber, request.getRemoteAddr(), null, null, now);
			meters.counter("devices.unclaimed.hits", "vendor", VENDOR).increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(UNREGISTERED);
		}
		AttendanceDevice device = found.get();
		devices.touchSeen(device.id(), request.getRemoteAddr(), now);
		if (!device.active()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(UNREGISTERED);
		}
		String kind = table == null ? "" : table.toUpperCase(Locale.ROOT);
		return switch (kind) {
			case "ATTLOG" -> attlog(device, body, stamp);
			case "OPERLOG" -> operlog(device, body, now);
			case "OPTIONS" -> {
				ZkTecoOptionsUpload.SelfDescription self = ZkTecoOptionsUpload.parse(body);
				devices.recordSelfDescription(device.id(), self.model(), self.firmware(), self.pushVersion());
				yield ResponseEntity.ok(OK);
			}
			// Templates, photos and anything else the handshake did not ask
			// for: acknowledged so the terminal does not retry forever,
			// stored nowhere, body already forgotten.
			default -> {
				meters.counter("devices.uploads.discarded", "vendor", VENDOR, "table", metricTag(kind)).increment();
				LOG.warn("device {} uploaded table {} which this receiver does not accept; discarded",
						device.serialNumber(), DeviceInput.forLog(kind, 32));
				yield ResponseEntity.ok(OK);
			}
		};
	}

	private ResponseEntity<String> attlog(AttendanceDevice device, String body, String stamp) {
		ZkTecoAttlogParser.Result parsed = ZkTecoAttlogParser.parse(device.serialNumber(), body, device.zone());
		DevicePunchIngestionService.Outcome outcome = ingestion.ingest(device, parsed.events());
		if (parsed.malformed() > 0) {
			meters.counter("devices.punches.malformed", "vendor", VENDOR).increment(parsed.malformed());
			LOG.warn("device {} sent {} malformed ATTLOG line(s); quarantined, batch acknowledged",
					device.serialNumber(), parsed.malformed());
		}
		recordStamp(device, stamp, parsed.events().isEmpty());
		return ResponseEntity.ok(OK + ": " + outcome.accepted());
	}

	/**
	 * The stamp is the device's own resume bookmark and comes back to it in
	 * the next handshake, so a bad one is not cosmetic: a value with a CR/LF
	 * writes extra lines into that response, and a far-future value tells the
	 * real terminal the server already has everything -- it then uploads
	 * nothing, and the attendance is silently lost rather than visibly
	 * broken.
	 *
	 * <p>Two guards, because the requests are unauthenticated: the value must
	 * be digits, and it is only accepted from a delivery that actually
	 * carried punches, so an empty POST cannot move the bookmark at all.
	 */
	private void recordStamp(AttendanceDevice device, String stamp, boolean deliveryWasEmpty) {
		if (stamp == null || stamp.isBlank()) {
			return;
		}
		if (!DeviceInput.isValidStamp(stamp)) {
			meters.counter("devices.stamp.rejected", "vendor", VENDOR).increment();
			LOG.warn("device {} sent a malformed ATTLOG stamp {}; not recorded",
					device.serialNumber(), DeviceInput.forLog(stamp, 40));
			return;
		}
		if (deliveryWasEmpty) {
			meters.counter("devices.stamp.rejected", "vendor", VENDOR).increment();
			LOG.warn("device {} sent stamp {} on a delivery carrying no punches; not recorded",
					device.serialNumber(), DeviceInput.forLog(stamp, 40));
			return;
		}
		devices.recordAttlogStamp(device.id(), stamp);
	}

	private ResponseEntity<String> operlog(AttendanceDevice device, String body, LocalDateTime now) {
		ZkTecoOperlogFilter.Result filtered = ZkTecoOperlogFilter.filter(body);
		for (String line : filtered.operationLines()) {
			operationLogs.append(device.id(), device.companyId(), line, now);
		}
		if (filtered.biometricLinesDiscarded() > 0) {
			meters.counter("devices.biometric.discarded", "vendor", VENDOR).increment(filtered.biometricLinesDiscarded());
			LOG.warn("device {} uploaded {} biometric template line(s) despite TransFlag; discarded",
					device.serialNumber(), filtered.biometricLinesDiscarded());
		}
		return ResponseEntity.ok(OK + ": " + (filtered.operationLines().size() + filtered.userLines()
				+ filtered.biometricLinesDiscarded() + filtered.otherLines()));
	}

	/** {@code GET /iclock/getrequest?SN=..} -- the command poll, and therefore the heartbeat. */
	@GetMapping("/getrequest")
	public ResponseEntity<String> getRequest(@RequestParam("SN") String serialNumber, HttpServletRequest request) {
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return invalidSerial(serialNumber);
		}
		LocalDateTime now = clock.now();
		Optional<AttendanceDevice> device = devices.findBySerial(serialNumber);
		if (device.isPresent()) {
			devices.touchSeen(device.get().id(), request.getRemoteAddr(), now);
		} else {
			sightings.record(serialNumber, request.getRemoteAddr(), null, null, now);
		}
		// No command queue in this slice (design section 11, Slice C). The
		// same answer for a known, an inactive and an unknown serial, so this
		// route tells a caller nothing about which it is.
		return ResponseEntity.ok(OK);
	}

	/**
	 * {@code POST /iclock/devicecmd} -- {@code ID=..&Return=..&CMD=..};
	 * acknowledged and logged. Same body-preserving parameter read as
	 * {@link #upload}.
	 */
	@PostMapping("/devicecmd")
	public ResponseEntity<String> deviceCommandResult(HttpServletRequest request) throws IOException {
		String serialNumber = QueryParameters.parse(request.getQueryString()).get("SN");
		if (serialNumber == null) {
			return missingSerial();
		}
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return invalidSerial(serialNumber);
		}
		String body = readBody(request);
		if (body == null) {
			return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("ERROR: body too large");
		}
		LOG.info("device {} reported command result {}", serialNumber, DeviceInput.forLog(body.strip(), 200));
		return ResponseEntity.ok(OK);
	}

	/**
	 * Refused before the serial can reach a query or create a sighting row.
	 * Nothing of the rejected value is echoed back or persisted; the log line
	 * carries a sanitized, bounded copy so the operator can still see what
	 * arrived.
	 */
	private ResponseEntity<String> missingSerial() {
		meters.counter("devices.requests.rejected", "vendor", VENDOR).increment();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("ERROR: missing SN");
	}

	private ResponseEntity<String> invalidSerial(String serialNumber) {
		meters.counter("devices.requests.rejected", "vendor", VENDOR).increment();
		LOG.warn("rejected a device request whose SN is not a valid serial number: {}",
				DeviceInput.forLog(serialNumber, 80));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(INVALID_SERIAL);
	}

	private static String metricTag(String kind) {
		if (kind.isEmpty()) {
			return "none";
		}
		return KNOWN_TABLES.contains(kind) ? kind : OTHER_TABLE;
	}

	/**
	 * The body {@link DeviceRequestBodyFilter} captured before anything could
	 * consume it. The stream is only a fallback for a request that somehow
	 * arrived without passing that filter; null still means the cap was
	 * exceeded.
	 */
	private String readBody(HttpServletRequest request) throws IOException {
		Object captured = request.getAttribute(DeviceRequestBodyFilter.BODY_ATTRIBUTE);
		if (captured instanceof String body) {
			return body;
		}
		try (InputStream in = request.getInputStream()) {
			byte[] bytes = in.readNBytes(maxBodyBytes + 1);
			if (bytes.length > maxBodyBytes) {
				return null;
			}
			return new String(bytes, StandardCharsets.UTF_8);
		}
	}
}
