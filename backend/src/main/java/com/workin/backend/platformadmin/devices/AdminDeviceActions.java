package com.workin.backend.platformadmin.devices;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.backend.i18n.ApiException;
import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.devices.agent.DeviceAgent;
import com.workin.devices.agent.DeviceAgentService;
import com.workin.devices.api.DeviceAdministrationService;
import com.workin.devices.ingest.DeviceFileImportService;
import com.workin.devices.registry.AttendanceDevice;

/**
 * The administrator's writes on devices and agents: the surface flag, then the
 * write and its audit row in one transaction. The device module's own
 * transactions join this one rather than committing on their own
 * ({@link com.workin.devices.DeviceTransactions}).
 *
 * <p>A file import is the exception, and deliberately so. It stores in bounded
 * batches, each committed on its own, so a large USB export is not one long
 * lock on the device; the audit row is therefore written first, in its own
 * transaction, as "an import was started". An import that fails part-way leaves
 * that row and the batches it finished -- which is what happened -- rather than
 * no record of punches that are nonetheless stored.
 */
@Service
public class AdminDeviceActions {

	/** Why a write was refused; {@code code} is the {@code devices.*} key or {@code admin_actions_disabled}. */
	public static class RefusedException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public RefusedException(String code) {
			super(code);
		}

		public String code() {
			return getMessage();
		}
	}

	private static final String DEVICE = "DEVICE";

	private static final String AGENT = "DEVICE_AGENT";

	private final DeviceAdministrationService devices;
	private final PlatformAdminAuditService audit;
	private final TransactionTemplate transactions;
	private final boolean actionsEnabled;

	public AdminDeviceActions(DeviceAdministrationService devices, PlatformAdminAuditService audit,
			@Qualifier("legacyTransactionManager") PlatformTransactionManager transactionManager,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.devices = devices;
		this.audit = audit;
		this.transactions = new TransactionTemplate(transactionManager);
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return actionsEnabled;
	}

	public AttendanceDevice allocate(long adminId, long branchId, String vendor, String serial, String name, String zone) {
		return write(() -> {
			AttendanceDevice device = devices.allocate(branchId, vendor, serial, name, zone);
			audit.recordAction(adminId, PlatformAdminAuditEventType.DEVICE_ALLOCATED, DEVICE, String.valueOf(device.id()),
					device.vendor() + " " + device.serialNumber() + " allocated to company " + device.companyId()
							+ ", branch " + device.branchId() + ", zone " + device.deviceTimeZone());
			return device;
		});
	}

	public AttendanceDevice setActive(long adminId, long deviceId, boolean active) {
		return write(() -> {
			AttendanceDevice device = devices.setActive(deviceId, active);
			audit.recordAction(adminId, PlatformAdminAuditEventType.DEVICE_UPDATED, DEVICE, String.valueOf(deviceId),
					(active ? "activated " : "deactivated ") + device.serialNumber() + " in company " + device.companyId());
			return device;
		});
	}

	public DeviceFileImportService.Result importAttlog(long adminId, long deviceId, String fileName, byte[] content) {
		AttendanceDevice device = write(() -> {
			AttendanceDevice found = devices.device(deviceId)
					.orElseThrow(() -> new RefusedException("devices.not_found"));
			audit.recordAction(adminId, PlatformAdminAuditEventType.DEVICE_PUNCHES_IMPORTED, DEVICE,
					String.valueOf(deviceId), "import of " + content.length + " bytes started for "
							+ found.serialNumber() + " in company " + found.companyId() + " from "
							+ (fileName == null ? "an unnamed file" : fileName.replaceAll("\\p{Cntrl}", "?")));
			return found;
		});
		try {
			return devices.importAttlog(device.id(), content);
		} catch (ApiException ex) {
			throw new RefusedException(ex.getCode());
		}
	}

	public DeviceAgentService.IssuedAgent issueAgent(long adminId, long companyId, String name) {
		return write(() -> {
			DeviceAgentService.IssuedAgent issued = devices.issueAgent(companyId, name);
			audit.recordAction(adminId, PlatformAdminAuditEventType.DEVICE_AGENT_ISSUED, AGENT,
					String.valueOf(issued.agent().id()), "agent '" + issued.agent().name() + "' issued for company "
							+ companyId + ", token ending " + issued.agent().tokenHint());
			return issued;
		});
	}

	public DeviceAgent setAgentActive(long adminId, long agentId, boolean active) {
		return write(() -> {
			DeviceAgent agent = devices.setAgentActive(agentId, active);
			audit.recordAction(adminId, PlatformAdminAuditEventType.DEVICE_AGENT_UPDATED, AGENT, String.valueOf(agentId),
					(active ? "re-enabled" : "revoked") + " agent '" + agent.name() + "' of company " + agent.companyId());
			return agent;
		});
	}

	private <T> T write(java.util.function.Supplier<T> action) {
		if (!actionsEnabled) {
			throw new RefusedException("admin_actions_disabled");
		}
		try {
			return transactions.execute(status -> action.get());
		} catch (ApiException ex) {
			throw new RefusedException(ex.getCode());
		}
	}
}
