package com.workin.backend.attendance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.workin.backend.employees.Employee;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.holidays.OfficialHolidayService;
import com.workin.backend.i18n.MessageKeys;
import com.workin.backend.i18n.Messages;
import com.workin.backend.organization.JobTitle;
import com.workin.backend.organization.JobTitleRepository;
import com.workin.backend.organization.Shift;
import com.workin.backend.organization.ShiftTimes;
import com.workin.backend.schedule.ScheduleService;

/**
 * Ported 1:1 from {@code attendance_import_expected_for_day}
 * (hr-legacy/apis/helpers/attendance_excel_analyzer.php:498-548 @
 * d113204) plus the label rule it leans on,
 * {@code schedule_exception_for_day} ({@code schedule_helper.php:73-90}).
 *
 * <p>Its own component rather than a method on either consumer because
 * both the calendar engine and the open-session logic need it, and
 * folding it into either would make the pair mutually dependent.
 *
 * <p>Deliberate divergence from legacy, both non-behavioural:
 *
 * <ul>
 * <li>Legacy re-queries holidays and the shift assignment on <em>every
 * call</em>, and the calendar then calls it once per day while the
 * deadline scan calls it up to eight more times per open punch. The
 * shape is preserved; callers that can batch should, and none of that
 * changes an outcome.</li>
 * <li>Legacy's expected-hours fallback has a dead guard: {@code db_value()}
 * returns {@code false} for a missing employee, so {@code false ?? 8}
 * is {@code false} and expected collapses to 0 rather than 8 hours.
 * Unreachable here — callers resolve the employee first and 404 — so
 * the bug has nowhere to land.</li>
 * </ul>
 */
@Component
public class ExpectedDayResolver {

	/** Legacy's last-resort expected day length, the literal {@code 8} in its COALESCE. */
	private static final BigDecimal DEFAULT_DAILY_HOURS = BigDecimal.valueOf(8);

	private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

	private final ScheduleService scheduleService;
	private final EmployeeRepository employeeRepository;
	private final JobTitleRepository jobTitleRepository;
	private final OfficialHolidayService holidayService;
	private final Messages messages;

	public ExpectedDayResolver(
			ScheduleService scheduleService,
			EmployeeRepository employeeRepository,
			JobTitleRepository jobTitleRepository,
			OfficialHolidayService holidayService,
			Messages messages) {
		this.scheduleService = scheduleService;
		this.employeeRepository = employeeRepository;
		this.jobTitleRepository = jobTitleRepository;
		this.holidayService = holidayService;
		this.messages = messages;
	}

	/**
	 * Caller must already hold a tenant transaction (the ScheduleService
	 * convention). Looks the day's holiday up on its own; a caller
	 * walking a range should pre-fetch and use the overload instead.
	 */
	public ExpectedDay resolve(Long companyId, Long employeeId, LocalDate date) {
		return resolve(companyId, employeeId, date, holidayService.holidaysByDate(companyId, date, date));
	}

	/** The batched form: {@code holidayByDate} covers at least {@code date}. */
	public ExpectedDay resolve(
			Long companyId, Long employeeId, LocalDate date, Map<LocalDate, String> holidayByDate) {
		Shift shift = scheduleService.shiftForEmployeeOnDate(companyId, employeeId, date).orElse(null);
		String exception = scheduleExceptionForDay(companyId, shift, date, holidayByDate);

		// Branch 1 -- rest or holiday. Note legacy tests `!== null`, not
		// emptiness, and keeps the shift's own columns on the result even
		// though expected collapses to zero.
		if (exception != null) {
			return new ExpectedDay(
					0,
					shift == null ? null : shift.getName(),
					shift == null ? null : shift.getStartTime(),
					shift == null ? null : shift.getEndTime(),
					true,
					exception);
		}

		// Branch 2 -- no shift assigned: the COALESCE fallback chain.
		if (shift == null) {
			return new ExpectedDay(fallbackExpectedMinutes(companyId, employeeId), null, null, null, false, null);
		}

		// Branch 3 -- a shift applies. expected_daily_hours and work_hours
		// are ignored entirely once a shift resolves.
		return new ExpectedDay(
				ShiftTimes.durationMinutesOrZero(shift.getStartTime(), shift.getEndTime()),
				shift.getName(),
				shift.getStartTime(),
				shift.getEndTime(),
				false,
				null);
	}

	/**
	 * schedule_exception_for_day: official holiday wins by its own name
	 * (an empty name falls back to the weekly-rest label), then weekly
	 * rest, then null.
	 *
	 * <p>The holiday arm is live as of V38; it was a declared stub while
	 * {@code company_official_holidays} had no counterpart here.
	 */
	private String scheduleExceptionForDay(
			Long companyId, Shift shift, LocalDate date, Map<LocalDate, String> holidayByDate) {
		if (holidayByDate.containsKey(date)) {
			// A blank holiday name renders as the weekly-rest label while
			// the day still reports as a holiday -- label and flag
			// disagree. Legacy's behaviour, ported rather than corrected.
			String name = holidayByDate.get(date) == null ? "" : holidayByDate.get(date).trim();
			return name.isEmpty() ? weeklyRestLabel() : name;
		}
		if (scheduleService.isWeeklyRestDay(companyId, shift, date)) {
			return weeklyRestLabel();
		}
		return null;
	}

	private String weeklyRestLabel() {
		return messages.get(MessageKeys.SCHEDULE_WEEKLY_REST);
	}

	/**
	 * {@code COALESCE(NULLIF(e.expected_daily_hours, 0), NULLIF(jt.work_hours, 0), 8) * 60},
	 * rounded half-up and floored at zero. NULL and 0 are both "unset",
	 * which is what the NULLIFs buy legacy.
	 */
	private int fallbackExpectedMinutes(Long companyId, Long employeeId) {
		BigDecimal hours = employeeRepository.findByIdAndCompanyId(employeeId, companyId)
				.map(Employee::getExpectedDailyHours)
				.filter(ExpectedDayResolver::isSet)
				.orElse(null);
		if (hours == null) {
			hours = employeeRepository.findByIdAndCompanyId(employeeId, companyId)
					.map(Employee::getJobTitleId)
					.flatMap(jobTitleId -> jobTitleRepository.findByIdAndCompanyId(jobTitleId, companyId))
					.map(JobTitle::getWorkHours)
					.filter(ExpectedDayResolver::isSet)
					.orElse(DEFAULT_DAILY_HOURS);
		}
		int minutes = hours.multiply(MINUTES_PER_HOUR).setScale(0, RoundingMode.HALF_UP).intValue();
		return Math.max(0, minutes);
	}

	private static boolean isSet(BigDecimal value) {
		return value != null && value.signum() != 0;
	}

}
