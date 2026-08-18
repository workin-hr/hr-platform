package com.workin.legacy.organization;

import java.io.Serializable;
import java.util.Objects;

/** Composite identity matching {@code department_branches(department_id, branch_id)} exactly. */
public class LegacyDepartmentBranchId implements Serializable {

	private Long departmentId;
	private Long branchId;

	protected LegacyDepartmentBranchId() {
	}

	public LegacyDepartmentBranchId(Long departmentId, Long branchId) {
		this.departmentId = departmentId;
		this.branchId = branchId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof LegacyDepartmentBranchId that)) {
			return false;
		}
		return Objects.equals(departmentId, that.departmentId) && Objects.equals(branchId, that.branchId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(departmentId, branchId);
	}

}
