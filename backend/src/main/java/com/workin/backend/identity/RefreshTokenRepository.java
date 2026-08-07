package com.workin.backend.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Guarded rotation flip: returns 0 when a concurrent rotation
	 * already retired this link, which the caller must treat as reuse.
	 */
	@Modifying
	@Query("update RefreshToken t set t.status = :to where t.id = :id and t.status = :expected")
	int transitionIfStatus(
			@Param("id") Long id,
			@Param("expected") RefreshTokenStatus expected,
			@Param("to") RefreshTokenStatus to);

	@Modifying
	@Query("update RefreshToken t set t.status = :to where t.familyId = :familyId")
	int setStatusForFamily(@Param("familyId") UUID familyId, @Param("to") RefreshTokenStatus to);

	@Modifying
	@Query("update RefreshToken t set t.status = :to where t.identityId = :identityId")
	int setStatusForIdentity(@Param("identityId") Long identityId, @Param("to") RefreshTokenStatus to);

}
