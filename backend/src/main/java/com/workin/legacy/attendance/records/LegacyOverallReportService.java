package com.workin.legacy.attendance.records;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.calendar.LegacyAttendanceReportDetails;
import com.workin.legacy.attendance.calendar.LegacyWeeklyRestCredit;
import com.workin.legacy.payroll.LegacyPayrollAttendanceFigures;
import com.workin.legacy.payroll.LegacyPayrollPeriod;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code overall_attendance_report_build()}
 * ({@code overall_attendance_report_helper.php}), the whole of
 * {@code attendance/overall_report.php} and {@code attendance/export.php} --
 * its own docblock says "Shared by overall_report API and attendance export",
 * and {@code data_export_attendance_csv()} calls it as its first statement.
 *
 * <h2>Only elapsed days count</h2>
 * <p>While the period is still open the maths runs to {@code asOf}, not to the
 * period end: future days are not absence. Every helper below is handed
 * {@code rangeTo} for that reason -- <b>except</b>
 * {@link LegacyPayrollAttendanceFigures#expectedWorkDaysUntil}, which legacy
 * hands both {@code periodTo} and {@code asOf} and which would return a
 * different figure if given the clamped end.
 *
 * <h2>An empty period still emits a row</h2>
 * <p>When nothing has elapsed the builder does not skip the employee: it emits
 * a zeroed row carrying every key, including the three labelled
 * {@code paid_rest_details} entries, and moves on. A caller diffing key sets
 * between the two shapes must find them identical.
 */
@Service
public class LegacyOverallReportService {

	/** {@code WEEKLY_REST_MIN_COVERED_WORKDAYS} -- the coverage the holiday credit is gated on. */
	private static final int MIN_COVERED_WORKDAYS = 3;

	private final LegacyOverallReportStore store;
	private final LegacyAttendanceReportDetails details;
	private final LegacyPayrollAttendanceFigures figures;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyClock clock;

	public LegacyOverallReportService(
			LegacyOverallReportStore store, LegacyAttendanceReportDetails details,
			LegacyPayrollAttendanceFigures figures, LegacyAttendanceCalendar calendar,
			LegacyWeeklyRestCredit weeklyRestCredit, LegacyClock clock) {
		this.store = store;
		this.details = details;
		this.figures = figures;
		this.calendar = calendar;
		this.weeklyRestCredit = weeklyRestCredit;
		this.clock = clock;
	}

	/** The labels the report embeds in its detail rows, resolved once per request. */
	public record Labels(
			String officialHolidayDays, String paidLeaveDays, String earnedWeeklyRest,
			String absentDay, String presentDay, String voidWeeklyRest, String weeklyRest) {
	}

	/**
	 * The filters as {@code overall_report.php} and {@code export.php} assemble
	 * them. {@code from}/{@code to} win; when either is blank the month/year
	 * pair builds the period instead.
	 */
	public record Filters(
			String from, String to, int month, int year,
			long employeeId, long branchId, long departmentId, String search) {
	}

	public List<Map<String, Object>> build(
			long companyId, Long selfEmployeeId, Long managerEmployeeId, Filters filters, Labels labels) {

		String from = filters.from() == null ? "" : filters.from().trim();
		String to = filters.to() == null ? "" : filters.to().trim();
		if (from.isEmpty() || to.isEmpty()) {
			int month = filters.month() > 0 ? filters.month() : Integer.parseInt(clock.todayAsString().substring(5, 7));
			int year = filters.year() > 0 ? filters.year() : Integer.parseInt(clock.todayAsString().substring(0, 4));
			from = "%04d-%02d-01".formatted(year, month);
			// Through the legacy date path, not LocalDate.parse: an out-of-range
			// month such as `13` builds "2026-13-01", which PHP's strtotime
			// rejects into fail(INVALID_DATE, 400). Parsing it directly would
			// throw before that translation and surface as a 500.
			LocalDate monthStart = LegacyPhpStrtotime.dateOf(from, LocalDate.parse(clock.todayAsString()));
			if (monthStart == null) {
				throw new LegacyApiException(400, "invalid_date");
			}
			to = monthStart.withDayOfMonth(monthStart.lengthOfMonth()).toString();
		}

		String periodFrom = phpDate(from);
		String periodTo = phpDate(to);
		if (periodFrom.compareTo(periodTo) > 0) {
			throw new LegacyApiException(400, "invalid_date");
		}

		String asOf = LegacyPayrollPeriod.asOfDate(clock.todayAsString(), periodTo);
		String rangeTo = LegacyPayrollPeriod.rangeEnd(periodTo, asOf);
		int totalDaysInPeriod = rangeTo.compareTo(periodFrom) < 0
				? 0
				: (int) ChronoUnit.DAYS.between(LocalDate.parse(periodFrom), LocalDate.parse(rangeTo)) + 1;

		LegacyOverallReportStore.Scope scope = new LegacyOverallReportStore.Scope(
				companyId, selfEmployeeId, managerEmployeeId,
				positiveOrNull(filters.employeeId()), positiveOrNull(filters.branchId()),
				positiveOrNull(filters.departmentId()),
				filters.search() == null || filters.search().isBlank() ? null : filters.search().trim());

		List<Map<String, Object>> report = new ArrayList<>();
		for (LegacyOverallReportStore.EmployeeRow employee : store.employees(scope, periodFrom, rangeTo)) {
			report.add(totalDaysInPeriod <= 0 || employee.id() <= 0 || rangeTo.compareTo(periodFrom) < 0
					? emptyRow(employee, periodFrom, periodTo, labels)
					: row(companyId, employee, periodFrom, periodTo, rangeTo, asOf, totalDaysInPeriod, labels));
		}
		return report;
	}

	private Map<String, Object> row(
			long companyId, LegacyOverallReportStore.EmployeeRow employee,
			String periodFrom, String periodTo, String rangeTo, String asOf,
			int totalDaysInPeriod, Labels labels) {

		long employeeId = employee.id();
		int present = employee.presentDays();

		// The credit is earned only once the employee has covered enough
		// workdays; the gate is applied here, on the query's present_days, not
		// inside the helper.
		int holidayCredit = present < MIN_COVERED_WORKDAYS
				? 0
				: details.holidayCreditDays(companyId, employeeId, periodFrom, rangeTo);

		int paidLeaveDays = figures.approvedLeaveDays(employeeId, periodFrom, rangeTo);
		BigDecimal workHoursPerDay = figures.employeeWorkHoursPerDay(employeeId);

		Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attendanceFlags =
				weeklyRestCredit.attendanceFlagsInRange(companyId, employeeId, periodFrom, rangeTo);
		String lookbackFrom = LocalDate.parse(periodFrom).minusDays(7).toString();
		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, lookbackFrom, rangeTo);

		int earnedWeeklyRest = figures.weeklyRestDatesByStatus(companyId, employeeId, periodFrom, rangeTo,
				LegacyWeeklyRestCredit.EARNED, attendanceFlags, holidayByDate, asOf).size();
		int voidWeeklyRest = figures.weeklyRestDatesByStatus(companyId, employeeId, periodFrom, rangeTo,
				LegacyWeeklyRestCredit.VOID, attendanceFlags, holidayByDate, asOf).size();

		// periodTo and asOf, deliberately -- not rangeTo. See the class javadoc.
		int expectedWorkDaysDue = figures.expectedWorkDaysUntil(companyId, employeeId, periodFrom, periodTo, asOf);

		int paidDue = Math.min(Math.max(0, expectedWorkDaysDue),
				Math.max(0, present) + paidLeaveDays + holidayCredit);
		int workdayAbsent = Math.max(0, expectedWorkDaysDue - paidDue);
		// Unearned weekly rest is unpaid, so it counts as absence on top.
		int absent = workdayAbsent + Math.max(0, voidWeeklyRest);

		int paidRestDays = holidayCredit + paidLeaveDays + earnedWeeklyRest;
		int paidRestMinutes = BigDecimal.valueOf(paidRestDays)
				.multiply(workHoursPerDay).multiply(BigDecimal.valueOf(60))
				.setScale(0, RoundingMode.HALF_UP).intValue();
		int effectivePresent = Math.min(totalDaysInPeriod,
				present + holidayCredit + paidLeaveDays + earnedWeeklyRest);

		LegacyAttendanceReportDetails.WorkMinutes workMinutes =
				details.periodWorkMinutes(companyId, employeeId, periodFrom, rangeTo, labels.weeklyRest());

		List<Map<String, Object>> absentDetails = new ArrayList<>(details.absentDetails(
				companyId, employeeId, periodFrom, rangeTo, asOf,
				labels.absentDay(), labels.presentDay(), labels.weeklyRest()));
		absentDetails.addAll(details.voidWeeklyRestAbsentDetails(
				companyId, employeeId, periodFrom, rangeTo, attendanceFlags, holidayByDate, asOf,
				labels.voidWeeklyRest()));
		absentDetails.sort(Comparator.comparing(entry -> (String) entry.getOrDefault("date", "")));

		Map<String, Object> row = identity(employee);
		row.put("total_days_in_month", totalDaysInPeriod);
		row.put("present_days", present);
		row.put("present_details", figures.attendancePresentDetails(
				employeeId, periodFrom, rangeTo, labels.presentDay()));
		row.put("official_holiday_days", holidayCredit);
		row.put("paid_leave_days", paidLeaveDays);
		row.put("earned_weekly_rest_days", earnedWeeklyRest);
		row.put("void_weekly_rest_days", voidWeeklyRest);
		row.put("paid_rest_details", paidRestDetails(holidayCredit, paidLeaveDays, earnedWeeklyRest, labels));
		row.put("effective_present_days", effectivePresent);
		row.put("absent_days", absent);
		row.put("absent_details", absentDetails);
		row.put("exception_days", employee.exceptionDays());
		row.put("exception_details", details.exceptionDetails(employeeId, periodFrom, rangeTo));
		row.put("total_duration_minutes", workMinutes.workedMinutes());
		row.put("paid_rest_days", paidRestDays);
		row.put("paid_rest_minutes", paidRestMinutes);
		row.put("overtime_minutes", workMinutes.workedMinutes() - workMinutes.expectedMinutes());
		row.put("_period_from", periodFrom);
		row.put("_period_to", periodTo);
		return row;
	}

	private static Map<String, Object> emptyRow(
			LegacyOverallReportStore.EmployeeRow employee, String periodFrom, String periodTo, Labels labels) {
		Map<String, Object> row = identity(employee);
		row.put("total_days_in_month", 0);
		row.put("present_days", 0);
		row.put("present_details", List.of());
		row.put("absent_details", List.of());
		row.put("official_holiday_days", 0);
		row.put("paid_leave_days", 0);
		row.put("earned_weekly_rest_days", 0);
		row.put("void_weekly_rest_days", 0);
		row.put("paid_rest_details", paidRestDetails(0, 0, 0, labels));
		row.put("effective_present_days", 0);
		row.put("absent_days", 0);
		row.put("exception_days", 0);
		row.put("exception_details", List.of());
		row.put("total_duration_minutes", 0);
		row.put("paid_rest_days", 0);
		row.put("paid_rest_minutes", 0);
		row.put("overtime_minutes", 0);
		row.put("_period_from", periodFrom);
		row.put("_period_to", periodTo);
		return row;
	}

	private static Map<String, Object> identity(LegacyOverallReportStore.EmployeeRow employee) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("employee_id", employee.id());
		row.put("employee_code", employee.employeeCode());
		row.put("employee_name", employee.name());
		row.put("photo_url", employee.photoUrl());
		row.put("job_title_name", employee.jobTitleName());
		// Empty string reaches the client as null, not "".
		row.put("branch_name", blankToNull(employee.branchName()));
		row.put("department_name", blankToNull(employee.departmentName()));
		return row;
	}

	private static List<Map<String, Object>> paidRestDetails(
			int holidayCredit, int paidLeaveDays, int earnedWeeklyRest, Labels labels) {
		return List.of(
				labelled("official_holiday_days", labels.officialHolidayDays(), holidayCredit),
				labelled("paid_leave_days", labels.paidLeaveDays(), paidLeaveDays),
				labelled("earned_weekly_rest_days", labels.earnedWeeklyRest(), earnedWeeklyRest));
	}

	private static Map<String, Object> labelled(String key, String label, int value) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("label_key", key);
		entry.put("label", label);
		entry.put("value", value);
		return entry;
	}

	private static String blankToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	private static Long positiveOrNull(long value) {
		return value > 0 ? value : null;
	}

	/**
	 * {@code date('Y-m-d', strtotime($value))}, with legacy's own failure:
	 * {@code strtotime} returning false is {@code fail(INVALID_DATE, 400)}.
	 */
	private String phpDate(String value) {
		LocalDate parsed = LegacyPhpStrtotime.dateOf(value, LocalDate.parse(clock.todayAsString()));
		if (parsed == null) {
			throw new LegacyApiException(400, "invalid_date");
		}
		return parsed.toString();
	}
}
