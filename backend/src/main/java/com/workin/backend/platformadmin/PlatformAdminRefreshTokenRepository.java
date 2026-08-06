package com.workin.backend.platformadmin;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlatformAdminRefreshTokenRepository extends JpaRepository<PlatformAdminRefreshToken, Long> {

	Optional<PlatformAdminRefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Guarded rotation flip: returns 0 when a concurrent rotation
	 * already retired this link, which the caller must treat as reuse.
	 */
	@Modifying
	@Query("update PlatformAdminRefreshToken t set t.status = :to where t.id = :id and t.status = :expected")
	int transitionIfStatus(
			@Param("id") Long id,
			@Param("expected") PlatformAdminSessionStatus expected,
			@Param("to") PlatformAdminSessionStatus to);

	@Modifying
	@Query("update PlatformAdminRefreshToken t set t.status = :to where t.familyId = :familyId")
	int setStatusForFamily(@Param("familyId") UUID familyId, @Param("to") PlatformAdminSessionStatus to);

	@Modifying
	@Query("update PlatformAdminRefreshToken t set t.status = :to where t.platformAdminId = :platformAdminId")
	int setStatusForPlatformAdmin(
			@Param("platformAdminId") Long platformAdminId,
			@Param("to") PlatformAdminSessionStatus to);

}
