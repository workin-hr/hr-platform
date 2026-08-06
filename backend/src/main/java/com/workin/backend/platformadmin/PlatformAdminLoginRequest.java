package com.workin.backend.platformadmin;

import jakarta.validation.constraints.NotBlank;

public record PlatformAdminLoginRequest(
		@NotBlank String phone,
		@NotBlank String password) {
}
