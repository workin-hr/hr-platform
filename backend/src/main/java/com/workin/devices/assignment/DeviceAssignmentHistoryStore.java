package com.workin.devices.assignment;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.devices.DeviceAttendanceEvent;

/** Append-only record of what a device's branch and zone were, and from when. */
@Component
public class DeviceAssignmentHistoryStore {

	private final JdbcTemplate jdbcTemplate;

	public DeviceAssignmentHistoryStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * Convert a legacy-runtime wall clock to the instant it names.
	 *
	 * <p>{@code LegacyClock.now()} is a wall clock in the runtime's offset, not
	 * an instant. Writing it straight into a column called
	 * {@code effective_from_utc} would make the timeline wrong by that offset
	 * -- and wrong in the direction that silently shifts which configuration a
	 * punch resolves to, which is the entire failure this table exists to end.
	 */
	public static LocalDateTime toUtc(LocalDateTime runtimeLocal, ZoneOffset runtimeOffset) {
		return runtimeLocal.atOffset(runtimeOffset).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	/** Must run inside the caller's transaction, beside the registry write. */
	public void append(long deviceId, long companyId, long branchId, String deviceTimeZone,
			LocalDateTime effectiveFromUtc, LocalDateTime createdAtRuntime) {
		jdbcTemplate.update(
				"INSERT INTO device_assignment_history"
						+ " (device_id, company_id, branch_id, device_time_zone, effective_from_utc, created_at)"
						+ " VALUES (?, ?, ?, ?, ?, ?)",
				deviceId, companyId, branchId, deviceTimeZone,
				DeviceAttendanceEvent.SQL_DATE_TIME.format(effectiveFromUtc),
				DeviceAttendanceEvent.SQL_DATE_TIME.format(createdAtRuntime));
	}

	/**
	 * One device's whole timeline, loaded ONCE per delivery.
	 *
	 * <p>A reconnect can carry thousands of buffered punches; a query each
	 * would turn one upload into thousands of round trips.
	 */
	public DeviceAssignmentTimeline timelineFor(long deviceId) {
		List<DeviceAssignmentTimeline.Assignment> rows = jdbcTemplate.query(
				"SELECT id, branch_id, device_time_zone, effective_from_utc"
						+ " FROM device_assignment_history WHERE device_id = ?"
						+ " ORDER BY effective_from_utc ASC, id ASC",
				(rs, i) -> new DeviceAssignmentTimeline.Assignment(
						rs.getLong("id"),
						rs.getLong("branch_id"),
						ZoneId.of(rs.getString("device_time_zone")),
						rs.getTimestamp("effective_from_utc").toLocalDateTime()),
				deviceId);
		return new DeviceAssignmentTimeline(rows);
	}

	/** The newest row, for the invariant that registry state and history agree. */
	public DeviceAssignmentTimeline.Assignment latestFor(long deviceId) {
		List<DeviceAssignmentTimeline.Assignment> rows = jdbcTemplate.query(
				"SELECT id, branch_id, device_time_zone, effective_from_utc"
						+ " FROM device_assignment_history WHERE device_id = ?"
						+ " ORDER BY effective_from_utc DESC, id DESC LIMIT 1",
				(rs, i) -> new DeviceAssignmentTimeline.Assignment(
						rs.getLong("id"),
						rs.getLong("branch_id"),
						ZoneId.of(rs.getString("device_time_zone")),
						rs.getTimestamp("effective_from_utc").toLocalDateTime()),
				deviceId);
		return rows.isEmpty() ? null : rows.get(0);
	}
}
