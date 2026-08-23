package com.workin.legacy.attendance.spreadsheet;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;

/**
 * {@code attendance_excel_analyze()}
 * ({@code attendance_excel_analyzer.php:596-857}) -- the dry run behind
 * {@code attendance/analyze_excel.php} (Wave 12.6.4's clear endpoint).
 *
 * <h2>It shares the importer's reader, not its writes</h2>
 * <p>The same {@link LegacyAttendanceImportReader} produces the rows, so every
 * format quirk the import has -- the XLS branch's unconditional
 * {@code punch_log} fallback, the inverted BOM handling, D-097's BIFF8 surface
 * -- is identical here. What differs is that nothing is written and no
 * transaction exists: an analysis is a pure read, and its failures are the
 * reader's own.
 *
 * <h2>Four shapes of answer, and three of them are early returns</h2>
 * <p>{@code empty}, {@code unknown} and {@code template} each return before a
 * single punch is examined, with an empty {@code employees} list and a
 * hard-coded English warning that is <b>not</b> a catalog key -- so it does not
 * follow the request's locale. Only {@code punch_log} produces the real
 * analysis.
 *
 * <h2>Missing days are filled inside the punch range only</h2>
 * <p>After the punched days are classified, the gap between the first and last
 * punched date is walked and every absent day is added as {@code missing} or
 * {@code rest_or_holiday}. PHP's own comment says why it is not the calendar
 * month: doing that "wrongly starts from day 1 of the month". A one-punch
 * upload therefore reports exactly one day.
 */
@Service
public class LegacyAttendanceAnalyzer {

	/**
	 * {@code attendance_import_fetch_employee_by_code()}
	 * ({@code attendance_excel_analyzer.php:476-491}).
	 *
	 * <p>Company-scoped and exact on {@code employee_code} -- no LIKE, no
	 * fallback to id. A sheet code that is not a code in this company is
	 * unmatched, which is what makes an employee {@code skipped} rather than an
	 * error.
	 */
	private static final String EMPLOYEE_BY_CODE = """
			SELECT e.*, TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) AS employee_name
			FROM employees e
			WHERE e.company_id = ? AND e.employee_code = ?
			LIMIT 1""";

	private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;

	public LegacyAttendanceAnalyzer(
			javax.sql.DataSource legacyDataSource, LegacyAttendanceCalendar calendar) {
		this.jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
	}

