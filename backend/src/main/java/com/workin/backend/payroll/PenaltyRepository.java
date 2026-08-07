package com.workin.backend.payroll;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PenaltyRepository extends JpaRepository<Penalty, Long> {

	Optional<Penalty> findByIdAndCompanyId(Long id, Long companyId);

	List<Penalty> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

	List<Penalty> findByCompanyId(Long companyId);

	/**
	 * Used by {@link PayrollBatchService} at both calculate/finalize time
	 * (appliedToPayroll = false) and reopen time (appliedToPayroll =
	 * true, to reverse) -- matched by penaltyDate falling inside the
	 * batch's period, since penalties carry no batch_id back-reference
	 * in this schema. Relies on calendar-month, non-overlapping batch
	 * periods per company (the fiscal-period simplification documented
	 * in docs/migration/payroll-module-execution-plan.md) to stay
	 * unambiguous.
	 */
	List<Penalty> findByEmployeeIdAndPenaltyDateBetweenAndAppliedToPayroll(
			Long employeeId, LocalDate from, LocalDate to, boolean appliedToPayroll);

}
