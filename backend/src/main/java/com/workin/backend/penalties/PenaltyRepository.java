package com.workin.backend.penalties;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PenaltyRepository extends JpaRepository<Penalty, Long> {

	List<Penalty> findByCompanyIdOrderById(Long companyId);

	Optional<Penalty> findByIdAndCompanyId(Long id, Long companyId);

}
