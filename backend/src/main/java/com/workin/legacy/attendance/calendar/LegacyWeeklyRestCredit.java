package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code weekly_rest_credit_helper.php}'s block/coverage arithmetic -- only
 * the functions {@code attendance_build_employee_range_calendar()} and
 * {@code attendance_employee_period_stats()} reach. The report/payslip-hover
 * functions in the same PHP file ({@code weekly_rest_dates_by_status_in_range()}
 * and friends) belong to {@code overall_report.php}/{@code export.php}, which
 * this wave does not deliver (the broad J.2 payroll boundary remains open),
 * so they are not ported here.
 *
 * <p>Weekly rest is paid only when the employee covered enough workdays in
 * the week before that rest (worked, exception, or paid leave). Official
 * holidays do not count toward that coverage threshold. Below the minimum,
 * the rest day is void: neither present, leave, nor absent.
 */
@Component
public class LegacyWeeklyRestCredit {

	public static final String EARNED = "earned";
	public static final String VOID = "void";
	public static final String PENDING = "pending";

	/** {@code WEEKLY_REST_MIN_COVERED_WORKDAYS}. */
	private static final int MIN_COVERED_WORKDAYS = 3;

	private static final String ATTENDANCE_FLAGS_IN_RANGE = """
			SELECT check_in, check_out, exception_type_id
			FROM attendance
			WHERE employee_id = ?
			  AND DATE(check_in) >= ?
			  AND DATE(check_in) <= ?""";

