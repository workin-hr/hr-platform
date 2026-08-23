package com.workin.legacy.schedules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;

/**
 * The month-view and generation half of {@code schedule_helper.php}, for Wave
 * 12.6.5.
 *
 * <p>Ports {@code schedule_month_overview()},
 * {@code schedule_compute_days_for_range()}, {@code schedule_manual_rows_map()},
 * {@code schedule_shift_summary()}, {@code schedule_collect_weekly_rest_days()},
 * {@code schedule_company_weekly_rest_dows()},
 * {@code schedule_parse_days_off_to_dows()}, {@code schedule_dow_label()} and
 * {@code schedule_generate_for_employee()}. The day-level rules --
 * {@code schedule_shift_for_employee_on_date}, {@code schedule_exception_for_day},
 * the holiday map -- are <b>not</b> re-implemented; they come from
 * {@link LegacyAttendanceCalendar}, which Wave 12.6.3 landed.
 *
 * <h2>A manual row always wins</h2>
 * <p>{@code compute_days_for_range()} checks {@code employee_schedules} first
 * and only computes from the shift when there is no stored row. So a day
 * written by {@code assign_employee_schedule.php} overrides the assignment, and
 * it does so <em>even when it is a rest day or a holiday</em> -- the exception
 * logic never runs for a manual day.
 *
 * <h2>The two grammars are still different</h2>
 * <p>{@code schedule_company_weekly_rest_dows()} accepts a <b>numeric</b>
 * setting value ({@code "5"} is Friday) while
 * {@code schedule_parse_days_off_to_dows()}, which reads the shift's own
 * {@code days_off} text, does not. Same day names, two matchers -- and this is
 * a third one again, distinct from {@code payroll_is_weekly_rest_day()}, which
 * compares a single day rather than collecting a set.
 */
@Component
public class LegacyScheduleCalendar {

	/** {@code schedule_manual_rows_map()}. */
	private static final String MANUAL_ROWS = """
			SELECT es.id, es.schedule_date, es.name, es.start_time, es.end_time, es.exception_note
			FROM employee_schedules AS es
			WHERE es.employee_id = ? AND es.schedule_date BETWEEN ? AND ?
			ORDER BY es.schedule_date ASC""";

	/** The assignment that supersedes the current one, for {@code effective_to}. */
	private static final String NEXT_ASSIGNMENT = """
			SELECT effective_from FROM employee_shift_assignments
			WHERE employee_id = ? AND effective_from > ?
			ORDER BY effective_from ASC, id ASC
			LIMIT 1""";

	/** {@code schedule_parse_days_off_to_dows()} and the settings variant share this table. */
	private static final Map<String, Integer> DAY_NAMES = Map.ofEntries(
			Map.entry("sunday", 0), Map.entry("monday", 1), Map.entry("tuesday", 2),
			Map.entry("wednesday", 3), Map.entry("thursday", 4), Map.entry("friday", 5),
			Map.entry("saturday", 6),
			Map.entry("sun", 0), Map.entry("mon", 1), Map.entry("tue", 2), Map.entry("wed", 3),
			Map.entry("thu", 4), Map.entry("fri", 5), Map.entry("sat", 6),
			Map.entry("الأحد", 0), Map.entry("الاحد", 0),
			Map.entry("الإثنين", 1), Map.entry("الاثنين", 1),
			Map.entry("الثلاثاء", 2),
			Map.entry("الأربعاء", 3), Map.entry("الاربعاء", 3),
			Map.entry("الخميس", 4), Map.entry("الجمعة", 5), Map.entry("السبت", 6));

	/** {@code schedule_dow_label()}, both locales. */
	private static final List<String> ENGLISH_DAYS = List.of(
			"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday");

	private static final List<String> ARABIC_DAYS = List.of(
			"الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت");

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyWeeklyOffDays weeklyOffDays;
	private final LegacyScheduleStore store;
	private final com.workin.legacy.LegacyClock clock;

	public LegacyScheduleCalendar(
			DataSource legacyDataSource, LegacyAttendanceCalendar calendar,
			LegacyWeeklyOffDays weeklyOffDays, LegacyScheduleStore store,
			com.workin.legacy.LegacyClock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
		this.weeklyOffDays = weeklyOffDays;
		this.store = store;
		this.clock = clock;
	}

	/** {@code schedule_generate_for_employee()}'s return array. */
	public record GenerationOutcome(int count, Long shiftId, String shiftName) {
	}