	/** The matched employee row, or null. */
	private Map<String, Object> employeeByCode(long companyId, String code) {
		if (code.isEmpty()) {
			return null;
		}
		List<Map<String, Object>> rows = jdbcTemplate.query(
				EMPLOYEE_BY_CODE, com.workin.legacy.LegacyJdbcValues.rowMapper(), companyId, code);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * The analysis payload, in PHP's key order.
	 *
	 * @param now the reference instant for punch parsing -- the same clock the
	 *        import uses, so a relative punch value resolves identically
	 */
	public Map<String, Object> analyze(
			byte[] content, long companyId, LocalDateTime now, String weeklyRestLabel) {
		LegacyAttendanceImportReader.Loaded loaded = LegacyAttendanceImportReader.loadRows(content);

		if ("empty".equals(loaded.format())) {
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("total_punches", 0);
			return envelope("empty", summary, List.of(), List.of("File is empty"));
		}
		if ("unknown".equals(loaded.format())) {
			return envelope("unknown", new LinkedHashMap<>(), List.of(), List.of(
					"Unrecognized column layout. Expected fingerprint punch log (name, datetime,"
							+ " employee code) or attendance template."));
		}
		if ("template".equals(loaded.format())) {
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("total_rows", loaded.rows().size());
			return envelope("template", summary, List.of(), List.of(
					"Template format detected — import will use row-by-row check-in/check-out columns."));
		}

		List<LegacyAttendanceImportReader.Punch> punches =
				LegacyAttendanceImportReader.extractPunches(loaded.rows(), loaded.keys(), now);
		List<LegacyAttendanceImportReader.DayRecord> records =
				LegacyAttendanceImportReader.groupPunches(punches);

		// Group by sheet code, keeping the first non-empty name seen.
		Map<String, Group> byEmployee = new LinkedHashMap<>();
		for (LegacyAttendanceImportReader.DayRecord record : records) {
			String code = LegacyAttendanceImportReader.sheetCode(record.sheetCode());
			if (code.isEmpty()) {
				continue;
			}
			Group group = byEmployee.computeIfAbsent(code, key -> new Group(record.sheetName()));
			if (group.sheetName.isEmpty() && !record.sheetName().isEmpty()) {
				group.sheetName = record.sheetName();
			}
			group.days.add(record);
		}

		List<Map<String, Object>> employeesOut = new ArrayList<>();
		int matched = 0;
		int unknown = 0;
		int toImport = 0;
		int skipped = 0;
		List<String> dates = new ArrayList<>();

		for (Map.Entry<String, Group> entry : byEmployee.entrySet()) {
			String code = LegacyAttendanceImportReader.sheetCode(entry.getKey());
			Group group = entry.getValue();
			Map<String, Object> employee = employeeByCode(companyId, code);
			Long employeeId = employee == null
					? null
					: com.workin.legacy.LegacyValues.toPhpLong(employee.get("id"));
			if (employeeId != null) {
				matched++;
				toImport += group.days.size();
			} else {
				unknown++;
				skipped += group.days.size();
			}

			Totals totals = new Totals();
			List<Map<String, Object>> daysOut = new ArrayList<>();
			String[] shift = {null, null, null};

			for (LegacyAttendanceImportReader.DayRecord day : group.days) {
				dates.add(day.date());
				LegacyAttendanceCalendar.DayExpectation expected = employeeId == null
						? new LegacyAttendanceCalendar.DayExpectation(0, null, null, null, false, null)
						: calendar.expectedForDay(companyId, employeeId, day.date(), weeklyRestLabel);
				if (employeeId != null && shift[0] == null && expected.shiftName() != null
						&& !expected.shiftName().isEmpty()) {
					shift[0] = expected.shiftName();
					shift[1] = expected.shiftStart();
					shift[2] = expected.shiftEnd();
				}

				Classification classify = classifyDay(
						day.actualMinutes(), expected.expectedMinutes(), day.complete());
				if (day.complete()) {
					totals.completeDays++;
				} else {
					totals.incompleteDays++;
				}
				if (expected.restDay()) {
					totals.restDays++;
				}
				totals.actualMinutes += day.actualMinutes();
				totals.expectedMinutes += expected.expectedMinutes();
				totals.overtimeMinutes += classify.overtimeMinutes();
				totals.undertimeMinutes += classify.undertimeMinutes();

				daysOut.add(dayRow(day.date(), day.checkIn(), day.checkOut(), day.punchCount(),
						day.actualMinutes(), expected.expectedMinutes(), classify.overtimeMinutes(),
						classify.undertimeMinutes(), classify.status(), expected.restDay(),
						expected.restNote(), expected.shiftStart(), expected.shiftEnd()));
			}

			if (employeeId != null && !daysOut.isEmpty()) {
				fillMissingDays(companyId, employeeId, daysOut, totals, shift, weeklyRestLabel);
			}

			String[] nameParts = splitSheetName(group.sheetName);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("sheet_code", code);
			out.put("sheet_name", group.sheetName);
			out.put("suggested_first_name", nameParts[0]);
			out.put("suggested_last_name", nameParts[1]);
			out.put("status", employeeId != null ? "matched" : "skipped");
			out.put("employee_id", employeeId);
			out.put("employee_name", employee == null ? null : text(employee.get("employee_name")));
			out.put("employee_code_in_db", employee == null ? null : text(employee.get("employee_code")));
			out.put("shift_name", shift[0]);
			out.put("shift_start", shift[1]);
			out.put("shift_end", shift[2]);
			out.put("punch_count", group.days.stream()
					.mapToInt(LegacyAttendanceImportReader.DayRecord::punchCount).sum());
			out.put("days", daysOut);
			out.put("totals", totals.toMap());
			employeesOut.add(out);
		}

		// usort(): numeric when both codes are all digits, natural-case-insensitive otherwise.
		employeesOut.sort((left, right) -> {
			String a = text(left.get("sheet_code"));
			String b = text(right.get("sheet_code"));
			if (isDigits(a) && isDigits(b)) {
				return Long.compare(Long.parseLong(a), Long.parseLong(b));
			}
			return a.compareToIgnoreCase(b);
		});

		dates.sort(Comparator.naturalOrder());
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("total_punches", punches.size());
		summary.put("total_employees", employeesOut.size());
		summary.put("matched_employees", matched);
		summary.put("unknown_employees", unknown);
		summary.put("total_days", records.size());
		summary.put("date_from", dates.isEmpty() ? null : dates.get(0));
		summary.put("date_to", dates.isEmpty() ? null : dates.get(dates.size() - 1));
		summary.put("records_to_import", toImport);
		summary.put("records_skipped", skipped);
		return envelope("punch_log", summary, employeesOut, List.of());
	}

	/**
	 * The gap fill, bounded by the punched range.
	 *
	 * <p>A rest day contributes to {@code rest_days} and nothing else; a missing
	 * working day contributes its whole expected duration as
	 * <b>undertime</b> and counts as incomplete. So an employee who punched
	 * Monday and Friday of the same week is charged for Tuesday to Thursday.
	 */
	private void fillMissingDays(
			long companyId, long employeeId, List<Map<String, Object>> daysOut, Totals totals,
			String[] shift, String weeklyRestLabel) {
		List<String> present = new ArrayList<>(daysOut.stream().map(day -> text(day.get("date"))).toList());
		present.sort(Comparator.naturalOrder());
		java.time.LocalDate from = java.time.LocalDate.parse(present.get(0));
		java.time.LocalDate to = java.time.LocalDate.parse(present.get(present.size() - 1));
		java.util.Set<String> seen = new java.util.HashSet<>(present);

		List<Map<String, Object>> missing = new ArrayList<>();
		for (java.time.LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			String date = day.toString();
			if (seen.contains(date)) {
				continue;
			}
			LegacyAttendanceCalendar.DayExpectation expected =
					calendar.expectedForDay(companyId, employeeId, date, weeklyRestLabel);
			if (expected.restDay()) {
				missing.add(dayRow(date, null, null, 0, 0, 0, 0, 0, "rest_or_holiday", true,
						expected.restNote(), expected.shiftStart(), expected.shiftEnd()));
				totals.restDays++;
				continue;
			}
			int expectedMinutes = expected.expectedMinutes();
			missing.add(dayRow(date, null, null, 0, 0, expectedMinutes, 0, expectedMinutes, "missing",
					false, null, expected.shiftStart(), expected.shiftEnd()));
			totals.incompleteDays++;
			totals.expectedMinutes += expectedMinutes;
			totals.undertimeMinutes += expectedMinutes;
			if (shift[0] == null && expected.shiftName() != null && !expected.shiftName().isEmpty()) {
				shift[0] = expected.shiftName();
				shift[1] = expected.shiftStart();
				shift[2] = expected.shiftEnd();
			}
		}
		if (!missing.isEmpty()) {
			daysOut.addAll(missing);
			daysOut.sort(Comparator.comparing(day -> text(day.get("date"))));
		}
	}

	/** {@code attendance_import_classify_day()} ({@code attendance_excel_analyzer.php:555-573}). */
	public record Classification(String status, int overtimeMinutes, int undertimeMinutes) {
	}

	/**
	 * The classification, including its <b>15-minute dead band</b>.
	 *
	 * <p>A difference strictly inside plus or minus fifteen minutes is
	 * {@code ok} with both counters zero, so a day fourteen minutes short
	 * reports no undertime at all. And a day with no expected minutes -- a rest
	 * day worked -- is {@code rest_or_holiday} with every worked minute counted
	 * as overtime.
	 */
	public static Classification classifyDay(int actualMinutes, int expectedMinutes, boolean complete) {
		if (!complete) {
			return new Classification("incomplete", 0, 0);
		}
		if (expectedMinutes <= 0) {
			return new Classification("rest_or_holiday", Math.max(0, actualMinutes), 0);
		}
		int difference = actualMinutes - expectedMinutes;
		if (difference >= 15) {
			return new Classification("overtime", difference, 0);
		}
		if (difference <= -15) {
			return new Classification("undertime", 0, Math.abs(difference));
		}
		return new Classification("ok", 0, 0);
	}

	/**
	 * {@code attendance_import_split_sheet_name()}: an empty name becomes the
	 * literal {@code "Employee"} with no surname.
	 */
	public static String[] splitSheetName(String name) {
		String collapsed = (name == null ? "" : name).replaceAll("\\s+", " ").trim();
		if (collapsed.isEmpty()) {
			return new String[] {"Employee", ""};
		}
		String[] parts = collapsed.split("\\s+");
		if (parts.length == 1) {
			return new String[] {parts[0], ""};
		}
		return new String[] {parts[0], String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length))};
	}

	private static Map<String, Object> dayRow(
			String date, String checkIn, String checkOut, int punchCount, int actualMinutes,
			int expectedMinutes, int overtimeMinutes, int undertimeMinutes, String status,
			boolean restDay, String restNote, String shiftStart, String shiftEnd) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("date", date);
		row.put("check_in", checkIn);
		row.put("check_out", checkOut);
		row.put("punch_count", punchCount);
		row.put("actual_minutes", actualMinutes);
		row.put("expected_minutes", expectedMinutes);
		row.put("overtime_minutes", overtimeMinutes);
		row.put("undertime_minutes", undertimeMinutes);
		row.put("status", status);
		row.put("is_rest_day", restDay);
		row.put("rest_note", restNote);
		row.put("shift_start", shiftStart);
		row.put("shift_end", shiftEnd);
		return row;
	}

	private static Map<String, Object> envelope(
			String format, Map<String, Object> summary, List<Map<String, Object>> employees,
			List<String> warnings) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("import_format", format);
		out.put("summary", summary);
		out.put("employees", employees);
		out.put("warnings", warnings);
		return out;
	}

	private static boolean isDigits(String value) {
		return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static final class Group {
		private String sheetName;
		private final List<LegacyAttendanceImportReader.DayRecord> days = new ArrayList<>();

		private Group(String sheetName) {
			this.sheetName = sheetName;
		}
	}

	private static final class Totals {
		private int actualMinutes;
		private int expectedMinutes;
		private int overtimeMinutes;
		private int undertimeMinutes;
		private int completeDays;
		private int incompleteDays;
		private int restDays;

		private Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("actual_minutes", actualMinutes);
			map.put("expected_minutes", expectedMinutes);
			map.put("overtime_minutes", overtimeMinutes);
			map.put("undertime_minutes", undertimeMinutes);
			map.put("complete_days", completeDays);
			map.put("incomplete_days", incompleteDays);
			map.put("rest_days", restDays);
			return map;
		}
	}

}
