package com.workin.backend.identity;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
		@NotBlank String phone,
		@NotBlank String password) {
}
