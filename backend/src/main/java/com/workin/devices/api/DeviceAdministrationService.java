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

	private static final int LIST_LIMIT = 500;

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

	public List<Map<String, Object>> devices(Long companyId) {
		return store.devices(companyId, LIST_LIMIT);
	}

	/** Unclaimed serials are nobody's, so the company filter does not apply to them. */
	public List<Map<String, Object>> sightings() {
		return store.sightings(200);
	}

	public List<Map<String, Object>> punches(Long companyId, Long deviceId, int limit) {
		return store.punches(companyId, deviceId, Math.max(1, Math.min(limit, 500)));
	}

	public List<Map<String, Object>> punchCounts(long deviceId) {
		return store.punchCounts(deviceId);
	}

	public List<Map<String, Object>> malformed(long deviceId) {
		return store.malformed(deviceId, 100);
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

	public List<DeviceAgent> agents(Long companyId) {
		return agents.list(companyId);
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

	private AttendanceDevice requireDevice(long deviceId) {
		return devices.findById(deviceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "devices.not_found"));
	}
}
