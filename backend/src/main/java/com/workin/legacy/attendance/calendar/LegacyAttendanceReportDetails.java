package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.payroll.LegacyPayrollAttendanceFigures;

/**
 * The per-employee detail helpers {@code overall_attendance_report_build()}
 * alone reaches, all from {@code attendance_calendar_helper.php} except the
 * holiday count, which is {@code official_holidays_helper.php:129-151}.
 *
 * <p>They live together because they share exactly one consumer -- the Wave
 * 12.6.6 overall attendance report and the export built on the same builder --
 * and because none of them existed in Java before it: every other helper in that
 * builder's fifteen-function closure was already ported for payroll or for the
 * Wave 12.6.4b attendance endpoints.
 *
 * <h2>Why the holiday count is here and not beside the other holiday helpers</h2>
 * <p>{@code official_holidays_credit_days_for_employee()} is easy to confuse
 * with {@code official_holidays_working_credit_for_employee()}, which
 * {@link LegacyPayrollAttendanceFigures} already ports. They are different
 * functions: this one counts <b>every</b> holiday in range without a check-in,
 * while the payroll one filters weekly rest days out and reads
 * {@code company_settings}. Substituting one for the other under-reports the
 * report's holiday credit, so this port sits with its only caller and says so.
 */
@Component
public class LegacyAttendanceReportDetails {

	private final LegacyAttendanceCalendar calendar;
	private final LegacyAttendanceWorkedMinutes workedMinutes;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyPayrollAttendanceFigures payrollFigures;
	private final LegacyAttendanceRangeRows rangeRows;

	public LegacyAttendanceReportDetails(
			LegacyAttendanceCalendar calendar, LegacyAttendanceWorkedMinutes workedMinutes,
			LegacyWeeklyRestCredit weeklyRestCredit, LegacyPayrollAttendanceFigures payrollFigures,
			LegacyAttendanceRangeRows rangeRows) {
		this.calendar = calendar;
		this.workedMinutes = workedMinutes;
		this.weeklyRestCredit = weeklyRestCredit;
		this.payrollFigures = payrollFigures;
		this.rangeRows = rangeRows;
	}

	/** Worked and expected minutes for a period, as {@code attendance_period_work_minutes()} returns them. */
	public record WorkMinutes(int workedMinutes, int expectedMinutes) {
	}

	/**
	 * {@code official_holidays_credit_days_for_employee()}
	 * ({@code official_holidays_helper.php:129-151}): holidays in the range with
	 * no check-in for this employee, credited as present.
	 *
	 * <p>No weekly-rest filter and no {@code company_settings} read -- see the
	 * class javadoc for why that distinction matters.
	 */
	public int holidayCreditDays(long companyId, long employeeId, String from, String to) {
		if (companyId <= 0 || employeeId <= 0 || from.isEmpty() || to.isEmpty()) {
			return 0;
		}
		return holidayCreditDays(companyId, employeeId, rangeRows.forEmployee(employeeId, from, to), from, to);
	}

