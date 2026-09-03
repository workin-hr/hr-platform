package com.workin.backend.platformadmin;

import jakarta.validation.constraints.NotBlank;

/**
 * Platform-admin credentials.
 *
 * <p>{@code code} is the TOTP second factor, required by the bearer surface
 * (ADR-0015 prerequisite 8) and unused by the JTE surface, which collects it on
 * a separate page after the password step. It is nullable rather than
 * {@code @NotBlank} so the shared credential check stays one method for both
 * surfaces; the bearer controller enforces its presence itself, which keeps the
 * requirement at the surface that has it.
 */
public record PlatformAdminLoginRequest(
		@NotBlank String phone,
		@NotBlank String password,
		String code) {

	public PlatformAdminLoginRequest(String phone, String password) {
		this(phone, password, null);
	}

}
