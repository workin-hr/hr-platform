package com.workin.legacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.Filter;

/**
 * Test-only, minimal read mapping of {@code department_branches} —
 * proves P-1c, the one table that reaches tenancy via {@code
 * department_id → departments.company_id} rather than {@code
 * employee_id}. See {@link AttendanceProbeRow}'s javadoc for why this is
 * test-scoped and deliberately minimal.
 */
@Entity
@Table(name = "department_branches")
@IdClass(DepartmentBranchProbeId.class)
@Filter(name = DepartmentBranchesTenantFilter.NAME, condition = DepartmentBranchesTenantFilter.CONDITION)
class DepartmentBranchProbeRow {

	@Id
	@Column(name = "department_id")
	private Long departmentId;

	@Id
	@Column(name = "branch_id")
	private Long branchId;

	protected DepartmentBranchProbeRow() {
	}

	Long getDepartmentId() {
		return departmentId;
	}

	Long getBranchId() {
		return branchId;
	}

}
