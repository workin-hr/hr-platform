package com.workin.devices.registry;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.legacy.LegacyJdbcValues;

/**
 * Serials knocking on the receiver before anyone has claimed them. Global
 * by nature -- no tenant owns an unclaimed serial -- which is why the only
 * tenant-facing read is {@link #lookup an exact-serial lookup}: a caller has
 * to already know the serial printed on the unit to learn anything.
 */
@Component
public class UnclaimedDeviceSightingStore {

	private final JdbcTemplate jdbcTemplate;

	public UnclaimedDeviceSightingStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * How long a serial nobody claimed stays on record.
	 *
	 * <p>Every syntactically valid serial sent to the public routes creates a
	 * row here, and a claim is the only thing that removes one -- so without
	 * an expiry a slow, distributed probe could grow this table indefinitely
	 * without ever knowing a real serial. Rate limiting at the edge bounds how
	 * fast rows arrive, not how many accumulate.
	 */
	private static final int RETENTION_DAYS = 30;

	public void record(String serialNumber, String ip, String pushVersion, String deviceType, LocalDateTime now) {
		String stamp = DeviceAttendanceEvent.SQL_DATE_TIME.format(now);
		int affected = jdbcTemplate.update("""
				INSERT INTO unclaimed_device_sightings
				  (serial_number, first_seen_at, last_seen_at, last_seen_ip, push_version, device_type, hit_count)
				VALUES (?, ?, ?, ?, ?, ?, 1)
				ON DUPLICATE KEY UPDATE
				  last_seen_at = VALUES(last_seen_at),
				  last_seen_ip = VALUES(last_seen_ip),
				  push_version = COALESCE(VALUES(push_version), push_version),
				  device_type = COALESCE(VALUES(device_type), device_type),
				  hit_count = hit_count + 1""",
				serialNumber, stamp, stamp, ip, pushVersion, deviceType);
		// MariaDB answers 1 for an insert and 2 for an update on ON DUPLICATE
		// KEY, so this prunes only when a serial is genuinely new -- which is
		// exactly when the table can grow, and keeps the cost off the path a
		// real device takes every few seconds.
		if (affected == 1) {
			jdbcTemplate.update(
					"DELETE FROM unclaimed_device_sightings WHERE last_seen_at < ?",
					DeviceAttendanceEvent.SQL_DATE_TIME.format(now.minusDays(RETENTION_DAYS)));
		}
	}

	public Optional<Map<String, Object>> lookup(String serialNumber) {
		List<Map<String, Object>> rows = jdbcTemplate.query("""
				SELECT serial_number, first_seen_at, last_seen_at, hit_count, push_version, device_type
				FROM unclaimed_device_sightings WHERE serial_number = ?""",
				LegacyJdbcValues.rowMapper(), serialNumber);
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> row = rows.get(0);
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("serial_number", AttendanceDeviceStore.asText(row.get("serial_number")));
		view.put("first_seen_at", AttendanceDeviceStore.asText(row.get("first_seen_at")));
		view.put("last_seen_at", AttendanceDeviceStore.asText(row.get("last_seen_at")));
		view.put("hit_count", AttendanceDeviceStore.asLong(row.get("hit_count")));
		view.put("push_version", AttendanceDeviceStore.asText(row.get("push_version")));
		view.put("device_type", AttendanceDeviceStore.asText(row.get("device_type")));
		return Optional.of(view);
	}

	/** A claim retires the sighting: the serial now has an owner. */
	public void forget(String serialNumber) {
		jdbcTemplate.update("DELETE FROM unclaimed_device_sightings WHERE serial_number = ?", serialNumber);
	}
}
