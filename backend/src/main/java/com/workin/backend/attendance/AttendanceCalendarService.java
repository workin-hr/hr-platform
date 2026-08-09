package com.workin.backend.attendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.authorization.ResourceScopeService;
import com.workin.backend.employees.EmployeeRepository;
import com.workin.backend.holidays.OfficialHolidayService;
import com.workin.backend.requests.LeaveRequest;
import com.workin.backend.requests.LeaveRequestRepository;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantSessionVariable;

/**
 * Ported 1:1 from {@code attendance_build_employee_range_calendar}
 * (hr-legacy/apis/helpers/attendance_calendar_helper.php:217-423 @
 * d113204), which this replaces three overlapping legacy read paths
 * with: the {@code full_month=1} branch, {@code list.php}'s
 * {@code fill_days=1} branch, and the plain month scan.
 *
 * <p>The engine answers one question per day — was this day worked, an
 * exception, rest, a holiday, or an absence, and how many minutes does
 * it count for — and it is the classifier the weekly-rest-credit and
 * payroll-reconciliation slices consume. It computes; it owns no
 * schema.
 *
 * <p><b>Reads write.</b> Legacy auto-closes stale open punches inline
 * before serving a calendar read, and that is preserved here. See
 * {@link AttendanceSessionService} for why.
 *
 * <p>Legacy behaviours reproduced deliberately, each filed for a
 * keep-or-fix decision rather than corrected in passing: an approved
 * timed request outranks every other rule including an exception row;
 * a timed request alone credits minutes to a day with no punch at all;
 * timed-request hours run from the <em>scheduled</em> start rather than
 * the actual check-in; and {@code expectedDurationMinutes} is reported
 * only on a normal punched day.
 */
@Service
public class AttendanceCalendarService {

	private final AttendanceRepository attendanceRepository;
	private final ExceptionTypeRepository exceptionTypeRepository;
	private final LeaveRequestRepository leaveRequestRepository;
	private final EmployeeRepository employeeRepository;
	private final ExpectedDayResolver expectedDayResolver;
	private final OfficialHolidayService holidayService;
	private final WeeklyRestCreditService weeklyRestCreditService;
	private final AttendanceSessionService sessionService;
	private final ResourceScopeService resourceScopeService;
	private final TenantSessionVariable tenantSessionVariable;

	public AttendanceCalendarService(
			AttendanceRepository attendanceRepository,
			ExceptionTypeRepository exceptionTypeRepository,
			LeaveRequestRepository leaveRequestRepository,
			EmployeeRepository employeeRepository,
			ExpectedDayResolver expectedDayResolver,
			OfficialHolidayService holidayService,
			WeeklyRestCreditService weeklyRestCreditService,
			AttendanceSessionService sessionService,
			ResourceScopeService resourceScopeService,
			TenantSessionVariable tenantSessionVariable) {
		this.attendanceRepository = attendanceRepository;
		this.exceptionTypeRepository = exceptionTypeRepository;
		this.leaveRequestRepository = leaveRequestRepository;
		this.employeeRepository = employeeRepository;
		this.expectedDayResolver = expectedDayResolver;
		this.holidayService = holidayService;
		this.weeklyRestCreditService = weeklyRestCreditService;
		this.sessionService = sessionService;
		this.resourceScopeService = resourceScopeService;
		this.tenantSessionVariable = tenantSessionVariable;
	}

	/** Entry point; {@code asOf} is the wall clock legacy reads directly. */
	@Transactional
	public Optional<List<CalendarDayView>> calendar(
			AuthorizationContext context, Long employeeId, LocalDate from, LocalDate to) {
		return calendar(context, employeeId, from, to, Instant.now());
	}

