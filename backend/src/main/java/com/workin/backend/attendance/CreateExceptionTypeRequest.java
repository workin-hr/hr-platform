package com.workin.backend.attendance;

import jakarta.validation.constraints.NotBlank;

public record CreateExceptionTypeRequest(@NotBlank String name) {
}