	/**
	 * {@code schedule_month_overview()} ({@code schedule_helper.php:461-492}).
	 *
	 * <p>The month is clamped to 1-12 but the <b>year is not clamped at all</b>,
	 * so a caller asking for year 0 gets a real, empty month rather than an
	 * error.
	 *
	 * <p>The summary is taken from a single reference day: the last day of the
	 * month, except for the current month, where it is <em>today</em>. So asking
	 * about this month reports the shift in force now, and asking about any
	 * other month reports the one in force at its end.
	 */
	public Map<String, Object> monthOverview(
			long companyId, long employeeId, int year, int month, String locale,
			String weeklyRestLabel, LocalDate today) {
		int clampedMonth = Math.max(1, Math.min(12, month));
		String from = String.format("%04d-%02d-01", year, clampedMonth);
		LocalDate firstDay = LocalDate.parse(from);
		String to = firstDay.withDayOfMonth(firstDay.lengthOfMonth()).toString();

		String summaryDate = to;
		if (today.getYear() == year && today.getMonthValue() == clampedMonth) {
			summaryDate = today.toString();
		}

		Map<String, Object> shiftRow = calendar.shiftForEmployeeOnDate(employeeId, summaryDate);
		Map<String, Object> shiftSummary = shiftSummary(employeeId, summaryDate);
		List<Map<String, Object>> weeklyRest = shiftRow == null
				? List.of()
				: collectWeeklyRestDays(companyId, shiftRow, locale);

		List<Map<String, Object>> officialHolidays = new ArrayList<>();
		calendar.holidaysByDate(companyId, from, to).forEach((date, name) -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("date", date);
			entry.put("name", name.isEmpty() ? weeklyRestLabel : name);
			officialHolidays.add(entry);
		});

