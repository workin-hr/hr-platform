package com.workin.backend.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.backend.organization.Shift;
import com.workin.backend.requests.LeaveRequestRepository;
import com.workin.backend.schedule.ScheduleService;

/**
 * Ported 1:1 from hr-legacy/apis/helpers/weekly_rest_credit_helper.php @
 * d113204.
 *
 * <p>A weekly-rest day is <em>earned</em> by attending the week before
 * it. Legacy walks back from the rest day to the start of its rest
 * block, then back again over the run of scheduled workdays before that
 * block, and asks how many of those days the employee covered. Three or
 * more and the rest day is paid; fewer and it is not, and payroll counts
 * it as an absence.
 *
 * <p><b>The algorithm is smaller than the source suggests.</b> Three of
 * legacy's branches cannot be reached: every workday it examines is
 * strictly earlier than the rest day, and the function has already
 * returned if the rest day is in the future, so its
 * {@code has_future_workday} flag is permanently false and
 * {@code has_past_workday} permanently true. Reproducing those branches
 * here would be reproducing dead code, not behaviour, so the effective
 * rule is written out directly and the dead paths are documented on
 * {@link WeeklyRestCredit#PENDING}. The observable results are
 * identical.
 *
 * <p>Ported faithfully, and filed rather than fixed: an official holiday
 * inside the coverage window <em>consumes a slot and contributes
 * nothing</em>, so holidays can cost an employee their paid rest day —
 * the opposite of what legacy's own comment at that line claims. And
 * attendance flags are loaded with only a 7-day lookback while the
 * window can reach 14 days back, so days beyond the lookback silently
 * count as uncovered and bias the first rest day of a period toward
 * VOID.
 */
@Service
public class WeeklyRestCreditService {

	/** {@code WEEKLY_REST_MIN_COVERED_WORKDAYS} ({@code :24}). */
	public static final int MIN_COVERED_WORKDAYS = 3;

	/** Both backward walks are bounded by {@code $i < 7} ({@code :35, :63}). */
	private static final int MAX_WALK_DAYS = 7;

	/** The lookback legacy applies when loading attendance flags ({@code :310}). */
	private static final int FLAG_LOOKBACK_DAYS = 7;

	private final ScheduleService scheduleService;
	private final AttendanceRepository attendanceRepository;
	private final LeaveRequestRepository leaveRequestRepository;

	public WeeklyRestCreditService(
			ScheduleService scheduleService,
			AttendanceRepository attendanceRepository,
			LeaveRequestRepository leaveRequestRepository) {
		this.scheduleService = scheduleService;
		this.attendanceRepository = attendanceRepository;
		this.leaveRequestRepository = leaveRequestRepository;
	}

	/**
	 * weekly_rest_credit_status ({@code :83-146}). Caller must already
	 * hold a tenant transaction.
	 *
	 * @param flags attendance coverage by date, from {@link #attendanceFlags}
	 */
	public WeeklyRestCredit status(
			Long companyId, Long employeeId, LocalDate restDate,
			Map<LocalDate, DayCoverage> flags, LocalDate asOf) {
		if (restDate.isAfter(asOf)) {
			return WeeklyRestCredit.PENDING;
		}
		List<LocalDate> workdays = workdaysBeforeBlock(companyId, employeeId, restDate);
		if (workdays.isEmpty()) {
			// Nothing was scheduled before the block, so there was nothing
			// to attend and the rest day is paid.
			return WeeklyRestCredit.EARNED;
		}
		int covered = 0;
		for (LocalDate workday : workdays) {
			if (isCovered(companyId, employeeId, workday, flags)) {
				covered++;
			}
		}
		return covered >= MIN_COVERED_WORKDAYS ? WeeklyRestCredit.EARNED : WeeklyRestCredit.VOID;
	}