	/**
	 * {@link #holidayCreditDays(long, long, String, String)} over attendance
	 * already read for this employee (D-292). {@code rows} must cover
	 * {@code [from, to]}; rows outside it cannot share a date with a holiday
	 * inside it, so a wider window is harmless.
	 *
	 * <p>Legacy's statement is {@code COUNT(*)} over the company's holidays in
	 * range {@code NOT EXISTS} an attendance row of that date. The holidays are
	 * {@link LegacyAttendanceCalendar#holidaysByDate}'s rows -- one per date, by
	 * {@code uq_company_holiday_date} -- and "an attendance row of that date" is
	 * a row whose check-in date is that date, whatever kind of row it is.
	 */
	public int holidayCreditDays(
			long companyId, long employeeId, List<LegacyAttendanceRangeRows.Row> rows, String from, String to) {
		if (companyId <= 0 || employeeId <= 0 || from.isEmpty() || to.isEmpty()) {
			return 0;
		}
		Set<String> attended = new java.util.HashSet<>();
		for (LegacyAttendanceRangeRows.Row row : rows) {
			attended.add(row.dateKey());
		}
		int count = 0;
		for (String date : calendar.holidaysByDate(companyId, from, to).keySet()) {
			if (!attended.contains(date)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * {@code attendance_exception_details_for_period()}
	 * ({@code attendance_calendar_helper.php:447-477}): one row per day carrying
	 * an exception type, with the type's name.
	 *
	 * <p>A row whose exception name trims to empty is skipped rather than
	 * emitted with a blank label -- legacy's own {@code continue}.
	 */
	public List<Map<String, Object>> exceptionDetails(long employeeId, String from, String to) {
		return exceptionDetails(rangeRows.forEmployee(employeeId, from, to), from, to);
	}

	/**
	 * {@link #exceptionDetails(long, String, String)} over attendance already
	 * read for this employee (D-292).
	 *
	 * <p>Legacy's statement inner-joins {@code exception_types}, so a row whose
	 * type no longer exists is not listed; with the left join the rows are read
	 * through, that is a row whose type name is null ({@code name} is
	 * {@code NOT NULL}, so null means no match). Its order is
	 * {@code exception_date ASC} and nothing else, so two exception rows on one
	 * date came back in whatever order MariaDB's sort left them -- not a
	 * property of the rows: the same statement over the same rows was measured
	 * returning them in both orders. Here they come in check-in then id order,
	 * the one deliberate divergence D-292 records.
	 */
	public List<Map<String, Object>> exceptionDetails(
			List<LegacyAttendanceRangeRows.Row> rows, String from, String to) {
		List<Map<String, Object>> details = new ArrayList<>();
		for (LegacyAttendanceRangeRows.Row row : LegacyAttendanceRangeRows.between(rows, from, to)) {
			if (row.exceptionTypeId() == null || row.exceptionTypeName() == null) {
				continue;
			}
			String trimmed = row.exceptionTypeName().trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			Map<String, Object> detail = new LinkedHashMap<>();
			detail.put("date", row.dateKey());
			detail.put("exception_name", trimmed);
			details.add(detail);
		}
		return details;
	}

	/**
	 * {@code attendance_absent_details_for_period()}
	 * ({@code attendance_calendar_helper.php:540-601}): every elapsed day in the
	 * range that is not present, not an official holiday, not approved leave and
	 * not a rest day.
	 *
	 * <p>{@code to} is clamped to {@code asOf} first, so a period still in
	 * progress never reports its future days as absence.
	 */
	public List<Map<String, Object>> absentDetails(
			long companyId, long employeeId, String from, String to, String asOf,
			String absentLabel, String presentLabel, String weeklyRestLabel) {
		if (companyId <= 0 || employeeId <= 0 || from.isEmpty() || to.isEmpty()) {
			return new ArrayList<>();
		}
		String rangeTo = to.compareTo(asOf) > 0 ? asOf : to;
		if (rangeTo.compareTo(from) < 0) {
			return new ArrayList<>();
		}
		return absentDetails(companyId, employeeId, rangeRows.forEmployee(employeeId, from, rangeTo),
				from, to, asOf, absentLabel, weeklyRestLabel);
	}

	/**
	 * {@link #absentDetails(long, long, String, String, String, String, String, String)}
	 * over attendance already read for this employee (D-292); {@code rows} must
	 * cover {@code [from, min(to, asOf)]}.
	 *
	 * <p>Legacy reads the present dates through
	 * {@code attendance_present_details_for_period()}, whose statement groups by
	 * {@code DATE(check_in)}; only the dates are used here, and they are the
	 * distinct check-in dates of the rows in range. The present label that
	 * statement carried is therefore not needed.
	 */
	public List<Map<String, Object>> absentDetails(
			long companyId, long employeeId, List<LegacyAttendanceRangeRows.Row> rows, String from, String to,
			String asOf, String absentLabel, String weeklyRestLabel) {
		List<Map<String, Object>> details = new ArrayList<>();
		if (companyId <= 0 || employeeId <= 0 || from.isEmpty() || to.isEmpty()) {
			return details;
		}
		String rangeTo = to.compareTo(asOf) > 0 ? asOf : to;
		if (rangeTo.compareTo(from) < 0) {
			return details;
		}

		Set<String> presentDates = new LinkedHashSet<>();
		for (LegacyAttendanceRangeRows.Row row : LegacyAttendanceRangeRows.between(rows, from, rangeTo)) {
			presentDates.add(row.dateKey());
		}

		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, from, rangeTo);

		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(rangeTo);
		// One statement for the whole range instead of one per date, for the
		// isOnApprovedLeave below -- and none at all when a report already warmed
		// every employee at once. The window is the one voidWeeklyRestAbsentDetails
		// needs, not the narrower one this loop needs, so a report that calls both
		// warms once.
		calendar.warmApprovedLeaveForEmployees(
				java.util.List.of(employeeId), start.minusDays(14).toString(), rangeTo);
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			String dateStr = day.toString();
			if (presentDates.contains(dateStr) || holidayByDate.containsKey(dateStr)) {
				continue;
			}
			if (calendar.isOnApprovedLeave(employeeId, dateStr)) {
				continue;
			}
			if (calendar.expectedForDay(companyId, employeeId, dateStr, weeklyRestLabel).restDay()) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("date", dateStr);
			row.put("day_type", "absent");
			row.put("label", absentLabel);
			details.add(row);
		}
		return details;
	}

	/**
	 * {@code attendance_void_weekly_rest_absent_details_for_period()}
	 * ({@code attendance_calendar_helper.php:610-667}): the weekly rest days the
	 * employee did not earn, which count as unpaid absence.
	 *
	 * <p>The caller passes the attendance flags and holiday map it already built
	 * for the same range; legacy rebuilds them only when handed empty arrays, and
	 * the report never hands it empty ones.
	 */
	public List<Map<String, Object>> voidWeeklyRestAbsentDetails(
			long companyId, long employeeId, String from, String to,
			Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attendanceByDate,
			Map<String, String> holidayByDate, String asOf, String voidLabel) {
		return voidWeeklyRestAbsentDetails(
				companyId, employeeId, from, to, attendanceByDate, holidayByDate, asOf, voidLabel, null);
	}

	/**
	 * {@link #voidWeeklyRestAbsentDetails(long, long, String, String, Map, Map, String, String)}
	 * with the employee's attendance already read over
	 * {@code [from - 7, min(to, asOf)]} (D-292), so legacy's rebuild of empty
	 * flags is made from those rows rather than by another statement. Null
	 * {@code rows} reads them, as before.
	 */
	public List<Map<String, Object>> voidWeeklyRestAbsentDetails(
			long companyId, long employeeId, String from, String to,
			Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attendanceByDate,
			Map<String, String> holidayByDate, String asOf, String voidLabel,
			List<LegacyAttendanceRangeRows.Row> rows) {
		List<Map<String, Object>> details = new ArrayList<>();
		if (companyId <= 0 || employeeId <= 0 || from.isEmpty() || to.isEmpty()) {
			return details;
		}
		String rangeTo = to.compareTo(asOf) > 0 ? asOf : to;
		if (rangeTo.compareTo(from) < 0) {
			return details;
		}

		Map<String, LegacyWeeklyRestCredit.AttendanceFlag> flags;
		if (!attendanceByDate.isEmpty()) {
			flags = attendanceByDate;
		} else if (rows != null) {
			flags = LegacyWeeklyRestCredit.attendanceFlags(LegacyAttendanceRangeRows.between(
					rows, LocalDate.parse(from).minusDays(7).toString(), rangeTo));
		} else {
			flags = weeklyRestCredit.attendanceFlagsInRange(companyId, employeeId, from, rangeTo);
		}
		// Legacy's own fallback looks back seven days, because weekly-rest credit
		// is decided against the block of workdays preceding the rest day.
		Map<String, String> holidays = holidayByDate.isEmpty()
				? calendar.holidaysByDate(
						companyId, LocalDate.parse(from).minusDays(7).toString(), rangeTo)
				: holidayByDate;

		// creditStatus asks isOnApprovedLeave once per preceding workday per rest
		// date, and those workdays reach up to fourteen days behind `from` --
		// blockStart walks back up to seven and workdaysBeforeBlock seven more.
		calendar.warmApprovedLeaveForEmployees(
				java.util.List.of(employeeId), LocalDate.parse(from).minusDays(14).toString(), rangeTo);
		for (String date : payrollFigures.weeklyRestDatesByStatus(
				companyId, employeeId, from, rangeTo, LegacyWeeklyRestCredit.VOID, flags, holidays, asOf)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("date", date);
			row.put("day_type", "void_weekly_rest");
			row.put("label", voidLabel);
			details.add(row);
		}
		return details;
	}

	/**
	 * {@code attendance_period_work_minutes()}
	 * ({@code attendance_calendar_helper.php:859-928}): worked and expected
	 * minutes across a period.
	 *
	 * <p>Three legacy behaviours the loop preserves exactly. Rows are ordered by
	 * {@code check_in} and the <b>first</b> row for a date wins -- later punches
	 * on the same day are skipped entirely, not summed.
	 *
	 * <p><b>The date is reserved before the exception-only check, deliberately.</b>
	 * PHP sets {@code $seen[$date_key] = true} and only then tests
	 * {@code attendance_is_exception_only_row()}
	 * ({@code attendance_calendar_helper.php:888-900}), so a midnight marker
	 * followed by a real punch on the same date consumes the slot and the punch
	 * is skipped -- understating that day's minutes. That is legacy's behaviour,
	 * not a porting slip: moving the reservation after the check would read
	 * better and would diverge. D-058 forbids the silent fix; changing it needs
	 * its own numbered decision.
	 *
	 * <p>Expected minutes are counted for any <em>punched</em> day,
	 * including a single-punch day with no check-out, which is why the guard is
	 * on {@code checkIn != "" || checkOut != null || worked > 0} rather than on
	 * worked minutes alone.
	 */
	public WorkMinutes periodWorkMinutes(
			long companyId, long employeeId, String from, String to, String weeklyRestLabel) {
		return periodWorkMinutes(
				companyId, employeeId, rangeRows.forEmployee(employeeId, from, to), from, to, weeklyRestLabel);
	}

	/**
	 * {@link #periodWorkMinutes(long, long, String, String, String)} over
	 * attendance already read for this employee (D-292), in check-in order.
	 */
	public WorkMinutes periodWorkMinutes(
			long companyId, long employeeId, List<LegacyAttendanceRangeRows.Row> rows, String from, String to,
			String weeklyRestLabel) {
		Set<String> seen = new LinkedHashSet<>();
		int workedTotal = 0;
		int expectedTotal = 0;

		for (LegacyAttendanceRangeRows.Row row : LegacyAttendanceRangeRows.between(rows, from, to)) {
			String checkIn = row.checkIn();
			if (checkIn == null || checkIn.isEmpty()) {
				continue;
			}
			String dateKey = checkIn.substring(0, Math.min(10, checkIn.length()));
			if (!seen.add(dateKey)) {
				continue;
			}

			String rawCheckOut = row.checkOut();
			String checkOut = rawCheckOut == null || rawCheckOut.trim().isEmpty() ? null : rawCheckOut;
			Object exceptionTypeId = row.exceptionTypeId();
			if (LegacyAttendanceSessions.isExceptionOnlyRow(checkIn, checkOut, exceptionTypeId)) {
				continue;
			}

			int expectedMinutes = calendar
					.expectedForDay(companyId, employeeId, dateKey, weeklyRestLabel).expectedMinutes();
			int raw = checkOut != null ? Math.max(0, row.durationMinutes()) : 0;
			int dayWorked = workedMinutes.rowWorkedMinutes(
					companyId, employeeId, dateKey, checkIn, checkOut, raw, exceptionTypeId, weeklyRestLabel);

			if (dayWorked <= 0 && checkIn.isEmpty() && checkOut == null) {
				continue;
			}
			if (!checkIn.isEmpty() || checkOut != null || dayWorked > 0) {
				expectedTotal += expectedMinutes;
				workedTotal += dayWorked;
			}
		}

		return new WorkMinutes(workedTotal, expectedTotal);
	}
}
