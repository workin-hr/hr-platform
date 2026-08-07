package com.workin.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Department-branch junction row (V29), owned by the department aggregate. */
@Entity
@Table(name = "department_branches")
public class DepartmentBranch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "department_id", nullable = false)
	private Long departmentId;

	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	protected DepartmentBranch() {
	}

	public DepartmentBranch(Long departmentId, Long branchId, Long companyId) {
		this.departmentId = departmentId;
		this.branchId = branchId;
		this.companyId = companyId;
	}

	public Long getId() {
		return id;
	}

	public Long getDepartmentId() {
		return departmentId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public Long getCompanyId() {
		return companyId;
	}

}
