package com.workin.legacy.attendance.pairing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;

/**
 * Punch to attendance, against the real schema.
 *
 * <p>The cases that matter here are the ones a unit test cannot reach: what a
 * two-day backlog does when it all arrives at once, whether a second pass over
 * the same punches changes anything, and whether the window a device closes is
 * the same window the mobile endpoint closes.
 */
class PunchPairingServiceTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 24601L;
	private static final long BRANCH = 24611L;
	private static final long EMPLOYEE = 246011L;
	private static final long DEVICE = 24621L;

	/** A Tuesday, so the deadline scan finds ordinary working days after it. */
	private static final String DAY = "2025-06-03";
	/**
	 * The zone the seeded terminal's clock is set to. Deliberately equal to the
	 * legacy runtime's default offset, so these tests stay about PAIRING: a
	 * device that agrees with the runtime produces attendance at its own wall
	 * clock, and the existing expectations remain readable.
	 *
	 * <p>Divergence -- a terminal in another zone -- is what
	 * {@code aDeviceOutsideTheRuntimeZone...} covers, and is the case the old
	 * fixture could not express at all, because it stored punched_at_utc
	 * identical to punched_at_local.
	 */
	private static final ZoneId DEVICE_ZONE = ZoneOffset.ofHours(2);
	private static final String NEXT_DAY = "2025-06-04";

	private PunchPairingService service;
	private DataSource dataSource;
	private LegacyAttendanceSessions sessions;
	private com.workin.devices.identity.EmployeeDeviceIdentityStore identities;

	@BeforeEach
	void setUp() throws Exception {
		seedAsLegacyWould(
				"DELETE FROM device_punches WHERE company_id = " + COMPANY,
				"DELETE FROM attendance WHERE employee_id = " + EMPLOYEE,
				"DELETE FROM employee_device_identities WHERE company_id = " + COMPANY,
				"DELETE FROM employees WHERE company_id = " + COMPANY,
				"DELETE FROM branches WHERE id = " + BRANCH,
				"DELETE FROM companies WHERE id = " + COMPANY,
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES ("
						+ COMPANY + ", 'Pairing Co', '+201000024601', 'active', '2025-01-01 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
						+ BRANCH + ", " + COMPANY + ", 'HQ', 1, '2025-01-01 09:00:00')",
				"INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
						+ " phone, role, is_active, is_mobile_attendance_enabled, can_check_in_any_branch,"
						+ " join_request_status, token_version, created_at) VALUES ("
						+ EMPLOYEE + ", " + COMPANY + ", " + BRANCH + ", '7001', 'Punch', 'Pair',"
						+ " '+201100246011', 'employee', 1, 1, 0, 'accepted', 1, '2025-01-01 09:00:00')");

		this.dataSource = productionLikeDataSource();
		// The runtime-offset writers and a history row old enough to cover these
		// fixtures. Pairing fails closed without the triggers, and every punch is
		// PRE_HISTORY without a row at or before its instant -- both deliberate.
		com.workin.legacy.runtime.LegacyRuntimeOffsetHistoryTest.installHooks();
		seedAsLegacyWould("DELETE FROM legacy_runtime_offset_history");
		seedAsLegacyWould("INSERT INTO legacy_runtime_offset_history"
				+ " (effective_from_utc, offset_seconds) VALUES ('2000-01-01 00:00:00', 7200)");
		this.identities = new com.workin.devices.identity.EmployeeDeviceIdentityStore(dataSource);
		LegacyClock clock = new LegacyClock(dataSource);
		LegacyAttendanceCalendar calendar =
				new LegacyAttendanceCalendar(dataSource, new LegacyWeeklyOffDays(dataSource));
		this.sessions = new LegacyAttendanceSessions(dataSource, calendar, clock);
		this.service = new PunchPairingService(
				new PunchPairingStore(dataSource), sessions, dataSource, clock, 500);
	}

	@Test
	void aLonePunchOpensAnAttendanceRowAtItsOwnTimeAndMarksItselfPaired() throws Exception {
		long punch = punchAt(DAY + " 08:00:00");

		PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

		assertThat(outcome.opened()).isEqualTo(1);
		List<Map<String, Object>> rows = attendance();
		assertThat(rows).hasSize(1);
		assertThat(text(rows.get(0).get("check_in"))).startsWith(DAY + " 08:00:00");
		assertThat(rows.get(0).get("check_out")).isNull();
		assertThat(text(rows.get(0).get("method")))
				.describedAs("the fourth enum value, which is why the ALTER ships")
				.isEqualTo("device");

		Map<String, Object> stored = punchRow(punch);
		assertThat(text(stored.get("processing_state"))).isEqualTo("PAIRED");
		assertThat(stored.get("attendance_id"))
				.describedAs("the punch names the row it produced; that link is the retry guard")
				.isEqualTo(rows.get(0).get("id"));
	}

	@Test
	void aSecondPunchInsideTheWindowClosesTheSameRowRatherThanOpeningAnother() throws Exception {
		punchAt(DAY + " 08:00:00");
		punchAt(DAY + " 17:00:00");

		PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

		assertThat(outcome.opened()).isEqualTo(1);
		assertThat(outcome.closed()).isEqualTo(1);
		List<Map<String, Object>> rows = attendance();
		assertThat(rows).hasSize(1);
		assertThat(text(rows.get(0).get("check_out"))).startsWith(DAY + " 17:00:00");
	}

	/**
	 * The discriminating case for judging liveness as of the punch.
	 *
	 * <p>Both punches are inserted at once, as a reconnecting terminal delivers
	 * them, and both are already old. Asked "is a session live <em>now</em>",
	 * the answer for both is no -- the deadline passed long ago -- and pairing
	 * would open two check-ins and close neither. Asked as of the punch, the
	 * 17:00 record closes the 08:00 one, which is what actually happened.
	 */
	@Test
	void aBacklogDeliveredLatePairsByWhenItHappenedNotByWhenItArrived() throws Exception {
		punchAt("2025-06-03 08:00:00");
		punchAt("2025-06-03 17:00:00");

		service.pairCompany(COMPANY, "friday");

		List<Map<String, Object>> rows = attendance();
		assertThat(rows).describedAs("one day of work, not two unclosed arrivals").hasSize(1);
		assertThat(text(rows.get(0).get("check_in"))).startsWith("2025-06-03 08:00:00");
		assertThat(text(rows.get(0).get("check_out"))).startsWith("2025-06-03 17:00:00");
	}

	/**
	 * Past the 16-hour cap (D-213), the earlier row is not a session any more,
	 * so the punch opens its own rather than closing something a day old.
	 */
	@Test
	void aPunchAfterTheDeadlineOpensANewRowAndLeavesTheStaleOneOpen() throws Exception {
		punchAt(DAY + " 08:00:00");
		punchAt(NEXT_DAY + " 08:00:00");

		PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

		assertThat(outcome.opened()).isEqualTo(2);
		assertThat(outcome.closed()).isZero();
		List<Map<String, Object>> rows = attendance();
		assertThat(rows).hasSize(2);
		assertThat(rows).allSatisfy(row -> assertThat(row.get("check_out"))
				.describedAs("the stale row is left open, never closed synthetically")
				.isNull());
	}

	/**
	 * A terminal reading the same finger twice. Closing on it would record a
	 * zero-length day; opening a second row would record two arrivals.
	 */
	@Test
	void aDoubleReadWithinTheDebounceIsIgnoredRatherThanPairedOrDropped() throws Exception {
		punchAt(DAY + " 08:00:00");
		long second = punchAt(DAY + " 08:00:30");

		PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

		assertThat(outcome.opened()).isEqualTo(1);
		assertThat(outcome.ignored()).isEqualTo(1);
		assertThat(attendance()).hasSize(1);
		assertThat(attendance().get(0).get("check_out")).isNull();

		Map<String, Object> stored = punchRow(second);
		assertThat(text(stored.get("processing_state")))
				.describedAs("terminal, so later passes stop reconsidering it")
				.isEqualTo("IGNORED");
		assertThat(text(stored.get("review_flag"))).isEqualTo("DOUBLE_READ");
		assertThat(stored.get("attendance_id"))
				.describedAs("it produced no attendance row").isNull();
	}

	/**
	 * Legacy refuses a check-in inside two hours of the last one. A terminal
	 * cannot be refused -- it has already said "Thank you" and dropped the
	 * record -- so the punch is kept and flagged instead (D-165).
	 */
	@Test
	void aRapidReCheckInIsFlaggedForReviewAndNeverRejected() throws Exception {
		punchAt(DAY + " 08:00:00");   // opens
		punchAt(DAY + " 08:05:00");   // closes: past the debounce, inside the window
		long third = punchAt(DAY + " 09:00:00");   // opens again, 60 minutes after the first

		PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

		Map<String, Object> stored = punchRow(third);
		assertThat(text(stored.get("processing_state")))
				.describedAs("kept, because the evidence of presence is real")
				.isEqualTo("PAIRED");
		assertThat(text(stored.get("review_flag")))
				.describedAs("legacy would have refused this; a terminal cannot be refused, so it is flagged")
				.isEqualTo("RAPID_RECHECKIN");
		assertThat(outcome.flagged()).isEqualTo(1);

		// Two rows, and that is the point: legacy's rule exists to stop a
		// second check-in this soon, so the day being split in two is the
		// visible consequence a human is being asked to look at.
		assertThat(attendance()).hasSize(2);
		assertThat(attendance().get(1).get("check_out")).isNull();
	}

	/**
	 * The idempotency property the whole design turns on: a second pass over
	 * punches that are already paired must find nothing to do.
	 */
	@Test
	void aSecondPassChangesNothingBecauseOnlyReceivedPunchesAreClaimed() throws Exception {
		punchAt(DAY + " 08:00:00");
		punchAt(DAY + " 17:00:00");
		service.pairCompany(COMPANY, "friday");
		List<Map<String, Object>> afterFirst = attendance();

		PunchPairingService.Outcome again = service.pairCompany(COMPANY, "friday");

		assertThat(again.total()).describedAs("nothing left in RECEIVED").isZero();
		assertThat(attendance()).isEqualTo(afterFirst);
	}

	/**
	 * The crash-safety property, proven rather than asserted.
	 *
	 * <p>A punch pointing at an employee row that no longer exists fails the
	 * {@code attendance} foreign key, so the insert throws part-way through
	 * pairing. If the attendance write and the punch's state change were not
	 * one transaction, the punch could be left {@code PAIRED} while naming a
	 * row that was never committed -- and no later pass would look at it again,
	 * because passes claim only {@code RECEIVED}. That is the loss this design
	 * exists to make impossible.
	 *
	 * <p>It stays {@code RECEIVED}, so the next pass retries it, and the failure
	 * does not strand the punches beside it: the good one still pairs.
	 */
	@Test
	void aPunchThatFailsMidWriteStaysReceivedAndDoesNotStrandTheRest() throws Exception {
		long doomed = insertPunch(DAY + " 08:00:00", "999999", "RECEIVED");
		long healthy = punchAt(DAY + " 09:30:00");

		PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

		assertThat(text(punchRow(doomed).get("processing_state")))
				.describedAs("nothing was committed for it, so it is still work to do")
				.isEqualTo("RECEIVED");
		assertThat(punchRow(doomed).get("attendance_id")).isNull();

		assertThat(outcome.opened()).describedAs("one bad punch does not stop the pass").isEqualTo(1);
		assertThat(text(punchRow(healthy).get("processing_state"))).isEqualTo("PAIRED");
	}

	/**
	 * The transaction is doing the work, and this is the test that says so.
	 *
	 * <p>The test above proves the punch survives a failure, but it does not
	 * prove <em>why</em>: there, the foreign key rejects the insert before
	 * anything is written, so the punch would stay {@code RECEIVED} with or
	 * without a transaction. The dangerous ordering is the opposite one -- the
	 * attendance row is written and the punch's state change then fails. With
	 * no transaction that leaves an orphan attendance row and a punch still
	 * marked as work to do, so the next pass writes a <b>second</b> attendance
	 * row for the same punch. That is the double-processing the design promises
	 * cannot happen.
	 *
	 * <p>So the failure is injected exactly there, between the two writes.
	 */
	@Test
	void aFailureBetweenTheTwoWritesRollsBackTheAttendanceRowAsWell() throws Exception {
		punchAt(DAY + " 08:00:00");

		DataSource dataSource = productionLikeDataSource();
		PunchPairingStore failing = new PunchPairingStore(dataSource) {
			// BOTH marks are overridden. The opening path calls
			// markPairedAsOpener and the closing path calls markPaired, so
			// stubbing only one silently moves the injection point off the
			// path under test and the crash never happens -- the failure looks
			// like a rollback bug when it is really a test that stopped
			// testing.
			@Override
			public void markPairedAsOpener(long punchId, long attendanceId,
					java.time.LocalDateTime pairedAt, String reviewFlag,
					java.time.LocalDateTime checkInAt,
					PunchPairingStore.RuntimeOffsetProvenance provenance) {
				throw new IllegalStateException("crash between the attendance write and the punch's state");
			}

			@Override
			public void markPaired(long punchId, long attendanceId,
					java.time.LocalDateTime pairedAt, String reviewFlag,
					PunchPairingStore.RuntimeOffsetProvenance provenance) {
				throw new IllegalStateException("crash between the attendance write and the punch's state");
			}
		};
		LegacyClock clock = new LegacyClock(dataSource);
		LegacyAttendanceCalendar calendar =
				new LegacyAttendanceCalendar(dataSource, new LegacyWeeklyOffDays(dataSource));
		PunchPairingService crashing = new PunchPairingService(failing,
				new LegacyAttendanceSessions(dataSource, calendar, clock), dataSource, clock, 500);

		crashing.pairCompany(COMPANY, "friday");

		assertThat(attendance())
				.describedAs("the attendance row went back with the punch, or the next pass would write a second one")
				.isEmpty();
	}

	/** An unresolved PIN is not this pass's work: it has no employee to pair to. */
	@Test
	void aPunchWhosePinNeverResolvedIsNotClaimed() throws Exception {
		long orphan = unmatchedPunchAt(DAY + " 08:00:00");

		PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

		assertThat(outcome.total()).isZero();
		assertThat(attendance()).isEmpty();
		assertThat(text(punchRow(orphan).get("processing_state"))).isEqualTo("UNMATCHED");
	}

	/**
	 * A punch older than ones already paired rewinds and replays the window.
	 *
	 * <p>The guarantee this design claims is that pairing depends on the
	 * punches and the schedule, not on the order they arrived. Without a
	 * replay that is only true within a single pass: 08:00 and 17:00 pair into
	 * one closed row, and a 12:00 arriving afterwards finds nothing open and
	 * starts a second, overlapping one -- two rows for one day, the later of
	 * them never closed.
	 *
	 * <p>Processed in order, the same three punches close 08:00 at 12:00 and
	 * leave 17:00 open. This asserts the late arrival produces exactly that,
	 * which is what "replayable" has to mean.
	 */
	@Test
	void aPunchArrivingAfterLaterOnesWereAlreadyPairedRewindsAndReplaysTheDay() throws Exception {
		punchAt(DAY + " 08:00:00");
		punchAt(DAY + " 17:00:00");
		service.pairCompany(COMPANY, "friday");
		assertThat(attendance()).describedAs("the two-punch day pairs into one closed row").hasSize(1);

		// The late one: a terminal reconnecting with a buffered record.
		punchAt(DAY + " 12:00:00");

		service.pairCompany(COMPANY, "friday");

		List<Map<String, Object>> rows = attendance();
		assertThat(rows).describedAs("08:00-12:00 closed, 17:00 open -- not an overlapping pair").hasSize(2);
		assertThat(text(rows.get(0).get("check_in"))).startsWith(DAY + " 08:00:00");
		assertThat(text(rows.get(0).get("check_out"))).startsWith(DAY + " 12:00:00");
		assertThat(text(rows.get(1).get("check_in"))).startsWith(DAY + " 17:00:00");
		assertThat(rows.get(1).get("check_out")).isNull();
	}

	/**
	 * A replay must not discard a row a human has touched.
	 *
	 * <p>The rewind removes only what pairing itself created and left alone:
	 * named by a punch's {@code attendance_id}, still {@code method='device'},
	 * still carrying the {@code check_in} the punch recorded. An HR correction
	 * fails that test, so the row survives and the replay works around it.
	 */
	@Test
	void aReplayLeavesAnAttendanceRowAHumanHasEditedAlone() throws Exception {
		punchAt(DAY + " 08:00:00");
		punchAt(DAY + " 17:00:00");
		service.pairCompany(COMPANY, "friday");

		// HR corrects the arrival: same row, different check_in.
		seedAsLegacyWould("UPDATE attendance SET check_in = '" + DAY + " 07:45:00'"
				+ " WHERE employee_id = " + EMPLOYEE);

		punchAt(DAY + " 12:00:00");
		service.pairCompany(COMPANY, "friday");

		assertThat(query("SELECT id FROM attendance WHERE employee_id = " + EMPLOYEE
				+ " AND check_in = '" + DAY + " 07:45:00'"))
				.describedAs("the corrected row is still there -- a replay does not delete a human's work")
				.hasSize(1);
	}

	/**
	 * A PIN bound to a departed employee never reaches the employee_code
	 * fallback.
	 *
	 * <p>An explicit binding claims the PIN whether or not its employee is
	 * still active. Treating an inactive binding as "unbound" sent the punch to
	 * the fallback, where an active colleague whose {@code employee_code}
	 * happens to equal that PIN absorbed it -- attendance recorded against the
	 * wrong person. The documented rule is that a departed badge is
	 * {@code UNMATCHED} for review.
	 */
	@Test
	void aPinBoundToADepartedEmployeeDoesNotFallThroughToAColleaguesCode() throws Exception {
		long departed = 246012L;
		long colleague = 246013L;
		seedAsLegacyWould(
				"INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
						+ " phone, role, is_active, join_request_status, created_at) VALUES ("
						+ departed + ", " + COMPANY + ", " + BRANCH + ", '9001', 'Gone', 'Person',"
						+ " '+201100246012', 'employee', 0, 'accepted', '2025-01-01 09:00:00')",
				// The colleague's CODE equals the departed employee's PIN.
				"INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
						+ " phone, role, is_active, join_request_status, created_at) VALUES ("
						+ colleague + ", " + COMPANY + ", " + BRANCH + ", '7777', 'Still', 'Here',"
						+ " '+201100246013', 'employee', 1, 'accepted', '2025-01-01 09:00:00')",
				"INSERT INTO employee_device_identities (company_id, employee_id, pin, source,"
						+ " created_at, updated_at) VALUES (" + COMPANY + ", " + departed
						+ ", '7777', 'MANUAL', '2025-01-01 09:00:00', '2025-01-01 09:00:00')");

		Map<String, Long> resolved = identities.resolveEmployeeIds(COMPANY, List.of("7777"));

		assertThat(resolved)
				.describedAs("claimed by a departed employee, so it resolves to nobody -- "
						+ "and above all not to the colleague whose code is 7777")
				.doesNotContainKey("7777");
	}

	/**
	 * Without the fourth enum value, pairing refuses rather than writing a
	 * blank method.
	 *
	 * <p>The runbook claimed the INSERT would be refused. It would not: every
	 * connection runs {@code sql_mode=''}, under which MariaDB stores the
	 * empty-string error value for an out-of-range ENUM and warns. The punch
	 * would then be marked PAIRED against a row whose method is blank, and no
	 * later pass would revisit it. Refusing leaves it RECEIVED, which is
	 * recoverable.
	 */
	@Test
	void pairingRefusesWhenTheMethodEnumHasNotBeenWidened() throws Exception {
		long punch = punchAt(DAY + " 08:00:00");
		seedAsLegacyWould("ALTER TABLE attendance MODIFY COLUMN method"
				+ " ENUM('app','excel','qr') NOT NULL DEFAULT 'app'");
		try {
			// A fresh store, so the cached enum probe is taken after the ALTER.
			PunchPairingService guarded = new PunchPairingService(
					new PunchPairingStore(dataSource), sessions, dataSource, new LegacyClock(dataSource), 500);

			PunchPairingService.Outcome outcome = guarded.pairCompany(COMPANY, "friday");

			assertThat(outcome.total()).describedAs("nothing paired").isZero();
			assertThat(attendance()).describedAs("and nothing written").isEmpty();
			assertThat(text(punchRow(punch).get("processing_state")))
					.describedAs("still claimable, so it pairs once the DDL is applied")
					.isEqualTo("RECEIVED");
		} finally {
			seedAsLegacyWould("ALTER TABLE attendance MODIFY COLUMN method"
					+ " ENUM('app','excel','qr','device') NOT NULL DEFAULT 'app'");
		}
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	/**
	 * A data source that connects the way the application does.
	 *
	 * <p>{@code application.properties} sets
	 * {@code connection-init-sql=SET SESSION sql_mode=''} on every legacy
	 * connection, because the vendored data requires it. A plain
	 * {@code DriverManagerDataSource} does not, so it connects in the
	 * container's STRICT mode -- and strict MariaDB rejects an out-of-range
	 * ENUM where non-strict silently stores the empty-string error value.
	 *
	 * <p>That difference is the entire subject of
	 * {@code pairingRefusesWhenTheMethodEnumHasNotBeenWidened}. Under strict
	 * mode the bad INSERT throws, the punch stays RECEIVED, and the test passes
	 * whether or not the guard exists -- proving nothing, which is exactly what
	 * it did before this existed. Every test here uses it so the database under
	 * test behaves like the real one.
	 */
	private static DataSource productionLikeDataSource() {
		// The same statement application.properties runs, run the same way:
		// once per connection, before anything else uses it. Done here rather
		// than through a URL parameter because the driver's sessionVariables
		// syntax does not take an empty value cleanly.
		return new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()) {
			@Override
			protected Connection getConnectionFromDriver(java.util.Properties props)
					throws java.sql.SQLException {
				Connection connection = super.getConnectionFromDriver(props);
				try (Statement statement = connection.createStatement()) {
					statement.execute("SET SESSION sql_mode=''");
				}
				return connection;
			}
		};
	}

	private static long punchAt(String localTime) throws Exception {
		return insertPunch(localTime, String.valueOf(EMPLOYEE), "RECEIVED");
	}

	private static long unmatchedPunchAt(String localTime) throws Exception {
		return insertPunch(localTime, "NULL", "UNMATCHED");
	}

	/**
	 * The real UTC instant for a device wall-clock time.
	 *
	 * <p>This fixture used to store {@code punched_at_utc} identical to
	 * {@code punched_at_local}, which is physically impossible for a device in
	 * Cairo and is why the clock-basis defect survived: with the two columns
	 * equal, reading either one gave the same answer, so a test could not tell
	 * a correct implementation from one copying the device's wall clock.
	 */
	private static String utcOf(String deviceLocalTime) {
		return LocalDateTime.parse(deviceLocalTime.replace(' ', 'T'))
				.atZone(DEVICE_ZONE)
				.withZoneSameInstant(ZoneOffset.UTC)
				.toLocalDateTime()
				.toString()
				.replace('T', ' ');
	}

	private static long insertPunch(String localTime, String employee, String state) throws Exception {
		String key = String.format("%064x", (localTime + employee + state).hashCode() & 0xffffffffL);
		seedAsLegacyWould("INSERT INTO device_punches (device_id, company_id, branch_id, employee_id,"
				+ " pin, punched_at_local, punched_at_utc, received_at, dedup_key, raw_line,"
				+ " processing_state) VALUES (" + DEVICE + ", " + COMPANY + ", " + BRANCH + ", "
				+ employee + ", '7001', '" + localTime + "', '" + utcOf(localTime) + "', '" + localTime
				+ "', '" + key + "', 'seed', '" + state + "')");
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT id FROM device_punches WHERE dedup_key = '" + key + "'")) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static List<Map<String, Object>> attendance() throws Exception {
		return query("SELECT id, check_in, check_out, method FROM attendance WHERE employee_id = "
				+ EMPLOYEE + " ORDER BY id");
	}

	private static Map<String, Object> punchRow(long id) throws Exception {
		return query("SELECT processing_state, attendance_id, review_flag FROM device_punches WHERE id = " + id)
				.get(0);
	}

	private static List<Map<String, Object>> query(String sql) throws Exception {
		List<Map<String, Object>> rows = new ArrayList<>();
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				Map<String, Object> row = new LinkedHashMap<>();
				for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
					row.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
				}
				rows.add(row);
			}
		}
		return rows;
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	@Test
	void everyPunchRecordsWhichRuntimeOffsetProducedItsTimestamp() throws Exception {
		// Both provenance columns existed and neither was ever written, so
		// every punch carried the 'EXACT' default with a null offset beside it
		// -- including pre-history punches, which pairing refuses precisely
		// BECAUSE their offset is unknown. The schema asserted certainty about
		// a value it did not hold.
		long paired = punchAt(DAY + " 08:00:00");

		service.pairCompany(COMPANY, "friday");

		Map<String, Object> row = query("SELECT legacy_runtime_offset_seconds,"
				+ " runtime_offset_resolution FROM device_punches WHERE id = " + paired).get(0);
		assertThat(text(row.get("runtime_offset_resolution")))
				.isEqualTo(PunchPairingService.RUNTIME_OFFSET_EXACT);
		assertThat(Long.parseLong(text(row.get("legacy_runtime_offset_seconds"))))
				.as("the offset actually used, not a null beside a confident label")
				.isEqualTo(7200L);
	}

	@Test
	void theClosingPunchRecordsProvenanceToo() throws Exception {
		// markPaired's provenance columns were folded in with the opener's and
		// nothing asserted them, so deleting them from the closer's UPDATE broke
		// no test while the javadoc claimed provenance is recorded on every
		// path. The closer is the one disposition that had none before.
		punchAt(DAY + " 08:00:00");
		long closer = punchAt(DAY + " 17:00:00");

		PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

		// Pinned as a CLOSER, not merely PAIRED. Both dispositions produce
		// PAIRED, so without this a regression that made the 17:00 punch open a
		// second row would still pass here -- through markPairedAsOpener -- and
		// silently stop covering markPaired, which is the method under test.
		assertThat(outcome.closed()).as("the 17:00 punch closed the morning row").isEqualTo(1);
		Map<String, Object> row = query("SELECT legacy_runtime_offset_seconds,"
				+ " runtime_offset_resolution, processing_state, attendance_check_in_at"
				+ " FROM device_punches WHERE id = " + closer).get(0);
		assertThat(text(row.get("processing_state"))).isEqualTo("PAIRED");
		assertThat(row.get("attendance_check_in_at"))
				.as("only the opener stamps this, so a null here proves markPaired ran")
				.isNull();
		assertThat(text(row.get("runtime_offset_resolution")))
				.isEqualTo(PunchPairingService.RUNTIME_OFFSET_EXACT);
		assertThat(Long.parseLong(text(row.get("legacy_runtime_offset_seconds"))))
				.as("the offset that produced the check-out's timestamp")
				.isEqualTo(7200L);
	}

	@Test
	void aPrePunchHistoryPunchIsRecordedAsPreHistoryRatherThanExact() throws Exception {
		// Older than the one history row the fixture seeds, so no offset
		// governs it. This is the row the old default described as EXACT.
		seedAsLegacyWould("DELETE FROM legacy_runtime_offset_history");
		seedAsLegacyWould("INSERT INTO legacy_runtime_offset_history"
				+ " (effective_from_utc, offset_seconds) VALUES ('2030-01-01 00:00:00', 7200)");
		long orphan = punchAt(DAY + " 08:00:00");

		service.pairCompany(COMPANY, "friday");

		Map<String, Object> row = query("SELECT legacy_runtime_offset_seconds,"
				+ " runtime_offset_resolution, processing_state FROM device_punches WHERE id = "
				+ orphan).get(0);
		assertThat(text(row.get("runtime_offset_resolution")))
				.as("the one case where the offset is definitively unknown")
				.isEqualTo(PunchPairingService.RUNTIME_OFFSET_PRE_HISTORY);
		assertThat(row.get("legacy_runtime_offset_seconds")).isNull();
		assertThat(text(row.get("processing_state"))).isEqualTo("IGNORED");
	}

	@Test
	void theWrongBranchIsFlaggedOnEveryOutcome() throws Exception {
		// outOfHomeBranch was evaluated only on the open-session path, so a
		// check-OUT at the wrong branch and a debounced duplicate read at the
		// wrong branch carried no signal -- and those are exactly the cases a
		// reviewer is looking for. Where the punch happened is a fact about the
		// punch, not about which outcome it took.
		long foreign = BRANCH + 77;
		seedAsLegacyWould("INSERT INTO branches (id, company_id, name, is_active, created_at)"
				+ " VALUES (" + foreign + ", " + COMPANY + ", 'Away', 1, '2025-01-01 09:00:00')");

		long opener = punchAt(DAY + " 08:00:00");
		long closer = punchAt(DAY + " 17:00:00");
		seedAsLegacyWould("UPDATE device_punches SET branch_id = " + foreign
				+ " WHERE id IN (" + opener + ", " + closer + ")");

		service.pairCompany(COMPANY, "friday");

		assertThat(text(punchRow(opener).get("review_flag")))
				.as("the opener was already flagged")
				.contains(PunchPairingService.FLAG_OUT_OF_HOME_BRANCH);
		assertThat(text(punchRow(closer).get("review_flag")))
				.as("and the check-out must be too -- it is the same wrong branch")
				.contains(PunchPairingService.FLAG_OUT_OF_HOME_BRANCH);
	}

	@Test
	void aPunchThatCanNeverPairIsQuarantinedOnceItReachesTheAttemptCap() throws Exception {
		// The claim filtered on pair_attempts but never PROJECTED it, so the
		// service read every failure as attempt 1 and the cap below was
		// unreachable. The row then stopped being claimable (pair_attempts <
		// max) while still sitting in RECEIVED -- invisible to pairing, and
		// still looking like pending work to an operator.
		long punch = punchAt(DAY + " 08:00:00");
		// The motivating case from the claim's own comment: the employee is
		// gone between ingestion and pairing, so the attendance insert can
		// never succeed.
		seedAsLegacyWould("DELETE FROM employees WHERE id = " + EMPLOYEE);

		for (int pass = 0; pass < PunchPairingService.MAX_PAIR_ATTEMPTS; pass++) {
			service.pairCompany(COMPANY, "friday");
		}

		Map<String, Object> row = punchRow(punch);
		assertThat(text(row.get("processing_state")))
				.as("after %d failed passes the punch must be quarantined, not left RECEIVED",
						PunchPairingService.MAX_PAIR_ATTEMPTS)
				.isEqualTo("IGNORED");
		assertThat(text(row.get("review_flag"))).contains(PunchPairingService.FLAG_PAIRING_FAILED);
	}

	@Test
	void aRetriedPunchDoesNotOvertakeAnEarlierOneFromTheSameDay() throws Exception {
		// Ordering the claim by pair_attempts sank failures, which was the
		// intent -- but it reorders an employee's own day. Pairing is stateful
		// per employee, so processing 17:00 before 08:00 opens the evening
		// session first and leaves the morning punch to open a SECOND row:
		// two open sessions, in reverse order, from one ordinary day.
		long morning = punchAt(DAY + " 08:00:00");
		punchAt(DAY + " 17:00:00");
		// The morning punch has already failed once; the evening one has not.
		seedAsLegacyWould("UPDATE device_punches SET pair_attempts = 1 WHERE id = " + morning);

		service.pairCompany(COMPANY, "friday");

		List<Map<String, Object>> rows = attendance();
		assertThat(rows).as("one session for the day, not one per punch").hasSize(1);
		assertThat(text(rows.get(0).get("check_in"))).startsWith(DAY + " 08:00:00");
		assertThat(text(rows.get(0).get("check_out"))).startsWith(DAY + " 17:00:00");
	}

	@Test
	void anUnsupportedMethodEnumIsReprobedRatherThanCachedForTheLifeOfTheJvm() throws Exception {
		// The guard's log tells the operator that punches pair on the next
		// pass once the DDL is applied. Caching the negative made that untrue:
		// the answer froze for the life of the JVM, so the documented remedy
		// silently needed an application restart nobody mentioned.
		PunchPairingStore store = new PunchPairingStore(dataSource);
		seedAsLegacyWould("ALTER TABLE attendance"
				+ " MODIFY COLUMN method ENUM('app', 'excel', 'qr') NOT NULL DEFAULT 'app'");
		assertThat(store.attendanceMethodAcceptsDevice())
				.as("without the DDL the guard must refuse").isFalse();

		// Exactly the documented remedy, with no restart: same store instance.
		seedAsLegacyWould("ALTER TABLE attendance"
				+ " MODIFY COLUMN method ENUM('app', 'excel', 'qr', 'device') NOT NULL DEFAULT 'app'");

		assertThat(store.attendanceMethodAcceptsDevice())
				.as("applying the DDL must be enough; a restart must not be required")
				.isTrue();
	}

	@Test
	void reviewFlagColumnHoldsTheLongestCombinationPairingCanProduce() throws Exception {
		// Production runs sql_mode='' (application.properties), where an
		// over-long value is silently TRUNCATED rather than rejected. A column
		// one character too narrow therefore stores a flag no exact review
		// filter can match, with no error anywhere. Derived from the flags
		// themselves so adding a longer one fails here rather than in review.
		List<String> flags = List.of(PunchPairingService.FLAG_RAPID_RECHECKIN,
				PunchPairingService.FLAG_DOUBLE_READ,
				PunchPairingService.FLAG_OUT_OF_HOME_BRANCH,
				PunchPairingService.FLAG_PAIRING_FAILED);
		int longestPair = 0;
		for (String first : flags) {
			for (String second : flags) {
				if (!first.equals(second)) {
					longestPair = Math.max(longestPair, (first + "," + second).length());
				}
			}
		}

		int width;
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet columns = statement.executeQuery(
						"SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS"
								+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'device_punches'"
								+ " AND COLUMN_NAME = 'review_flag'")) {
			assertThat(columns.next()).as("device_punches.review_flag must exist").isTrue();
			width = columns.getInt(1);
		}

		assertThat(width)
				.as("review_flag must hold %d chars (longest pair pairing composes)", longestPair)
				.isGreaterThanOrEqualTo(longestPair);
	}


	// ---- clock basis and opening-punch provenance -------------------------

	@Test
	void aDeviceOutsideTheRuntimeZoneStillWritesAttendanceInTheRuntimeOffset() throws Exception {
		// A terminal in London: 09:00 there is 08:00 UTC, which the legacy
		// runtime (+02:00) calls 10:00. Copying the device wall clock would
		// have written 09:00 and put this row an hour away from the app and QR
		// rows beside it -- with nothing reporting the discrepancy.
		String londonLocal = DAY + " 09:00:00";
		insertPunchWithUtc(londonLocal, londonUtc(londonLocal));

		service.pairCompany(COMPANY, "friday");

		assertThat(attendance()).hasSize(1);
		assertThat(attendance().get(0).get("check_in").toString())
				.as("attendance is written in the runtime offset, not the device's wall clock")
				.startsWith(DAY + " 10:00:00");
	}

	@Test
	void theOpeningPunchIsFoundByProvenanceNotByATimestampCoincidence() throws Exception {
		// A LONDON terminal on purpose. With a device that agrees with the
		// runtime offset, punched_at_local happens to equal check_in, so the old
		// "match the timestamps" lookup still works and this test would prove
		// nothing -- which is exactly what an earlier version of it did.
		// Divergence is what separates provenance from coincidence.
		insertPunchWithUtc(DAY + " 09:00:00", londonUtc(DAY + " 09:00:00"));
		insertPunchWithUtc(DAY + " 17:00:00", londonUtc(DAY + " 17:00:00"));
		service.pairCompany(COMPANY, "friday");

		List<Map<String, Object>> punches = query(
				"SELECT id, attendance_check_in_at FROM device_punches WHERE employee_id = "
						+ EMPLOYEE + " ORDER BY punched_at_local");
		assertThat(punches).hasSize(2);
		assertThat(punches.get(0).get("attendance_check_in_at"))
				.as("the opener carries the exact value written to attendance.check_in").isNotNull();
		assertThat(punches.get(1).get("attendance_check_in_at"))
				.as("the closer shares the attendance_id but must NOT look like the opener").isNull();

		long attendanceId = ((Number) attendance().get(0).get("id")).longValue();
		assertThat(store().punchInstantAt(attendanceId))
				.as("the opener's stored UTC instant is still reachable after the basis change")
				.isEqualTo(LocalDateTime.parse(londonUtc(DAY + " 09:00:00").replace(' ', 'T')));
	}

	@Test
	void anHrEditedCheckInSurvivesRewindWhileAnUntouchedRowDoesNot() throws Exception {
		punchAt(DAY + " 08:00:00");
		service.pairCompany(COMPANY, "friday");
		long edited = ((Number) attendance().get(0).get("id")).longValue();
		seedAsLegacyWould("UPDATE attendance SET check_in = '" + DAY + " 07:45:00' WHERE id = " + edited);

		// Both bounds set to the start of the day in their own clock -- the
		// attendance bound in the runtime offset, the punch bound as an instant.
		store().rewindPairedFrom(EMPLOYEE,
				LocalDateTime.parse(DAY + "T00:00:00"),
				LocalDateTime.parse(DAY + "T00:00:00").minusHours(2));
		assertThat(attendance())
				.as("an HR correction moves check_in off the provenance value, so the row is left alone")
				.hasSize(1);

		// And the control: an untouched pairing-created row IS removed.
		seedAsLegacyWould("DELETE FROM attendance WHERE id = " + edited);
		seedAsLegacyWould("UPDATE device_punches SET processing_state = 'RECEIVED',"
				+ " attendance_id = NULL, attendance_check_in_at = NULL WHERE employee_id = " + EMPLOYEE);
		service.pairCompany(COMPANY, "friday");
		assertThat(attendance()).hasSize(1);

		// Both bounds set to the start of the day in their own clock -- the
		// attendance bound in the runtime offset, the punch bound as an instant.
		store().rewindPairedFrom(EMPLOYEE,
				LocalDateTime.parse(DAY + "T00:00:00"),
				LocalDateTime.parse(DAY + "T00:00:00").minusHours(2));
		assertThat(attendance()).as("an untouched pairing-created row is still rewindable").isEmpty();
	}

	@Test
	void aDaylightSavingDateUsesTheRuntimeOffsetRatherThanAFixedInterval() throws Exception {
		// The runtime offset moves +02:00 <-> +03:00, which is exactly why the
		// conversion must ask LegacyClock instead of adding a constant. Whatever
		// the configured offset is, the attendance value must be the punch's
		// stored instant expressed in it -- never the device's wall clock.
		String local = DAY + " 08:00:00";
		insertPunchWithUtc(local, utcOf(local));
		service.pairCompany(COMPANY, "friday");

		LocalDateTime expected = LocalDateTime.parse(utcOf(local).replace(' ', 'T'))
				.atOffset(ZoneOffset.UTC)
				.withOffsetSameInstant(new LegacyClock(productionLikeDataSource()).offset())
				.toLocalDateTime();
		assertThat(attendance().get(0).get("check_in").toString())
				.startsWith(expected.toString().replace('T', ' '));
	}

	private static String londonUtc(String londonLocal) {
		return LocalDateTime.parse(londonLocal.replace(' ', 'T'))
				.atZone(ZoneId.of("Europe/London")).withZoneSameInstant(ZoneOffset.UTC)
				.toLocalDateTime().toString().replace('T', ' ');
	}

	private PunchPairingStore store() {
		return new PunchPairingStore(this.dataSource);
	}

	private static long insertPunchWithUtc(String localTime, String utcTime) throws Exception {
		String key = String.format("%064x", (localTime + utcTime).hashCode() & 0xffffffffL);
		seedAsLegacyWould("INSERT INTO device_punches (device_id, company_id, branch_id, employee_id,"
				+ " pin, punched_at_local, punched_at_utc, received_at, dedup_key, raw_line,"
				+ " processing_state) VALUES (" + DEVICE + ", " + COMPANY + ", " + BRANCH + ", "
				+ EMPLOYEE + ", '7001', '" + localTime + "', '" + utcTime + "', '" + localTime
				+ "', '" + key + "', 'seed', 'RECEIVED')");
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT id FROM device_punches WHERE dedup_key = '" + key + "'")) {
			rs.next();
			return rs.getLong(1);
		}
	}


	@Test
	void aDstOverlapPairsByStoredInstantWhateverOrderTheArrivalsCameIn() throws Exception {
		// The autumn fallback: the wall clock reads 02:30 twice, an hour apart.
		// Both punches therefore carry the SAME punched_at_local and DIFFERENT
		// punched_at_utc -- and the later instant is delivered first, so the
		// arrival-derived id disagrees with chronology too.
		//
		// Ordering by (punched_at_local, id) puts the 01:30Z punch first and
		// then cannot see the 00:30Z punch as late, because their LOCAL times
		// are equal and "strictly earlier local" is false. The earlier punch is
		// then debounced against a newer instant, producing a negative duration
		// and a DOUBLE_READ on the punch that should have opened the session.
		String overlapLocal = "2025-10-31 02:30:00";
		insertPunchWithUtc(overlapLocal, "2025-10-31 01:30:00");   // LATER instant, arrives FIRST
		insertPunchWithUtc(overlapLocal, "2025-10-31 00:30:00");   // EARLIER instant, arrives SECOND

		service.pairCompany(COMPANY, "friday");

		List<Map<String, Object>> rows = attendance();
		assertThat(rows).as("the two instants are one session, not two").hasSize(1);
		assertThat(rows.get(0).get("check_in").toString())
				.as("the EARLIER instant opens the session: 00:30Z at +02:00")
				.startsWith("2025-10-31 02:30:00");
		assertThat(rows.get(0).get("check_out"))
				.as("the LATER instant closes it rather than being discarded")
				.isNotNull();
		assertThat(rows.get(0).get("check_out").toString())
				.as("closed at the later instant: 01:30Z at +02:00")
				.startsWith("2025-10-31 03:30:00");

		assertThat(query("SELECT review_flag FROM device_punches WHERE employee_id = " + EMPLOYEE
				+ " AND review_flag = 'DOUBLE_READ'"))
				.as("neither punch is a double read -- they are an hour apart by instant")
				.isEmpty();
	}


	@Test
	void aLaterPunchDoesNotTriggerASpuriousReplayOfAnAlreadyPairedSession() throws Exception {
		// Lateness compares the arriving punch's INSTANT against the newest
		// already-paired punch. If that newest value is read as a wall clock
		// while the arriving one is an instant, the two are on different clocks
		// and an ordinary later punch looks late: 09:00 local is 07:00Z, which
		// is "before" a previous punch's 08:00 LOCAL. The day is then rewound
		// and replayed for nothing.
		//
		// The final state converges either way, so the observable difference is
		// that the attendance row is deleted and recreated -- a new id.
		punchAt(DAY + " 08:00:00");
		service.pairCompany(COMPANY, "friday");
		long firstId = ((Number) attendance().get(0).get("id")).longValue();

		punchAt(DAY + " 09:00:00");
		service.pairCompany(COMPANY, "friday");

		assertThat(attendance()).hasSize(1);
		assertThat(((Number) attendance().get(0).get("id")).longValue())
				.as("no spurious rewind: the session row survives rather than being recreated")
				.isEqualTo(firstId);
	}


	@Test
	void punchesOfAnHrEditedRowStayOutOfReplayAndCannotOverlapIt() throws Exception {
		// The DELETE deliberately preserves a row HR has corrected. The UPDATE
		// beside it reset EVERY punch in the window regardless -- including the
		// two belonging to that preserved row. They then re-paired and built a
		// second, overlapping session around the human edit.
		punchAt(DAY + " 08:00:00");
		punchAt(DAY + " 17:00:00");
		service.pairCompany(COMPANY, "friday");
		assertThat(attendance()).hasSize(1);
		long editedId = ((Number) attendance().get(0).get("id")).longValue();

		// HR corrects the check-in. The row is now a human artefact.
		seedAsLegacyWould("UPDATE attendance SET check_in = '" + DAY + " 07:45:00' WHERE id = " + editedId);

		// A late punch inside that session triggers a replay of the window.
		punchAt(DAY + " 12:00:00");
		service.pairCompany(COMPANY, "friday");

		List<Map<String, Object>> rows = attendance();
		assertThat(rows)
				.as("the corrected row must not gain an overlapping neighbour built from its own punches")
				.hasSize(1);
		assertThat(((Number) rows.get(0).get("id")).longValue())
				.as("and it is still the human's row, not a rebuilt one")
				.isEqualTo(editedId);
		assertThat(rows.get(0).get("check_in").toString())
				.as("the correction survives")
				.startsWith(DAY + " 07:45:00");

		assertThat(query("SELECT id FROM device_punches WHERE employee_id = " + EMPLOYEE
				+ " AND attendance_id = " + editedId + " AND processing_state = 'PAIRED'"))
				.as("the preserved row's own punches stay paired to it rather than being replayed")
				.isNotEmpty();
	}


	@Test
	void anUnknownAssignmentResolutionIsRejectedEvenUnderNonStrictSqlMode() throws Exception {
		// The trap this column exists to avoid, proved rather than assumed.
		// Production runs sql_mode='' , where an out-of-range ENUM value is
		// stored as the empty error value with only a warning -- this pull
		// request demonstrated exactly that on attendance.method. A provenance
		// column must fail loudly instead of gaining a silent fourth state.
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			ResultSet mode = statement.executeQuery("SELECT @@SESSION.sql_mode");
			mode.next();
			assertThat(mode.getString(1))
					.as("this fixture must be as permissive as production, or it proves nothing")
					.isEmpty();

			assertThatThrownBy(() -> {
				try (Statement bad = connection.createStatement()) {
					bad.executeUpdate("INSERT INTO device_punches (device_id, company_id, branch_id,"
							+ " employee_id, pin, punched_at_local, punched_at_utc, received_at,"
							+ " dedup_key, raw_line, processing_state, assignment_resolution) VALUES ("
							+ DEVICE + ", " + COMPANY + ", " + BRANCH + ", " + EMPLOYEE + ", '7001',"
							+ " '" + DAY + " 08:00:00', '" + DAY + " 06:00:00', '" + DAY + " 08:00:00',"
							+ " 'badresolution01', 'seed', 'RECEIVED', 'PROBABLY_FINE')");
				}
			}).as("an unknown resolution must be refused, not coerced to an empty value")
					.isInstanceOf(SQLException.class);

			try (ResultSet rs = statement.executeQuery(
					"SELECT COUNT(*) FROM device_punches WHERE dedup_key = 'badresolution01'")) {
				rs.next();
				assertThat(rs.getInt(1)).as("and nothing may be left behind").isZero();
			}
		}
	}


	@Test
	void aPunchIsPairedWithTheRuntimeOffsetInForceWhenItHappenedNotWhenItIsPaired() throws Exception {
		// The punch happens under +02:00. The runtime then switches to +03:00 --
		// an offline device delivers late, and pairing runs afterwards.
		// Converting with the CURRENT offset would write 10:00 for an instant
		// that contemporaneous app and QR attendance recorded as 09:00.
		seedAsLegacyWould("DELETE FROM legacy_runtime_offset_history");
		seedAsLegacyWould("INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)"
				+ " VALUES ('2025-06-01 00:00:00', 7200)");
		seedAsLegacyWould("INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)"
				+ " VALUES ('" + DAY + " 12:00:00', 10800)");

		// And make the CURRENT offset differ from the historical one, or this
		// test cannot tell the two implementations apart: with both at +02:00 a
		// clock.offset() lookup gives the right answer for the wrong reason.
		seedAsLegacyWould("INSERT INTO configs (config_key, config_value) VALUES"
				+ " ('is_daylight_saving', '1')"
				+ " ON DUPLICATE KEY UPDATE config_value = '1'");
		PunchPairingService withCurrentOffsetPlusThree = new PunchPairingService(
				new PunchPairingStore(this.dataSource), sessions, this.dataSource,
				new LegacyClock(this.dataSource), 500);

		insertPunchWithUtc(DAY + " 09:00:00", DAY + " 07:00:00");   // instant under +02
		withCurrentOffsetPlusThree.pairCompany(COMPANY, "friday");

		assertThat(attendance()).hasSize(1);
		assertThat(attendance().get(0).get("check_in").toString())
				.as("07:00Z happened under +02:00, so legacy attendance called it 09:00 -- "
						+ "the current offset is +03:00 and must not be used")
				.startsWith(DAY + " 09:00:00");
	}

	@Test
	void aPunchOlderThanTheRecordedHistoryProducesNoAttendance() throws Exception {
		// The offset that governed this instant was never recorded. An hour of
		// silent error moves session boundaries and payroll, and a review flag
		// beside an already-derived row is too late -- so nothing is derived.
		seedAsLegacyWould("DELETE FROM legacy_runtime_offset_history");
		seedAsLegacyWould("INSERT INTO legacy_runtime_offset_history (effective_from_utc, offset_seconds)"
				+ " VALUES ('" + DAY + " 00:00:00', 7200)");

		insertPunchWithUtc("2020-01-02 09:00:00", "2020-01-02 07:00:00");
		service.pairCompany(COMPANY, "friday");

		assertThat(attendance()).as("no attendance may be derived from an unrecorded offset").isEmpty();
		assertThat(query("SELECT review_flag FROM device_punches WHERE employee_id = " + EMPLOYEE
				+ " AND review_flag = 'RUNTIME_OFFSET_PRE_HISTORY'"))
				.as("but the punch survives, visibly held for review")
				.isNotEmpty();
	}

	@Test
	void pairingRefusesEntirelyWhenTheRuntimeOffsetWritersAreMissing() throws Exception {
		// A seeded history with no triggers looks authoritative and silently
		// stops tracking. Refusing is the recoverable outcome.
		punchAt(DAY + " 08:00:00");
		seedAsLegacyWould("DROP TRIGGER IF EXISTS configs_runtime_offset_after_update");
		try {
			PunchPairingService.Outcome outcome = service.pairCompany(COMPANY, "friday");

			assertThat(outcome.opened()).as("nothing is paired without the writers").isZero();
			assertThat(attendance()).isEmpty();
		} finally {
			com.workin.legacy.runtime.LegacyRuntimeOffsetHistoryTest.installHooks();
		}
	}

}
