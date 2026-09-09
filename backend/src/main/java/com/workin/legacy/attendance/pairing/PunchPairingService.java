package com.workin.legacy.attendance.pairing;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;

/**
 * Turns stored device punches into {@code attendance} rows.
 *
 * <p><b>Where the boundary falls.</b> The device module owns the wire: the
 * protocol, the registry, the raw evidence, dedupe. It writes a punch and
 * stops. This class owns the meaning -- which punch is an arrival and which a
 * departure -- and it lives here, beside the rest of the attendance rules,
 * because that judgment is the same judgment {@code check_in.php} makes and
 * has to stay consistent with it. A punch is an observation; attendance is an
 * interpretation (D-164).
 *
 * <h2>Liveness is judged as of the punch, not as of now</h2>
 * <p>{@code LegacyAttendanceSessions.findOpenSession} asks whether a session is
 * live <em>at this moment</em>, which is exactly right for someone standing at
 * a phone. It is exactly wrong here. A terminal that lost its network for two
 * days delivers hundreds of records the moment it reconnects, and every one of
 * them is hours or days old. Asked "is this session live now", the answer for
 * the whole backlog is no, and pairing would open a new attendance row for
 * every punch -- turning two days of arrivals and departures into a column of
 * unclosed check-ins.
 *
 * <p>So the deadline is computed the same way, from
 * {@link LegacyAttendanceSessions#openSessionDeadline}, and compared against
 * the punch's own timestamp. That makes pairing a function of the punches and
 * the schedule alone, which is what lets a late arrival be replayed and reach
 * the same answer it would have reached had it arrived on time.
 *
 * <h2>Crash safety</h2>
 * <p>One punch, one transaction. The attendance write and the punch's state
 * change commit together, so a punch is either {@code PAIRED} and names the
 * row it produced, or still {@code RECEIVED} and produced nothing. There is no
 * third state to reconcile, no outbox to drain, and a pass that dies halfway
 * leaves work to redo rather than work to guess at.
 */
@Service
public class PunchPairingService {

	private static final Logger log = LoggerFactory.getLogger(PunchPairingService.class);

	/**
	 * {@code check_in.php}'s 120-minute minimum between check-ins, as a flag
	 * rather than a refusal.
	 *
	 * <p>Legacy rejects a second check-in inside two hours and tells the app so.
	 * A terminal cannot be told: it has already displayed "Thank you" and
	 * dropped the record from its buffer. Refusing here would destroy the only
	 * evidence that the person was present, and show them nothing. So the punch
	 * is always stored and paired, and the row is flagged for a human instead
	 * (D-165).
	 */
	static final Duration RAPID_RECHECKIN_WINDOW = Duration.ofMinutes(120);

	/** A punch paired to a row it opened less than this ago is a double-read. */
	static final Duration DEBOUNCE = Duration.ofMinutes(1);

	static final String FLAG_RAPID_RECHECKIN = "RAPID_RECHECKIN";
	static final String FLAG_DOUBLE_READ = "DOUBLE_READ";

	private final PunchPairingStore store;
	private final LegacyAttendanceSessions sessions;
	private final int batchSize;
	private final TransactionTemplate transactions;

	public PunchPairingService(
			PunchPairingStore store,
			LegacyAttendanceSessions sessions,
			DataSource legacyDataSource,
			@Value("${app.devices.pairing.batch-size:500}") int batchSize) {
		this.store = store;
		this.sessions = sessions;
		this.batchSize = batchSize;
		// A TransactionTemplate rather than @Transactional, and the difference
		// is not stylistic. The per-punch boundary is entered from pairCompany
		// in this same class, and a self-invocation never touches the proxy --
		// the annotation would be silently inert and every punch would commit
		// outside any transaction, which is exactly the property this design
		// depends on having. Made explicit so it cannot quietly stop working.
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(legacyDataSource));
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** What one pass did, for the caller's metrics and for tests. */
	public record Outcome(int opened, int closed, int ignored, int flagged) {

		Outcome plus(Outcome other) {
			return new Outcome(opened + other.opened, closed + other.closed,
					ignored + other.ignored, flagged + other.flagged);
		}

		public int total() {
			return opened + closed + ignored;
		}
	}

