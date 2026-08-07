package com.workin.backend.members;

import com.workin.backend.authorization.ResourceScopeType;

public record ResourceScopeView(ResourceScopeType scopeType, Long scopeId) {
}
