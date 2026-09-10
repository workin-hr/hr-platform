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
	/**
	 * @param branchId null when the punch's configuration could not be
	 *        established -- see {@code assignment_resolution}. A fallback value
	 *        here would be indistinguishable from an observed one.
	 * @param punchedAtUtc null for the same reason
	 */
	public InsertOutcome insert(
			long deviceId, long companyId, Long branchId, Long employeeId, DeviceAttendanceEvent event,
			LocalDateTime punchedAtUtc, LocalDateTime receivedAt, String state,
			Long assignmentId, String assignmentResolution) {
		try {
			jdbcTemplate.update("""
					INSERT INTO device_punches
					  (device_id, company_id, branch_id, employee_id, pin, punched_at_local, punched_at_utc,
					   status_code, verify_code, work_code, received_at, dedup_key, raw_line, processing_state,
					   device_assignment_id, assignment_resolution)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
					deviceId, companyId, branchId, employeeId, event.pin(),
					DeviceAttendanceEvent.SQL_DATE_TIME.format(event.punchedAtLocal()),
					punchedAtUtc == null ? null : DeviceAttendanceEvent.SQL_DATE_TIME.format(punchedAtUtc),
					event.statusCode(), event.verifyCode(), event.workCode(),
					DeviceAttendanceEvent.SQL_DATE_TIME.format(receivedAt),
					event.dedupKey(), DeviceInput.bounded(event.rawLine(), MAX_RAW_LINE), state,
					assignmentId, assignmentResolution);
			return InsertOutcome.STORED;
		} catch (DuplicateKeyException ex) {
			return InsertOutcome.DUPLICATE;
		} catch (DataIntegrityViolationException ex) {
			return InsertOutcome.REJECTED;
		}
	}

	/**
	 * Adopts the punches a newly-bound PIN already produced.
	 *
	 * <p>Punches whose PIN resolved to nobody are stored {@code UNMATCHED} with
	 * {@code employee_id} null, precisely so the evidence survives until
	 * somebody says who the PIN belongs to. Binding is that moment -- and
	 * without this, it did nothing for them: pairing claims only
	 * {@code RECEIVED} rows, and re-delivery cannot help either because
	 * {@code dedup_key} is unique and the terminal's retry returns
	 * {@code DUPLICATE}. Those punches could never enter pairing at all, which
	 * makes the documented bind-and-replay flow a promise the code did not
	 * keep.
	 *
	 * <p>Scoped to one company and one PIN, and only rows still
	 * {@code UNMATCHED}: a punch already paired, ignored or attributed
	 * elsewhere is left exactly as it is.
	 *
	 * @return how many punches were adopted
	 */
	public int adoptUnmatched(long companyId, long employeeId, String pin) {
		return jdbcTemplate.update(
				"UPDATE device_punches SET employee_id = ?, processing_state = 'RECEIVED',"
						+ " pair_attempts = 0"
						+ " WHERE company_id = ? AND pin = ? AND processing_state = 'UNMATCHED'"
						+ " AND employee_id IS NULL",
				employeeId, companyId, pin);
	}

	/**
	 * Promotes a device's pre-claim punches from inferred attribution to
	 * established, on an operator's word.
	 *
	 * <p>A terminal buffers punches while unclaimed and uploads them once it is
	 * claimed. The device's assignment history begins at claim time, so every
	 * one of those punches resolves INFERRED_EARLIEST -- and the pairing claim
	 * requires EXACT, deliberately, because an acknowledged guess must not
	 * become payroll-facing attendance on its own. Nothing then existed to
	 * change that, so those punches sat in RECEIVED for ever: excluded from
	 * pairing, invisible as work, and not recoverable by any path.
	 *
	 * <p>The missing input is a fact only a person has -- that the terminal was
	 * already in this branch, on this zone, before it was claimed. This is
	 * where they supply it. It is narrow on purpose: one device, only rows
	 * still RECEIVED, and only INFERRED_EARLIEST ones, so it can neither
	 * revive a quarantined punch nor overwrite an attribution that was
	 * established rather than guessed.
	 *
	 * @return how many punches were promoted
	 */
	public int confirmInferredAssignment(long companyId, long deviceId) {
		return jdbcTemplate.update("""
				UPDATE device_punches
				SET assignment_resolution = 'EXACT'
				WHERE company_id = ? AND device_id = ?
				  AND processing_state = 'RECEIVED'
				  AND assignment_resolution = 'INFERRED_EARLIEST'
				  -- Both are required by the pairing claim, and an inferred row
				  -- can be missing them; promoting one that is would move it
				  -- from "held" to "silently never selected".
				  AND punched_at_utc IS NOT NULL AND branch_id IS NOT NULL""",
				companyId, deviceId);
	}

	/** Newest first, always inside one company; the optional filters narrow, never widen. */
	public List<Map<String, Object>> recentForCompany(
			long companyId, Long deviceId, String state, boolean flaggedOnly, int limit) {
		StringBuilder sql = new StringBuilder("""
				-- review_flag and assignment_resolution are PROJECTED, not just
				-- stored. This is the only tenant-facing punch query, and
				-- without them a punch flagged RAPID_RECHECKIN,
				-- OUT_OF_HOME_BRANCH or PAIRING_FAILED was indistinguishable
				-- from an ordinary one -- so the human review those flags exist
				-- to demand could not be performed at all. assignment_resolution
				-- answers the next question an operator asks about a punch that
				-- is not pairing: whether its attribution was established or
				-- only inferred.
				SELECT p.id, p.device_id, d.name AS device_name, p.branch_id, p.employee_id, p.pin,
				       p.punched_at_local, p.punched_at_utc, p.status_code, p.verify_code, p.work_code,
				       p.received_at, p.processing_state, p.review_flag, p.assignment_resolution
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
		if (flaggedOnly) {
			// Seeing the flag is not enough on its own: a reviewer needs the
			// flagged punches without paging through everything else.
			sql.append(" AND p.review_flag IS NOT NULL AND p.review_flag <> ''");
		}
		sql.append(" ORDER BY p.punched_at_local DESC, p.id DESC LIMIT ").append(limit);
		return jdbcTemplate.query(sql.toString(), LegacyJdbcValues.rowMapper(), args.toArray());
	}

}
