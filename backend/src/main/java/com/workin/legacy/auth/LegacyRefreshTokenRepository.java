package com.workin.legacy.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Mirrors {@code com.workin.backend.identity.RefreshTokenRepository}'s
 * shape and the same compensating control its javadoc documents: every
 * finder here is keyed on something unguessable or already proven (a
 * SHA-256 token hash, the primary key, a family id the caller
 * demonstrated possession of, or the authenticated employee) -- never a
 * company. Unlike its PostgreSQL counterpart this table has no
 * {@code company_id} column at all to accidentally key on (see
 * {@code LegacyRefreshToken}'s javadoc), so there is no
 * {@code RefreshTokenRepositoryScopeTest}-shaped guard needed here: the
 * schema itself makes the unsafe finder unwritable.
 */
public interface LegacyRefreshTokenRepository extends JpaRepository<LegacyRefreshToken, Long> {

	/** Pre-tenant-context by necessity: the hash is the only thing the caller has yet. */
	Optional<LegacyRefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Guarded rotation flip: returns 0 when a concurrent rotation already
	 * retired this link, which the caller must treat as reuse.
	 */
	@Modifying
	@Query("update LegacyRefreshToken t set t.status = :to where t.id = :id and t.status = :expected")
	int transitionIfStatus(
			@Param("id") Long id,
			@Param("expected") LegacyRefreshTokenStatus expected,
			@Param("to") LegacyRefreshTokenStatus to);

	@Modifying
	@Query("update LegacyRefreshToken t set t.status = :to where t.familyId = :familyId")
	int setStatusForFamily(@Param("familyId") String familyId, @Param("to") LegacyRefreshTokenStatus to);

	@Modifying
	@Query("update LegacyRefreshToken t set t.status = :to where t.employeeId = :employeeId")
	int setStatusForEmployee(@Param("employeeId") Long employeeId, @Param("to") LegacyRefreshTokenStatus to);

}
