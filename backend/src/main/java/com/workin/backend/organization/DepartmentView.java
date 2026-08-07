package com.workin.backend.organization;

import java.util.List;

public record DepartmentView(Long id, String name, Long managerId, List<Long> branchIds, boolean isActive) {

	static DepartmentView of(Department d, List<Long> branchIds) {
		return new DepartmentView(d.getId(), d.getName(), d.getManagerId(), branchIds, d.isActive());
	}

}
