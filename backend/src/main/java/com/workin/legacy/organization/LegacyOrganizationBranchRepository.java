package com.workin.legacy.organization;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Aggregate lookup queries needed by departments/job titles without widening 12.3a's repository. */
public interface LegacyOrganizationBranchRepository extends JpaRepository<LegacyBranch, Long> {

	List<LegacyBranch> findByCompanyIdAndIdInOrderByNameAscIdAsc(Long companyId, Collection<Long> ids);

	List<LegacyBranch> findByCompanyIdAndIsActiveAndIdInOrderByNameAscIdAsc(
			Long companyId, Integer isActive, Collection<Long> ids);

}
