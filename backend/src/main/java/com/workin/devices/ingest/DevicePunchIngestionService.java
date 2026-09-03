package com.workin.devices.ingest;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.devices.DeviceInput;
import com.workin.devices.identity.EmployeeDeviceIdentityStore;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.legacy.LegacyClock;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The vendor-agnostic half of ingestion: given a claimed device and the
 * events an adapter translated, resolve the PINs inside that device's
 * company, derive the instant from the device's wall clock, and append
 * idempotently. The tenant comes from the device row and nowhere else -- an
 * adapter cannot pass one in.
 */
@Service
public class DevicePunchIngestionService {

	private static final Logger LOG = LoggerFactory.getLogger(DevicePunchIngestionService.class);

	private final DevicePunchStore punches;
	private final EmployeeDeviceIdentityStore identities;
	private final LegacyClock clock;
	private final MeterRegistry meters;

	public DevicePunchIngestionService(
			DevicePunchStore punches, EmployeeDeviceIdentityStore identities, LegacyClock clock,
			MeterRegistry meters) {
		this.punches = punches;
		this.identities = identities;
		this.clock = clock;
		this.meters = meters;
	}

	/** Counts a receiver reports back and a dashboard graphs. */
	public record Outcome(int stored, int duplicates, int unmatched, int rejected) {

		/**
		 * What the device is told it delivered. A rejected row is deliberately
		 * counted as accepted: the device can never produce a storable version
		 * of it, so leaving it unacknowledged would only make it re-send the
		 * batch forever.
		 */
		public int accepted() {
			return stored + duplicates + rejected;
		}
	}

	public Outcome ingest(AttendanceDevice device, List<DeviceAttendanceEvent> events) {
		if (events.isEmpty()) {
			return new Outcome(0, 0, 0, 0);
		}
		ZoneId zone = device.zone();
		LocalDateTime receivedAt = clock.now();
		Set<String> pins = new LinkedHashSet<>();
		for (DeviceAttendanceEvent event : events) {
			pins.add(event.pin());
		}
		// One resolution for the whole delivery. A terminal returning from an
		// outage sends its entire buffer at once, so doing this per punch is
		// the difference between two queries and two thousand.
		Map<String, Long> byPin = identities.resolveEmployeeIds(device.companyId(), pins);

		int stored = 0;
		int duplicates = 0;
		int unmatched = 0;
		int rejected = 0;
		for (DeviceAttendanceEvent event : events) {
			Long employeeId = byPin.get(event.pin());
			String state = employeeId != null ? DevicePunchStore.STATE_RECEIVED : DevicePunchStore.STATE_UNMATCHED;
			LocalDateTime utc = toUtc(event, zone);
			switch (punches.insert(
					device.id(), device.companyId(), device.branchId(), employeeId, event, utc, receivedAt, state)) {
				case STORED -> {
					stored++;
					if (employeeId == null) {
						unmatched++;
					}
				}
				case DUPLICATE -> duplicates++;
				case REJECTED -> {
					rejected++;
					LOG.error("device {} punch for PIN {} at {} was refused by the database and is not stored: {}",
							device.serialNumber(), DeviceInput.forLog(event.pin(), 32), event.punchedAtLocal(),
							DeviceInput.forLog(event.rawLine(), 200));
				}
			}
		}
		String vendor = device.vendor();
		meters.counter("devices.punches.stored", "vendor", vendor).increment(stored);
		meters.counter("devices.punches.duplicate", "vendor", vendor).increment(duplicates);
		meters.counter("devices.punches.unmatched", "vendor", vendor).increment(unmatched);
		meters.counter("devices.punches.rejected", "vendor", vendor).increment(rejected);
		if (unmatched > 0) {
			LOG.warn("device {} (company {}) sent {} punch(es) for PINs bound to no active employee",
					device.serialNumber(), device.companyId(), unmatched);
		}
		return new Outcome(stored, duplicates, unmatched, rejected);
	}

	/**
	 * The device's wall clock to an instant.
	 *
	 * <p>A device that reported an <b>instant</b> needs no zone rule at all,
	 * and gets none: its value is used directly, which is also what keeps the
	 * two punches of an autumn overlap distinct.
	 *
	 * <p>For the wall-clock form, {@code punched_at_local} is the
	 * authoritative value and is stored exactly as the device reported it;
	 * this column is the derived one, and it is the only place a zone rule can
	 * bite. Two cases have no lossless answer, both from daylight saving in a
	 * device's IANA zone: a wall clock
	 * inside a spring-forward gap does not exist and {@code atZone} moves it
	 * forward by the gap, and one inside an autumn overlap is ambiguous and
	 * {@code atZone} resolves it to the earlier offset. Both are accepted
	 * deliberately rather than rejected -- refusing the punch would lose a
	 * real attendance record over a calendar artefact, and the local time,
	 * which is what pairing and the reports use, stays correct either way.
	 */
	static LocalDateTime toUtc(DeviceAttendanceEvent event, ZoneId zone) {
		return event.punchedAtInstant() != null
				? LocalDateTime.ofInstant(event.punchedAtInstant(), ZoneOffset.UTC)
				: toUtc(event.punchedAtLocal(), zone);
	}

	static LocalDateTime toUtc(LocalDateTime punchedAtLocal, ZoneId zone) {
		return punchedAtLocal.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

}
