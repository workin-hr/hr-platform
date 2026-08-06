package com.workin.backend.platformadmin;

public record PlatformAdminAuthResponse(
		String accessToken,
		Long platformAdminId) {
}
