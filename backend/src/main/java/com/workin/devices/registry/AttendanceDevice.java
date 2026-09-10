package com.workin.devices.registry;

import java.time.ZoneId;

/**
 * One row of {@code attendance_devices}. Temporal columns are carried as the
 * text MariaDB returns, matching the adapter-wide rule that legacy DATETIME
 * values never pass through JDBC temporal objects.
 */
public record AttendanceDevice(
		long id,
		long companyId,
		long branchId,
		String vendor,
		String serialNumber,
		String name,
		String model,
		String firmware,
		String pushVersion,
		String deviceTimeZone,
		boolean active,
		String lastSeenAt,
		String lastHandshakeAt,
		String lastAttlogStamp,
		String lastSeenIp,
		String createdAt,
		String updatedAt) {

	/**
	 * The zone this device's clock is set to, as validated at claim time.
	 * Throws rather than defaulting: a row holding something unparseable is a
	 * validation failure that did not happen, and silently choosing UTC would
	 * shift every punch of this device instead of failing visibly.
	 */
	public ZoneId zone() {
		return ZoneId.of(deviceTimeZone);
	}
}
