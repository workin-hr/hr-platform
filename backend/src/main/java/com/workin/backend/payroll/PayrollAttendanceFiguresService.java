package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.backend.attendance.AttendanceCalendarService;
import com.workin.backend.attendance.CalendarDayView;
import com.workin.backend.attendance.WeeklyRestCredit;
import com.workin.backend.attendance.WeeklyRestCreditService;
import com.workin.backend.holidays.OfficialHolidayService;
import com.workin.backend.organization.Shift;
import com.workin.backend.requests.LeaveRequestRepository;
import com.workin.backend.schedule.ScheduleService;
import com.workin.backend.tenancy.AuthorizationContext;

/**
 * Derives the attendance figures payroll pays on, from the
 * attendance-calendar engine — closing the second half of issue #71,
 * where {@code calculate()} assumed every employee present for the
 * whole period with no overtime.
 *
 * <p>Ported from {@code payroll_attendance_summary}
 * (payroll_calculation.php:672-738) and
 * {@code payroll_payslip_attendance_display} (:292-374) @ d113204.
 *
 * <p>Three parts of legacy's shape are easy to get wrong and are called
 * out where they happen: <b>any day holding an attendance row counts as
 * present</b>, including a category-only exception day; <b>earned</b>
 * weekly rest is added to present days while <b>void</b> weekly rest is
 * added to absences and therefore costs a day's pay; and holiday credit
 * is <b>zeroed entirely</b> when fewer than three days were punched.
 */
@Service
public class PayrollAttendanceFiguresService {

	/** {@code WEEKLY_REST_MIN_COVERED_WORKDAYS}, reused as the holiday-credit gate. */
	private static final int MIN_PRESENT_DAYS_FOR_HOLIDAY_CREDIT = 3;

	private final AttendanceCalendarService calendarService;
	private final WeeklyRestCreditService weeklyRestCreditService;
	private final OfficialHolidayService holidayService;
	private final ScheduleService scheduleService;
	private final LeaveRequestRepository leaveRequestRepository;
	private final WorkHoursResolver workHoursResolver;

	public PayrollAttendanceFiguresService(
			AttendanceCalendarService calendarService,
			WeeklyRestCreditService weeklyRestCreditService,
			OfficialHolidayService holidayService,
			ScheduleService scheduleService,
			LeaveRequestRepository leaveRequestRepository,
			WorkHoursResolver workHoursResolver) {
		this.calendarService = calendarService;
		this.weeklyRestCreditService = weeklyRestCreditService;
		this.holidayService = holidayService;
		this.scheduleService = scheduleService;
		this.leaveRequestRepository = leaveRequestRepository;
		this.workHoursResolver = workHoursResolver;
	}

	/** What the period looked like, and how much of it counts yet. */
	public record Derived(
			PayrollCalculationService.AttendanceFigures figures,
			PayrollCalculationService.PeriodProgress progress) {
	}

	/**
	 * {@code asOf} is legacy's {@code payroll_calculation_as_of_date}:
	 * today while the period is still open, otherwise its last day.
	 */
	public Derived derive(
			AuthorizationContext context, Long employeeId,
			LocalDate periodFrom, LocalDate periodTo, LocalDate today) {
		Long companyId = context.companyId();
		LocalDate asOf = today.isAfter(periodTo) ? periodTo : today;
		// Strict: on the period's last day it is already closed, and the
		// full gross applies rather than a prorated base.
		boolean inProgress = asOf.isBefore(periodTo);
		LocalDate rangeTo = inProgress ? asOf : periodTo;

		List<CalendarDayView> days = rangeTo.isBefore(periodFrom)
				? List.of()
				: calendarService.calendar(context, employeeId, periodFrom, rangeTo).orElse(List.of());

		// payroll_attendance_summary: a date is present if it holds ANY
		// attendance row -- a category-only exception day counts too. Days
		// the engine synthesised (rest, holiday, absence, or a bare timed
		// request) have no row and contribute neither presence nor minutes.
		int punchPresentDays = 0;
		long totalMinutes = 0;
		for (CalendarDayView day : days) {
			if (day.attendanceId() != null) {
				punchPresentDays++;
				totalMinutes += Math.max(0, day.durationMinutes());
			}
		}
		BigDecimal totalWorkedHours = BigDecimal.valueOf(totalMinutes)
				.divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);

		Map<LocalDate, String> holidayByDate = holidayService.holidaysByDate(companyId, periodFrom, rangeTo);
		int daysLeave = approvedLeaveDays(companyId, employeeId, periodFrom, rangeTo);
		int holidayCredit = holidayCredit(companyId, employeeId, days, holidayByDate, punchPresentDays);

