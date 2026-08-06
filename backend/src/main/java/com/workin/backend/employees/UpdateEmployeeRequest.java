package com.workin.backend.employees;

import jakarta.validation.constraints.NotBlank;

/**
 * Name changes only in this slice: role/active/lifecycle mutations
 * carry open product and privilege-escalation questions (hr-legacy#20,
 * authorization-model.md §8) and get their own slices. No credential
 * fields, structurally -- see Employee's Javadoc.
 */
public record UpdateEmployeeRequest(
		@NotBlank String firstName,
		String lastName) {
}
