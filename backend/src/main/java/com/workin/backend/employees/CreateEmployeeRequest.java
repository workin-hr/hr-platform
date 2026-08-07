package com.workin.backend.employees;

import jakarta.validation.constraints.NotBlank;

/** No credential fields, deliberately -- see Employee's Javadoc. */
public record CreateEmployeeRequest(
		@NotBlank String firstName,
		String lastName,
		String phone) {
}
