package com.workin.backend.members;

import jakarta.validation.constraints.NotNull;

import com.workin.backend.tenancy.MembershipStatus;

public record UpdateStatusRequest(@NotNull MembershipStatus status) {
}
