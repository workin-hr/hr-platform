package com.workin.devices.agentapi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.workin.devices.DeviceDelivery;
import com.workin.devices.DeviceInput;
import com.workin.devices.DeviceVendor;
import com.workin.devices.agent.DeviceAgent;
import com.workin.devices.agent.DeviceAgentStore;
import com.workin.devices.ingest.DeviceMalformedPunchStore;
import com.workin.devices.ingest.DevicePunchIngestionService;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.devices.registry.AttendanceDeviceStore;
import com.workin.devices.registry.UnclaimedDeviceSightingStore;
import com.workin.devices.zkteco.ZkTecoAttlogParser;
import com.workin.legacy.LegacyClock;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * What an on-premises agent may do: deliver punches it read from a terminal,
 * and report which terminals it can reach.
 *
 * <h2>Trust model</h2>
 * <p>The agent is authenticated, the terminal behind it is not. An agent speaks
 * for one company, so a serial it names is accepted only when the registry
 * already binds that serial to the same company and the device is active; the
 * registry row, never the agent, supplies the branch and zone. Any other serial
 * -- unknown, deactivated, or another company's -- gets the same answer, so a
 * token for one company cannot be used to learn which serials another owns.
 *
 * <p>Agents submit the ZKTeco ATTLOG line shape whatever the terminal's vendor,
 * so this reuses the receiver's parser and its dedup key: the same scan pushed
 * by the terminal and read again by an agent is one punch.
 */
@Service
@ConditionalOnProperty(name = "app.devices.agents.enabled", havingValue = "true")
public class DeviceAgentIngestService {

	private static final Logger LOG = LoggerFactory.getLogger(DeviceAgentIngestService.class);

	/** Enough for a branch; a report naming more is truncated rather than refused. */
	static final int MAX_REPORTED_DEVICES = 50;

	public enum Status { OK, INVALID_SERIAL, NOT_REGISTERED, TOO_MANY_RECORDS }

	public record Submission(Status status, int accepted, int stored, int duplicates, int unmatched, int malformed) {

		static Submission refused(Status status) {
			return new Submission(status, 0, 0, 0, 0, 0);
		}
	}

	/** @param registered false for unknown, deactivated and other companies' serials alike */
	public record ReportedDevice(String serialNumber, boolean registered) {
	}

	private final AttendanceDeviceStore devices;
	private final UnclaimedDeviceSightingStore sightings;
	private final DevicePunchIngestionService ingestion;
	private final DeviceMalformedPunchStore malformedPunches;
	private final DeviceAgentStore agents;
	private final LegacyClock clock;
	private final MeterRegistry meters;
	private final ObjectMapper json;
	private final int maxRecordsPerUpload;

	public DeviceAgentIngestService(
			AttendanceDeviceStore devices, UnclaimedDeviceSightingStore sightings,
			DevicePunchIngestionService ingestion, DeviceMalformedPunchStore malformedPunches,
			DeviceAgentStore agents, LegacyClock clock, MeterRegistry meters, ObjectMapper json,
			@Value("${app.devices.ingest.max-records-per-upload}") int maxRecordsPerUpload) {
		this.devices = devices;
		this.sightings = sightings;
		this.ingestion = ingestion;
		this.malformedPunches = malformedPunches;
		this.agents = agents;
		this.clock = clock;
		this.meters = meters;
		this.json = json;
		this.maxRecordsPerUpload = maxRecordsPerUpload;
	}

	/**
	 * @param delivery {@link DeviceDelivery#AGENT} for what the agent read from a
	 *        terminal, {@link DeviceDelivery#FILE} for a USB export an operator
	 *        handed it; nothing else is an agent's to claim
	 */
	public Submission submit(DeviceAgent agent, String serialNumber, String body, String ip, DeviceDelivery delivery) {
		if (delivery != DeviceDelivery.AGENT && delivery != DeviceDelivery.FILE) {
			throw new IllegalArgumentException("an agent delivers as AGENT or FILE, not " + delivery);
		}
		if (!DeviceInput.isValidSerialNumber(serialNumber)) {
			return Submission.refused(Status.INVALID_SERIAL);
		}
		LocalDateTime now = clock.now();
		agents.recordContact(agent.id(), ip, now);
		Optional<AttendanceDevice> found = ownActiveDevice(agent, serialNumber);
		if (found.isEmpty()) {
			meters.counter("devices.agent.refused", "reason", "not_registered").increment();
			LOG.warn("agent {} (company {}) submitted for serial {}, which is not an active device of that company",
					agent.id(), agent.companyId(), DeviceInput.forLog(serialNumber, 64));
			return Submission.refused(Status.NOT_REGISTERED);
		}
		AttendanceDevice device = found.get();
		if (DeviceInput.exceedsRecordCount(body, maxRecordsPerUpload)) {
			meters.counter("devices.agent.refused", "reason", "too_many_records").increment();
			return Submission.refused(Status.TOO_MANY_RECORDS);
		}
		devices.touchSeen(device.id(), ip, now);
		ZkTecoAttlogParser.Result parsed = ZkTecoAttlogParser.parse(device.serialNumber(), body, device.zone());
		DevicePunchIngestionService.Outcome outcome = ingestion.ingest(device, parsed.events(), delivery);
		if (parsed.malformed() > 0) {
			// Before the answer: the agent marks the batch delivered on a 200,
			// and these lines would otherwise exist nowhere once it prunes its spool.
			malformedPunches.quarantine(device.id(), device.companyId(), parsed.malformedLines(), now);
			meters.counter("devices.punches.malformed", "vendor", device.vendor()).increment(parsed.malformed());
		}
		return new Submission(Status.OK, outcome.accepted() + parsed.malformed(), outcome.stored(),
				outcome.duplicates(), outcome.unmatched(), parsed.malformed());
	}

