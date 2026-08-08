package com.workin.backend.employees;

import jakarta.validation.constraints.NotBlank;

/**
 * Deliberately carries no credential, role, or lifecycle fields (see
 * Employee's Javadoc). Org attribution ids nullable -- null clears;
 * each non-null id is tenant-validated -> the same 404. shiftId is the
 * exception to null-clears: assignment history is append-only (legacy
 * has no unassign), so null means "no schedule statement" and a
 * non-null value appends a new history row only when it differs from
 * the current assignment.
 */
public record UpdateEmployeeRequest(
		@NotBlank String firstName,
		String lastName,
		Long branchId,
		Long departmentId,
		Long jobTitleId,
		Long shiftId) {
}
