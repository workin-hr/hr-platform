package com.workin.backend.organization;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

	List<Shift> findByCompanyIdOrderById(Long companyId);

	Optional<Shift> findByIdAndCompanyId(Long id, Long companyId);

}
