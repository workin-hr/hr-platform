package com.workin.backend.identity;

import jakarta.validation.constraints.NotBlank;

public record RegisterCompanyRequest(
		@NotBlank String name,
		@NotBlank String phone,
		@NotBlank String password) {
}
