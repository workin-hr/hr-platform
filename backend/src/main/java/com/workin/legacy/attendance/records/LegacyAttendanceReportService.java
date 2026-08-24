package com.workin.legacy.attendance.records;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.calendar.LegacyAttendancePeriodStats;
import com.workin.legacy.attendance.calendar.LegacyAttendanceRangeCalendar;
import com.workin.legacy.attendance.calendar.LegacyAttendanceWorkedMinutes;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.wire.LegacyApiException;

/**
 * `attendance/list.php`, `attendance/stats.php` and
 * `attendance/employee_monthly_attendance.php` (Wave 12.6.4b) -- the three
 * reporting endpoints built on top of {@code attendance_calendar_helper.php}
 * and {@code weekly_rest_credit_helper.php}.
 *
 * <p>None of the three carries a role list on {@code requireAuth}: `list.php`
 * and `employee_monthly_attendance.php` (whose explicit four-role list names
 * every role that exists) both authenticate any role and then apply their own
 * finer-grained scoping; `stats.php` computes an `is_admin_or_hr` flag it
 * never actually reads, so it is bare `requireAuth()` in truth as well as in
 * source.
 */
@Service
public class LegacyAttendanceReportService {

	private final LegacyAttendanceReportStore store;
	private final LegacyAttendanceRangeCalendar rangeCalendar;
	private final LegacyAttendanceWorkedMinutes workedMinutes;
	private final LegacyAttendancePeriodStats periodStats;
	private final LegacyAttendanceSessions sessions;
	private final LegacyEmployeeStore employeeStore;
	private final LegacyClock clock;

	public LegacyAttendanceReportService(
			LegacyAttendanceReportStore store, LegacyAttendanceRangeCalendar rangeCalendar,
			LegacyAttendanceWorkedMinutes workedMinutes, LegacyAttendancePeriodStats periodStats,
			LegacyAttendanceSessions sessions, LegacyEmployeeStore employeeStore, LegacyClock clock) {
		this.store = store;
		this.rangeCalendar = rangeCalendar;
		this.workedMinutes = workedMinutes;
		this.periodStats = periodStats;
		this.sessions = sessions;
		this.employeeStore = employeeStore;
		this.clock = clock;
	}

