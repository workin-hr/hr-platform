package com.workin.backend.attendance;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExceptionTypeRepository extends JpaRepository<ExceptionType, Long> {

	List<ExceptionType> findByCompanyIdOrderById(Long companyId);

	Optional<ExceptionType> findByIdAndCompanyId(Long id, Long companyId);

}
