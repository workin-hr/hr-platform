package com.workin.backend.employees;

/** Admin-surface employee shape. No credential field exists to leak. */
public record EmployeeView(
		Long id,
		String firstName,
		String lastName,
		String phone,
		String role,
		boolean active) {

	static EmployeeView of(Employee employee) {
		return new EmployeeView(
				employee.getId(),
				employee.getFirstName(),
				employee.getLastName(),
				employee.getPhone(),
				employee.getRole(),
				employee.isActive());
	}

}
