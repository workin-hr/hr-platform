package com.workin.backend.members;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpsertOverrideRequest(@NotBlank String permissionKey, @NotNull OverrideEffect effect) {
}