	private static final String IS_ON_APPROVED_LEAVE = """
			SELECT COUNT(*)
			FROM requests r
			INNER JOIN request_types t ON t.id = r.request_type_id
			WHERE r.employee_id = ?
			  AND r.status = 'approved'
			  AND t.counts_as_paid_leave = 1
			  AND r.from_date <= ?
			  AND r.to_date >= ?""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;

	public LegacyWeeklyRestCredit(DataSource legacyDataSource, LegacyAttendanceCalendar calendar) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
	}

	/** Whether a punch was recorded, and whether it was exception-only, for one date. */
	public record AttendanceFlag(boolean hasPunch, boolean isExceptionOnly) {
	}

	/**
	 * {@code weekly_rest_block_start()} ({@code weekly_rest_credit_helper.php:29-45}):
	 * the first calendar day of the contiguous weekly-rest block containing
	 * {@code restYmd}, walking backward while each prior day is itself a
	 * weekly-rest day.
	 */
	public LocalDate blockStart(long companyId, long employeeId, String restYmd) {
		LocalDate day = LocalDate.parse(restYmd);
		for (int i = 0; i < 7; i++) {
			LocalDate prev = day.minusDays(1);
			Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, prev.toString());
			if (!calendar.isWeeklyRestDay(companyId, prev.toString(), shift)) {
				break;
			}
			day = prev;
		}
		return day;
	}

	/**
	 * {@code weekly_rest_workdays_before_block()}
	 * ({@code weekly_rest_credit_helper.php:53-76}): the scheduled workdays
	 * immediately before the rest block, ascending, stopping at the previous
	 * rest day (or after 7 days). Official holidays are still included here --
	 * they sit on calendar work slots and can earn coverage; they are simply
	 * not counted as "absences" elsewhere.
	 */
	public List<String> workdaysBeforeBlock(long companyId, long employeeId, String restYmd) {
		LocalDate blockStart = blockStart(companyId, employeeId, restYmd);
		List<String> workdays = new ArrayList<>();
		LocalDate day = blockStart.minusDays(1);
		for (int i = 0; i < 7; i++) {
			Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, day.toString());
			if (calendar.isWeeklyRestDay(companyId, day.toString(), shift)) {
				break;
			}
			workdays.add(day.toString());
			day = day.minusDays(1);
		}
		java.util.Collections.reverse(workdays);
		return workdays;
	}

	/**
	 * {@code weekly_rest_credit_status()}
	 * ({@code weekly_rest_credit_helper.php:83-146}): whether a weekly-rest day
	 * is earned (paid), void (unpaid, not counted as absence either), or
	 * pending (its coverage week has not finished yet).
	 */
	public String creditStatus(
			long companyId, long employeeId, String restYmd,
			Map<String, AttendanceFlag> attendanceByDate, Map<String, String> holidayByDate, String asOf) {
		if (restYmd.compareTo(asOf) > 0) {
			return PENDING;
		}

		List<String> workdays = workdaysBeforeBlock(companyId, employeeId, restYmd);
		if (workdays.isEmpty()) {
			// No preceding workdays (e.g. edge of schedule) -- keep paid rest.
			return EARNED;
		}

		boolean hasFutureWorkday = false;
		boolean hasPastWorkday = false;
		int covered = 0;

		for (String dateStr : workdays) {
			if (dateStr.compareTo(asOf) > 0) {
				hasFutureWorkday = true;
				continue;
			}
			hasPastWorkday = true;

			AttendanceFlag day = attendanceByDate.get(dateStr);
			boolean worked = day != null && day.hasPunch();
			boolean exceptionCover = day != null && day.isExceptionOnly();
			boolean onLeave = isOnApprovedLeave(employeeId, dateStr);

			// Official holidays do not count toward the 3-day coverage threshold.
			if (worked || exceptionCover || onLeave) {
				covered++;
			}
		}

		if (covered >= MIN_COVERED_WORKDAYS) {
			return EARNED;
		}
		if (!hasPastWorkday) {
			return PENDING;
		}
		if (!hasFutureWorkday) {
			return VOID;
		}
		// Coverage still short but the week is still open -- do not credit yet.
		return PENDING;
	}

	/**
	 * {@code attendance_is_on_approved_leave()}
	 * ({@code attendance_calendar_helper.php:669-683}).
	 */
	public boolean isOnApprovedLeave(long employeeId, String date) {
		Long count = jdbcTemplate.queryForObject(
				IS_ON_APPROVED_LEAVE, Long.class, employeeId, date, date);
		return count != null && count > 0;
	}

	/**
	 * {@code weekly_rest_attendance_flags_in_range()}
	 * ({@code weekly_rest_credit_helper.php:301-358}): one flag pair per date
	 * with at least one punch, preferring a genuine punch over an
	 * exception-only row when a date has both.
	 */
	public Map<String, AttendanceFlag> attendanceFlagsInRange(
			long companyId, long employeeId, String from, String to) {
		// Expand lookback so weeks starting before `from` still resolve correctly.
		String lookbackFrom = LocalDate.parse(from).minusDays(7).toString();
		List<AttendanceRow> rows = jdbcTemplate.query(
				ATTENDANCE_FLAGS_IN_RANGE,
				(rs, index) -> new AttendanceRow(
						rs.getString("check_in"), rs.getString("check_out"), rs.getObject("exception_type_id")),
				employeeId, lookbackFrom, to);

		Map<String, AttendanceFlag> byDate = new LinkedHashMap<>();
		for (AttendanceRow row : rows) {
			if (row.checkIn() == null || row.checkIn().isEmpty()) {
				continue;
			}
			String checkOut = row.checkOut() != null && !row.checkOut().trim().isEmpty() ? row.checkOut() : null;
			String dateKey = row.checkIn().length() >= 10 ? row.checkIn().substring(0, 10) : row.checkIn();
			boolean isExceptionOnly = com.workin.legacy.attendance.session.LegacyAttendanceSessions
					.isExceptionOnlyRow(row.checkIn(), checkOut, row.exceptionTypeId());
			boolean hasPunch = !isExceptionOnly;

			AttendanceFlag existing = byDate.get(dateKey);
			if (existing == null) {
				byDate.put(dateKey, new AttendanceFlag(hasPunch, isExceptionOnly));
			} else if (hasPunch && !existing.hasPunch()) {
				// Prefer punch over exception-only if both exist for the date.
				byDate.put(dateKey, new AttendanceFlag(true, false));
			}
		}
		return byDate;
	}

	private record AttendanceRow(String checkIn, String checkOut, Object exceptionTypeId) {
	}

}
