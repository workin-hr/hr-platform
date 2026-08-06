package com.workin.backend.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterCompanyRequest(
		@NotBlank String name,
		@NotBlank String phone,
		@NotBlank @Size(min = 8, max = 128) String password) {
}
