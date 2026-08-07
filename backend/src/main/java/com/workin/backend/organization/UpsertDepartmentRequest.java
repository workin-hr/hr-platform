package com.workin.backend.organization;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/** branchIds null is treated as empty; isActive null -> true. */
public record UpsertDepartmentRequest(
		@NotBlank String name,
		Long managerId,
		List<Long> branchIds,
		Boolean isActive) {
}
