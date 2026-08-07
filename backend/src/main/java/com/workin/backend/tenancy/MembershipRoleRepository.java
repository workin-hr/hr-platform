package com.workin.backend.tenancy;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRoleRepository extends JpaRepository<MembershipRoleAssignment, Long> {

	List<MembershipRoleAssignment> findByMembershipId(Long membershipId);

	List<MembershipRoleAssignment> findByCompanyId(Long companyId);

	Optional<MembershipRoleAssignment> findByMembershipIdAndRole(Long membershipId, TenantRole role);

}
