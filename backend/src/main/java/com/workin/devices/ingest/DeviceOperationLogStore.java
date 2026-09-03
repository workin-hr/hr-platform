package com.workin.devices.ingest;

import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.devices.DeviceInput;

/** Raw device operation-log lines, kept for audit; nothing here is interpreted. */
@Component
public class DeviceOperationLogStore {

	private final JdbcTemplate jdbcTemplate;

	public DeviceOperationLogStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * One statement for the whole upload: a line-per-statement loop is the
	 * amplification the record cap exists to bound.
	 *
	 * <p>{@code INSERT IGNORE} against the content-hash key, because the
	 * handshake always asks a device to resume from the beginning
	 * ({@code ZkTecoHandshake.ALWAYS_RESEND}); without it, every reconnect
	 * would append the terminal's whole operation history again and the table
	 * would grow without bound. An operation line already carries its own
	 * timestamp, so an identical line really is the same record.
	 */
	public void append(long deviceId, long companyId, List<String> rawLines, LocalDateTime receivedAt) {
		if (rawLines.isEmpty()) {
			return;
		}
		String stamp = DeviceAttendanceEvent.SQL_DATE_TIME.format(receivedAt);
		List<Object[]> batch = rawLines.stream()
				.map(line -> {
					String bounded = DeviceInput.bounded(line, DevicePunchStore.MAX_RAW_LINE);
					return new Object[] {
						deviceId, companyId, stamp, bounded, DeviceAttendanceEvent.contentKey(deviceId, bounded) };
				})
				.toList();
		jdbcTemplate.batchUpdate(
				"INSERT IGNORE INTO device_operation_logs"
						+ " (device_id, company_id, received_at, raw_line, dedup_key) VALUES (?, ?, ?, ?, ?)",
				batch);
	}
}
