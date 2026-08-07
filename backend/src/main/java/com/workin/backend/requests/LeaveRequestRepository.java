package com.workin.backend.requests;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

	List<LeaveRequest> findByCompanyIdOrderById(Long companyId);

	List<LeaveRequest> findByEmployeeIdAndCompanyIdOrderById(Long employeeId, Long companyId);

	Optional<LeaveRequest> findByIdAndCompanyId(Long id, Long companyId);

}
