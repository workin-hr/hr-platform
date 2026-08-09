package com.workin.backend.attendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ported 1:1 from hr-legacy/apis/helpers/attendance_session_helper.php @
 * d113204 — the rewrite that replaced the old flat 18-hour window with
 * a shift-aware deadline plus a real write.
 *
 * <p>An employee who forgets to check out leaves a row that would
 * otherwise read as an infinitely long shift. Legacy resolves the point
 * at which that punch stops being "live" — the start of their next
 * scheduled working day — and at that point synthesizes a check-out.
 *
 * <p><b>The auto-close is a write triggered by reads.</b> Legacy calls
 * it inline before serving find-open-session, monthly attendance, and
 * list ({@code :176-178, :218-222}), so a GET mutates rows. That is
 * preserved here on the owner's instruction to keep the new system's
 * business behaviour identical to legacy; the alternative considered
 * and not taken was a scheduled sweep, which would need scheduling
 * infrastructure this repository has no precedent for and would change
 * when the synthesized checkout appears.
 */
@Service
public class AttendanceSessionService {

	/** The flat fallback when no working day resolves in the scan window ({@code :13, :71}). */
	private static final Duration MAX_OPEN_SESSION = Duration.ofHours(18);

	/** {@code for ($i = 1; $i <= 8; $i++)} ({@code :54}). */
	private static final int DEADLINE_SCAN_DAYS = 8;

	/** The clock used when neither the check-in day nor the candidate day resolves one ({@code :52}). */
	private static final LocalTime FALLBACK_SHIFT_START = LocalTime.of(9, 0);

	private final AttendanceRepository attendanceRepository;
	private final ExpectedDayResolver expectedDayResolver;

	public AttendanceSessionService(
			AttendanceRepository attendanceRepository, ExpectedDayResolver expectedDayResolver) {
		this.attendanceRepository = attendanceRepository;
		this.expectedDayResolver = expectedDayResolver;
	}

	/**
	 * attendance_open_session_deadline ({@code :40-72}): starting the day
	 * after check-in, scan forward up to eight days, skip rest days and
	 * holidays, and take the first working day's shift start. Nothing
	 * resolves in eight days — a fortnight of rest, or no shift at all —
	 * and it falls back to check-in plus 18 hours.
	 */
	public Instant openSessionDeadline(Long companyId, Long employeeId, Instant checkIn) {
		LocalDate checkInDay = AttendanceRules.dayOf(checkIn);
		LocalTime fallbackStart = firstNonNull(
				expectedDayResolver.resolve(companyId, employeeId, checkInDay).shiftStart(), FALLBACK_SHIFT_START);

		for (int offset = 1; offset <= DEADLINE_SCAN_DAYS; offset++) {
			LocalDate candidate = checkInDay.plusDays(offset);
			ExpectedDay expected = expectedDayResolver.resolve(companyId, employeeId, candidate);
			if (expected.restDay()) {
				continue;
			}
			Instant deadline = candidate
					.atTime(firstNonNull(expected.shiftStart(), fallbackStart))
					.toInstant(ZoneOffset.UTC);
			// Legacy guards with `if ($deadline > $checkIn)`. It can never
			// fail -- the candidate is at least the next calendar day -- but
			// it is kept so the shapes line up if the scan ever starts at 0.
			if (deadline.isAfter(checkIn)) {
				return deadline;
			}
		}
		return checkIn.plus(MAX_OPEN_SESSION);
	}

	/**
	 * attendance_is_live_open_punch ({@code :235-245}): the punch is
	 * still inside its window, so its hours are not final and must not
	 * be costed with the single-punch formula yet.
	 *
	 * <p>Legacy reads the wall clock directly with no seam, which makes
	 * the same historical row score differently depending on when the
	 * report runs. {@code asOf} is threaded through here instead — the
	 * comparison and the outcome are identical, but a report becomes
	 * reproducible and the behaviour becomes testable.
	 */
	public boolean isLiveOpenPunch(Long companyId, Long employeeId, Instant checkIn, Instant asOf) {
		if (checkIn == null) {
			return false;
		}
		return asOf.isBefore(openSessionDeadline(companyId, employeeId, checkIn));
	}

	/**
	 * attendance_auto_close_stale_open_sessions ({@code :80-147}): for
	 * every punch past its deadline, write
	 * {@code check_out = check_in + (expected - 120) minutes}. Only
	 * {@code check_out} is touched.
	 *
	 * <p>Two ported consequences worth stating rather than discovering:
	 * a stale punch on a rest day has {@code expected = 0}, so the
	 * synthesized checkout equals the check-in and the day lands as a
	 * zero-minute <em>present</em> day; and exception-only rows are
	 * excluded outright, since they have no real punch to close.
	 *
	 * @return how many rows were actually closed
	 */
	@Transactional
	public int autoCloseStaleOpenSessions(Long companyId, Long employeeId, Instant asOf) {
		List<Attendance> open =
				attendanceRepository.findByEmployeeIdAndCompanyIdAndCheckOutIsNullOrderByIdAsc(employeeId, companyId);
		int closed = 0;
		for (Attendance row : open) {
			if (row.getCheckIn() == null || AttendanceRules.isExceptionOnlyRow(row)) {
				continue;
			}
			Instant deadline = openSessionDeadline(companyId, employeeId, row.getCheckIn());
			if (asOf.isBefore(deadline)) {
				continue;
			}
			int expectedMinutes = expectedDayResolver
					.resolve(companyId, employeeId, AttendanceRules.dayOf(row.getCheckIn()))
					.expectedMinutes();
			int workedMinutes = Math.max(0, expectedMinutes - AttendanceRules.INCOMPLETE_PUNCH_DEDUCTION_MINUTES);
			row.closeAt(row.getCheckIn().plus(Duration.ofMinutes(workedMinutes)));
			attendanceRepository.save(row);
			closed++;
		}
		return closed;
	}

	private static <T> T firstNonNull(T preferred, T fallback) {
		return preferred != null ? preferred : fallback;
	}

}
