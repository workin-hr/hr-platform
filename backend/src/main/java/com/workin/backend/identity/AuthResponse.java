package com.workin.backend.identity;

public record AuthResponse(
		String accessToken,
		Long membershipId,
		Long companyId) {
}
