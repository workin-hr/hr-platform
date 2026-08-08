package com.workin.backend.schedule;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: intentionally no update/delete queries. */
public interface EmployeeShiftAssignmentRepository extends JpaRepository<EmployeeShiftAssignment, Long> {

	/** schedule_shift_for_employee_on_date's exact rule (effective_from DESC, id DESC tiebreak). */
	Optional<EmployeeShiftAssignment> findFirstByEmployeeIdAndCompanyIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(
			Long employeeId, Long companyId, LocalDate onDate);

	/** The next assignment after a given effective_from -- schedule_shift_summary's effective_to lookup. */
	Optional<EmployeeShiftAssignment> findFirstByEmployeeIdAndCompanyIdAndEffectiveFromGreaterThanOrderByEffectiveFromAscIdAsc(
			Long employeeId, Long companyId, LocalDate afterDate);

}
