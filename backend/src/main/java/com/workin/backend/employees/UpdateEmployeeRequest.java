package com.workin.backend.employees;

import jakarta.validation.constraints.NotBlank;

/**
 * Deliberately carries no credential, role, or lifecycle fields (see
 * Employee's Javadoc). Org attribution ids nullable -- null clears;
 * each non-null id is tenant-validated -> the same 404.
 */
public record UpdateEmployeeRequest(
		@NotBlank String firstName,
		String lastName,
		Long branchId,
		Long departmentId,
		Long jobTitleId) {
}
