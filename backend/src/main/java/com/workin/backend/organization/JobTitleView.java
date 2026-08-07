package com.workin.backend.organization;

import java.math.BigDecimal;

public record JobTitleView(Long id, String name, Long departmentId, BigDecimal workHours, boolean isActive) {

	static JobTitleView of(JobTitle t) {
		return new JobTitleView(t.getId(), t.getName(), t.getDepartmentId(), t.getWorkHours(), t.isActive());
	}

}
