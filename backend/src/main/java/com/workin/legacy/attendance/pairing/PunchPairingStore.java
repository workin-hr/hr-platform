package com.workin.legacy.attendance.pairing;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyJdbcValues;

/**
 * The two tables punch pairing spans: {@code device_punches}, which the device
 * module writes, and {@code attendance}, which the legacy contract owns.
 *
 * <p>Reads and writes only. Every decision about which punch is an arrival and
 * which a departure belongs to {@link PunchPairingService}, so that the rules
 * can be exercised without a database and this class stays a description of
 * the two schemas.
 */
@Component
public class PunchPairingStore {

	static final DateTimeFormatter SQL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final JdbcTemplate jdbcTemplate;

	public PunchPairingStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * Unpaired punches for one company, oldest punch first.
	 *
	 * <p><b>Ordered by when the punch happened, never by when it arrived.</b> A
	 * terminal that was offline for two days delivers hundreds of old records
	 * in one burst, and pairing them in arrival order would read that burst as
	 * a rapid sequence of arrivals and departures on the day it reconnected.
	 * {@code id} breaks ties so the order is total: two punches can share a
	 * second, and an unstable sort would pair them differently on a retry.
	 *
	 * <p>Only {@code RECEIVED} rows, and only those whose PIN resolved to an
	 * employee. That is what makes a crash mid-pass cost a repeat rather than a
	 * loss: a punch is moved out of {@code RECEIVED} in the same transaction
	 * that writes its attendance row, so the next pass picks up exactly the
	 * work the crash interrupted and nothing that was already done.
	 */
	public List<Map<String, Object>> claimable(long companyId, int limit) {
		return jdbcTemplate.query("""
				SELECT id, employee_id, punched_at_local, branch_id, device_id
				FROM device_punches
				WHERE company_id = ? AND processing_state = 'RECEIVED' AND employee_id IS NOT NULL
				ORDER BY employee_id ASC, punched_at_local ASC, id ASC
				LIMIT ?""",
				LegacyJdbcValues.rowMapper(), companyId, limit);
	}

	/**
	 * The employee's newest attendance row that is still open, whatever its
	 * age.
	 *
	 * <p>Deliberately not {@code LegacyAttendanceSessions.findOpenSession}:
	 * that answers "is there a live session <em>now</em>", which is the right
	 * question for a person standing at a phone and the wrong one for a punch
	 * that happened yesterday. Pairing needs the row plus its check-in time so
	 * it can judge liveness as of the punch instead -- see
	 * {@link PunchPairingService}.
	 */
	public Map<String, Object> newestOpenRow(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query("""
				SELECT id, check_in, exception_type_id
				FROM attendance
				WHERE employee_id = ? AND check_out IS NULL AND check_in IS NOT NULL
				ORDER BY id DESC
				LIMIT 1""",
				LegacyJdbcValues.rowMapper(), employeeId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** The newest check-in of any kind, open or closed: the two-hour rule's input. */
	public LocalDateTime newestCheckIn(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT check_in FROM attendance WHERE employee_id = ? ORDER BY check_in DESC LIMIT 1",
				LegacyJdbcValues.rowMapper(), employeeId);
		if (rows.isEmpty()) {
			return null;
		}
		Object value = rows.get(0).get("check_in");
		return value == null ? null : LocalDateTime.parse(
				value.toString().replace(' ', 'T').substring(0, 19));
	}

	/**
	 * Opens an attendance row at the punch's own time.
	 *
	 * <p>{@code NOW()} would be wrong here in a way it is not for the mobile
	 * endpoint: a backlog is paired long after it happened, and stamping it
	 * with the current time would record the reconnection rather than the
	 * arrival. No coordinates -- a terminal is at a fixed place and reports
	 * none, and inventing the branch's location would make a device punch look
	 * like a geofenced one.
	 */
	public long openAttendance(long employeeId, LocalDateTime at) {
		jdbcTemplate.update(
				"INSERT INTO attendance (employee_id, check_in, method) VALUES (?, ?, 'device')",
				employeeId, SQL_DATE_TIME.format(at));
		return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	/**
	 * Closes one open row, and reports whether it was this call that closed it.
	 *
	 * <p>The {@code check_out IS NULL} guard is what makes a concurrent or
	 * repeated pass safe: two passes racing on the same row produce one update
	 * and one no-op, and the loser pairs its punch nowhere rather than
	 * overwriting a check-out that is already recorded.
	 */
	public boolean closeAttendance(long attendanceId, LocalDateTime at) {
		return jdbcTemplate.update(
				"UPDATE attendance SET check_out = ? WHERE id = ? AND check_out IS NULL",
				SQL_DATE_TIME.format(at), attendanceId) == 1;
	}

	/** Records what the pass did with a punch, in the pass's own transaction. */
	public void markPaired(long punchId, long attendanceId, LocalDateTime pairedAt, String reviewFlag) {
		jdbcTemplate.update("""
				UPDATE device_punches
				SET processing_state = 'PAIRED', attendance_id = ?, paired_at = ?, review_flag = ?
				WHERE id = ? AND processing_state = 'RECEIVED'""",
				attendanceId, SQL_DATE_TIME.format(pairedAt), reviewFlag, punchId);
	}

	/**
	 * A punch that produced no attendance row, and never will.
	 *
	 * <p>Terminal on purpose. A debounced double-read is not work to retry
	 * later, and leaving it {@code RECEIVED} would make every pass reconsider
	 * it forever.
	 */
	public void markIgnored(long punchId, LocalDateTime at, String reviewFlag) {
		jdbcTemplate.update("""
				UPDATE device_punches
				SET processing_state = 'IGNORED', paired_at = ?, review_flag = ?
				WHERE id = ? AND processing_state = 'RECEIVED'""",
				SQL_DATE_TIME.format(at), reviewFlag, punchId);
	}
}