	/**
	 * weekly_rest_dates_by_status_in_range ({@code :225-268}): every date
	 * in range carrying the given status.
	 *
	 * <p>Order of suppression matters and is legacy's: a future date is
	 * skipped, then <b>a holiday is skipped outright</b> — which is what
	 * stops a holiday landing on a rest day from being counted on both
	 * sides — then non-rest days are skipped.
	 */
	public List<LocalDate> datesByStatusInRange(
			Long companyId, Long employeeId, LocalDate from, LocalDate to,
			WeeklyRestCredit status, Map<LocalDate, String> holidayByDate, LocalDate asOf) {
		if (to.isBefore(from)) {
			return List.of();
		}
		Map<LocalDate, DayCoverage> flags = attendanceFlags(companyId, employeeId, from, to);
		List<LocalDate> dates = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			if (date.isAfter(asOf) || holidayByDate.containsKey(date) || !isRestDay(companyId, employeeId, date)) {
				continue;
			}
			if (status(companyId, employeeId, date, flags, asOf) == status) {
				dates.add(date);
			}
		}
		return dates;
	}

	/**
	 * weekly_rest_attendance_flags_in_range ({@code :301-357}), including
	 * its 7-day lookback.
	 *
	 * <p>A date with any real punch is covered; a date holding only a
	 * synthetic exception row is flagged separately. A real punch always
	 * wins over an exception row on the same date, whatever order they
	 * arrive in.
	 */
	public Map<LocalDate, DayCoverage> attendanceFlags(
			Long companyId, Long employeeId, LocalDate from, LocalDate to) {
		LocalDate lookbackFrom = from.minusDays(FLAG_LOOKBACK_DAYS);
		Map<LocalDate, DayCoverage> byDate = new HashMap<>();
		List<Attendance> rows = attendanceRepository
				.findByEmployeeIdAndCompanyIdAndCheckInGreaterThanEqualAndCheckInLessThanOrderByCheckInAsc(
						employeeId, companyId,
						AttendanceRules.startOfDay(lookbackFrom), AttendanceRules.startOfDay(to.plusDays(1)));
		for (Attendance row : rows) {
			if (row.getCheckIn() == null) {
				continue;
			}
			LocalDate date = AttendanceRules.dayOf(row.getCheckIn());
			boolean exceptionOnly = AttendanceRules.isExceptionOnlyRow(row);
			DayCoverage existing = byDate.get(date);
			if (existing == null) {
				byDate.put(date, new DayCoverage(!exceptionOnly, exceptionOnly));
				continue;
			}
			if (!exceptionOnly) {
				byDate.put(date, new DayCoverage(true, false));
			}
		}
		return byDate;
	}

	/**
	 * A workday counts as covered by a real punch, by a synthetic
	 * exception row, or by approved paid leave. <b>Official holidays do
	 * not count</b> — legacy consults them nowhere in this check.
	 */
	private boolean isCovered(
			Long companyId, Long employeeId, LocalDate date, Map<LocalDate, DayCoverage> flags) {
		DayCoverage coverage = flags.get(date);
		if (coverage != null && (coverage.hasPunch() || coverage.isExceptionOnly())) {
			return true;
		}
		return leaveRequestRepository.existsApprovedPaidLeaveOnDate(employeeId, companyId, date);
	}

	/**
	 * weekly_rest_block_start ({@code :29-45}): walk back from the rest
	 * day while the previous day is also rest, at most seven steps, to
	 * find where the block began.
	 */
	private LocalDate blockStart(Long companyId, Long employeeId, LocalDate restDate) {
		LocalDate start = restDate;
		for (int step = 0; step < MAX_WALK_DAYS; step++) {
			LocalDate previous = start.minusDays(1);
			if (!isRestDay(companyId, employeeId, previous)) {
				break;
			}
			start = previous;
		}
		return start;
	}

	/**
	 * weekly_rest_workdays_before_block ({@code :53-76}): from the day
	 * before the block, collect the run of consecutive non-rest days,
	 * at most seven, returned ascending.
	 *
	 * <p>Holidays are included in this run even though they cannot
	 * contribute coverage, which is the defect noted on the class.
	 */
	private List<LocalDate> workdaysBeforeBlock(Long companyId, Long employeeId, LocalDate restDate) {
		List<LocalDate> workdays = new ArrayList<>();
		LocalDate date = blockStart(companyId, employeeId, restDate).minusDays(1);
		for (int step = 0; step < MAX_WALK_DAYS; step++) {
			if (isRestDay(companyId, employeeId, date)) {
				break;
			}
			workdays.add(date);
			date = date.minusDays(1);
		}
		Collections.reverse(workdays);
		return workdays;
	}

	private boolean isRestDay(Long companyId, Long employeeId, LocalDate date) {
		Shift shift = scheduleService.shiftForEmployeeOnDate(companyId, employeeId, date).orElse(null);
		return scheduleService.isWeeklyRestDay(companyId, shift, date);
	}

	/** One date's coverage flags; the two are never both true. */
	public record DayCoverage(boolean hasPunch, boolean isExceptionOnly) {
	}

}
