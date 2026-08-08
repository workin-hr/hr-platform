package com.workin.backend.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeScheduleRepository extends JpaRepository<EmployeeSchedule, Long> {

	List<EmployeeSchedule> findByEmployeeIdAndCompanyIdAndScheduleDateBetweenOrderByScheduleDateAsc(
			Long employeeId, Long companyId, LocalDate from, LocalDate to);

	Optional<EmployeeSchedule> findByEmployeeIdAndCompanyIdAndScheduleDate(
			Long employeeId, Long companyId, LocalDate scheduleDate);

	/**
	 * Bulk JPQL delete, executed immediately -- a derived deleteBy...
	 * would queue entity removals that Hibernate flushes AFTER the
	 * regeneration's inserts, violating the (employee_id, schedule_date)
	 * unique constraint. Generate (Task 5) depends on this ordering.
	 */
	@Modifying
	@Query("DELETE FROM EmployeeSchedule s WHERE s.employeeId = :employeeId "
			+ "AND s.companyId = :companyId AND s.scheduleDate BETWEEN :from AND :to")
	void deleteRange(@Param("employeeId") Long employeeId, @Param("companyId") Long companyId,
			@Param("from") LocalDate from, @Param("to") LocalDate to);

}
