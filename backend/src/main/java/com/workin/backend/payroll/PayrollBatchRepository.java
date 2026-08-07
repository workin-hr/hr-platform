package com.workin.backend.payroll;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollBatchRepository extends JpaRepository<PayrollBatch, Long> {

	Optional<PayrollBatch> findByIdAndCompanyId(Long id, Long companyId);

	List<PayrollBatch> findByCompanyId(Long companyId);

}
