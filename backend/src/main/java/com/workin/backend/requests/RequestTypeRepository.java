package com.workin.backend.requests;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestTypeRepository extends JpaRepository<RequestType, Long> {

	List<RequestType> findByCompanyIdOrderById(Long companyId);

	Optional<RequestType> findByIdAndCompanyId(Long id, Long companyId);

}
