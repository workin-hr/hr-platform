package com.workin.backend.members;

import jakarta.validation.constraints.NotNull;

import com.workin.backend.authorization.ResourceScopeType;

public record AssignScopeRequest(@NotNull ResourceScopeType scopeType, @NotNull Long scopeId) {
}
