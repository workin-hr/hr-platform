package com.workin.backend.members;

import jakarta.validation.constraints.NotNull;

import com.workin.backend.tenancy.TenantRole;

public record AssignRoleRequest(@NotNull TenantRole role) {
}
