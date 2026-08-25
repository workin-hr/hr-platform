package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.records.LegacyAttendanceWorkedMinutes;

/** Frozen {@code weekly_rest_credit_helper.php}. */
@Component
public class LegacyWeeklyRestCredit {

	public static final String EARNED = "earned";
	public static final String VOID = "void";
	public static final String PENDING = "pending";
	static final int MIN_COVERED_WORKDAYS = 3;

	private static final String FLAGS = """
			SELECT a.check_in, a.check_out, a.exception_type_id
			FROM attendance AS a
			WHERE a.employee_id = ?
			  AND DATE(a.check_in) >= ?
			  AND DATE(a.check_in) <= ?""";

	private static final String APPROVED_PAID_LEAVE = """
			SELECT COUNT(*)
			FROM requests AS r
			INNER JOIN request_types AS t ON t.id = r.request_type_id
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

	/** {@code weekly_rest_credit_status(...)}. */
	public String status(long companyId, long employeeId, String restYmd,
			Map<String, Flags> attendanceByDate, Map<String, String> holidayByDate, String asOf) {
		if (restYmd.compareTo(asOf) > 0) {
			return PENDING;
		}
		List<String> workdays = workdaysBeforeBlock(companyId, employeeId, restYmd);
		if (workdays.isEmpty()) {
			return EARNED;
		}

		boolean hasFuture = false;
		boolean hasPast = false;
		int covered = 0;
		for (String date : workdays) {
			if (date.compareTo(asOf) > 0) {
				hasFuture = true;
				continue;
			}
			hasPast = true;
			Flags day = attendanceByDate.get(date);
			boolean worked = day != null && day.hasPunch();
			boolean exception = day != null && day.exceptionOnly();
			if (worked || exception || isOnApprovedLeave(employeeId, date)) {
				covered++;
			}
		}
		if (covered >= MIN_COVERED_WORKDAYS) {
			return EARNED;
		}
		if (!hasPast) {
			return PENDING;
		}
		return hasFuture ? PENDING : VOID;
	}

	/** {@code weekly_rest_attendance_flags_in_range(...)} including the seven-day lookback. */
	public Map<String, Flags> attendanceFlags(long employeeId, String from, String to) {
		String lookback = LocalDate.parse(from).minusDays(7).toString();
		Map<String, Flags> byDate = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbcTemplate.query(FLAGS, LegacyJdbcValues.rowMapper(), employeeId, lookback, to)) {
			Object rawIn = row.get("check_in");
			if (rawIn == null || LegacyValues.isPhpEmpty(rawIn)) {
				continue;
			}
			String checkIn = LegacyValues.toPhpString(rawIn);
			String checkOut = row.get("check_out") == null
					|| LegacyValues.phpTrim(LegacyValues.toPhpString(row.get("check_out"))).isEmpty()
						? null : LegacyValues.toPhpString(row.get("check_out"));
			String date = checkIn.length() >= 10 ? checkIn.substring(0, 10) : checkIn;
			boolean exceptionOnly = LegacyAttendanceWorkedMinutes.isExceptionOnly(
					checkIn, checkOut, row.get("exception_type_id"));
			boolean hasPunch = !exceptionOnly;
			Flags existing = byDate.get(date);
			if (existing == null) {
				byDate.put(date, new Flags(hasPunch, exceptionOnly));
			} else if (hasPunch) {
				byDate.put(date, new Flags(true, false));
			}
		}
		return byDate;
	}

	public boolean isOnApprovedLeave(long employeeId, String date) {
		Long count = jdbcTemplate.queryForObject(APPROVED_PAID_LEAVE, Long.class, employeeId, date, date);
		return count != null && count > 0;
	}

	private List<String> workdaysBeforeBlock(long companyId, long employeeId, String restYmd) {
		LocalDate blockStart = blockStart(companyId, employeeId, LocalDate.parse(restYmd));
		List<String> workdays = new ArrayList<>();
		LocalDate day = blockStart.minusDays(1);
		for (int i = 0; i < 7; i++) {
			Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, day.toString());
			if (calendar.isWeeklyRestDay(companyId, day.toString(), shift)) {
				break;
			}
			workdays.add(0, day.toString());
			day = day.minusDays(1);
		}
		return workdays;
	}

	private LocalDate blockStart(long companyId, long employeeId, LocalDate rest) {
		LocalDate day = rest;
		for (int i = 0; i < 7; i++) {
			LocalDate previous = day.minusDays(1);
			Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, previous.toString());
			if (!calendar.isWeeklyRestDay(companyId, previous.toString(), shift)) {
				break;
			}
			day = previous;
		}
		return day;
	}

	public record Flags(boolean hasPunch, boolean exceptionOnly) {
	}
}
