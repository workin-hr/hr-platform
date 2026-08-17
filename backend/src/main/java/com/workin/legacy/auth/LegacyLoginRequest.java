package com.workin.legacy.auth;

import jakarta.validation.constraints.NotBlank;

/** Mirrors {@code login_employee.php:11-15}'s two fields. */
public record LegacyLoginRequest(
		@NotBlank String phone,
		@NotBlank String password) {
}
