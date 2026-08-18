package com.workin.legacy;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@code @IdClass} for {@link DepartmentBranchProbeRow}, matching {@code
 * department_branches}'s real composite primary key {@code
 * (department_id, branch_id)} — not simplified to a single-column id,
 * so multiple branches under one department don't collide in the
 * persistence context's identity map.
 */
class DepartmentBranchProbeId implements Serializable {

	private Long departmentId;
	private Long branchId;

	protected DepartmentBranchProbeId() {
	}

	DepartmentBranchProbeId(Long departmentId, Long branchId) {
		this.departmentId = departmentId;
		this.branchId = branchId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof DepartmentBranchProbeId that)) {
			return false;
		}
		return Objects.equals(departmentId, that.departmentId) && Objects.equals(branchId, that.branchId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(departmentId, branchId);
	}

}
