package com.workin.legacy.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;

import com.workin.legacy.DepartmentBranchesTenantFilter;

/**
 * The legacy department/branch junction, part of the department aggregate rather than a standalone
 * module (D-054). Its tenant is derived through {@code department_id -> departments.company_id}
 * and therefore uses P-1c, not P-1a.
 */
@Entity
@Table(name = "department_branches")
@IdClass(LegacyDepartmentBranchId.class)
@Filter(name = DepartmentBranchesTenantFilter.NAME, condition = DepartmentBranchesTenantFilter.CONDITION)
public class LegacyDepartmentBranch {

	@Id
	@Column(name = "department_id", nullable = false)
	private Long departmentId;

	@Id
	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	protected LegacyDepartmentBranch() {
	}

	public LegacyDepartmentBranch(Long departmentId, Long branchId) {
		this.departmentId = departmentId;
		this.branchId = branchId;
	}

	public Long getDepartmentId() {
		return departmentId;
	}

	public Long getBranchId() {
		return branchId;
	}

}
