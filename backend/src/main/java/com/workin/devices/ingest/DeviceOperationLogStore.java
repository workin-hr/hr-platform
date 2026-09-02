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

	/** One statement for the whole upload: a line-per-statement loop is the amplification the cap exists to bound. */
	public void append(long deviceId, long companyId, List<String> rawLines, LocalDateTime receivedAt) {
		if (rawLines.isEmpty()) {
			return;
		}
		String stamp = DeviceAttendanceEvent.SQL_DATE_TIME.format(receivedAt);
		List<Object[]> batch = rawLines.stream()
				.map(line -> new Object[] {
					deviceId, companyId, stamp, DeviceInput.bounded(line, DevicePunchStore.MAX_RAW_LINE) })
				.toList();
		jdbcTemplate.batchUpdate(
				"INSERT INTO device_operation_logs (device_id, company_id, received_at, raw_line) VALUES (?, ?, ?, ?)",
				batch);
	}
}
