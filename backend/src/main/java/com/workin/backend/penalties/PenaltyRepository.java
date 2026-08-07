package com.workin.backend.penalties;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PenaltyRepository extends JpaRepository<Penalty, Long> {

	List<Penalty> findByCompanyIdOrderById(Long companyId);

	Optional<Penalty> findByIdAndCompanyId(Long id, Long companyId);

	/**
	 * Used by the payroll module's calculate/finalize/reopen -- matched
	 * by penaltyDate falling inside the batch's period, since this
	 * table carries no batch_id back-reference. Relies on calendar-month,
	 * non-overlapping batch periods per company to stay unambiguous.
	 */
	List<Penalty> findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
			Long employeeId, LocalDate from, LocalDate to, boolean appliedToPayroll);

}
