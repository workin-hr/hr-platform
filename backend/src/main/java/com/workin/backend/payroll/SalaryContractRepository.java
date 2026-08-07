package com.workin.backend.payroll;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SalaryContractRepository extends JpaRepository<SalaryContract, Long> {

	Optional<SalaryContract> findByIdAndCompanyId(Long id, Long companyId);

	List<SalaryContract> findByEmployeeIdAndCompanyIdOrderByEffectiveFromDesc(Long employeeId, Long companyId);

	/**
	 * The most recent contract effective on or before {@code asOf} --
	 * "the effective contract for a period," per business-rule
	 * extraction. Relies on RLS for company scoping (no explicit
	 * companyId parameter) since {@link PayrollCalculationService}
	 * always calls this from within an already-scoped transaction.
	 */
	Optional<SalaryContract> findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
			Long employeeId, LocalDate asOf);

}
