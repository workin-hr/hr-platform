package com.workin.backend.organization;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {

	List<Branch> findByCompanyIdOrderById(Long companyId);

	Optional<Branch> findByIdAndCompanyId(Long id, Long companyId);

}
