package com.workin.backend.employees;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;

/**
 * Org attribution ids nullable; each non-null id is tenant-validated
 * -> the same 404. shiftId optional (owner decision 2026-08-08,
 * diverging from legacy's required shift_id at creation to match this
 * platform's nullable org references); when present, one
 * employee_shift_assignments row is written, effective
 * shiftEffectiveFrom or today (legacy: shift_effective_from ??
 * hire_date ?? today -- no hire date exists here).
 */
public record CreateEmployeeRequest(
		@NotBlank String firstName,
		String lastName,
		String phone,
		Long branchId,
		Long departmentId,
		Long jobTitleId,
		Long shiftId,
		LocalDate shiftEffectiveFrom) {
}
