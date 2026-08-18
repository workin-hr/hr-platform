package com.workin.legacy.organization;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes legacy job titles under P-1a. */
public interface LegacyJobTitleRepository extends JpaRepository<LegacyJobTitle, Long> {

	Optional<LegacyJobTitle> findByIdAndCompanyId(Long id, Long companyId);

	List<LegacyJobTitle> findByCompanyIdAndIsActiveOrderByCreatedAtDescIdDesc(Long companyId, Integer isActive);

}
