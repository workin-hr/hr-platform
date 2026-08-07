package com.workin.backend.members;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipPermissionOverrideRepository extends JpaRepository<MembershipPermissionOverride, Long> {

	Optional<MembershipPermissionOverride> findByMembershipIdAndPermissionId(Long membershipId, Long permissionId);

}
