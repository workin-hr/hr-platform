package com.workin.backend.members;

import java.util.List;

import com.workin.backend.tenancy.MembershipStatus;
import com.workin.backend.tenancy.TenantRole;

public record MemberView(Long membershipId, String phone, List<TenantRole> roles, MembershipStatus status) {
}