	/**
	 * Pairs one company's outstanding punches.
	 *
	 * <p>Not transactional itself, on purpose: the transaction belongs to each
	 * punch. A pass over a thousand punches held in one transaction would take
	 * a lock for its whole duration and lose everything to a single bad row.
	 */
	public Outcome pairCompany(long companyId, String weeklyRestLabel) {
		List<Map<String, Object>> punches = store.claimable(companyId, batchSize);
		Outcome total = new Outcome(0, 0, 0, 0);
		for (Map<String, Object> punch : punches) {
			try {
				total = total.plus(pairOne(companyId, punch, weeklyRestLabel));
			} catch (RuntimeException ex) {
				// One unpairable punch must not strand the rest. It stays
				// RECEIVED, so the next pass retries it -- and if it is
				// genuinely poison, it is visible as a row that never leaves
				// that state rather than as a pass that never finishes.
				log.error("Could not pair device punch {} for company {}; it stays RECEIVED",
						punch.get("id"), companyId, ex);
			}
		}
		return total;
	}

	/**
	 * One punch, one transaction.
	 *
	 * <p>{@code REQUIRES_NEW} so a caller that already holds a transaction --
	 * an admin triggering a pass inside a request, say -- cannot widen this
	 * into a single unit that rolls the whole batch back.
	 */
	Outcome pairOne(long companyId, Map<String, Object> punch, String weeklyRestLabel) {
		return transactions.execute(status -> pairOneInTransaction(companyId, punch, weeklyRestLabel));
	}

	private Outcome pairOneInTransaction(long companyId, Map<String, Object> punch, String weeklyRestLabel) {
		long punchId = LegacyValues.toPhpLong(punch.get("id"));
		long employeeId = LegacyValues.toPhpLong(punch.get("employee_id"));
		LocalDateTime punchedAt = LocalDateTime.parse(
				LegacyValues.toPhpString(punch.get("punched_at_local")).replace(' ', 'T').substring(0, 19));

		Map<String, Object> open = store.newestOpenRow(employeeId);
		if (open != null && isLiveAt(companyId, employeeId, open, punchedAt, weeklyRestLabel)) {
			long attendanceId = LegacyValues.toPhpLong(open.get("id"));
			LocalDateTime openedAt = checkInOf(open);

			// A terminal reading the same finger twice, or a person tapping
			// again because the beep was missed. Closing on it would record a
			// zero-length day; opening a new row would record two.
			if (openedAt != null && Duration.between(openedAt, punchedAt).compareTo(DEBOUNCE) < 0) {
				store.markIgnored(punchId, punchedAt, FLAG_DOUBLE_READ);
				return new Outcome(0, 0, 1, 1);
			}

			if (store.closeAttendance(attendanceId, punchedAt)) {
				store.markPaired(punchId, attendanceId, punchedAt, null);
				return new Outcome(0, 1, 0, 0);
			}
			// Someone closed it between the read and the update. Fall through
			// and open a new row: the punch is real and must land somewhere.
		}

		String flag = isRapidRecheckIn(employeeId, punchedAt) ? FLAG_RAPID_RECHECKIN : null;
		long attendanceId = store.openAttendance(employeeId, punchedAt);
		store.markPaired(punchId, attendanceId, punchedAt, flag);
		return new Outcome(1, 0, 0, flag == null ? 0 : 1);
	}

	/**
	 * Whether an open row was still check-out-able when the punch happened.
	 *
	 * <p>The deadline is legacy's own -- the earlier of the next working day's
	 * shift start and check-in + 16 hours (D-213) -- so a device check-out and
	 * a mobile one close the same window. An exception-only row is not a
	 * session at all and is skipped, matching
	 * {@code attendance_find_open_session()}.
	 */
	private boolean isLiveAt(long companyId, long employeeId, Map<String, Object> open,
			LocalDateTime punchedAt, String weeklyRestLabel) {
		String checkIn = LegacyValues.toPhpString(open.get("check_in"));
		if (checkIn.isBlank()
				|| LegacyAttendanceSessions.isExceptionOnlyRow(checkIn, null, open.get("exception_type_id"))) {
			return false;
		}
		LocalDateTime openedAt = checkInOf(open);
		if (openedAt == null || punchedAt.isBefore(openedAt)) {
			return false;
		}
		return punchedAt.isBefore(sessions.openSessionDeadline(companyId, employeeId, checkIn, weeklyRestLabel));
	}

	/** {@code check_in.php}'s two-hour rule, measured against the punch. */
	private boolean isRapidRecheckIn(long employeeId, LocalDateTime punchedAt) {
		LocalDateTime last = store.newestCheckIn(employeeId);
		if (last == null || punchedAt.isBefore(last)) {
			return false;
		}
		return Duration.between(last, punchedAt).compareTo(RAPID_RECHECKIN_WINDOW) < 0;
	}

	private static LocalDateTime checkInOf(Map<String, Object> row) {
		String value = LegacyValues.toPhpString(row.get("check_in"));
		if (value.isBlank()) {
			return null;
		}
		return LocalDateTime.parse(value.replace(' ', 'T').substring(0, 19));
	}
}
