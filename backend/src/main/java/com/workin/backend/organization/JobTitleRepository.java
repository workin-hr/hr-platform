package com.workin.backend.organization;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobTitleRepository extends JpaRepository<JobTitle, Long> {

	List<JobTitle> findByCompanyIdOrderById(Long companyId);

	Optional<JobTitle> findByIdAndCompanyId(Long id, Long companyId);

}
