package com.workin.backend.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <b>The one tenant-attributable table with no row-level security</b>
 * (issue #74), so every query here carries an obligation the database
 * is not enforcing for it.
 *
 * <p>V15's own header calls the table "global like identities". That is
 * not accurate — {@code refresh_tokens} has {@code company_id NOT NULL}
 * and a {@code membership_id}, and the value is load-bearing: it is
 * carried through rotation into the next access token's tenant claim.
 * The comment cannot be corrected in place, because editing an applied
 * migration breaks Flyway's checksum validation, so the real reasoning
 * is recorded here instead.
 *
 * <p><b>Why RLS is not simply switched on.</b> The standard fail-closed
 * policy resolves {@code app.current_company_id}, and the whole point
 * of {@link #findByTokenHash} is that it runs <em>before</em> any
 * tenant context exists — login and refresh are how the caller earns
 * one. Under RLS that lookup would match zero rows and refresh would
 * fail for everyone. The company is discovered <em>from</em> the row;
 * it cannot be a precondition for reading it.
 *
 * <p><b>The compensating control.</b> Every finder here is keyed on
 * something unguessable or already proven: a SHA-256 token hash, the
 * primary key, a family UUID the caller demonstrated possession of, or
 * the authenticated identity. None accepts a company. That is what
 * makes the missing policy safe today, and
 * {@code RefreshTokenRepositoryScopeTest} pins it — a new finder that
 * breaks the pattern fails the build rather than silently becoming the
 * first cross-tenant read in the system.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	/** Pre-tenant-context by necessity: the hash is the only thing the caller has yet. */
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

	/**
	 * Whether this session family still has a usable token.
	 *
	 * <p>Logout and reuse-detection both set every row in a family to
	 * {@code REVOKED}, but until R-027 nothing read that on the request path:
	 * the access token carries the family in its {@code sid} claim and
	 * {@link com.workin.backend.security.JwtAuthenticationFilter} never looked
	 * at it, so a logged-out token kept authenticating until it expired.
	 *
	 * <p>Indexed on {@code family_id} (V15), so this is one indexed lookup --
	 * the same trade ADR-0010 already makes for authorization: immediate
	 * revocation over cached session state.
	 */
	boolean existsByFamilyIdAndStatusNot(UUID familyId, RefreshTokenStatus status);

	/** True while any token in this session family is not {@code REVOKED}. */
	default boolean familyIsLive(UUID familyId) {
		return existsByFamilyIdAndStatusNot(familyId, RefreshTokenStatus.REVOKED);
	}

	@Modifying
	@Query("update RefreshToken t set t.status = :to where t.identityId = :identityId")
	int setStatusForIdentity(@Param("identityId") Long identityId, @Param("to") RefreshTokenStatus to);

}
