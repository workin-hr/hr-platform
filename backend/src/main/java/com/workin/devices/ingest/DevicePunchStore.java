package com.workin.devices.ingest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.devices.DeviceInput;
import com.workin.legacy.LegacyJdbcValues;

/** Append-only writer and tenant-scoped reader for {@code device_punches}. */
@Component
public class DevicePunchStore {

	/** {@code device_punches.raw_line} / {@code device_operation_logs.raw_line}. */
	public static final int MAX_RAW_LINE = 512;

	public static final String STATE_RECEIVED = "RECEIVED";
	public static final String STATE_UNMATCHED = "UNMATCHED";

	private final JdbcTemplate jdbcTemplate;

	public DevicePunchStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/** What one punch did on its way to storage. */
	public enum InsertOutcome {
		STORED,
		/** The key already exists: a re-delivery, which is normal and not an error. */
		DUPLICATE,
		/** The row was refused by the database and will never be storable. */
		REJECTED
	}

	/**
	 * One insert per punch, deliberately: a batch is only as atomic as the
	 * device's retry, which re-sends the whole batch, so a partial failure
	 * here is repaired by the next delivery and the unique key.
	 *
	 * <p>A row the database refuses is reported, not thrown. The alternative
	 * is worse than losing the row: the exception would escape as a 500, the
	 * device would treat the whole batch as undelivered and re-send it after
	 * every {@code ErrorDelay}, and one unstorable punch would then block every
	 * good punch beside it forever. The parser already bounds what it emits to
	 * what the columns accept; this is the guard for whatever it did not
	 * anticipate.
	 */
	public InsertOutcome insert(
			long deviceId, long companyId, long branchId, Long employeeId, DeviceAttendanceEvent event,
			LocalDateTime punchedAtUtc, LocalDateTime receivedAt, String state) {
		try {
			jdbcTemplate.update("""
					INSERT INTO device_punches
					  (device_id, company_id, branch_id, employee_id, pin, punched_at_local, punched_at_utc,
					   status_code, verify_code, work_code, received_at, dedup_key, raw_line, processing_state)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
					deviceId, companyId, branchId, employeeId, event.pin(),
					DeviceAttendanceEvent.SQL_DATE_TIME.format(event.punchedAtLocal()),
					DeviceAttendanceEvent.SQL_DATE_TIME.format(punchedAtUtc),
					event.statusCode(), event.verifyCode(), event.workCode(),
					DeviceAttendanceEvent.SQL_DATE_TIME.format(receivedAt),
					event.dedupKey(), DeviceInput.bounded(event.rawLine(), MAX_RAW_LINE), state);
			return InsertOutcome.STORED;
		} catch (DuplicateKeyException ex) {
			return InsertOutcome.DUPLICATE;
		} catch (DataIntegrityViolationException ex) {
			return InsertOutcome.REJECTED;
		}
	}

	/** Newest first, always inside one company; the optional filters narrow, never widen. */
	public List<Map<String, Object>> recentForCompany(long companyId, Long deviceId, String state, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT p.id, p.device_id, d.name AS device_name, p.branch_id, p.employee_id, p.pin,
				       p.punched_at_local, p.punched_at_utc, p.status_code, p.verify_code, p.work_code,
				       p.received_at, p.processing_state
				FROM device_punches p
				JOIN attendance_devices d ON d.id = p.device_id
				WHERE p.company_id = ?""");
		java.util.List<Object> args = new java.util.ArrayList<>();
		args.add(companyId);
		if (deviceId != null) {
			sql.append(" AND p.device_id = ?");
			args.add(deviceId);
		}
		if (state != null) {
			sql.append(" AND p.processing_state = ?");
			args.add(state);
		}
		sql.append(" ORDER BY p.punched_at_local DESC, p.id DESC LIMIT ").append(limit);
		return jdbcTemplate.query(sql.toString(), LegacyJdbcValues.rowMapper(), args.toArray());
	}

}
