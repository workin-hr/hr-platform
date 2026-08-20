package com.workin.legacy.organization;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.workin.legacy.employees.LegacyEmployee;

/** Bulk manager lookup for department response aggregation. */
public interface LegacyOrganizationEmployeeRepository extends JpaRepository<LegacyEmployee, Long> {

	List<LegacyEmployee> findByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);

}
