package com.workin.backend.organization;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

	List<Department> findByCompanyIdOrderById(Long companyId);

	Optional<Department> findByIdAndCompanyId(Long id, Long companyId);

}
