package com.workin.legacy.attendance.records;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.workin.legacy.LegacyPhpStrtotime;

/**
 * The cell formatters and header rows {@code data_export_helper.php} uses for
 * the two attendance workbook sheets.
 *
 * <p>All of them are total functions over a string or an int, so they are
 * static and tested directly rather than through the endpoint. Each carries the
 * legacy quirk it exists to preserve.
 */
public final class LegacyAttendanceExportFormat {

	/** {@code xlsx_style_body_white()} / {@code _green()} / {@code _red()} ({@code xlsx_writer.php:91-96}). */
	public static final int STYLE_BODY_WHITE = 2;
	public static final int STYLE_BODY_GREEN = 6;
	public static final int STYLE_BODY_RED = 7;

	/** {@code data_export_weekday_name()}'s Arabic table, indexed by {@code date('w')}. */
	private static final List<String> ARABIC_WEEKDAYS = List.of(
			"الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت");

	private static final List<String> ENGLISH_WEEKDAYS = List.of(
			"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday");

	private LegacyAttendanceExportFormat() {
	}

	/**
	 * {@code data_export_format_attendance_date()}: {@code d/m/Y}, and the raw
	 * input back when it does not parse.
	 *
	 * <p>Numeric only, deliberately -- the helper's own comment records that
	 * Arabic month names beside Latin digits break in RTL Excel cells.
	 */
	public static String attendanceDate(String ymd, LocalDate today) {
		LocalDate parsed = LegacyPhpStrtotime.dateOf(ymd, today);
		return parsed == null ? ymd : "%02d/%02d/%04d".formatted(
				parsed.getDayOfMonth(), parsed.getMonthValue(), parsed.getYear());
	}

	/** {@code data_export_weekday_name()}: localized, empty string when unparseable. */
	public static String weekdayName(String ymd, LocalDate today, boolean arabic) {
		LocalDate parsed = LegacyPhpStrtotime.dateOf(ymd, today);
		if (parsed == null) {
			return "";
		}
		// PHP's date('w') is 0 = Sunday; java.time's getValue() is 1 = Monday.
		int phpDayOfWeek = parsed.getDayOfWeek().getValue() % 7;
		return (arabic ? ARABIC_WEEKDAYS : ENGLISH_WEEKDAYS).get(phpDayOfWeek);
	}

	/**
	 * {@code data_export_format_attendance_clock()}: {@code H:i}, and an em dash
	 * for null, blank or unparseable -- not an empty cell.
	 */
	public static String attendanceClock(String dateTime, LocalDate today) {
		if (dateTime == null || dateTime.trim().isEmpty()) {
			return "—";
		}
		var parsed = LegacyPhpStrtotime.dateTimeOf(dateTime.trim(), today.atStartOfDay());
		return parsed == null ? "—" : "%02d:%02d".formatted(parsed.getHour(), parsed.getMinute());
	}

	/**
	 * {@code data_export_format_attendance_duration()}: {@code HH:MM} above zero,
	 * and below it an em dash for an incomplete day but an <b>empty string</b>
	 * for a complete one. Three outcomes, not two.
	 */
	public static String attendanceDuration(int minutes, boolean incomplete) {
		if (minutes > 0) {
			return "%02d:%02d".formatted(minutes / 60, minutes % 60);
		}
		return incomplete ? "—" : "";
	}

	/**
	 * {@code data_export_format_overall_duration()}: {@code HH:MM}, with
	 * {@code 00:00} at or below zero -- where the day-level formatter above
	 * would give an em dash or an empty cell. The two are not interchangeable.
	 */
	public static String overallDuration(int minutes) {
		return minutes <= 0 ? "00:00" : "%02d:%02d".formatted(minutes / 60, minutes % 60);
	}

	/**
	 * {@code data_export_attendance_row_style()}: red for a missing day, white
	 * for rest or holiday, green for a complete punch pair, white otherwise.
	 *
	 * <p>Order matters: a missing day that is also a holiday is red, because the
	 * missing test runs first.
	 */
	public static int rowStyle(boolean missing, boolean weeklyRest, boolean officialHoliday,
			boolean hasCheckIn, boolean hasCheckOut) {
		if (missing) {
			return STYLE_BODY_RED;
		}
		if (weeklyRest || officialHoliday) {
			return STYLE_BODY_WHITE;
		}
		return hasCheckIn && hasCheckOut ? STYLE_BODY_GREEN : STYLE_BODY_WHITE;
	}

	/**
	 * {@code data_export_build_row_cell_styles()}: one style id repeated across
	 * every column of a data row, offset past the header rows.
	 */
	public static Map<Integer, Map<Integer, Integer>> rowCellStyles(
			List<Integer> rowStyles, int columnCount, int headerRows) {
		Map<Integer, Map<Integer, Integer>> cellStyles = new LinkedHashMap<>();
		for (int dataIndex = 0; dataIndex < rowStyles.size(); dataIndex++) {
			Map<Integer, Integer> columns = new LinkedHashMap<>();
			for (int column = 0; column < columnCount; column++) {
				columns.put(column, rowStyles.get(dataIndex));
			}
			cellStyles.put(headerRows + dataIndex, columns);
		}
		return cellStyles;
	}

	/** {@code data_export_attendance_csv_headers()} -- the overall sheet's thirteen columns. */
	public static final List<String> OVERALL_HEADER_KEYS = List.of(
			"csv_row_number", "csv_emp_code", "csv_the_employee", "csv_job", "csv_department",
			"csv_branch", "csv_total_days_in_month", "csv_working_days", "csv_exception_days_count",
			"csv_paid_rest_days", "csv_absent_days_count", "csv_hours_worked",
			"csv_overtime_hours_display");

	/** {@code data_export_fingerprints_sheet_headers()} -- the day-level sheet's nine columns. */
	public static final List<String> FINGERPRINTS_HEADER_KEYS = List.of(
			"csv_row_number", "csv_emp_code", "csv_the_employee", "csv_date", "csv_day",
			"csv_check_in", "csv_check_out", "csv_hours_worked", "csv_exception");
}
