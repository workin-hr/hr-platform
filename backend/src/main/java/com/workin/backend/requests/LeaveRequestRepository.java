package com.workin.backend.requests;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

	List<LeaveRequest> findByCompanyIdOrderById(Long companyId);

	List<LeaveRequest> findByEmployeeIdAndCompanyIdOrderById(Long employeeId, Long companyId);

	Optional<LeaveRequest> findByIdAndCompanyId(Long id, Long companyId);

	/**
	 * attendance_approved_timed_request_for_day
	 * (attendance_calendar_helper.php:53-84): the مأمورية / timed-request
	 * lookup the attendance-calendar engine consults before anything
	 * else when costing a day.
	 *
	 * <p>Ported exactly, including two things that read like omissions
	 * and are not: <b>there is no {@code requestTypeId} filter</b>, so a
	 * one-hour permission request is indistinguishable from a full
	 * mission; and the tie-break is <b>highest id</b>, not most-recently
	 * decided or most-specific. Both are tracked for a keep-or-fix call
	 * rather than corrected here. Legacy's {@code TRIM(from_time) <> ''}
	 * guards are unnecessary against a real {@code time} column -- a
	 * non-null {@link java.time.LocalTime} is never blank.
	 *
	 * <p>Company-scoped where legacy is not: legacy reaches company only
	 * through {@code employees}, while every read in this codebase is
	 * tenant-scoped by construction. That narrows nothing in practice
	 * (an employee belongs to exactly one company) and keeps the query
	 * consistent with RLS.
	 */
	@Query("""
			select r from LeaveRequest r
			where r.employeeId = :employeeId
			  and r.companyId = :companyId
			  and r.status = com.workin.backend.requests.RequestStatus.APPROVED
			  and r.fromDate <= :date
			  and r.toDate >= :date
			  and r.fromTime is not null
			  and r.toTime is not null
			order by r.id desc
			""")
	List<LeaveRequest> findApprovedTimedRequestsForDay(
			@Param("employeeId") Long employeeId,
			@Param("companyId") Long companyId,
			@Param("date") LocalDate date,
			Limit limit);

	/**
	 * attendance_is_on_approved_leave
	 * (attendance_calendar_helper.php:669-683): the absence carve-out.
	 * A day covered by an approved request whose type carries
	 * {@code countsAsPaidLeave} is never Absent, even with no attendance
	 * row -- this engine is the first consumer of that flag.
	 *
	 * <p>Legacy applies no {@code request_types.is_active} filter and no
	 * time-of-day check, so a half-day timed request of a paid-leave
	 * type clears the whole day. Ported as-is.
	 */
	@Query("""
			select count(r) > 0 from LeaveRequest r, RequestType t
			where t.id = r.requestTypeId
			  and r.employeeId = :employeeId
			  and r.companyId = :companyId
			  and r.status = com.workin.backend.requests.RequestStatus.APPROVED
			  and t.countsAsPaidLeave = true
			  and r.fromDate <= :date
			  and r.toDate >= :date
			""")
	boolean existsApprovedPaidLeaveOnDate(
			@Param("employeeId") Long employeeId,
			@Param("companyId") Long companyId,
			@Param("date") LocalDate date);

}
