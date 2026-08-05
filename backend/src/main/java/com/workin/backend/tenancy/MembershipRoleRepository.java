package com.workin.backend.tenancy;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRoleRepository extends JpaRepository<MembershipRoleAssignment, Long> {

	List<MembershipRoleAssignment> findByMembershipId(Long membershipId);

}
