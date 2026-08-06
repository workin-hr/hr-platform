package com.workin.backend.platformadmin;

public record PlatformAdminAuthResponse(
		String accessToken,
		String refreshToken,
		Long platformAdminId) {
}
