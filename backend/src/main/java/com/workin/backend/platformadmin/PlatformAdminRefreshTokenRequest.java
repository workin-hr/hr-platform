package com.workin.backend.platformadmin;

import jakarta.validation.constraints.NotBlank;

/** Shared request body for the platform-admin refresh and logout endpoints. */
public record PlatformAdminRefreshTokenRequest(@NotBlank String refreshToken) {
}
