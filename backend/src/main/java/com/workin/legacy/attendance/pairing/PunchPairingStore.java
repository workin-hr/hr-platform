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

	/** Null until first probed; see {@link #attendanceMethodAcceptsDevice()}. */
	private volatile Boolean deviceMethodSupported;

	public PunchPairingStore(DataSource legacyDataSource) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
	}

	/**
	 * Whether {@code attendance.method} actually accepts {@code 'device'}.
	 *
	 * <p><b>This exists because the failure it guards is silent.</b> The
	 * provisioning runbook claimed a deployment that skipped
	 * {@code slice_b_attendance_method.sql} would have its INSERT refused --
	 * loud and recoverable. That was wrong. Every connection runs
	 * {@code SET SESSION sql_mode=''} ({@code application.properties}), because
	 * the legacy data requires it, and a non-strict MariaDB does not reject an
	 * out-of-range {@code ENUM}: it stores the empty-string error value and
	 * raises a warning nobody reads. Pairing would commit, mark the punch
	 * {@code PAIRED}, and leave an attendance row whose method is blank -- and
	 * since the punch is no longer {@code RECEIVED}, no later pass would ever
	 * revisit it. Unrecoverable, and invisible.
	 *
	 * <p>Read once and cached: it is schema, it cannot change under a running
	 * application without a deployment, and the pass asks once per batch.
	 */
	public boolean attendanceMethodAcceptsDevice() {
		Boolean cached = this.deviceMethodSupported;
		if (cached != null) {
			return cached;
		}
		String columnType = jdbcTemplate.query(
				"SELECT COLUMN_TYPE FROM information_schema.COLUMNS"
						+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance'"
						+ " AND COLUMN_NAME = 'method'",
				rs -> rs.next() ? rs.getString(1) : null);
		// An absent column or unreadable metadata counts as unsupported. The
		// point is to fail closed, so "I could not tell" lands on the same side
		// as "no".
		boolean supported = columnType != null && columnType.contains("'device'");
		this.deviceMethodSupported = supported;
		return supported;
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
	 * <p><b>{@code punched_at_utc} travels with the row.</b> Two events an hour
	 * apart during an autumn DST fold share a {@code punched_at_local};
	 * ingestion deliberately keeps their instants distinct, and pairing has to
	 * use the instant for elapsed time or it reads the second one as a
	 * duplicate of the first and never closes the session. Local time stays for
	 * the schedule and for what is written to {@code attendance}.
	 *
	 * <p><b>Attempts order the claim, and bound it.</b> A punch that can never
	 * pair -- an employee deleted between ingestion and pairing -- would
	 * otherwise be re-claimed on every pass, because the claim takes the oldest
	 * rows under a {@code LIMIT}. Enough of those and no later employee's
	 * punches are ever reached. Failing rows sink below work that can succeed
	 * and drop out entirely once quarantined.
	 *
	 * <p>Only {@code RECEIVED} rows, and only those whose PIN resolved to an
	 * employee. That is what makes a crash mid-pass cost a repeat rather than a
	 * loss: a punch is moved out of {@code RECEIVED} in the same transaction
	 * that writes its attendance row, so the next pass picks up exactly the
	 * work the crash interrupted and nothing that was already done.
	 */
	public List<Map<String, Object>> claimable(long companyId, int limit, int maxAttempts) {
		return jdbcTemplate.query("""
				SELECT id, employee_id, punched_at_local, punched_at_utc, branch_id, device_id
				FROM device_punches
				WHERE company_id = ? AND processing_state = 'RECEIVED' AND employee_id IS NOT NULL
				  AND pair_attempts < ?
				ORDER BY pair_attempts ASC, employee_id ASC, punched_at_local ASC, id ASC
				LIMIT ?""",
				LegacyJdbcValues.rowMapper(), companyId, maxAttempts, limit);
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

	/**
	 * The stored instant of the punch that opened this attendance row, or null
	 * when it cannot be identified.
	 *
	 * <p>{@code attendance} has no UTC column -- only {@code device_punches}
	 * does -- so a DST-safe elapsed time needs the opening punch's instant, and
	 * that punch is the one whose {@code attendance_id} is this row and whose
	 * local time is its {@code check_in}.
	 */
	public LocalDateTime punchInstantAt(long attendanceId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT p.punched_at_utc AS instant FROM device_punches p"
						+ " INNER JOIN attendance a ON a.id = p.attendance_id"
						+ " WHERE p.attendance_id = ? AND p.punched_at_local = a.check_in"
						+ " ORDER BY p.id ASC LIMIT 1",
				LegacyJdbcValues.rowMapper(), attendanceId);
		if (rows.isEmpty() || rows.get(0).get("instant") == null) {
			return null;
		}
		return LocalDateTime.parse(
				rows.get(0).get("instant").toString().replace(' ', 'T').substring(0, 19));
	}

	/**
	 * The employee's own branch and whether they may punch anywhere, or null if
	 * the employee has gone.
	 */
	public Map<String, Object> branchPolicy(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT branch_id, can_check_in_any_branch FROM employees WHERE id = ?",
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

	/**
	 * The newest punch already paired for this employee, or null.
	 *
	 * <p>A claimable punch older than this one arrived late: punches after it
	 * have already produced attendance rows, so pairing it against the current
	 * state would read a window that its own presence changes.
	 */
	public LocalDateTime newestPairedPunch(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT MAX(punched_at_local) AS newest FROM device_punches"
						+ " WHERE employee_id = ? AND processing_state = 'PAIRED'",
				LegacyJdbcValues.rowMapper(), employeeId);
		if (rows.isEmpty() || rows.get(0).get("newest") == null) {
			return null;
		}
		return LocalDateTime.parse(
				rows.get(0).get("newest").toString().replace(' ', 'T').substring(0, 19));
	}

	/**
	 * The {@code check_in} of the pairing-created session that covers or
	 * precedes {@code at}, or null if there is none.
	 *
	 * <p>The rewind point has to be the start of the affected session, not the
	 * late punch itself. A punch landing at 12:00 belongs to a session opened
	 * at 08:00, and rewinding from 12:00 would leave that row in place -- the
	 * replay would then see a day already half-paired and produce something
	 * different again.
	 */
	public LocalDateTime sessionStartCovering(long employeeId, LocalDateTime at) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT MAX(a.check_in) AS started FROM attendance a"
						+ " INNER JOIN device_punches p ON p.attendance_id = a.id"
						+ " WHERE p.employee_id = ? AND a.method = 'device' AND a.check_in <= ?",
				LegacyJdbcValues.rowMapper(), employeeId, SQL_DATE_TIME.format(at));
		if (rows.isEmpty() || rows.get(0).get("started") == null) {
			return null;
		}
		return LocalDateTime.parse(
				rows.get(0).get("started").toString().replace(' ', 'T').substring(0, 19));
	}

	/**
	 * Rewinds every punch paired at or after {@code from} back to
	 * {@code RECEIVED}, and removes the attendance rows they produced.
	 *
	 * <p>Only rows this pairing created are removed, and the test is narrow on
	 * purpose: the row must be named by a punch's {@code attendance_id}, still
	 * carry {@code method = 'device'}, and still have the {@code check_in} the
	 * punch recorded. An HR user who corrected the time, or re-entered the day
	 * by hand, fails that test and their row survives -- a replay must never
	 * discard a human's work to tidy its own.
	 *
	 * @return how many attendance rows were removed
	 */
	public int rewindPairedFrom(long employeeId, LocalDateTime from) {
		String at = SQL_DATE_TIME.format(from);
		// The row itself must lie inside the window, not merely be referenced
		// by a punch inside it. A punch that CLOSED a session points at a row
		// whose check_in is an earlier punch's time, so keying only on the
		// punch would leave that row behind and the replay would find the day
		// half-paired.
		int removed = jdbcTemplate.update(
				"DELETE a FROM attendance a"
						+ " INNER JOIN device_punches p ON p.attendance_id = a.id"
						+ " WHERE p.employee_id = ? AND p.processing_state = 'PAIRED'"
						+ " AND a.check_in >= ? AND a.method = 'device'"
						// Only a row pairing itself opened: its check_in is
						// exactly some punch's timestamp. An HR correction moves
						// check_in off the punch and the row survives.
						+ " AND EXISTS (SELECT 1 FROM device_punches o"
						+ "   WHERE o.employee_id = p.employee_id AND o.attendance_id = a.id"
						+ "     AND o.punched_at_local = a.check_in)",
				employeeId, at);
		jdbcTemplate.update(
				"UPDATE device_punches SET processing_state = 'RECEIVED',"
						+ " attendance_id = NULL, paired_at = NULL, review_flag = NULL"
						+ " WHERE employee_id = ? AND processing_state IN ('PAIRED', 'IGNORED')"
						+ " AND punched_at_local >= ?",
				employeeId, at);
		return removed;
	}

	/** One more failed turn for this punch; see {@link #claimable}. */
	public void recordFailedAttempt(long punchId) {
		jdbcTemplate.update(
				"UPDATE device_punches SET pair_attempts = pair_attempts + 1 WHERE id = ?", punchId);
	}

	/**
	 * Takes a punch out of the claim after it has failed enough times.
	 *
	 * <p>{@code IGNORED} rather than deleted, and flagged, so it is still
	 * visible to somebody looking for why a terminal's punches never appeared.
	 * Quarantine is the alternative to two bad outcomes: retrying forever, or
	 * discarding evidence a device really produced.
	 */
	public void quarantine(long punchId, LocalDateTime at, String reviewFlag) {
		jdbcTemplate.update("""
				UPDATE device_punches
				SET processing_state = 'IGNORED', paired_at = ?, review_flag = ?
				WHERE id = ? AND processing_state = 'RECEIVED'""",
				SQL_DATE_TIME.format(at), reviewFlag, punchId);
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
