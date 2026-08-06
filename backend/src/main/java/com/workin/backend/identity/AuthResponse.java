package com.workin.backend.identity;

public record AuthResponse(
		String accessToken,
		String refreshToken,
		Long membershipId,
		Long companyId) {
}
