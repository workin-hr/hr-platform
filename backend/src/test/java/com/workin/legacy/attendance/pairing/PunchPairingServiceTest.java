package com.workin.legacy.attendance.pairing;

import static org.assertj.core.api.Assertions.assertThat;

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
		this.identities = new com.workin.devices.identity.EmployeeDeviceIdentityStore(dataSource);
		LegacyClock clock = new LegacyClock(dataSource);
		LegacyAttendanceCalendar calendar =
				new LegacyAttendanceCalendar(dataSource, new LegacyWeeklyOffDays(dataSource));
		this.sessions = new LegacyAttendanceSessions(dataSource, calendar, clock);
		this.service = new PunchPairingService(
				new PunchPairingStore(dataSource), sessions, dataSource, 500);
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
			@Override
			public void markPaired(long punchId, long attendanceId,
					java.time.LocalDateTime pairedAt, String reviewFlag) {
				throw new IllegalStateException("crash between the attendance write and the punch's state");
			}
		};
		LegacyClock clock = new LegacyClock(dataSource);
		LegacyAttendanceCalendar calendar =
				new LegacyAttendanceCalendar(dataSource, new LegacyWeeklyOffDays(dataSource));
		PunchPairingService crashing = new PunchPairingService(failing,
				new LegacyAttendanceSessions(dataSource, calendar, clock), dataSource, 500);

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
					new PunchPairingStore(dataSource), sessions, dataSource, 500);

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

	private static long insertPunch(String localTime, String employee, String state) throws Exception {
		String key = String.format("%064x", (localTime + employee + state).hashCode() & 0xffffffffL);
		seedAsLegacyWould("INSERT INTO device_punches (device_id, company_id, branch_id, employee_id,"
				+ " pin, punched_at_local, punched_at_utc, received_at, dedup_key, raw_line,"
				+ " processing_state) VALUES (" + DEVICE + ", " + COMPANY + ", " + BRANCH + ", "
				+ employee + ", '7001', '" + localTime + "', '" + localTime + "', '" + localTime
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
}
