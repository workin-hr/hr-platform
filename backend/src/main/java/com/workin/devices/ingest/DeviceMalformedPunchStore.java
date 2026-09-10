package com.workin.devices.ingest;

import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.devices.DeviceInput;

/**
 * Raw ATTLOG lines this build could not parse, kept so a real punch is
 * recoverable.
 *
 * <p>Why this table exists at all: the batch is acknowledged with 200 OK
 * whether or not every line parsed, because refusing the batch would make one
 * unrecognised line block every good punch behind it. The terminal then drops
 * its copy. So at the moment we answer OK, the raw line is the <em>only</em>
 * remaining evidence that an employee punched, and counting it was losing that
 * punch permanently -- the log said "quarantined" while nothing was.
 *
 * <p>A malformed line is not necessarily corrupt: the far more likely cause is
 * a firmware revision emitting an ATTLOG shape this parser has not been taught.
 * That makes these rows the input for teaching it, and the punches recoverable
 * once it is.
 */
@Component
public class DeviceMalformedPunchStore {

	private final JdbcTemplate jdbcTemplate;

	public DeviceMalformedPunchStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * One statement for the whole upload, and {@code INSERT IGNORE} on the
	 * content hash for the same reason {@link DeviceOperationLogStore} uses it:
	 * the handshake always asks a device to resume from the beginning, so an
	 * unparseable line arrives again on every reconnect and would otherwise
	 * grow this table without bound.
	 */
	public void quarantine(long deviceId, long companyId, List<String> rawLines, LocalDateTime receivedAt) {
		if (rawLines.isEmpty()) {
			return;
		}
		String stamp = DeviceAttendanceEvent.SQL_DATE_TIME.format(receivedAt);
		List<Object[]> batch = rawLines.stream()
				.map(line -> {
					String bounded = DeviceInput.bounded(line, DevicePunchStore.MAX_RAW_LINE);
					return new Object[] {
						deviceId, companyId, stamp, bounded,
						DeviceAttendanceEvent.contentKey(deviceId, bounded) };
				})
				.toList();
		jdbcTemplate.batchUpdate(
				"INSERT IGNORE INTO device_malformed_punches"
						+ " (device_id, company_id, received_at, raw_line, dedup_key) VALUES (?, ?, ?, ?, ?)",
				batch);
	}

	/** Every quarantined line for a device, oldest first. For recovery, not for pairing. */
	public List<String> quarantinedFor(long deviceId) {
		return jdbcTemplate.queryForList(
				"SELECT raw_line FROM device_malformed_punches WHERE device_id = ? ORDER BY id",
				String.class, deviceId);
	}
}
