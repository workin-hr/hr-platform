package com.workin.backend.employees;

import jakarta.validation.constraints.NotNull;

/** The only path that changes employee lifecycle -- create/update carry no active field. */
public record UpdateEmployeeStatusRequest(@NotNull Boolean active) {
}
