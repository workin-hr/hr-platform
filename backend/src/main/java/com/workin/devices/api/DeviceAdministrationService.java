package com.workin.devices.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.workin.backend.i18n.ApiException;
import com.workin.devices.agent.DeviceAgent;
import com.workin.devices.agent.DeviceAgentService;
import com.workin.devices.ingest.DeviceFileImportService;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.devices.registry.AttendanceDeviceStore;
import com.workin.legacy.LegacyClock;

/**
 * What the platform administrator's dashboard does with devices: watch every
 * company's terminals and agents, allocate a terminal that has reached the
 * platform, switch one off, import a USB export, and issue or revoke an agent.
 *
 * <p>Across companies by design -- the administrator's role -- so this has no
 * tenant parameter to trust or ignore. Who may call it is the dashboard's
 * decision; auditing and the surface flag belong to the caller's transaction.
 * Refusals are {@link ApiException}s carrying the same {@code devices.*} codes
 * the tenant API uses, so the two surfaces cannot disagree about what is valid.
 */
@Service
public class DeviceAdministrationService {

	/**
	 * The largest page any of these lists will serve.
	 *
	 * <p>It used to be the whole read: every list took a fixed cap and returned
	 * the first N rows with nothing saying there were more, so a page that said
	 * "Devices (500)" was indistinguishable from one that meant it. The cap is
	 * still here because a page size arrives from a query string and a boundary
	 * clamps what it is given, but it is now a clamp on one page rather than a
	 * silent limit on the truth.
	 */
	private static final int PAGE_LIMIT = 500;

	private final DeviceAdministrationStore store;
	private final AttendanceDeviceStore devices;
	private final DeviceManagementService management;
	private final DeviceFileImportService fileImport;
	private final DeviceAgentService agents;
	private final LegacyClock clock;

	public DeviceAdministrationService(DeviceAdministrationStore store, AttendanceDeviceStore devices,
			DeviceManagementService management, DeviceFileImportService fileImport, DeviceAgentService agents,
			LegacyClock clock) {
		this.store = store;
		this.devices = devices;
		this.management = management;
		this.fileImport = fileImport;
		this.agents = agents;
		this.clock = clock;
	}

	public List<Map<String, Object>> devices(Long companyId, int limit, long offset) {
		return store.devicePage(companyId, pageSize(limit), offset(offset));
	}

	public int deviceCount(Long companyId) {
		return store.deviceCount(companyId);
	}

	/** Unclaimed serials are nobody's, so the company filter does not apply to them. */
	public List<Map<String, Object>> sightings(int limit, long offset) {
		return store.sightings(pageSize(limit), offset(offset));
	}

	public int sightingCount() {
		return store.sightingCount();
	}

	public List<Map<String, Object>> punches(Long companyId, Long deviceId, int limit, long offset) {
		return store.punches(companyId, deviceId, pageSize(limit), offset(offset));
	}

	public int punchCount(Long companyId, Long deviceId) {
		return store.punchCount(companyId, deviceId);
	}

	public List<Map<String, Object>> punchCounts(long deviceId) {
		return store.punchCounts(deviceId);
	}

	public List<Map<String, Object>> malformed(long deviceId, int limit, long offset) {
		return store.malformed(deviceId, pageSize(limit), offset(offset));
	}

	public int malformedCount(long deviceId) {
		return store.malformedCount(deviceId);
	}

	public List<Map<String, Object>> branches(Long companyId) {
		return store.branches(companyId);
	}

	/** The device with its company and branch names, for display; empty when there is none. */
	public Map<String, Object> deviceRow(long id) {
		return store.device(id);
	}

	public Optional<AttendanceDevice> device(long id) {
		return devices.findById(id);
	}

	public List<DeviceAgent> agents(Long companyId, int limit, long offset) {
		return agents.list(companyId, pageSize(limit), offset(offset));
	}

	public int agentCount(Long companyId) {
		return agents.count(companyId);
	}

	/** The company is the branch's: the form names a branch, and a branch has exactly one owner. */
	public AttendanceDevice allocate(long branchId, String vendor, String serialNumber, String name, String zone) {
		Long companyId = branchId > 0 ? devices.branchCompanyId(branchId) : null;
		if (companyId == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "devices.branch_not_found");
		}
		return management.allocate(companyId, branchId, vendor, serialNumber, name, zone);
	}

	public AttendanceDevice setActive(long deviceId, boolean active) {
		AttendanceDevice device = requireDevice(deviceId);
		try {
			devices.update(device.companyId(), deviceId, null, null, null, active, clock.now());
		} catch (AttendanceDeviceStore.DeviceBranchMissingException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "devices.branch_no_longer_exists");
		}
		return requireDevice(deviceId);
	}

	public DeviceFileImportService.Result importAttlog(long deviceId, byte[] content) {
		return fileImport.importAttlog(requireDevice(deviceId), content);
	}

	public DeviceAgentService.IssuedAgent issueAgent(long companyId, String name) {
		if (companyId <= 0 || !store.companyExists(companyId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "devices.company_not_found");
		}
		try {
			return agents.issue(companyId, name);
		} catch (IllegalArgumentException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "devices.name_required");
		}
	}

	public DeviceAgent setAgentActive(long agentId, boolean active) {
		if (!agents.setActive(agentId, active)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "devices.agent_not_found");
		}
		return agents.find(agentId).orElseThrow();
	}

	/**
	 * A page size from a request, clamped. Zero or negative would make a page
	 * that can never advance, and an unbounded one is what this replaced.
	 */
	private static int pageSize(int limit) {
		return Math.clamp(limit, 1, PAGE_LIMIT);
	}

	/** A negative offset is a crafted page number, not a page before the first. */
	private static long offset(long offset) {
		return Math.max(0L, offset);
	}

	private AttendanceDevice requireDevice(long deviceId) {
		return devices.findById(deviceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "devices.not_found"));
	}
}
