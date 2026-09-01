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

	/**
	 * Whether this session family still has a usable token (R-027).
	 *
	 * <p>Indexed on {@code family_id} (V16). Mirrors the tenant surface's
	 * {@code RefreshTokenRepository.familyIsLive} -- R-027 is one defect on
	 * two surfaces, and splitting the fix would leave the two paths with
	 * different revocation semantics.
	 */
	boolean existsByFamilyIdAndStatusNot(UUID familyId, PlatformAdminSessionStatus status);

	/** True while any token in this session family is not {@code REVOKED}. */
	default boolean familyIsLive(UUID familyId) {
		return existsByFamilyIdAndStatusNot(familyId, PlatformAdminSessionStatus.REVOKED);
	}

}
