package com.workin.backend.platformadmin;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformAdminLoginAttemptRepository extends JpaRepository<PlatformAdminLoginAttempt, Long> {

	long countByIdentifierHashAndAttemptedAtAfter(String identifierHash, Instant cutoff);

	@Modifying
	@Query("DELETE FROM PlatformAdminLoginAttempt a WHERE a.identifierHash = :identifierHash")
	void deleteByIdentifierHash(@Param("identifierHash") String identifierHash);

	@Modifying
	@Query("DELETE FROM PlatformAdminLoginAttempt a WHERE a.attemptedAt < :cutoff")
	int deleteOlderThan(@Param("cutoff") Instant cutoff);

}
