package com.workin.devices.zkteco;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.workin.devices.DeviceInput;
import com.workin.devices.DeviceVendor;
import com.workin.devices.ingest.DeviceOperationLogStore;
import com.workin.devices.ingest.DevicePunchIngestionService;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.devices.registry.AttendanceDeviceStore;
import com.workin.devices.registry.UnclaimedDeviceSightingStore;
import com.workin.legacy.LegacyClock;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * What the receiver does with a device request, independent of how that
 * request arrived. {@link ZkTecoAdmsController} owns the HTTP: reading the
 * query string, taking the captured body, and turning a {@link Status} into a
 * status code. Everything that decides an outcome lives here.
 *
 * <h2>Trust model</h2>
 * <p>The serial number is an identifier, not a credential. It is validated
 * for shape, then resolved against the registry; the registry row supplies
 * the tenant and branch, and nothing the payload asserts about itself is
 * believed. An unclaimed <em>or deactivated</em> serial gets a neutral
 * handshake carrying no stamp and no zone, is recorded as a sighting, and has
 * every upload refused, so it keeps buffering and nothing of it is stored.
 *
 * <p>Three consequences of "unauthenticated" are handled explicitly rather
 * than left to chance: a serial is bounded and shape-checked <em>before</em>
 * it can reach a query or create a row (the legacy database is non-strict and
 * would truncate an over-long one into a collision with another device); every
 * value that reaches a metric tag comes from a closed set, never from the
 * request; and anything logged is control-character-stripped and bounded, so a
 * caller cannot forge or flood log lines.
 */
@Service
@ConditionalOnProperty(name = "app.devices.ingest.enabled", havingValue = "true")
public class ZkTecoAdmsService {

	private static final Logger LOG = LoggerFactory.getLogger(ZkTecoAdmsService.class);

	static final String VENDOR = DeviceVendor.ZKTECO.code();

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

	/** Every way a device request can end, before it becomes a status code. */
	public enum Status { OK, INVALID_SERIAL, UNREGISTERED, TOO_MANY_RECORDS }

	/**
	 * @param configured the device whose settings the reply should carry --
	 *        empty for an unknown <em>or</em> deactivated serial, which is
	 *        what makes those two indistinguishable to a caller
	 */
	public record Handshake(Status status, Optional<AttendanceDevice> configured) {
	}

	/** @param acknowledged how many records to report back, or null for a bare acknowledgement */
	public record Upload(Status status, Integer acknowledged) {
	}

	private final AttendanceDeviceStore devices;
	private final UnclaimedDeviceSightingStore sightings;
	private final DevicePunchIngestionService ingestion;
	private final DeviceOperationLogStore operationLogs;
	private final LegacyClock clock;
	private final MeterRegistry meters;

	/**
	 * A byte cap alone does not bound the work one request creates: a body of
	 * minimal lines is tens of thousands of records, and each becomes its own
	 * statement. Rate limiting at the proxy cannot see that amplification --
	 * it counts one request.
	 */
	private final int maxRecordsPerUpload;

	public ZkTecoAdmsService(
			AttendanceDeviceStore devices, UnclaimedDeviceSightingStore sightings,
			DevicePunchIngestionService ingestion, DeviceOperationLogStore operationLogs, LegacyClock clock,
			MeterRegistry meters,
			@Value("${app.devices.ingest.max-records-per-upload}") int maxRecordsPerUpload) {
		this.devices = devices;
		this.sightings = sightings;
		this.ingestion = ingestion;
		this.operationLogs = operationLogs;
		this.clock = clock;
		this.meters = meters;
		this.maxRecordsPerUpload = maxRecordsPerUpload;
	}

