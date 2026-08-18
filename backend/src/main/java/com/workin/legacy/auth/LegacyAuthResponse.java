package com.workin.legacy.auth;

/**
 * Session material only -- no {@code public_row(employee)} payload
 * (punch-list item #9, response-body decision). The client fetches the
 * employee profile through its own tenant-scoped endpoint after
 * authenticating, the same pattern
 * {@code com.workin.backend.identity.AuthController} already uses.
 */
public record LegacyAuthResponse(
		String accessToken,
		String refreshToken,
		Long employeeId,
		Long companyId) {
}
