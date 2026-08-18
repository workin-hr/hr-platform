package com.workin.legacy.organization;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes the aggregate-owned junction under P-1c. */
public interface LegacyDepartmentBranchRepository
		extends JpaRepository<LegacyDepartmentBranch, LegacyDepartmentBranchId> {

	List<LegacyDepartmentBranch> findByDepartmentId(Long departmentId);

	List<LegacyDepartmentBranch> findByDepartmentIdIn(Collection<Long> departmentIds);

}
