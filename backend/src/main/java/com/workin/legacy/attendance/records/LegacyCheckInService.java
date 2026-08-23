package com.workin.legacy.attendance.records;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.location.LegacyAttendanceLocation;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code attendance/check_in.php}, {@code check_in_qr.php} and
 * {@code check_out.php} (Wave 12.6.3).
 *
 * <h2>Three endpoints, three different authority shapes</h2>
 * <p>All three call a bare {@code requireAuth()}, so every role reaches them.
 * What differs is what happens next:
 *
 * <ul>
 *   <li>{@code check_in} and {@code check_out} compute
 *       {@code $is_self_attendance} and use it twice -- once to gate the
 *       mobile-attendance flag, and once as the geofence's
 *       {@code require_location_configured}. D-092 restricts the target to
 *       self for an EMPLOYEE session;</li>
 *   <li>{@code check_in_qr} has <b>no</b> {@code $is_self_attendance} variable
 *       at all: no mobile-attendance check, and no geofence -- the QR code is
 *       the proof of presence. D-092's target restriction is added for
 *       consistency of authority, not to close a bypass, because there is no
 *       bypass to close.</li>
 * </ul>
 *
 * <h2>Nothing here is transactional, on purpose</h2>
 * <p>The session lookup auto-closes stale rows before the endpoint's own work
 * begins, and PHP wraps none of it. A check-in that is then refused -- by the
 * two-hour rule or by the geofence -- keeps the auto-close.
 */
@Service
public class LegacyCheckInService {

	/** {@code SELECT * FROM employees WHERE id=? AND company_id=?}. */
	private static final String EMPLOYEE = "SELECT * FROM employees WHERE id=? AND company_id=?";

