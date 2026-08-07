package com.workin.backend.authorization;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceScopeRepository extends JpaRepository<ResourceScope, Long> {

	List<ResourceScope> findByMembershipId(Long membershipId);

	Optional<ResourceScope> findByMembershipIdAndScopeTypeAndScopeId(
			Long membershipId, ResourceScopeType scopeType, Long scopeId);

}
