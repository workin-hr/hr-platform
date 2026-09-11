package com.workin.legacy.attendance.pairing;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;

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
	 * <p>A successful probe is cached: it is schema, it cannot change under a
	 * running application without a deployment, and the pass asks once per
	 * batch. A FAILED probe is not cached -- applying the missing DDL is the
	 * documented remedy, and caching the negative made that remedy silently
	 * require an application restart as well.
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
		// Cache ONLY success. A false here means the DDL has not been applied
		// yet, and the log tells the operator that punches will pair on the
		// next pass once they apply it -- which was untrue while this cached
		// the negative too: the answer was frozen for the life of the JVM and
		// the promised recovery actually needed a restart nobody documented.
		// Re-probing costs one information_schema read per pass, and only
		// while the deployment is incomplete.
		if (supported) {
			this.deviceMethodSupported = true;
		}
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
				-- pair_attempts is PROJECTED, not only filtered on. The caller
				-- decides quarantine from it; without it every failure read as
				-- attempt 1, the cap was never reached, and a punch that could
				-- never pair stayed RECEIVED for ever -- invisible to the claim
				-- once over the cap, yet still looking claimable to an operator.
				SELECT id, employee_id, punched_at_local, punched_at_utc, branch_id, device_id,
				       pair_attempts
				FROM device_punches
				WHERE company_id = ? AND processing_state = 'RECEIVED' AND employee_id IS NOT NULL
				  AND pair_attempts < ?
				  -- Only punches whose temporal attribution was established.
				  -- INFERRED_EARLIEST and UNRESOLVED keep their evidence but must
				  -- not become payroll-facing attendance on their own: an
				  -- acknowledged guess is not a measurement. The NOT NULL checks
				  -- are belt and braces -- those columns are nullable precisely
				  -- so an unresolved punch asserts nothing, and every ordering
				  -- and comparison below assumes a real instant.
				  AND assignment_resolution = 'EXACT'
				  AND punched_at_utc IS NOT NULL AND branch_id IS NOT NULL
				-- punched_at_utc, not punched_at_local: during an autumn DST
				-- overlap the wall clock reads the same value twice an hour
				-- apart, so ordering by local time leaves chronology to the
				-- arrival id -- the one thing a replay guarantee cannot rest on.
				-- NOT ordered by pair_attempts. Sinking failures was the intent,
				-- but ordering by attempts REORDERS AN EMPLOYEE'S DAY: after an
				-- 08:00 punch fails once and a 17:00 punch is rewound, the next
				-- pass sees 17:00 at attempt 0 before 08:00 at attempt 1, opens
				-- the evening session first, and leaves two open rows in reverse
				-- order. Pairing is stateful per employee, so chronology is the
				-- invariant; the `pair_attempts < ?` bound above is what stops a
				-- permanently failing punch holding the claim, and it does that
				-- without touching order.
				ORDER BY employee_id ASC, punched_at_utc ASC, id ASC
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
						+ " WHERE p.attendance_id = ? AND p.attendance_check_in_at IS NOT NULL"
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
	/** The newest already-paired punch as a stored INSTANT, never a wall clock. */
	public LocalDateTime newestPairedPunch(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT MAX(punched_at_utc) AS newest FROM device_punches"
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
	/**
	 * A CLOSED attendance row that already spans this moment, if one exists.
	 *
	 * <p>Only reachable when a row pairing cannot rewind is in the way -- in
	 * practice one HR has corrected, which the rewind deliberately preserves.
	 * Opening another row inside its span would record the same stretch of the
	 * day twice and quietly duplicate the correction.
	 */
	/**
	 * The legacy runtime offset in force at an instant, or null when the
	 * instant predates recorded history.
	 *
	 * <p>Null is not a failure to look up -- it is the honest answer. The old
	 * value of {@code configs.is_daylight_saving} is gone, and guessing it from
	 * the earliest row, or from Africa/Cairo's rules, would answer a different
	 * question: what the operator theoretically should have configured, rather
	 * than what app and QR attendance actually used at that moment.
	 */
	public Integer runtimeOffsetSecondsAt(LocalDateTime instantUtc) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT offset_seconds FROM legacy_runtime_offset_history"
						+ " WHERE effective_from_utc <= ?"
						+ " ORDER BY effective_from_utc DESC, id DESC LIMIT 1",
				LegacyJdbcValues.rowMapper(), SQL_DATE_TIME.format(instantUtc));
		return rows.isEmpty() ? null : (int) LegacyValues.toPhpLong(rows.get(0).get("offset_seconds"));
	}

	/**
	 * The whole runtime-offset history, oldest first, for one pass to resolve
	 * against in memory.
	 *
	 * <p>Measured: the per-punch lookup above was one of eleven statements each
	 * punch cost, and it asks the same tiny table the same way every time. The
	 * table holds one row per offset change for the life of the system --
	 * roughly two a year -- so reading it once per pass and selecting in memory
	 * gives the identical answer for a fraction of the round trips. This is the
	 * shape {@code DeviceAssignmentTimeline} already uses for assignments.
	 *
	 * <p>The staleness this introduces is bounded and, in practice, empty: a
	 * history row appearing mid-pass has {@code effective_from_utc} at about
	 * "now", while a pass is resolving punches that already happened. The next
	 * pass reads it.
	 */
	public List<RuntimeOffsetPeriod> runtimeOffsetHistory() {
		return jdbcTemplate.query(
				"SELECT effective_from_utc, offset_seconds FROM legacy_runtime_offset_history"
						+ " ORDER BY effective_from_utc ASC, id ASC",
				(rs, rowNumber) -> new RuntimeOffsetPeriod(
						rs.getTimestamp("effective_from_utc").toLocalDateTime(),
						rs.getInt("offset_seconds")));
	}

	/** One recorded offset, in force from {@code effectiveFromUtc} until the next. */
	public record RuntimeOffsetPeriod(LocalDateTime effectiveFromUtc, int offsetSeconds) {
	}

	/**
	 * Whether the triggers that WRITE that history are installed.
	 *
	 * <p>A seeded table with no active writers is worse than an obvious
	 * failure: it looks authoritative and silently stops tracking, so every
	 * later punch is converted with a stale offset that nothing reports.
	 */
	public boolean runtimeOffsetHooksInstalled() {
		Integer found = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.TRIGGERS"
						+ " WHERE TRIGGER_SCHEMA = DATABASE()"
						+ " AND TRIGGER_NAME IN ('configs_runtime_offset_after_insert',"
						+ "   'configs_runtime_offset_after_update', 'configs_runtime_offset_after_delete')",
				Integer.class);
		return found != null && found == 3;
	}

	public Long closedRowCovering(long employeeId, LocalDateTime at) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT id FROM attendance WHERE employee_id = ? AND method = 'device'"
						+ " AND check_out IS NOT NULL AND check_in <= ? AND check_out >= ?"
						+ " ORDER BY check_in DESC LIMIT 1",
				LegacyJdbcValues.rowMapper(), employeeId,
				SQL_DATE_TIME.format(at), SQL_DATE_TIME.format(at));
		return rows.isEmpty() ? null : LegacyValues.toPhpLong(rows.get(0).get("id"));
	}

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
	/**
	 * @param fromCheckIn the attendance bound, in the legacy runtime offset --
	 *        the clock {@code attendance.check_in} is written in
	 * @param fromInstant the punch bound, as a stored UTC instant. Two bounds
	 *        because they are two different clocks: one value cannot address
	 *        both tables once attendance stopped being the device's wall clock.
	 */
	public int rewindPairedFrom(long employeeId, LocalDateTime fromCheckIn, LocalDateTime fromInstant) {
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
						// Edit detection, not identity: the opener is found by
						// its non-null provenance, and the row survives only if
						// its check_in still equals what pairing wrote. An HR
						// correction moves check_in and the row is left alone.
						+ " AND EXISTS (SELECT 1 FROM device_punches o"
						+ "   WHERE o.employee_id = p.employee_id AND o.attendance_id = a.id"
						+ "     AND o.attendance_check_in_at IS NOT NULL"
						+ "     AND o.attendance_check_in_at = a.check_in)",
				employeeId, SQL_DATE_TIME.format(fromCheckIn));
		jdbcTemplate.update(
				// Only punches whose attendance row actually went. The DELETE
				// above deliberately spares a row HR has corrected, and
				// resetting its punches anyway sent them back through pairing to
				// build a SECOND, overlapping session around the human edit --
				// the correction survived while being quietly duplicated.
				//
				// The LEFT JOIN is the test: after the delete, a punch whose row
				// survived still resolves to it, and a punch whose row went has
				// a dangling attendance_id. `a.id IS NULL` catches that and the
				// IGNORED punches, which never had a row to begin with and must
				// still be replayed.
				//
				// Bounded by the INSTANT: during a DST overlap the local value
				// cannot separate two punches an hour apart at all.
				"UPDATE device_punches p"
						+ " LEFT JOIN attendance a ON a.id = p.attendance_id"
						+ " SET p.processing_state = 'RECEIVED',"
						+ " p.attendance_id = NULL, p.paired_at = NULL, p.review_flag = NULL,"
						+ " p.attendance_check_in_at = NULL"
						+ " WHERE p.employee_id = ? AND p.processing_state IN ('PAIRED', 'IGNORED')"
						+ " AND p.punched_at_utc >= ? AND a.id IS NULL",
				employeeId, SQL_DATE_TIME.format(fromInstant));
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
	/**
	 * The punch that OPENED {@code attendanceId}, recording the exact value
	 * written to {@code attendance.check_in} as its provenance.
	 *
	 * <p>Separate from {@link #markPaired} because only the opener may carry
	 * that field: both punches reference the same attendance row, so the field
	 * being non-null is what distinguishes them.
	 */
	public void markPairedAsOpener(long punchId, long attendanceId, LocalDateTime pairedAt,
			String reviewFlag, LocalDateTime checkInAt, RuntimeOffsetProvenance provenance) {
		jdbcTemplate.update("""
				UPDATE device_punches
				SET processing_state = 'PAIRED', attendance_id = ?, paired_at = ?, review_flag = ?,
				    attendance_check_in_at = ?,
				    legacy_runtime_offset_seconds = ?, runtime_offset_resolution = ?
				WHERE id = ? AND processing_state = 'RECEIVED'""",
				attendanceId, SQL_DATE_TIME.format(pairedAt), reviewFlag,
				SQL_DATE_TIME.format(checkInAt),
				provenance.offsetSeconds(), provenance.resolution(), punchId);
	}

	/** A punch that CLOSED an existing row; it sets no {@code attendance_check_in_at}. */
	public void markPaired(long punchId, long attendanceId, LocalDateTime pairedAt,
			String reviewFlag, RuntimeOffsetProvenance provenance) {
		jdbcTemplate.update("""
				UPDATE device_punches
				SET processing_state = 'PAIRED', attendance_id = ?, paired_at = ?, review_flag = ?,
				    legacy_runtime_offset_seconds = ?, runtime_offset_resolution = ?
				WHERE id = ? AND processing_state = 'RECEIVED'""",
				attendanceId, SQL_DATE_TIME.format(pairedAt), reviewFlag,
				provenance.offsetSeconds(), provenance.resolution(), punchId);
	}

	/**
	 * A punch that produced no attendance row, and never will.
	 *
	 * <p>Terminal on purpose. A debounced double-read is not work to retry
	 * later, and leaving it {@code RECEIVED} would make every pass reconsider
	 * it forever.
	 */
	public void markIgnored(long punchId, LocalDateTime at, String reviewFlag,
			RuntimeOffsetProvenance provenance) {
		jdbcTemplate.update("""
				UPDATE device_punches
				SET processing_state = 'IGNORED', paired_at = ?, review_flag = ?,
				    legacy_runtime_offset_seconds = ?, runtime_offset_resolution = ?
				WHERE id = ? AND processing_state = 'RECEIVED'""",
				SQL_DATE_TIME.format(at), reviewFlag,
				provenance.offsetSeconds(), provenance.resolution(), punchId);
	}

	/**
	 * Which runtime offset produced a punch's timestamp, carried to whichever
	 * disposition the punch takes.
	 *
	 * <p>It used to be its own {@code UPDATE} immediately before that
	 * disposition -- correct, and one of the nine statements each punch cost,
	 * spent writing two columns on a row the very next statement rewrote. The
	 * provenance is still recorded on every path; it just rides along now.
	 */
	public record RuntimeOffsetProvenance(Integer offsetSeconds, String resolution) {
	}
}
