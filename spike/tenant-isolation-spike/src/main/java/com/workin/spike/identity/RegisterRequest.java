package com.workin.spike.identity;

import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(@NotBlank String name, @NotBlank String phone, @NotBlank String password) {
}
