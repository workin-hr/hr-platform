package com.workin.backend.advances;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvanceRepository extends JpaRepository<Advance, Long> {

	List<Advance> findByCompanyIdOrderById(Long companyId);

	Optional<Advance> findByIdAndCompanyId(Long id, Long companyId);

	/**
	 * Used by the payroll module's finalize side effect -- oldest
	 * APPROVED advance first (a simple FIFO deduction order). V12's
	 * deduction_* scheduling columns remain unmapped/unused (see this
	 * entity's Javadoc); this is a documented v1 heuristic, not a port
	 * of a specific legacy scheduling rule.
	 */
	List<Advance> findByEmployeeIdAndStatusOrderByRequestDateAsc(Long employeeId, AdvanceStatus status);

}
