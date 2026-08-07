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

}