	/** What the controller writes back as `data` plus `meta`. */
	public record Listing(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	// ------------------------------------------------------------------
	// list.php
	// ------------------------------------------------------------------

	public Listing list(LegacyRequestContext context, LegacyQueryParameters query, String weeklyRestLabel) {
		if (truthyFlag(query, "fill_days")) {
			return listFillDays(context, query, weeklyRestLabel);
		}
		return listNormal(context, query, weeklyRestLabel);
	}

	private Listing listFillDays(LegacyRequestContext context, LegacyQueryParameters query, String weeklyRestLabel) {
		LocalDate today = clock.today();
		String fromRaw = LegacyValues.toPhpString(query.value("date_from"));
		String toRaw = LegacyValues.toPhpString(query.value("date_to"));
		LocalDate fromParsed = fromRaw.isEmpty() ? null : LegacyPhpStrtotime.dateOf(fromRaw, today);
		LocalDate toParsed = toRaw.isEmpty() ? null : LegacyPhpStrtotime.dateOf(toRaw, today);

		String from;
		String to;
		if (fromParsed == null || toParsed == null) {
			from = today.withDayOfMonth(1).toString();
			to = today.withDayOfMonth(today.lengthOfMonth()).toString();
		} else {
			from = fromParsed.toString();
			to = toParsed.toString();
		}
		if (to.compareTo(from) < 0) {
			throw new LegacyApiException(400, "invalid_input");
		}

		boolean isEmployee = context.role() == LegacyEmployee.Role.EMPLOYEE;
		Long employeeId = isEmployee ? context.employeeId() : nonZeroLong(query, "employee_id");
		Long branchId = isEmployee ? null : nonZeroLong(query, "branch_id");
		Long departmentId = isEmployee ? null : nonZeroLong(query, "department_id");
		String search = LegacyPagination.searchQueryParam(query);

		List<LegacyAttendanceReportStore.RosterEmployee> roster =
				store.rosterForFillDays(context.companyId(), employeeId, branchId, departmentId, search);

		List<Map<String, Object>> rows = new ArrayList<>();
		for (LegacyAttendanceReportStore.RosterEmployee employee : roster) {
			List<Map<String, Object>> days = rangeCalendar.buildEmployeeRangeCalendar(
					context.companyId(), employee.id(), from, to, true, weeklyRestLabel, today);
			for (Map<String, Object> day : days) {
				Map<String, Object> row = new LinkedHashMap<>(day);
				row.put("employee_id", employee.id());
				row.put("employee_name", employee.employeeName());
				row.put("employee_code", employee.employeeCode());
				row.put("photo_url", employee.photoUrl());
				row.put("branch_name", employee.branchName());
				row.put("department_name", employee.departmentName());
				row.put("job_title_name", employee.jobTitleName());
				rows.add(row);
			}
		}

		long total = rows.size();
		LegacyPagination.Params pseudoPagination = new LegacyPagination.Params(1, Math.max(1, total), 0);
		return new Listing(rows, LegacyPagination.meta(total, pseudoPagination));
	}

	private Listing listNormal(LegacyRequestContext context, LegacyQueryParameters query, String weeklyRestLabel) {
		LocalDate today = clock.today();
		boolean isEmployee = context.role() == LegacyEmployee.Role.EMPLOYEE;

		LegacyAttendanceReportStore.ListFilter filter = new LegacyAttendanceReportStore.ListFilter(
				isEmployee ? context.employeeId() : null,
				context.companyId(),
				isEmployee ? null : nonZeroLong(query, "employee_id"),
				isEmployee ? null : nonZeroLong(query, "branch_id"),
				isEmployee ? null : nonZeroLong(query, "department_id"),
				dateBoundOrNull(query, "date_from", today),
				dateBoundOrNull(query, "date_to", today),
				LegacyPagination.searchQueryParam(query));

		long total = store.count(filter);
		LegacyPagination.Params pagination = LegacyPagination.params(query);
		List<Map<String, Object>> rows = store.list(filter, pagination);

		for (Map<String, Object> row : rows) {
			long employeeId = LegacyValues.toPhpLong(row.get("employee_id"));
			String checkIn = nullIfEmpty(row.get("check_in"));
			String checkOut = nullIfEmpty(row.get("check_out"));
			LocalDate dateYmd = checkIn != null ? LegacyPhpStrtotime.dateOf(checkIn, today) : null;
			if (dateYmd == null) {
				dateYmd = today;
			}
			int raw = (int) LegacyValues.toPhpLong(row.get("duration_minutes"));
			int minutes = workedMinutes.rowWorkedMinutes(
					context.companyId(), employeeId, dateYmd.toString(), checkIn, checkOut, raw,
					row.get("exception_type_id"), weeklyRestLabel);
			row.put("duration_minutes", minutes);
		}

		return new Listing(rows, LegacyPagination.meta(total, pagination));
	}

	// ------------------------------------------------------------------
	// stats.php
	// ------------------------------------------------------------------

	public Map<String, Object> stats(LegacyRequestContext context, LegacyQueryParameters query, String weeklyRestLabel) {
		LocalDate today = clock.today();
		boolean isEmployee = context.role() == LegacyEmployee.Role.EMPLOYEE;

		long targetEmployeeId = isEmployee ? context.employeeId() : orZero(nonZeroLong(query, "employee_id"));

		boolean hasDateFrom = !LegacyValues.isPhpEmpty(query.value("date_from"));
		boolean hasDateTo = !LegacyValues.isPhpEmpty(query.value("date_to"));
		String dateFromParam = LegacyValues.toPhpString(query.value("date_from"));
		String dateToParam = LegacyValues.toPhpString(query.value("date_to"));

		String referenceDate = hasDateFrom ? dateFromParam : (hasDateTo ? dateToParam : today.toString());
		String dateFromFilter = hasDateFrom
				? dateFromParam
				: phpDateOrEpoch(referenceDate, today).withDayOfMonth(1).toString();
		LocalDate dateFromFilterParsed = phpDateOrEpoch(dateFromFilter, today);
		String dateToFilter = hasDateTo
				? dateToParam
				: dateFromFilterParsed.withDayOfMonth(dateFromFilterParsed.lengthOfMonth()).toString();

		if (targetEmployeeId > 0) {
			Map<String, Object> employee = employeeStore.findOne(targetEmployeeId, context.companyId());
			if (employee == null) {
				throw new LegacyApiException(404, "employee_not_found");
			}
			return periodStatsPayload(context, targetEmployeeId, dateFromFilter, dateToFilter, weeklyRestLabel, today);
		}

		return aggregateStatsPayload(context, query, dateFromFilter, dateToFilter, today);
	}

	private Map<String, Object> periodStatsPayload(
			LegacyRequestContext context, long employeeId, String dateFromFilter, String dateToFilter,
			String weeklyRestLabel, LocalDate today) {
		LocalDate from = LegacyPhpStrtotime.dateOf(dateFromFilter, today);
		LocalDate to = LegacyPhpStrtotime.dateOf(dateToFilter, today);
		if (from == null || to == null) {
			throw new IllegalStateException("attendance_employee_period_stats: unparseable date range");
		}
		LegacyAttendancePeriodStats.PeriodStats result = periodStats.employeePeriodStats(
				context.companyId(), employeeId, from.toString(), to.toString(), weeklyRestLabel, today);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("total_days_in_month", result.totalDaysInMonth());
		payload.put("present_days", result.presentDays());
		payload.put("leave_days", result.leaveDays());
		payload.put("official_holiday_days", result.officialHolidayDays());
		payload.put("absent_days", result.absentDays());
		payload.put("total_duration_minutes", result.totalDurationMinutes());
		payload.put("overtime_minutes", result.overtimeMinutes());
		return payload;
	}

	private Map<String, Object> aggregateStatsPayload(
			LegacyRequestContext context, LegacyQueryParameters query, String dateFromFilter, String dateToFilter,
			LocalDate today) {
		Long branchId = nonZeroLong(query, "branch_id");
		Long departmentId = nonZeroLong(query, "department_id");
		String rawDateFrom = LegacyValues.isPhpEmpty(query.value("date_from"))
				? null : LegacyValues.toPhpString(query.value("date_from"));
		String rawDateTo = LegacyValues.isPhpEmpty(query.value("date_to"))
				? null : LegacyValues.toPhpString(query.value("date_to"));

		LegacyAttendanceReportStore.AggregateStats aggregate =
				store.aggregateStats(context.companyId(), branchId, departmentId, rawDateFrom, rawDateTo);

		long holidayCredit = store.officialHolidayCountInRange(context.companyId(), dateFromFilter, dateToFilter);

		String[] dateParts = dateFromFilter.split("-", -1);
		int year = (int) LegacyValues.toPhpLong(dateParts.length > 0 ? dateParts[0] : String.valueOf(today.getYear()));
		int month = (int) LegacyValues.toPhpLong(
				dateParts.length > 1 ? dateParts[1] : String.valueOf(today.getMonthValue()));
		int totalDaysInMonth = YearMonth.of(year, month).lengthOfMonth();

		long effectivePresent = Math.min(totalDaysInMonth, aggregate.presentDays() + holidayCredit);
		long absentDays = Math.max(0, totalDaysInMonth - effectivePresent);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("total_days_in_month", totalDaysInMonth);
		payload.put("present_days", aggregate.presentDays());
		payload.put("leave_days", Math.max(0, holidayCredit));
		payload.put("official_holiday_days", holidayCredit);
		payload.put("absent_days", absentDays);
		payload.put("total_duration_minutes", aggregate.totalDurationMinutes());
		payload.put("overtime_minutes", 0);
		return payload;
	}

	// ------------------------------------------------------------------
	// employee_monthly_attendance.php
	// ------------------------------------------------------------------

	public record MonthlyAttendance(Object data, Map<String, Object> meta) {
	}

	public MonthlyAttendance employeeMonthlyAttendance(
			LegacyRequestContext context, LegacyQueryParameters query, String weeklyRestLabel) {
		LocalDate today = clock.today();
		Object idParam = query.value("id");
		long targetEmployeeId = idParam != null ? LegacyValues.toPhpLong(idParam) : context.employeeId();
		if (targetEmployeeId == 0L) {
			throw new LegacyApiException(400, "employee_id_required");
		}
		if (context.role() == LegacyEmployee.Role.EMPLOYEE && targetEmployeeId != context.employeeId()) {
			throw new LegacyApiException(403, "forbidden");
		}

		Map<String, Object> employee = employeeStore.findOne(targetEmployeeId, context.companyId());
		if (employee == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		String employeeName = displayName(employee);

		int month = (int) (query.value("month") != null
				? LegacyValues.toPhpLong(query.value("month")) : today.getMonthValue());
		int year = (int) (query.value("year") != null
				? LegacyValues.toPhpLong(query.value("year")) : today.getYear());
		boolean fullMonth = truthyFlag(query, "full_month");

		if (fullMonth) {
			List<Map<String, Object>> calendar = rangeCalendar.buildEmployeeMonthlyCalendar(
					context.companyId(), targetEmployeeId, month, year, weeklyRestLabel, today);
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("has_open_check_in", false);
			meta.put("full_month", true);
			meta.put("employee_id", targetEmployeeId);
			meta.put("employee_name", employeeName);
			meta.put("month", month);
			meta.put("year", year);
			return new MonthlyAttendance(calendar, meta);
		}

		List<Map<String, Object>> rows = store.monthRows(targetEmployeeId, month, year);
		int closed = sessions.autoCloseStaleOpenSessions(context.companyId(), targetEmployeeId, weeklyRestLabel);
		if (closed > 0) {
			rows = store.monthRows(targetEmployeeId, month, year);
		}

		for (Map<String, Object> row : rows) {
			String checkIn = nullIfEmpty(row.get("check_in"));
			String checkOut = nullIfEmpty(row.get("check_out"));
			LocalDate dateYmd = checkIn != null ? LegacyPhpStrtotime.dateOf(checkIn, today) : null;
			if (dateYmd == null) {
				dateYmd = today;
			}
			int raw = checkIn != null && checkOut != null
					? Math.max(0, (int) LegacyValues.toPhpLong(row.get("duration_minutes"))) : 0;
			int minutes = workedMinutes.rowWorkedMinutes(
					context.companyId(), targetEmployeeId, dateYmd.toString(), checkIn, checkOut, raw,
					row.get("exception_type_id"), weeklyRestLabel);
			row.put("duration_minutes", minutes);
		}

		boolean hasOpenCheckIn = sessions.findOpenSession(targetEmployeeId, false, weeklyRestLabel) != null;
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("has_open_check_in", hasOpenCheckIn);
		meta.put("full_month", false);
		// `public_rows()` strips password_hash/token_version; attendance rows
		// carry neither, so it is a no-op here.
		return new MonthlyAttendance(rows, meta);
	}

	// ------------------------------------------------------------------
	// shared helpers
	// ------------------------------------------------------------------

	private static String displayName(Map<String, Object> employee) {
		String first = LegacyValues.toPhpString(employee.get("first_name"));
		String last = LegacyValues.toPhpString(employee.get("last_name"));
		return LegacyValues.phpTrim(first + " " + last);
	}

	private static boolean truthyFlag(LegacyQueryParameters query, String name) {
		Object raw = query.value(name);
		if (raw == null) {
			return false;
		}
		String value = LegacyValues.toPhpString(raw);
		return !value.isEmpty() && !value.equals("0");
	}

	/** `!empty($_GET[$name]) ? (int) $_GET[$name] : null` -- absent when zero, empty or missing. */
	private static Long nonZeroLong(LegacyQueryParameters query, String name) {
		Object raw = query.value(name);
		if (LegacyValues.isPhpEmpty(raw)) {
			return null;
		}
		return LegacyValues.toPhpLong(raw);
	}

	private static long orZero(Long value) {
		return value == null ? 0L : value;
	}

	/** `date('Y-m-d', strtotime($v))`, with PHP's own false-to-epoch coercion on parse failure. */
	private static LocalDate phpDateOrEpoch(String raw, LocalDate today) {
		LocalDate parsed = LegacyPhpStrtotime.dateOf(raw, today);
		return parsed != null ? parsed : LocalDate.of(1970, 1, 1);
	}

	/** `!empty($_GET[$name]) ? date('Y-m-d', strtotime((string) $_GET[$name])) : null`. */
	private static String dateBoundOrNull(LegacyQueryParameters query, String name, LocalDate today) {
		Object raw = query.value(name);
		if (LegacyValues.isPhpEmpty(raw)) {
			return null;
		}
		return phpDateOrEpoch(LegacyValues.toPhpString(raw), today).toString();
	}

	private static String nullIfEmpty(Object value) {
		if (value == null) {
			return null;
		}
		String text = LegacyValues.toPhpString(value);
		return text.isEmpty() ? null : text;
	}

}
