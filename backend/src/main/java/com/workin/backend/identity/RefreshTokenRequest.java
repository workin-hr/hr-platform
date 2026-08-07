package com.workin.backend.identity;

import jakarta.validation.constraints.NotBlank;

/** Shared request body for the refresh and logout endpoints. */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
