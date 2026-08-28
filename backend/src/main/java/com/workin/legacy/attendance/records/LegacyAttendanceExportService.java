package com.workin.legacy.attendance.records;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.calendar.LegacyAttendanceRangeCalendar;
import com.workin.legacy.wire.LegacyApiException;

/**
 * The two workbook sheets {@code attendance/export.php} serves:
 * {@code data_export_attendance_csv()} (the overall sheet) and
 * {@code data_export_fingerprints_sheet()} (the day-level one).
 *
 * <p>Both end in {@code api_xlsx_export_send()}, so despite the {@code _csv}
 * name neither produces CSV. Per D-085 the port owes the same
 * reader-observable workbook, not the same archive bytes.
 *
 * <h2>The overall sheet is the report, reformatted</h2>
 * <p>It calls {@link LegacyOverallReportService} and turns each row into
 * thirteen cells. Nothing about the figures is recomputed here.
 *
 * <h2>The fingerprints sheet is a different query with different rules</h2>
 * <p>It does <b>not</b> use the report builder. It has its own employee query,
 * and three of its rules differ from the report's:
 * <ul>
 *   <li>ordering uses the {@code REGEXP '^[0-9]+$'} guard the attendance
 *       listing uses, not the report's bare {@code CAST(NULLIF(...))};</li>
 *   <li>a purely numeric search matches the employee <b>code only</b>, where the
 *       report always searches name or code;</li>
 *   <li>it filters {@code is_active = 1}, which the report does not.</li>
 * </ul>
 * Three deliberate divergences between two queries in the same endpoint file.
 */
@Service
public class LegacyAttendanceExportService {

	private final LegacyOverallReportService reportService;
	private final LegacyOverallReportStore reportStore;
	private final LegacyAttendanceRangeCalendar rangeCalendar;
	private final LegacyClock clock;

	public LegacyAttendanceExportService(
			LegacyOverallReportService reportService, LegacyOverallReportStore reportStore,
			LegacyAttendanceRangeCalendar rangeCalendar, LegacyClock clock) {
		this.reportService = reportService;
		this.reportStore = reportStore;
		this.rangeCalendar = rangeCalendar;
		this.clock = clock;
	}

	/** One rendered sheet: its rows, its filename, and the per-row styles if it has any. */
	public record Sheet(String filename, List<List<String>> rows, List<Integer> rowStyles) {
	}

	/**
	 * {@code data_export_attendance_csv()}: the report's rows as thirteen
	 * columns, filename {@code overall_attendance_{from}_{to}.xlsx}.
	 *
	 * <p>The date range in the filename comes from the first row's internal
	 * {@code _period_*} keys when there is one, and from the raw filters (or
	 * today's month) when the report is empty -- which is exactly why the
	 * builder carries those keys rather than stripping them itself.
	 */
	public Sheet overallSheet(
			long companyId, Long selfEmployeeId, Long managerEmployeeId,
			LegacyOverallReportService.Filters filters, LegacyOverallReportService.Labels labels) {

		List<Map<String, Object>> report =
				reportService.build(companyId, selfEmployeeId, managerEmployeeId, filters, labels);

		String from = "%s-%02d-01".formatted(clock.todayAsString().substring(0, 4), 1);
		String to = clock.todayAsString();
		if (!report.isEmpty()) {
			from = (String) report.get(0).get("_period_from");
			to = (String) report.get(0).get("_period_to");
		} else {
			LocalDate today = LocalDate.parse(clock.todayAsString());
			from = today.withDayOfMonth(1).toString();
			to = today.toString();
			String filterFrom = trimmed(filters.from());
			String filterTo = trimmed(filters.to());
			if (!filterFrom.isEmpty()) {
				LocalDate parsed = LegacyPhpStrtotime.dateOf(filterFrom, today);
				from = parsed == null ? today.toString() : parsed.toString();
			}
			if (!filterTo.isEmpty()) {
				LocalDate parsed = LegacyPhpStrtotime.dateOf(filterTo, today);
				to = parsed == null ? today.toString() : parsed.toString();
			}
		}

		List<List<String>> rows = new ArrayList<>();
		int rowNumber = 0;
		for (Map<String, Object> row : report) {
			rowNumber++;
			int overtime = intOf(row.get("overtime_minutes"));
			rows.add(List.of(
					String.valueOf(rowNumber),
					text(row.get("employee_code")),
					text(row.get("employee_name")),
					text(row.get("job_title_name")),
					text(row.get("department_name")),
					text(row.get("branch_name")),
					String.valueOf(intOf(row.get("total_days_in_month"))),
					String.valueOf(intOf(row.get("present_days"))),
					String.valueOf(intOf(row.get("exception_days"))),
					String.valueOf(intOf(row.get("paid_rest_days"))),
					String.valueOf(intOf(row.get("absent_days"))),
					LegacyAttendanceExportFormat.overallDuration(intOf(row.get("total_duration_minutes"))),
					// Legacy shows a bare "0" rather than "00:00" when there is
					// no surplus -- the one column that is not an HH:MM cell.
					overtime > 0 ? LegacyAttendanceExportFormat.overallDuration(overtime) : "0"));
		}

		return new Sheet("overall_attendance_" + from + "_" + to + ".xlsx", rows, List.of());
	}

