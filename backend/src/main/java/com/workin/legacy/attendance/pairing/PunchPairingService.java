package com.workin.legacy.attendance.pairing;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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

import com.workin.legacy.LegacyClock;
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
	/** Punched on a device assigned to a branch that is not the employee's. */
	static final String FLAG_OUT_OF_HOME_BRANCH = "OUT_OF_HOME_BRANCH";
	/** Failed to pair {@link #MAX_PAIR_ATTEMPTS} times; see the store's quarantine. */
	static final String FLAG_PAIRING_FAILED = "PAIRING_FAILED";
	/** A punch inside a session a human corrected: attributable to no new row. */
	static final String FLAG_INSIDE_CORRECTED_SESSION = "INSIDE_CORRECTED_SESSION";

	/**
	 * How many passes a punch may fail before it is taken out of the claim.
	 *
	 * <p>Generous, because the ordinary reasons a pass fails are transient -- a
	 * database blip, a lock timeout -- and losing a real punch to a moment's
	 * unavailability is worse than retrying it a few extra times.
	 */
	static final int MAX_PAIR_ATTEMPTS = 5;

	private final PunchPairingStore store;
	private final LegacyClock clock;
	private final LegacyAttendanceSessions sessions;
	private final int batchSize;
	private final TransactionTemplate transactions;

	public PunchPairingService(
			PunchPairingStore store,
			LegacyAttendanceSessions sessions,
			DataSource legacyDataSource,
			LegacyClock clock,
			@Value("${app.devices.pairing.batch-size:500}") int batchSize) {
		this.store = store;
		this.sessions = sessions;
		this.clock = clock;
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
		// Fail closed. Without the fourth enum value, a non-strict MariaDB
		// stores a blank method rather than refusing the insert, and the punch
		// is marked PAIRED on the way past -- so the row is wrong and nothing
		// will ever revisit it. Refusing to pair leaves every punch RECEIVED,
		// which is the recoverable state: apply the DDL and the next pass picks
		// them all up untouched.
		if (!store.attendanceMethodAcceptsDevice()) {
			log.error("Not pairing: attendance.method does not accept 'device'. Apply "
					+ "db/phase1-mysql/slice_b_attendance_method.sql -- see "
					+ "docs/operations/provisioning-phase1-tables.md. Punches stay RECEIVED "
					+ "and pair on the next pass once it is applied; pairing without it would "
					+ "write blank-method attendance rows that no later pass can repair.");
			return new Outcome(0, 0, 0, 0);
		}

		List<Map<String, Object>> punches = store.claimable(companyId, batchSize, MAX_PAIR_ATTEMPTS);

		// A punch older than one already paired for the same employee arrived
		// late -- a terminal reconnecting with a buffered backlog, or a 4370
		// pulled after the fact. Pairing it against the current state would
		// read a window its own presence changes: with 08:00 and 17:00 already
		// a closed row, a late 12:00 finds nothing open and starts a second,
		// overlapping one. Processing all three in order instead closes 08:00
		// at 12:00 and leaves 17:00 open.
		//
		// So the affected window is rewound and replayed. That is what makes
		// the result depend on the punches rather than on the order they
		// happened to arrive in, which is the guarantee this design claims.
		int replayed = rewindLateArrivals(punches);
		if (replayed > 0) {
			punches = store.claimable(companyId, batchSize, MAX_PAIR_ATTEMPTS);
		}

		Outcome total = new Outcome(0, 0, 0, 0);
		for (Map<String, Object> punch : punches) {
			try {
				total = total.plus(pairOne(companyId, punch, weeklyRestLabel));
			} catch (RuntimeException ex) {
				// One unpairable punch must not strand the rest, and must not
				// hold the claim either: the claim takes the oldest rows under
				// a LIMIT, so a permanently failing punch would be re-selected
				// on every pass and eventually starve every employee behind it.
				long punchId = LegacyValues.toPhpLong(punch.get("id"));
				store.recordFailedAttempt(punchId);
				int attempts = LegacyValues.toPhpLong(punch.get("pair_attempts")) == 0
						? 1
						: (int) LegacyValues.toPhpLong(punch.get("pair_attempts")) + 1;
				if (attempts >= MAX_PAIR_ATTEMPTS) {
					store.quarantine(punchId, punchedAtOf(punch), FLAG_PAIRING_FAILED);
					log.error("Device punch {} for company {} failed to pair {} times and is "
									+ "quarantined as IGNORED/PAIRING_FAILED. It is kept, not "
									+ "deleted -- the device really produced it.",
							punchId, companyId, attempts, ex);
				} else {
					log.error("Could not pair device punch {} for company {} (attempt {} of {}); "
									+ "it stays RECEIVED and sinks below work that can succeed",
							punchId, companyId, attempts, MAX_PAIR_ATTEMPTS, ex);
				}
			}
		}
		return total;
	}

	/**
	 * Rewinds each employee whose batch contains a punch older than something
	 * already paired for them.
	 *
	 * <p>One rewind per employee, from their earliest late punch, so a backlog
	 * spanning days costs one replay rather than one per punch. The rewind and
	 * the re-pairing are separate transactions on purpose: a crash between them
	 * leaves punches {@code RECEIVED} with their attendance rows removed, which
	 * the next pass repairs by pairing them again. The reverse order -- pairing
	 * first, rewinding after -- has no such recovery.
	 *
	 * @return how many employees were rewound
	 */
	private int rewindLateArrivals(List<Map<String, Object>> punches) {
		// Lateness is decided on INSTANTS. Comparing wall clocks cannot see a
		// punch as late when the DST overlap gives it the same local time as
		// the one already paired, and -- since attendance moved to the runtime
		// offset -- comparing a converted value against a device-local one
		// would be two different clocks besides.
		Map<Long, LocalDateTime> earliestLate = new LinkedHashMap<>();
		for (Map<String, Object> punch : punches) {
			long employeeId = LegacyValues.toPhpLong(punch.get("employee_id"));
			LocalDateTime instant = instantOf(punch);
			LocalDateTime newestPairedInstant = store.newestPairedPunch(employeeId);
			if (newestPairedInstant == null || !instant.isBefore(newestPairedInstant)) {
				continue;
			}
			earliestLate.merge(employeeId, instant,
					(existing, candidate) -> candidate.isBefore(existing) ? candidate : existing);
		}

		for (Map.Entry<Long, LocalDateTime> entry : earliestLate.entrySet()) {
			// Back to the start of the session the late punch belongs to, not
			// the punch itself: a 12:00 arrival belongs to a session opened at
			// 08:00, and rewinding from 12:00 would leave that row standing.
			// Two bounds, because two clocks: attendance is addressed in the
			// runtime offset, punches by their stored instant.
			LocalDateTime lateInstant = entry.getValue();
			LocalDateTime lateCheckIn = lateInstant.atOffset(ZoneOffset.UTC)
					.withOffsetSameInstant(clock.offset()).toLocalDateTime();
			LocalDateTime sessionStart = store.sessionStartCovering(entry.getKey(), lateCheckIn);
			LocalDateTime fromCheckIn = sessionStart != null && sessionStart.isBefore(lateCheckIn)
					? sessionStart
					: lateCheckIn;
			// The punch bound moves back by the same amount the attendance
			// bound did, so a session opened earlier releases its own punches.
			LocalDateTime fromInstant = lateInstant.minus(
					java.time.Duration.between(fromCheckIn, lateCheckIn));
			int removed = transactions.execute(
					status -> store.rewindPairedFrom(entry.getKey(), fromCheckIn, fromInstant));
			log.info("Replaying attendance for employee {} from {}: a punch at {} arrived after "
							+ "later ones were already paired. {} attendance row(s) removed and "
							+ "their punches returned to RECEIVED.",
					entry.getKey(), fromCheckIn, lateCheckIn, removed);
		}
		return earliestLate.size();
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
		LocalDateTime punchedAt = punchedAtOf(punch);

		Map<String, Object> open = store.newestOpenRow(employeeId);
		if (open != null && isLiveAt(companyId, employeeId, open, punchedAt, weeklyRestLabel)) {
			long attendanceId = LegacyValues.toPhpLong(open.get("id"));
			LocalDateTime openedAt = checkInOf(open);

			// A terminal reading the same finger twice, or a person tapping
			// again because the beep was missed. Closing on it would record a
			// zero-length day; opening a new row would record two.
			// Elapsed time from the INSTANT, never the local clock. During an
			// autumn DST fold two events an hour apart share a
			// punched_at_local, so a local-time debounce reads the second as a
			// duplicate of the first and the session is never closed. Ingestion
			// keeps the instants distinct precisely so this can use them.
			Duration sinceOpened = elapsedBetween(open, punch, openedAt, punchedAt);
			if (openedAt != null && sinceOpened.compareTo(DEBOUNCE) < 0) {
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

		// Both anomalies can apply to one punch, so they compose rather than
		// one silently winning: a rapid re-check-in at the wrong branch is two
		// facts a reviewer needs, not one.
		String flag = joinFlags(
				isRapidRecheckIn(employeeId, punchedAt) ? FLAG_RAPID_RECHECKIN : null,
				outOfHomeBranch(employeeId, punch) ? FLAG_OUT_OF_HOME_BRANCH : null);
		// A row pairing could not rewind -- an HR correction -- may already span
		// this moment. Opening another inside it records the same stretch of the
		// day twice, so the punch is held for review instead. The raw punch is
		// never discarded; only its attribution is withheld.
		Long covering = store.closedRowCovering(employeeId, punchedAt);
		if (covering != null) {
			store.markIgnored(punchId, punchedAt, FLAG_INSIDE_CORRECTED_SESSION);
			return new Outcome(0, 0, 1, 1);
		}

		long attendanceId = store.openAttendance(employeeId, punchedAt);
		// The opener records the exact value written to attendance.check_in, so
		// nothing downstream has to infer which punch created the row.
		store.markPairedAsOpener(punchId, attendanceId, punchedAt, flag, punchedAt);
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

	/**
	 * Time between the punch that opened a session and this one, preferring the
	 * stored instants and falling back to local time.
	 *
	 * <p>The fallback exists because the opening row is an {@code attendance}
	 * row, which has no UTC column -- only the punch does. When the opening
	 * punch can still be found, its instant is used; otherwise local time is
	 * the best available, and outside a DST fold the two agree exactly.
	 */
	private Duration elapsedBetween(Map<String, Object> open, Map<String, Object> punch,
			LocalDateTime openedAt, LocalDateTime punchedAt) {
		LocalDateTime punchedAtUtc = utcOf(punch);
		LocalDateTime openedAtUtc = openedAt == null ? null : store.punchInstantAt(
				LegacyValues.toPhpLong(open.get("id")));
		if (punchedAtUtc != null && openedAtUtc != null) {
			return Duration.between(openedAtUtc, punchedAtUtc);
		}
		return openedAt == null ? Duration.ZERO : Duration.between(openedAt, punchedAt);
	}

	private static LocalDateTime utcOf(Map<String, Object> punch) {
		String value = LegacyValues.toPhpString(punch.get("punched_at_utc"));
		return value.isBlank() ? null
				: LocalDateTime.parse(value.replace(' ', 'T').substring(0, 19));
	}

	/**
	 * {@code check_in.php}'s two-hour rule, measured against the punch.
	 */
	private boolean isRapidRecheckIn(long employeeId, LocalDateTime punchedAt) {
		LocalDateTime last = store.newestCheckIn(employeeId);
		if (last == null || punchedAt.isBefore(last)) {
			return false;
		}
		return Duration.between(last, punchedAt).compareTo(RAPID_RECHECKIN_WINDOW) < 0;
	}

	/**
	 * Whether this punch happened on a device belonging to a branch that is not
	 * the employee's, without permission to roam.
	 *
	 * <p>The punch's own {@code branch_id} is used, snapshotted at ingestion
	 * rather than read back through the registry -- a terminal can be moved
	 * between branches, and reading the registry's current branch would
	 * retroactively relabel every punch it ever sent, which is exactly what
	 * makes this policy unreconstructable.
	 */
	private boolean outOfHomeBranch(long employeeId, Map<String, Object> punch) {
		Map<String, Object> policy = store.branchPolicy(employeeId);
		if (policy == null || LegacyValues.toPhpLong(policy.get("can_check_in_any_branch")) == 1) {
			return false;
		}
		long homeBranch = LegacyValues.toPhpLong(policy.get("branch_id"));
		long punchedAtBranch = LegacyValues.toPhpLong(punch.get("branch_id"));
		return homeBranch > 0 && punchedAtBranch > 0 && homeBranch != punchedAtBranch;
	}

	/** Both flags, or the one that applies, or null. */
	private static String joinFlags(String first, String second) {
		if (first == null) {
			return second;
		}
		return second == null ? first : first + "," + second;
	}

	/**
	 * The attendance timestamp for a punch, in the legacy runtime offset.
	 *
	 * <p>Derived from {@code punched_at_utc}, never {@code punched_at_local}.
	 * The local value is the device's own wall clock, so a terminal configured
	 * outside the runtime's zone wrote check-ins two or three hours away from
	 * the app and QR rows beside them in the same table -- a discrepancy
	 * nothing reported, while session deadlines and every report read it as
	 * real.
	 *
	 * <p>The offset comes from {@link LegacyClock} rather than a constant: it
	 * moves between +02:00 and +03:00, so a fixed interval would relocate the
	 * defect to the daylight-saving boundary instead of removing it.
	 */
	/** The punch's stored instant, the only value safe to order or compare by. */
	private static LocalDateTime instantOf(Map<String, Object> punch) {
		return LocalDateTime.parse(LegacyValues.toPhpString(punch.get("punched_at_utc"))
				.replace(' ', 'T').substring(0, 19));
	}

	private LocalDateTime punchedAtOf(Map<String, Object> punch) {
		String utc = LegacyValues.toPhpString(punch.get("punched_at_utc"));
		LocalDateTime instant = LocalDateTime.parse(utc.replace(' ', 'T').substring(0, 19));
		return instant.atOffset(ZoneOffset.UTC).withOffsetSameInstant(clock.offset()).toLocalDateTime();
	}

	private static LocalDateTime checkInOf(Map<String, Object> row) {
		String value = LegacyValues.toPhpString(row.get("check_in"));
		if (value.isBlank()) {
			return null;
		}
		return LocalDateTime.parse(value.replace(' ', 'T').substring(0, 19));
	}
}
