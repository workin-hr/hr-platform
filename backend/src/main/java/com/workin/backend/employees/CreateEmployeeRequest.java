package com.workin.backend.employees;

import jakarta.validation.constraints.NotBlank;

/** Org attribution ids nullable; each non-null id is tenant-validated -> the same 404. */
public record CreateEmployeeRequest(
		@NotBlank String firstName,
		String lastName,
		String phone,
		Long branchId,
		Long departmentId,
		Long jobTitleId) {
}
