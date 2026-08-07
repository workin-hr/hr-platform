package com.workin.backend.employees;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

	List<Employee> findByCompanyIdOrderById(Long companyId);

	Optional<Employee> findByIdAndCompanyId(Long id, Long companyId);

}