	@Transactional
	public Optional<List<CalendarDayView>> calendar(
			AuthorizationContext context, Long employeeId, LocalDate from, LocalDate to, Instant asOf) {
		Long companyId = context.companyId();
		tenantSessionVariable.apply(companyId);
		if (!employeeInScope(context, employeeId)) {
			return Optional.empty();
		}
		if (to.isBefore(from)) {
			return Optional.of(List.of());
		}

		// Legacy's inline call: the calendar must not report a forgotten
		// punch as still running once its deadline has passed.
		sessionService.autoCloseStaleOpenSessions(companyId, employeeId, asOf);

		Map<LocalDate, Attendance> byDate = rowsByDate(companyId, employeeId, from, to);
		Map<Long, String> exceptionNames = exceptionTypeNames(companyId);
		// One query for the range instead of legacy's one per day. Same
		// answers; the per-day form remains for the deadline scan.
		Map<LocalDate, String> holidayByDate = holidayService.holidaysByDate(companyId, from, to);
		Map<LocalDate, WeeklyRestCreditService.DayCoverage> coverage =
				weeklyRestCreditService.attendanceFlags(companyId, employeeId, from, to);
		LocalDate asOfDate = AttendanceRules.dayOf(asOf);

		List<CalendarDayView> days = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			days.add(classify(companyId, employeeId, date, byDate.get(date), exceptionNames,
					holidayByDate, coverage, asOfDate, asOf));
		}
		return Optional.of(days);
	}

	private CalendarDayView classify(
			Long companyId, Long employeeId, LocalDate date, Attendance row,
			Map<Long, String> exceptionNames, Map<LocalDate, String> holidayByDate,
			Map<LocalDate, WeeklyRestCreditService.DayCoverage> coverage, LocalDate asOfDate, Instant asOf) {
		ExpectedDay expected = expectedDayResolver.resolve(companyId, employeeId, date, holidayByDate);
		boolean restOrHoliday = expected.restDay();
		// A holiday outranks weekly rest: a day that is both reports as the
		// holiday only, which is what stops it being counted twice.
		boolean officialHoliday = holidayByDate.containsKey(date);
		boolean weeklyRest = restOrHoliday && !officialHoliday;
		// Only a weekly-rest day carries a credit; a holiday does not, which
		// is what keeps the two from being counted twice.
		WeeklyRestCredit credit = weeklyRest
				? weeklyRestCreditService.status(companyId, employeeId, date, coverage, asOfDate)
				: null;

		if (row != null) {
			return classifyExistingRow(companyId, employeeId, date, row, expected, exceptionNames,
					weeklyRest, officialHoliday, credit, asOf);
		}
		if (restOrHoliday) {
			// Branch B -- a rest or holiday day with nothing recorded.
			return CalendarDayView.of(
					date, null, AttendanceRules.syntheticRowId(employeeId, date), null, null,
					0, 0, null, expected.restNote(), false, weeklyRest, officialHoliday, credit);
		}
		// Branch C -- a working day with no row. An approved timed request
		// still credits its window here, with no punch behind it.
		int missingDuration = approvedTimedRequest(companyId, employeeId, date)
				.map(request -> missionWindowMinutes(request))
				.orElse(0);
		return CalendarDayView.of(
				date, null, AttendanceRules.syntheticRowId(employeeId, date), null, null,
				missingDuration, 0, null, null, missingDuration <= 0, false, false, null);
	}

	private CalendarDayView classifyExistingRow(
			Long companyId, Long employeeId, LocalDate date, Attendance row, ExpectedDay expected,
			Map<Long, String> exceptionNames, boolean weeklyRest, boolean officialHoliday,
			WeeklyRestCredit credit, Instant asOf) {
		String exceptionName = row.getExceptionTypeId() == null
				? null
				: exceptionNames.get(row.getExceptionTypeId());
		if (isBlank(exceptionName) && expected.restDay()) {
			exceptionName = expected.restNote();
		}

		if (AttendanceRules.isExceptionOnlyRow(row)) {
			// Branch A1. The midnight punch is deliberately not reported --
			// it is bookkeeping, not a real arrival.
			int duration = approvedTimedRequest(companyId, employeeId, date)
					.map(request -> timedRequestWorkedMinutes(date, row, expected, request))
					.orElse(0);
			return CalendarDayView.of(
					date, row.getId(), row.getId(), null, null,
					duration, 0, row.getExceptionTypeId(), blankToNull(exceptionName),
					false, weeklyRest, officialHoliday, credit);
		}

		// Branch A2 -- a normal punched day, the only branch that reports
		// real expected minutes.
		int duration = workedMinutes(companyId, employeeId, date, row, expected, asOf);
		return CalendarDayView.of(
				date, row.getId(), row.getId(), row.getCheckIn(), row.getCheckOut(),
				duration, expected.expectedMinutes(), row.getExceptionTypeId(), blankToNull(exceptionName),
				false, weeklyRest, officialHoliday, credit);
	}

	/**
	 * attendance_row_worked_minutes ({@code :152-201}) — the precedence
	 * chain, in legacy's order. A timed request wins outright; then an
	 * exception row scores nothing; then a row with no punches scores
	 * nothing; then a punch that is still live scores nothing, which is
	 * what stops an in-progress shift from showing phantom hours; only
	 * then does the display formula apply.
	 */
	private int workedMinutes(
			Long companyId, Long employeeId, LocalDate date, Attendance row, ExpectedDay expected, Instant asOf) {
		Optional<LeaveRequest> timed = approvedTimedRequest(companyId, employeeId, date);
		if (timed.isPresent()) {
			return timedRequestWorkedMinutes(date, row, expected, timed.get());
		}
		if (AttendanceRules.isExceptionOnlyRow(row)) {
			return 0;
		}
		if (row.getCheckIn() == null && row.getCheckOut() == null) {
			return 0;
		}
		if (row.getCheckIn() != null && row.getCheckOut() == null
				&& sessionService.isLiveOpenPunch(companyId, employeeId, row.getCheckIn(), asOf)) {
			return 0;
		}
		return AttendanceRules.displayDurationMinutes(
				row.getCheckIn(), row.getCheckOut(), expected.expectedMinutes());
	}

	/**
	 * attendance_timed_request_worked_minutes ({@code :110-145}). With no
	 * complete punches the mission window itself is credited. With
	 * complete punches the day runs from the <em>scheduled</em> start to
	 * the actual check-out — so an employee returning from a mission is
	 * credited from their shift start regardless of when they arrived —
	 * rolling over a day when the checkout clock time precedes the start.
	 */
	private int timedRequestWorkedMinutes(
			LocalDate date, Attendance row, ExpectedDay expected, LeaveRequest request) {
		int missionMinutes = missionWindowMinutes(request);
		Instant checkIn = row == null ? null : row.getCheckIn();
		Instant checkOut = row == null ? null : row.getCheckOut();
		if (checkIn == null || checkOut == null) {
			return missionMinutes;
		}
		LocalTime shiftStart = expected.shiftStart() != null ? expected.shiftStart() : request.getFromTime();
		if (shiftStart == null) {
			return missionMinutes;
		}
		Instant start = date.atTime(shiftStart).toInstant(ZoneOffset.UTC);
		Instant end = checkOut.isBefore(start) ? checkOut.plus(Duration.ofDays(1)) : checkOut;
		// round(), not truncation -- legacy uses PHP round() on this path
		// while the display path inherits SQL TIMESTAMPDIFF truncation.
		long seconds = Duration.between(start, end).getSeconds();
		return (int) Math.max(0, Math.round(seconds / 60.0));
	}

	private int missionWindowMinutes(LeaveRequest request) {
		return Math.max(0, com.workin.backend.organization.ShiftTimes
				.durationMinutesOrZero(request.getFromTime(), request.getToTime()));
	}

	/** Highest request id wins, exactly as legacy orders it. */
	private Optional<LeaveRequest> approvedTimedRequest(Long companyId, Long employeeId, LocalDate date) {
		return leaveRequestRepository
				.findApprovedTimedRequestsForDay(employeeId, companyId, date, Limit.of(1))
				.stream()
				.findFirst();
	}

	/**
	 * The day index. Ascending by check-in with plain overwriting, so the
	 * <b>latest</b> punch of a day wins when several exist — legacy's
	 * behaviour in this function, though two of its other call sites keep
	 * the first instead.
	 */
	private Map<LocalDate, Attendance> rowsByDate(Long companyId, Long employeeId, LocalDate from, LocalDate to) {
		List<Attendance> rows = attendanceRepository
				.findByEmployeeIdAndCompanyIdAndCheckInGreaterThanEqualAndCheckInLessThanOrderByCheckInAsc(
						employeeId, companyId,
						AttendanceRules.startOfDay(from), AttendanceRules.startOfDay(to.plusDays(1)));
		Map<LocalDate, Attendance> byDate = new HashMap<>();
		for (Attendance row : rows) {
			byDate.put(AttendanceRules.dayOf(row.getCheckIn()), row);
		}
		return byDate;
	}

	private Map<Long, String> exceptionTypeNames(Long companyId) {
		Map<Long, String> names = new HashMap<>();
		for (ExceptionType type : exceptionTypeRepository.findByCompanyIdOrderById(companyId)) {
			names.put(type.getId(), type.getName());
		}
		return names;
	}

	private boolean employeeInScope(AuthorizationContext context, Long employeeId) {
		return employeeRepository.findByIdAndCompanyId(employeeId, context.companyId())
				.filter(employee -> resourceScopeService.isEmployeeInScope(context, employee.getId()))
				.isPresent();
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static String blankToNull(String value) {
		return isBlank(value) ? null : value;
	}

}