	/** The device asking for its operating options. */
	public Handshake handshake(String serialNumber, String pushVersion, String deviceType, String ip) {
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return new Handshake(rejectSerial(serialNumber), Optional.empty());
		}
		LocalDateTime now = clock.now();
		Optional<AttendanceDevice> device = devices.findBySerial(serialNumber);
		Optional<AttendanceDevice> configured = device.filter(AttendanceDevice::active);
		if (device.isPresent()) {
			// A deactivated device is still recorded as seen -- an operator
			// needs to know it is out there knocking -- but it is configured
			// like an unknown one, so deactivating really does stop every flow
			// of information, not only uploads.
			devices.recordHandshake(
					device.get().id(), ip,
					configured.isPresent() ? DeviceInput.bounded(pushVersion, 32) : null, now);
		} else {
			recordSighting(serialNumber, ip, DeviceInput.bounded(pushVersion, 32), DeviceInput.bounded(deviceType, 64), now);
		}
		return new Handshake(Status.OK, configured);
	}

	/** A device delivering one of its tables. */
	public Upload upload(String serialNumber, String table, String stamp, String body, String ip) {
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return new Upload(rejectSerial(serialNumber), null);
		}
		LocalDateTime now = clock.now();
		Optional<AttendanceDevice> found = devices.findBySerial(serialNumber);
		if (found.isEmpty()) {
			recordSighting(serialNumber, ip, null, null, now);
			return new Upload(Status.UNREGISTERED, null);
		}
		AttendanceDevice device = found.get();
		devices.touchSeen(device.id(), ip, now);
		if (!device.active()) {
			return new Upload(Status.UNREGISTERED, null);
		}
		String kind = table == null ? "" : table.toUpperCase(Locale.ROOT);
		return switch (kind) {
			case "ATTLOG" -> attlog(device, body, stamp);
			case "OPERLOG" -> operlog(device, body, now);
			case "OPTIONS" -> {
				ZkTecoOptionsUpload.SelfDescription self = ZkTecoOptionsUpload.parse(body);
				devices.recordSelfDescription(device.id(), self.model(), self.firmware(), self.pushVersion());
				yield new Upload(Status.OK, null);
			}
			// Templates, photos and anything else the handshake did not ask
			// for: acknowledged so the terminal does not retry forever,
			// stored nowhere, body already forgotten.
			default -> {
				meters.counter("devices.uploads.discarded", "vendor", VENDOR, "table", metricTag(kind)).increment();
				LOG.warn("device {} uploaded table {} which this receiver does not accept; discarded",
						device.serialNumber(), DeviceInput.forLog(kind, 32));
				yield new Upload(Status.OK, null);
			}
		};
	}

	/** The command poll, and therefore the heartbeat. */
	public Status poll(String serialNumber, String ip) {
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return rejectSerial(serialNumber);
		}
		LocalDateTime now = clock.now();
		Optional<AttendanceDevice> device = devices.findBySerial(serialNumber);
		if (device.isPresent()) {
			devices.touchSeen(device.get().id(), ip, now);
		} else {
			recordSighting(serialNumber, ip, null, null, now);
		}
		// No command queue in this slice (design section 11, Slice C). The
		// same answer for a known, an inactive and an unknown serial, so this
		// route tells a caller nothing about which it is.
		return Status.OK;
	}

	/** A device reporting what it did with a queued command. */
	public Status commandResult(String serialNumber, String body) {
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return rejectSerial(serialNumber);
		}
		LOG.info("device {} reported command result {}",
				DeviceInput.forLog(serialNumber, 64), DeviceInput.forLog(body.strip(), 200));
		return Status.OK;
	}

	private Upload attlog(AttendanceDevice device, String body, String stamp) {
		if (exceedsRecordCap(body)) {
			return tooManyRecords(device, "ATTLOG");
		}
		ZkTecoAttlogParser.Result parsed = ZkTecoAttlogParser.parse(device.serialNumber(), body, device.zone());
		DevicePunchIngestionService.Outcome outcome = ingestion.ingest(device, parsed.events());
		if (parsed.malformed() > 0) {
			meters.counter("devices.punches.malformed", "vendor", VENDOR).increment(parsed.malformed());
			LOG.warn("device {} sent {} malformed ATTLOG line(s); quarantined, batch acknowledged",
					device.serialNumber(), parsed.malformed());
		}
		recordStamp(device, stamp, parsed.events().isEmpty());
		return new Upload(Status.OK, outcome.accepted());
	}

	private Upload operlog(AttendanceDevice device, String body, LocalDateTime now) {
		if (exceedsRecordCap(body)) {
			return tooManyRecords(device, "OPERLOG");
		}
		ZkTecoOperlogFilter.Result filtered = ZkTecoOperlogFilter.filter(body);
		operationLogs.append(device.id(), device.companyId(), filtered.operationLines(), now);
		if (filtered.biometricLinesDiscarded() > 0) {
			meters.counter("devices.biometric.discarded", "vendor", VENDOR)
					.increment(filtered.biometricLinesDiscarded());
			LOG.warn("device {} uploaded {} biometric template line(s) despite TransFlag; discarded",
					device.serialNumber(), filtered.biometricLinesDiscarded());
		}
		return new Upload(Status.OK, filtered.operationLines().size() + filtered.userLines()
				+ filtered.biometricLinesDiscarded() + filtered.otherLines());
	}

	/**
	 * The stamp is recorded as a diagnostic and never returned to the device
	 * ({@link ZkTecoHandshake#ALWAYS_RESEND}), so a bad one costs nothing --
	 * but it is still refused rather than stored, because a value nobody can
	 * explain is worth seeing in a counter.
	 */
	private void recordStamp(AttendanceDevice device, String stamp, boolean deliveryWasEmpty) {
		if (stamp == null || stamp.isBlank()) {
			return;
		}
		if (!DeviceInput.isValidStamp(stamp) || deliveryWasEmpty) {
			meters.counter("devices.stamp.rejected", "vendor", VENDOR).increment();
			LOG.warn("device {} sent stamp {} that was {}; not recorded", device.serialNumber(),
					DeviceInput.forLog(stamp, 40),
					DeviceInput.isValidStamp(stamp) ? "on a delivery carrying no punches" : "malformed");
			return;
		}
		devices.recordAttlogStamp(device.id(), stamp);
	}

	/**
	 * Counts line breaks rather than parsing first: the point is to refuse the
	 * work before doing it. Refused, not truncated -- silently keeping the
	 * first N records of a batch the device believes was delivered in full is
	 * how punches disappear.
	 */
	private boolean exceedsRecordCap(String body) {
		int records = 0;
		int lineLength = 0;
		for (int index = 0; index < body.length(); index++) {
			char character = body.charAt(index);
			if (character == '\n') {
				// A line's own terminator does not make it two records, and the
				// trailing one at the end of a batch makes it none: counting
				// separators instead would refuse a device that always sends
				// exactly the maximum, and it would then retry that same batch
				// forever.
				if (lineLength > 0 && ++records > maxRecordsPerUpload) {
					return true;
				}
				lineLength = 0;
			} else if (character != '\r') {
				lineLength++;
			}
		}
		return lineLength > 0 && records + 1 > maxRecordsPerUpload;
	}

	private Upload tooManyRecords(AttendanceDevice device, String table) {
		meters.counter("devices.uploads.oversized", "vendor", VENDOR, "table", table).increment();
		LOG.warn("device {} sent a {} upload above the {}-record cap; refused so it re-sends in smaller batches",
				device.serialNumber(), table, maxRecordsPerUpload);
		return new Upload(Status.TOO_MANY_RECORDS, null);
	}

	private void recordSighting(String serialNumber, String ip, String pushVersion, String deviceType,
			LocalDateTime now) {
		sightings.record(serialNumber, ip, pushVersion, deviceType, now);
		meters.counter("devices.unclaimed.hits", "vendor", VENDOR).increment();
	}

	/** Refused before the serial can reach a query or create a row. */
	private Status rejectSerial(String serialNumber) {
		meters.counter("devices.requests.rejected", "vendor", VENDOR).increment();
		LOG.warn("rejected a device request whose SN is not a valid serial number: {}",
				DeviceInput.forLog(serialNumber, 80));
		return Status.INVALID_SERIAL;
	}

	private static String metricTag(String kind) {
		if (kind.isEmpty()) {
			return "none";
		}
		return KNOWN_TABLES.contains(kind) ? kind : OTHER_TABLE;
	}
}
