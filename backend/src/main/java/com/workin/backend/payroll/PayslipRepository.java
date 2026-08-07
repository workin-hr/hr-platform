package com.workin.backend.payroll;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PayslipRepository extends JpaRepository<Payslip, Long> {

	Optional<Payslip> findByIdAndCompanyId(Long id, Long companyId);

	List<Payslip> findByBatchId(Long batchId);

	Optional<Payslip> findByBatchIdAndEmployeeId(Long batchId, Long employeeId);

	List<Payslip> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

	List<Payslip> findByCompanyId(Long companyId);

	void deleteByBatchId(Long batchId);

}