		Map<String, Object> overview = new LinkedHashMap<>();
		overview.put("shift", shiftSummary);
		overview.put("weekly_rest_days", weeklyRest);
		overview.put("official_holidays", officialHolidays);
		overview.put("days", computeDaysForRange(companyId, employeeId, from, to, weeklyRestLabel));
		return overview;
	}

	/**
	 * {@code schedule_shift_summary()} ({@code schedule_helper.php:361-392}).
	 *
	 * <p>{@code effective_to} is the day before the next assignment starts, or
	 * null when this is the latest one -- so an open-ended assignment reports
	 * null rather than a far-future date.
	 */
	public Map<String, Object> shiftSummary(long employeeId, String onDate) {
		Map<String, Object> assignment = calendar.shiftForEmployeeOnDate(employeeId, onDate);
		if (assignment == null) {
			return null;
		}
		Object effectiveFrom = assignment.get("effective_from");
		String effectiveTo = null;
		if (effectiveFrom != null && !"".equals(effectiveFrom)) {
			List<String> next = jdbcTemplate.queryForList(
					NEXT_ASSIGNMENT, String.class, employeeId, effectiveFrom);
			if (!next.isEmpty() && next.get(0) != null && !next.get(0).isEmpty()) {
				effectiveTo = LocalDate.parse(next.get(0)).minusDays(1).toString();
			}
		}

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("shift_id", LegacyValues.toPhpLong(assignment.get("shift_id")));
		summary.put("name", assignment.get("name") == null ? "" : String.valueOf(assignment.get("name")));
		summary.put("start_time", assignment.get("start_time"));
		summary.put("end_time", assignment.get("end_time"));
		summary.put("effective_from", effectiveFrom);
		summary.put("effective_to", effectiveTo);
		return summary;
	}

	/**
	 * {@code schedule_compute_days_for_range()}
	 * ({@code schedule_helper.php:399-449}).
	 *
	 * <p>Three outcomes per day: a manual row is emitted with its real
	 * {@code id}; a day with an assigned shift is computed and emitted with
	 * {@code id = 0}; and a day with <b>no</b> shift at all is <em>skipped
	 * entirely</em> -- so the array is not necessarily one entry per calendar
	 * day, and a client cannot index it by day-of-month.
	 */
	public List<Map<String, Object>> computeDaysForRange(
			long companyId, long employeeId, String from, String to, String weeklyRestLabel) {
		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(to);
		if (end.isBefore(start)) {
			return List.of();
		}
		Map<String, Map<String, Object>> manual = manualRowsMap(employeeId, from, to);
		Map<String, String> holidays = calendar.holidaysByDate(companyId, from, to);

		List<Map<String, Object>> out = new ArrayList<>();
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			String date = day.toString();
			Map<String, Object> stored = manual.get(date);
			if (stored != null) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("id", LegacyValues.toPhpLong(stored.get("id")));
				row.put("schedule_date", date);
				row.put("name", stored.get("name"));
				row.put("start_time", stored.get("start_time"));
				row.put("end_time", stored.get("end_time"));
				row.put("exception", stored.get("exception_note"));
				out.add(row);
				continue;
			}

			Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, date);
			if (shift == null) {
				continue;
			}
			String exception = calendar.exceptionForDay(companyId, date, shift, holidays, weeklyRestLabel);
			Map<String, Object> computed = rowFromShift(shift, exception);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", 0L);
			row.put("schedule_date", date);
			row.put("name", computed.get("name"));
			row.put("start_time", computed.get("start_time"));
			row.put("end_time", computed.get("end_time"));
			row.put("exception", computed.get("exception_note"));
			out.add(row);
		}
		return out;
	}

	/**
	 * {@code schedule_generate_for_employee()}
	 * ({@code schedule_helper.php:147-200}).
	 *
	 * <p>The assignment that decides the <b>reported</b> shift is the one
	 * effective on the range's <em>end</em> date, while each day is generated
	 * from the assignment effective on that day -- so a range spanning a shift
	 * change generates both shifts and reports only the later one.
	 *
	 * <p>{@code replace_existing} deletes the whole range first, including days
	 * the loop will then skip for having no shift. So replacing a range whose
	 * assignment starts midway <b>removes</b> earlier manual rows without
	 * writing anything in their place.
	 */
	public GenerationOutcome generateForEmployee(
			long companyId, long employeeId, String from, String to, boolean replaceExisting,
			String weeklyRestLabel) {
		// Constructed before anything else, exactly as PHP does -- so a
		// malformed bound throws before the assignment is looked up, and the
		// inverted-range early return is a *separate* outcome from a parse
		// failure and from having no assignment. Three different results.
		LocalDate start = constructDate(from);
		LocalDate end = constructDate(to);
		if (end.isBefore(start)) {
			return new GenerationOutcome(0, null, null);
		}

		Map<String, Object> assignment = calendar.shiftForEmployeeOnDate(employeeId, to);
		if (assignment == null) {
			return new GenerationOutcome(0, null, null);
		}
		long shiftId = LegacyValues.toPhpLong(assignment.get("shift_id"));
		String shiftName = assignment.get("name") == null ? "" : String.valueOf(assignment.get("name"));

		if (replaceExisting) {
			jdbcTemplate.update(
					"DELETE FROM employee_schedules WHERE employee_id = ? AND schedule_date BETWEEN ? AND ?",
					employeeId, from, to);
		}

		Map<String, String> holidays = calendar.holidaysByDate(companyId, from, to);
		int count = 0;
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			String date = day.toString();
			Map<String, Object> dayShift = calendar.shiftForEmployeeOnDate(employeeId, date);
			if (dayShift == null) {
				continue;
			}
			String exception = calendar.exceptionForDay(companyId, date, dayShift, holidays, weeklyRestLabel);
			Map<String, Object> row = rowFromShift(dayShift, exception);
			store.upsertDay(employeeId, date,
					(String) row.get("name"), (String) row.get("start_time"),
					(String) row.get("end_time"), (String) row.get("exception_note"));
			count++;
		}
		return new GenerationOutcome(count, shiftId, shiftName);
	}

	/**
	 * {@code schedule_row_from_shift()} ({@code schedule_helper.php:96-114}).
	 *
	 * <p>An exception note <b>replaces</b> the whole row: name, start and end
	 * all become null. So a generated rest day stores no times at all, which is
	 * how a rest day is distinguishable from a working day in
	 * {@code employee_schedules}.
	 */
	public static Map<String, Object> rowFromShift(Map<String, Object> shift, String exceptionNote) {
		Map<String, Object> row = new LinkedHashMap<>();
		if (exceptionNote != null && !exceptionNote.trim().isEmpty()) {
			row.put("name", null);
			row.put("start_time", null);
			row.put("end_time", null);
			row.put("exception_note", exceptionNote.trim());
			return row;
		}
		String name = shift.get("name") == null ? "" : String.valueOf(shift.get("name")).trim();
		row.put("name", name.isEmpty() ? null : name);
		row.put("start_time", text(shift.get("start_time")));
		row.put("end_time", text(shift.get("end_time")));
		row.put("exception_note", null);
		return row;
	}

	/** {@code schedule_manual_rows_map()}: last row per date wins. */
	public Map<String, Map<String, Object>> manualRowsMap(long employeeId, String from, String to) {
		Map<String, Map<String, Object>> map = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbcTemplate.query(
				MANUAL_ROWS, LegacyJdbcValues.rowMapper(), employeeId, from, to)) {
			Object date = row.get("schedule_date");
			String key = date == null ? "" : String.valueOf(date);
			if (!key.isEmpty()) {
				map.put(key, row);
			}
		}
		return map;
	}

	/**
	 * {@code schedule_collect_weekly_rest_days()}: the shift's own days off
	 * merged with the company setting, de-duplicated and sorted ascending.
	 */
	public List<Map<String, Object>> collectWeeklyRestDays(
			long companyId, Map<String, Object> shift, String locale) {
		TreeSet<Integer> days = new TreeSet<>();
		days.addAll(parseDaysOffToDows(
				shift == null || shift.get("days_off") == null ? "" : String.valueOf(shift.get("days_off"))));
		days.addAll(companyWeeklyRestDows(companyId));

		List<Map<String, Object>> out = new ArrayList<>();
		for (Integer day : days) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("day_of_week", day);
			entry.put("name", dayLabel(day, locale));
			out.add(entry);
		}
		return out;
	}

	/**
	 * {@code schedule_company_weekly_rest_dows()}
	 * ({@code schedule_helper.php:267-304}).
	 *
	 * <p>Unlike the shift's own {@code days_off} text, this one accepts a
	 * <b>numeric</b> value and takes it as the day number directly, with no
	 * range check -- a setting of {@code "9"} collects day 9, which no calendar
	 * day can match.
	 */
	public List<Integer> companyWeeklyRestDows(long companyId) {
		List<Integer> days = new ArrayList<>();
		for (String raw : weeklyOffDays.forCompany(companyId)) {
			String trimmed = raw.trim();
			String lowered = trimmed.toLowerCase(Locale.ROOT);
			if (lowered.isEmpty()) {
				continue;
			}
			if (lowered.matches("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$")) {
				days.add((int) Double.parseDouble(lowered));
				continue;
			}
			Integer byLowered = DAY_NAMES.get(lowered);
			if (byLowered != null) {
				days.add(byLowered);
				continue;
			}
			Integer byTrimmed = DAY_NAMES.get(trimmed);
			if (byTrimmed != null) {
				days.add(byTrimmed);
			}
		}
		return days.stream().distinct().toList();
	}

	/**
	 * {@code schedule_parse_days_off_to_dows()}
	 * ({@code schedule_helper.php:233-262}): names only, no numbers.
	 */
	public static List<Integer> parseDaysOffToDows(String daysOff) {
		String trimmed = daysOff == null ? "" : daysOff.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}
		List<Integer> days = new ArrayList<>();
		for (String part : trimmed.split("[,،;]+")) {
			String token = part.trim().toLowerCase(Locale.ROOT);
			if (token.isEmpty()) {
				continue;
			}
			Integer mapped = DAY_NAMES.get(token);
			if (mapped != null) {
				days.add(mapped);
			}
		}
		return days.stream().distinct().toList();
	}

	/** {@code schedule_dow_label()}: clamped to 0-6, and locale-dependent. */
	public static String dayLabel(int dayOfWeek, String locale) {
		int clamped = Math.max(0, Math.min(6, dayOfWeek));
		return "ar".equals(locale) ? ARABIC_DAYS.get(clamped) : ENGLISH_DAYS.get(clamped);
	}

	/**
	 * {@code new DateTimeImmutable($raw)} for the range bounds.
	 *
	 * <p>Measured on PHP 8.3: the constructor accepts exactly what
	 * {@code strtotime()} accepts, with the same value, so the bounded
	 * {@link com.workin.legacy.LegacyPhpStrtotime} grammar is reused rather than
	 * a second parser being written. {@code 2026/04/26}, {@code 26-04-2026},
	 * {@code 20260426} and {@code tomorrow} are all accepted; {@code 2026-02-30}
	 * rolls to 2 March. See {@code LegacyPhpDateTimeConstructorTest} for the
	 * matrix.
	 *
	 * <p><b>The empty string is the one divergence</b>, and it is reachable:
	 * {@code required()} accepts {@code "  "} and {@code trim()} makes it empty,
	 * at which point the constructor answers <em>now</em> while
	 * {@code strtotime('')} is {@code false}. So it is handled here rather than
	 * read as a parse failure.
	 *
	 * @throws PhpDateConstructionException where PHP's constructor would throw
	 *         {@code DateMalformedStringException} -- which is <b>not</b> a
	 *         business error and must not become {@code shift_not_assigned}
	 */
	private LocalDate constructDate(String raw) {
		String value = raw == null ? "" : raw;
		if (value.isEmpty()) {
			// `new DateTimeImmutable('')` is "now".
			return clock.today();
		}
		LocalDate parsed = com.workin.legacy.LegacyPhpStrtotime.dateOf(value, clock.today());
		if (parsed == null) {
			throw new PhpDateConstructionException();
		}
		return parsed;
	}

	/**
	 * PHP's {@code DateMalformedStringException}, reaching D-084's generic 500.
	 *
	 * <p>Deliberately carries no message and no PHP file or line: legacy's
	 * uncaught exception is not part of any wire contract, and inventing its
	 * text would be fabricating evidence. What the client sees is D-084's fixed
	 * envelope, which is what an uncaught PHP throwable produces.
	 */
	public static class PhpDateConstructionException extends RuntimeException {
		PhpDateConstructionException() {
			super("new DateTimeImmutable() would have thrown");
		}
	}

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

}