	/**
	 * Records what the agent says it can reach. Nothing here decides anything
	 * about punches: a terminal the agent reports is refreshed only if it is
	 * already this company's, and one nobody has registered becomes a sighting a
	 * platform administrator can allocate.
	 */
	/** Below TEXT's 65,535 bytes, which a non-strict database would otherwise truncate into invalid JSON. */
	static final int MAX_REPORT_BYTES = 60_000;

	public List<ReportedDevice> heartbeat(DeviceAgent agent, Map<String, Object> body, String ip) {
		LocalDateTime now = clock.now();
		List<Map<String, Object>> kept = new ArrayList<>();
		List<ReportedDevice> answer = new ArrayList<>();
		Object reported = body == null ? null : body.get("devices");
		if (reported instanceof List<?> entries) {
			for (Object entry : entries) {
				if (kept.size() >= MAX_REPORTED_DEVICES) {
					break;
				}
				if (!(entry instanceof Map<?, ?> fields)) {
					continue;
				}
				String serial = text(fields.get("serial"));
				if (!DeviceInput.isValidSerialNumber(serial)) {
					continue;
				}
				Map<String, Object> clean = sanitise(serial, fields);
				kept.add(clean);
				answer.add(new ReportedDevice(serial, refresh(agent, serial, clean, ip, now)));
			}
		}
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("received_at", now.toString());
		report.put("devices", kept);
		String stored = json.writeValueAsString(report);
		while (stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_REPORT_BYTES && !kept.isEmpty()) {
			kept.remove(kept.size() - 1);
			stored = json.writeValueAsString(report);
		}
		agents.recordHeartbeat(agent.id(), ip, DeviceInput.bounded(text(body == null ? null : body.get("agent_version")), 32),
				stored, now);
		return answer;
	}

	private boolean refresh(DeviceAgent agent, String serial, Map<String, Object> clean, String ip, LocalDateTime now) {
		Optional<AttendanceDevice> registered = devices.findBySerial(serial);
		if (registered.isPresent()) {
			AttendanceDevice device = registered.get();
			if (device.companyId() != agent.companyId() || !device.active()) {
				return false;
			}
			if (Boolean.TRUE.equals(clean.get("reachable"))) {
				devices.touchSeen(device.id(), ip, now);
				devices.recordSelfDescription(device.id(), (String) clean.get("model"), (String) clean.get("firmware"), null);
			}
			return true;
		}
		// Says which agent and company reported it. A sighting is evidence an administrator allocates
		// by, and an agent can report any serial on its LAN -- including another company's terminal
		// that has not been allocated yet -- so the dashboard has to show whose word it is.
		Object vendor = clean.get("vendor");
		sightings.record(serial, ip, null, DeviceInput.bounded(
				"agent " + agent.id() + " of company " + agent.companyId() + (vendor == null ? "" : " (" + vendor + ")"), 64),
				now);
		return false;
	}

	/** Only known fields, each bounded, so the stored report is what this class wrote rather than what was sent. */
	private static Map<String, Object> sanitise(String serial, Map<?, ?> fields) {
		Map<String, Object> clean = new LinkedHashMap<>();
		clean.put("serial", serial);
		clean.put("vendor", DeviceVendor.fromCode(text(fields.get("vendor"))).map(DeviceVendor::code).orElse(null));
		clean.put("reachable", Boolean.TRUE.equals(fields.get("reachable")));
		clean.put("model", DeviceInput.bounded(text(fields.get("model")), 100));
		clean.put("firmware", DeviceInput.bounded(text(fields.get("firmware")), 100));
		clean.put("platform", DeviceInput.bounded(text(fields.get("platform")), 64));
		clean.put("records", whole(fields.get("records")));
		clean.put("record_capacity", whole(fields.get("record_capacity")));
		clean.put("users", whole(fields.get("users")));
		clean.put("device_time", DeviceInput.bounded(text(fields.get("device_time")), 32));
		clean.put("error", DeviceInput.bounded(DeviceInput.forLog(text(fields.get("error")), 200), 200));
		return clean;
	}

	private Optional<AttendanceDevice> ownActiveDevice(DeviceAgent agent, String serialNumber) {
		return devices.findBySerial(serialNumber)
				.filter(device -> device.companyId() == agent.companyId() && device.active());
	}

	private static Long whole(Object value) {
		if (value instanceof Integer || value instanceof Long || value instanceof Short) {
			long number = ((Number) value).longValue();
			return number >= 0 ? number : null;
		}
		return null;
	}

	private static String text(Object value) {
		return value instanceof String string ? string : null;
	}
}
