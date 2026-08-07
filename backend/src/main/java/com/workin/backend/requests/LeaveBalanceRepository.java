package com.workin.backend.requests;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

	List<LeaveBalance> findByCompanyIdOrderById(Long companyId);

	List<LeaveBalance> findByEmployeeIdAndCompanyIdOrderById(Long employeeId, Long companyId);

	Optional<LeaveBalance> findByIdAndCompanyId(Long id, Long companyId);

	Optional<LeaveBalance> findByEmployeeIdAndYear(Long employeeId, Short year);

}
