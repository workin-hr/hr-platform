package com.workin.backend.attendance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

	List<Attendance> findByCompanyIdOrderById(Long companyId);

	List<Attendance> findByEmployeeIdAndCompanyIdOrderById(Long employeeId, Long companyId);

	Optional<Attendance> findByIdAndCompanyId(Long id, Long companyId);

	/**
	 * The 2-hour-gap guard's lookup: the latest punch row (exception
	 * rows excluded) at or before the prospective check-in. Self-
	 * exclusion lives here, not in a service-side skip -- if the row
	 * being updated is itself the latest earlier punch, the next-earlier
	 * row still needs checking (create passes -1).
	 */
	Optional<Attendance> findFirstByEmployeeIdAndCompanyIdAndExceptionTypeIdIsNullAndIdNotAndCheckInLessThanEqualOrderByCheckInDesc(
			Long employeeId, Long companyId, Long excludedId, Instant checkIn);

	/**
	 * The request-approval side effect's skip rule (legacy
	 * request_apply_attendance_exceptions): a day already holding any
	 * attendance row for the employee gets no exception row.
	 */
	boolean existsByEmployeeIdAndCheckInGreaterThanEqualAndCheckInLessThan(
			Long employeeId, Instant dayStart, Instant nextDayStart);

	/**
	 * The calendar engine's one bulk fetch, replacing legacy's per-day
	 * queries (attendance_build_employee_range_calendar:244-259 issues
	 * this once; its helpers then re-query per day, which this port
	 * deliberately does not do -- a pure performance change with no
	 * behavioural effect).
	 *
	 * <p>Ascending by check-in because the day index is built by
	 * overwriting, so <b>the latest punch of a day wins</b> when an
	 * employee has more than one row for it (legacy {@code :264}). There
	 * is no unique key on (employee, day) in either system.
	 */
	List<Attendance> findByEmployeeIdAndCompanyIdAndCheckInGreaterThanEqualAndCheckInLessThanOrderByCheckInAsc(
			Long employeeId, Long companyId, Instant fromInclusive, Instant toExclusive);

	/**
	 * attendance_auto_close_stale_open_sessions' scan
	 * (attendance_session_helper.php:94-102): every punch with no
	 * check-out, oldest id first.
	 *
	 * <p>Company-scoped where legacy is not -- legacy filters on
	 * {@code employee_id} alone and uses the company only to resolve
	 * expected minutes, so its write is unauthorized by construction.
	 * Narrowing it here changes no outcome (an employee has one company)
	 * and keeps the write inside the tenant boundary.
	 */
	List<Attendance> findByEmployeeIdAndCompanyIdAndCheckOutIsNullOrderByIdAsc(Long employeeId, Long companyId);

}
