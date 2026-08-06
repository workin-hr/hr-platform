package com.workin.backend.advances;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvanceRepository extends JpaRepository<Advance, Long> {

	List<Advance> findByCompanyIdOrderById(Long companyId);

	Optional<Advance> findByIdAndCompanyId(Long id, Long companyId);

}