	/**
	 * {@code data_export_fingerprints_sheet()}: one row per employee per day in
	 * the range, filename {@code fingerprints_{from}_{to}.xlsx}.
	 *
	 * <p>Its date fallback is its own: when either bound is missing or
	 * unparseable it uses <b>this month's first and last day</b> -- not the
	 * report's month/year filters, and not today. An inverted range is
	 * {@code invalid_input}, where the report's is {@code invalid_date}.
	 */
	public Sheet fingerprintsSheet(
			long companyId, Long selfEmployeeId, Long managerEmployeeId,
			LegacyOverallReportService.Filters filters, String weeklyRestLabel, boolean arabic) {

		LocalDate today = LocalDate.parse(clock.todayAsString());
		LocalDate fromParsed = trimmed(filters.from()).isEmpty()
				? null : LegacyPhpStrtotime.dateOf(trimmed(filters.from()), today);
		LocalDate toParsed = trimmed(filters.to()).isEmpty()
				? null : LegacyPhpStrtotime.dateOf(trimmed(filters.to()), today);

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

		List<List<String>> rows = new ArrayList<>();
		List<Integer> rowStyles = new ArrayList<>();
		int rowNumber = 0;

		for (LegacyOverallReportStore.EmployeeRow employee
				: reportStore.fingerprintEmployees(scopeFor(companyId, selfEmployeeId, managerEmployeeId, filters))) {
			// capAtToday = true, exactly as PHP passes it
			// (`data_export_helper.php:268`). A default export before month-end
			// therefore stops at today even though the filename names the whole
			// month -- legacy's behaviour, and changing it here would diverge.
			for (Map<String, Object> day : rangeCalendar.buildEmployeeRangeCalendar(
					companyId, employee.id(), from, to, true, weeklyRestLabel, today)) {
				rowNumber++;
				String dateStr = text(day.get("date"));
				String checkIn = blankToNull(day.get("check_in"));
				String checkOut = blankToNull(day.get("check_out"));
				boolean incomplete = checkIn == null || checkOut == null;

				rows.add(List.of(
						String.valueOf(rowNumber),
						text(employee.employeeCode()),
						text(employee.name()),
						dateStr.isEmpty() ? "" : LegacyAttendanceExportFormat.attendanceDate(dateStr, today),
						dateStr.isEmpty() ? "" : LegacyAttendanceExportFormat.weekdayName(dateStr, today, arabic),
						LegacyAttendanceExportFormat.attendanceClock(checkIn, today),
						LegacyAttendanceExportFormat.attendanceClock(checkOut, today),
						LegacyAttendanceExportFormat.attendanceDuration(
								intOf(day.get("duration_minutes")), incomplete),
						text(day.get("exception_type_name"))));

				rowStyles.add(LegacyAttendanceExportFormat.rowStyle(
						truthy(day.get("is_missing")), truthy(day.get("is_weekly_rest")),
						truthy(day.get("is_official_holiday")), checkIn != null, checkOut != null));
			}
		}

		return new Sheet("fingerprints_" + from + "_" + to + ".xlsx", rows, rowStyles);
	}

	private static LegacyOverallReportStore.Scope scopeFor(
			long companyId, Long selfEmployeeId, Long managerEmployeeId,
			LegacyOverallReportService.Filters filters) {
		return new LegacyOverallReportStore.Scope(
				companyId, selfEmployeeId, managerEmployeeId,
				filters.employeeId() > 0 ? filters.employeeId() : null,
				filters.branchId() > 0 ? filters.branchId() : null,
				filters.departmentId() > 0 ? filters.departmentId() : null,
				trimmed(filters.search()).isEmpty() ? null : trimmed(filters.search()));
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}

	private static String text(Object value) {
		return value == null ? "" : LegacyValues.toPhpString(value);
	}

	private static String blankToNull(Object value) {
		String text = text(value);
		return text.isEmpty() ? null : text;
	}

	private static int intOf(Object value) {
		return value == null ? 0 : (int) LegacyValues.toPhpLong(value);
	}

	/** PHP's `!empty($day['flag'])`. */
	private static boolean truthy(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		String text = text(value);
		return !text.isEmpty() && !"0".equals(text) && !"false".equals(text);
	}
}
