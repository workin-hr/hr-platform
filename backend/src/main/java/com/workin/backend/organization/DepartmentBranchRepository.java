package com.workin.backend.organization;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentBranchRepository extends JpaRepository<DepartmentBranch, Long> {

	List<DepartmentBranch> findByDepartmentId(Long departmentId);

	void deleteByDepartmentId(Long departmentId);

}
