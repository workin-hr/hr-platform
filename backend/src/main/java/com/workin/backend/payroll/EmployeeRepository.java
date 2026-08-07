package com.workin.backend.payroll;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every lookup here runs against the RLS-scoped application DataSource --
 * a query for an id belonging to another company returns empty, not an
 * exception. Callers must treat "not found" as the correct outcome for
 * a cross-tenant reference, never a special case (see
 * docs/migration/payroll-module-execution-plan.md, "The Structural Fix
 * For hr-legacy#5/#6/#8's IDOR Pattern").
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

	Optional<Employee> findByIdAndCompanyId(Long id, Long companyId);

	Optional<Employee> findByIdentityIdAndCompanyId(Long identityId, Long companyId);

	List<Employee> findByCompanyIdAndActiveTrue(Long companyId);

}
