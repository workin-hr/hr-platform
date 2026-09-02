package com.workin.devices.ingest;

import java.time.LocalDateTime;

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

	public void append(long deviceId, long companyId, String rawLine, LocalDateTime receivedAt) {
		jdbcTemplate.update(
				"INSERT INTO device_operation_logs (device_id, company_id, received_at, raw_line) VALUES (?, ?, ?, ?)",
				deviceId, companyId, DeviceAttendanceEvent.SQL_DATE_TIME.format(receivedAt),
				DeviceInput.bounded(rawLine, DevicePunchStore.MAX_RAW_LINE));
	}
}