	/**
	 * The QR branch lookup.
	 *
	 * <p>{@code expires_at > NOW()} is evaluated by the <b>database</b>, so it
	 * moves with D-099's session zone. An expiry is compared against the
	 * configured legacy offset, not the JVM's clock and not UTC.
	 */
	private static final String QR_BRANCH = """
			SELECT id FROM branches
			WHERE qr_code = ? AND company_id = ? AND is_active = 1
			  AND expires_at IS NOT NULL AND expires_at > NOW()""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceSessions sessions;
	private final LegacyAttendanceLocation location;
	private final LegacyClock clock;

	public LegacyCheckInService(
			DataSource legacyDataSource, LegacyAttendanceSessions sessions,
			LegacyAttendanceLocation location, LegacyClock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.sessions = sessions;
		this.location = location;
		this.clock = clock;
	}

	/** {@code ok(CHECK_IN_RECORDED, ['attendance_id' => ..., 'time' => ...])}. */
	public record CheckInOutcome(long attendanceId, String time) {
	}

	/** {@code ok(CHECK_OUT_RECORDED, ['duration_minutes' => ..., 'time' => ...])}. */
	public record CheckOutOutcome(int durationMinutes, String time) {
	}

	/**
	 * {@code check_in.php}.
	 *
	 * <p>The order is the contract and every step of it is observable:
	 * required fields, target id, employee lookup, mobile-attendance gate, open
	 * session, the two-hour rule, the geofence, then the INSERT.
	 */
	public CheckInOutcome checkIn(
			LegacyRequestContext context, Map<String, Object> body, String weeklyRestLabel) {
		requireField(body, "latitude");
		requireField(body, "longitude");
		requireField(body, "method");

		long employeeId = targetEmployeeId(context, body);
		Map<String, Object> employee = employeeInCompany(context.companyId(), employeeId);
		boolean self = isSelfAttendance(context, employeeId);
		requireMobileAttendance(employee, self);

		if (sessions.findOpenSession(employeeId, false, weeklyRestLabel) != null) {
			throw new LegacyApiException(400, "already_checked_in");
		}

		requireTwoHourGap(employeeId);

		// D-093: the geofence is no longer skipped when a coordinate is
		// PHP-empty. `required()` already accepted "0", and Null Island is a
		// real coordinate reachable by an honest client near the equator.
		location.validate(employee, context.companyId(),
				phpFloat(body.get("latitude")),
				phpFloat(body.get("longitude")),
				self);

		Object method = body.get("method") == null ? "app" : body.get("method");
		long id = insertCheckIn(employeeId, method, body.get("latitude"), body.get("longitude"));
		return new CheckInOutcome(id, clock.now().format(SQL_DATE_TIME));
	}

	/**
	 * {@code check_in_qr.php}.
	 *
	 * <p>Two orderings differ from {@code check_in.php} and both are visible.
	 * The QR code is validated <b>before</b> the employee is looked up, so a bad
	 * code on a foreign employee is {@code invalid_qr}, not
	 * {@code invalid_employee}. And there is no two-hour rule at all -- a QR
	 * check-in immediately after a closed session is accepted where an ordinary
	 * one is refused.
	 */
	public CheckInOutcome checkInByQr(
			LegacyRequestContext context, Map<String, Object> body, String weeklyRestLabel) {
		requireField(body, "qr_code");

		long employeeId = targetEmployeeId(context, body, false);

		List<Long> branches = jdbcTemplate.queryForList(
				QR_BRANCH, Long.class, LegacyValues.toPhpString(body.get("qr_code")), context.companyId());
		if (branches.isEmpty()) {
			throw new LegacyApiException(400, "invalid_qr");
		}
		long branchId = branches.get(0);

		Map<String, Object> employee = employeeInCompany(context.companyId(), employeeId);
		if (!location.mayCheckInAtBranch(employee, branchId, context.companyId())) {
			throw new LegacyApiException(400, "employee_not_branch");
		}
		if (sessions.findOpenSession(employeeId, false, weeklyRestLabel) != null) {
			throw new LegacyApiException(400, "already_checked_in");
		}

		long id = insertCheckIn(employeeId, "qr", null, null);
		return new CheckInOutcome(id, clock.now().format(SQL_DATE_TIME));
	}

	/**
	 * {@code check_out.php}.
	 *
	 * <p>No {@code required()} call: the body may be empty, because the
	 * coordinates fall back to the open session's own. The geofence runs only
	 * for a self check-out, and then with
	 * {@code require_location_configured} defaulted to <b>true</b> -- the
	 * opposite default from check-in's self case, reached by passing four
	 * arguments instead of five.
	 *
	 * <p>The duration is computed in <b>PHP</b>, not by the database:
	 * {@code round((time() - strtotime($check_in)) / 60)} against the
	 * application clock, while the row is stamped with the database's
	 * {@code NOW()}. Both now sit on the same offset (D-099), but they are still
	 * two clocks and the reported duration is the application's.
	 */
	public CheckOutOutcome checkOut(
			LegacyRequestContext context, Map<String, Object> body, String weeklyRestLabel) {
		long employeeId = targetEmployeeId(context, body);
		Map<String, Object> employee = employeeInCompany(context.companyId(), employeeId);
		boolean self = isSelfAttendance(context, employeeId);
		requireMobileAttendance(employee, self);

		Map<String, Object> open = sessions.findOpenSession(employeeId, true, weeklyRestLabel);
		if (open == null) {
			throw new LegacyApiException(400, "no_open_check_in");
		}

		if (self) {
			double[] coordinates = LegacyAttendanceLocation.parseRequestCoordinates(body, open);
			location.validate(employee, context.companyId(), coordinates[0], coordinates[1], true);
		}

		jdbcTemplate.update("UPDATE attendance SET check_out = NOW() WHERE id = ?", open.get("id"));

		java.time.LocalDateTime checkIn = com.workin.legacy.LegacyPhpStrtotime.dateTimeOf(
				LegacyValues.toPhpString(open.get("check_in")), clock.now());
		long seconds = checkIn == null
				? 0L
				: java.time.Duration.between(checkIn, clock.now()).getSeconds();
		return new CheckOutOutcome((int) phpRound(seconds / 60d), clock.now().format(SQL_DATE_TIME));
	}

	// ------------------------------------------------------------------
	// The pieces all three share
	// ------------------------------------------------------------------

	/**
	 * {@code (int) ($body['employee_id'] ?? $auth['employee_id'] ?? 0)}, plus
	 * D-092's restriction.
	 *
	 * <p>D-092: an EMPLOYEE session may target only its own {@code employee_id}.
	 * The check runs <b>before</b> any employee row is read, so a 403 cannot be
	 * used to probe which ids exist or which company they belong to.
	 */
	private long targetEmployeeId(LegacyRequestContext context, Map<String, Object> body) {
		return targetEmployeeId(context, body, true);
	}

	private long targetEmployeeId(
			LegacyRequestContext context, Map<String, Object> body, boolean failWhenMissing) {
		Object supplied = body == null ? null : body.get("employee_id");
		long employeeId = supplied != null
				? LegacyValues.toPhpLong(supplied)
				: context.employeeId();
		if (employeeId == 0L && failWhenMissing) {
			throw new LegacyApiException(400, "employee_id_required");
		}
		if (context.role() == LegacyEmployee.Role.EMPLOYEE && employeeId != context.employeeId()) {
			throw new LegacyApiException(403, "forbidden");
		}
		return employeeId;
	}

	/**
	 * {@code $is_self_attendance}: an <b>employee-type session acting on
	 * itself</b>.
	 *
	 * <p>Under D-092 an EMPLOYEE can only ever target itself, so for that role
	 * this is always true; it is false for COMPANY_ADMIN, HR and MANAGER even
	 * when they target their own row, which is what makes an admin's own
	 * check-in skip the mobile-attendance flag and the location-configured
	 * requirement.
	 */
	private static boolean isSelfAttendance(LegacyRequestContext context, long employeeId) {
		return context.role() == LegacyEmployee.Role.EMPLOYEE && context.employeeId() == employeeId;
	}

	/**
	 * {@code if ($is_self_attendance && empty($employee['is_mobile_attendance_enabled']))}.
	 *
	 * <p>PHP's {@code empty()}, so 0, null and the string {@code "0"} all
	 * disable it -- and the check only applies to the employee acting on
	 * themselves. An admin checking that same employee in is unaffected.
	 */
	private static void requireMobileAttendance(Map<String, Object> employee, boolean self) {
		if (self && LegacyValues.isPhpEmpty(employee.get("is_mobile_attendance_enabled"))) {
			throw new LegacyApiException(403, "mobile_attendance_disabled");
		}
	}

	/**
	 * The two-hour rule, evaluated in the <b>database</b>.
	 *
	 * <p>{@code TIMESTAMPDIFF(MINUTE, $last_check_in, NOW())}, against the most
	 * recent check-in by {@code check_in DESC} -- not the most recent row, and
	 * not only open ones. A negative difference (a check-in stamped in the
	 * future) passes, because the guard is {@code >= 0 && < 120}.
	 *
	 * <p>The 422's reason is a hard-coded Arabic string in PHP with no catalog
	 * key, and it is reproduced literally rather than translated (Wave 12.6
	 * discovery §J.5).
	 */
	private void requireTwoHourGap(long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				"SELECT id, check_in FROM attendance WHERE employee_id=? ORDER BY check_in DESC LIMIT 1",
				LegacyJdbcValues.rowMapper(), employeeId);
		if (rows.isEmpty()) {
			return;
		}
		Long minutes = jdbcTemplate.queryForObject(
				"SELECT TIMESTAMPDIFF(MINUTE, ?, NOW())", Long.class, rows.get(0).get("check_in"));
		long since = minutes == null ? 0L : minutes;
		if (since >= 0 && since < 120) {
			throw new LegacyApiException(422, "invalid_input", null, Map.of(
					"field", "check_in",
					"reason", "لا يمكن تسجيل بصمتين متتاليتين خلال اقل من ساعتين"));
		}
	}

	private Map<String, Object> employeeInCompany(long companyId, long employeeId) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				EMPLOYEE, LegacyJdbcValues.rowMapper(), employeeId, companyId);
		if (rows.isEmpty()) {
			throw new LegacyApiException(404, "invalid_employee");
		}
		return rows.get(0);
	}

	/** The INSERT, with {@code NOW()} supplying {@code check_in}. */
	private long insertCheckIn(long employeeId, Object method, Object latitude, Object longitude) {
		org.springframework.jdbc.support.KeyHolder keys =
				new org.springframework.jdbc.support.GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			java.sql.PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO attendance (employee_id, check_in, method, latitude, longitude)"
							+ " VALUES (?, NOW(), ?, ?, ?)",
					java.sql.Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, employeeId);
			statement.setString(2, LegacyValues.toPhpString(method));
			setNullable(statement, 3, latitude);
			setNullable(statement, 4, longitude);
			return statement;
		}, keys);
		Number key = keys.getKey();
		return key == null ? 0L : key.longValue();
	}

	private static void setNullable(java.sql.PreparedStatement statement, int index, Object value)
			throws java.sql.SQLException {
		if (value == null) {
			statement.setNull(index, java.sql.Types.DECIMAL);
		} else {
			statement.setString(index, LegacyValues.toPhpString(value));
		}
	}

	/** {@code required()}: absent or the empty string. */
	private static void requireField(Map<String, Object> body, String field) {
		Object value = body == null ? null : body.get(field);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
	}

	/** PHP's {@code (float)} cast, through the shared numeric primitive. */
	private static double phpFloat(Object value) {
		return LegacyValues.toPhpDecimal(value).doubleValue();
	}

	/** PHP's {@code round()}: halves away from zero. */
	private static long phpRound(double value) {
		return value < 0 ? -(long) Math.floor(-value + 0.5d) : (long) Math.floor(value + 0.5d);
	}

	private static final java.time.format.DateTimeFormatter SQL_DATE_TIME =
			java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

}