		int expectedFull = expectedWorkDays(companyId, employeeId, periodFrom, periodTo);
		int expectedDue = rangeTo.isBefore(periodFrom)
				? 0
				: expectedWorkDays(companyId, employeeId, periodFrom, rangeTo);
		int dueCap = Math.max(0, inProgress ? expectedDue : expectedFull);

		int paidDue = Math.min(dueCap, Math.max(0, punchPresentDays) + daysLeave + holidayCredit);
		int workdayAbsent = Math.max(0, dueCap - paidDue);

		// Unearned weekly rest is unpaid and shown as absence.
		int voidWeeklyRest = countRestDays(
				companyId, employeeId, periodFrom, rangeTo, WeeklyRestCredit.VOID, holidayByDate, asOf);
		int earnedWeeklyRest = countRestDays(
				companyId, employeeId, periodFrom, rangeTo, WeeklyRestCredit.EARNED, holidayByDate, asOf);
		int daysAbsent = workdayAbsent + Math.max(0, voidWeeklyRest);

		PayrollCalculationService.AttendanceFigures figures =
				new PayrollCalculationService.AttendanceFigures(
						punchPresentDays, totalWorkedHours, daysAbsent, daysLeave,
						earnedWeeklyRest, holidayCredit,
						workHoursResolver.forEmployee(companyId, employeeId));

		int elapsed = rangeTo.isBefore(periodFrom)
				? 0
				: (int) (java.time.temporal.ChronoUnit.DAYS.between(periodFrom, rangeTo) + 1);
		return new Derived(figures, new PayrollCalculationService.PeriodProgress(inProgress, elapsed));
	}

	private int countRestDays(
			Long companyId, Long employeeId, LocalDate from, LocalDate to,
			WeeklyRestCredit status, Map<LocalDate, String> holidayByDate, LocalDate asOf) {
		if (to.isBefore(from)) {
			return 0;
		}
		return weeklyRestCreditService
				.datesByStatusInRange(companyId, employeeId, from, to, status, holidayByDate, asOf)
				.size();
	}

	private int approvedLeaveDays(Long companyId, Long employeeId, LocalDate from, LocalDate to) {
		int count = 0;
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			if (leaveRequestRepository.existsApprovedPaidLeaveOnDate(employeeId, companyId, date)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * {@code official_holidays_working_credit_for_employee}: holidays with
	 * no attendance row that do not land on a company weekly-off day.
	 * Zeroed outright when fewer than three days were punched.
	 *
	 * <p>The rest-day test here uses <b>only</b> the company setting, not
	 * the shift's own days off — legacy's holiday path and its weekly-rest
	 * path disagree on that, and this is the holiday one.
	 */
	private int holidayCredit(
			Long companyId, Long employeeId, List<CalendarDayView> days,
			Map<LocalDate, String> holidayByDate, int punchPresentDays) {
		if (punchPresentDays < MIN_PRESENT_DAYS_FOR_HOLIDAY_CREDIT) {
			return 0;
		}
		int credit = 0;
		for (LocalDate date : holidayByDate.keySet()) {
			boolean hasRow = days.stream()
					.anyMatch(day -> day.date().equals(date) && day.attendanceId() != null);
			if (hasRow || scheduleService.isWeeklyRestDay(companyId, null, date)) {
				continue;
			}
			credit++;
		}
		return credit;
	}

	/**
	 * {@code payroll_expected_work_days_until}: scheduled working days in
	 * range, minus holidays that fall on one.
	 *
	 * <p>Legacy resolves <b>one</b> shift for the entire range — as of the
	 * first day, else the last — and applies it to every day, so a
	 * mid-period shift change is ignored. Ported as-is.
	 */
	private int expectedWorkDays(Long companyId, Long employeeId, LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			return 0;
		}
		Shift shift = scheduleService.shiftForEmployeeOnDate(companyId, employeeId, from)
				.or(() -> scheduleService.shiftForEmployeeOnDate(companyId, employeeId, to))
				.orElse(null);
		Map<LocalDate, String> holidays = holidayService.holidaysByDate(companyId, from, to);
		int working = 0;
		int holidaysOnWorkingDays = 0;
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			if (scheduleService.isWeeklyRestDay(companyId, shift, date)) {
				continue;
			}
			working++;
			// The holiday deduction uses the company-only rest test, so a
			// holiday on a shift-level day off still subtracts here.
			if (holidays.containsKey(date) && !scheduleService.isWeeklyRestDay(companyId, null, date)) {
				holidaysOnWorkingDays++;
			}
		}
		return Math.max(0, working - holidaysOnWorkingDays);
	}

}
