package com.workin.backend.payroll;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvanceRepository extends JpaRepository<Advance, Long> {

	/**
	 * Every mutating method in {@link AdvanceService} resolves through
	 * this -- not {@code findById} -- specifically because
	 * approve/reject/pay/delete in hr-legacy skipped the company_id
	 * check (hr-legacy#5). Going through this method structurally
	 * closes that gap for every caller, not just the ones someone
	 * remembers to check.
	 */
	Optional<Advance> findByIdAndCompanyId(Long id, Long companyId);

	List<Advance> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

	List<Advance> findByCompanyId(Long companyId);

	/**
	 * Used by {@link PayrollBatchService} to find advances deductible in
	 * a given batch's period -- matched by deductionPayrollYear/Month
	 * equality, the mechanism V12's schema was designed for (see the
	 * schema migration's own comment on deductionMode). Only APPROVED
	 * advances are ever deducted.
	 */
	List<Advance> findByEmployeeIdAndStatusAndDeductionPayrollYearAndDeductionPayrollMonth(
			Long employeeId, AdvanceStatus status, Short deductionPayrollYear, Short deductionPayrollMonth);

}
