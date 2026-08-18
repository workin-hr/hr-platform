package com.workin.legacy.organization;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes legacy departments under P-1a. */
public interface LegacyDepartmentRepository extends JpaRepository<LegacyDepartment, Long> {

	Optional<LegacyDepartment> findByIdAndCompanyId(Long id, Long companyId);

	List<LegacyDepartment> findByCompanyIdAndIsActiveOrderByCreatedAtDescIdDesc(Long companyId, Integer isActive);

	List<LegacyDepartment> findByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);

}
