package com.workin.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.RefreshTokenService.IssuedRefreshToken;
import com.workin.backend.security.OpaqueTokens;

/**
 * Service-level coverage of the session state machine (rotation, reuse
 * detection, revocation). HTTP-level behavior is AuthSessionFlowTest's
 * job; this class exercises the transitions directly, including the
 * ones that are awkward to reach through HTTP (expiry aging).
 */
class RefreshTokenServiceTest extends AbstractIntegrationTest {

	@Autowired
	private RefreshTokenService refreshTokenService;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	private record Fixture(Long identityId, Long membershipId, Long companyId) {
	}

	private Fixture createIdentityWithMembership() {
		JdbcTemplate jdbc = new JdbcTemplate(flywayDataSource);
		String phone = "+2077" + System.nanoTime() % 100_000_000L;
		Long companyId = jdbc.queryForObject(
				"INSERT INTO companies (name, phone) VALUES ('Session Co', ?) RETURNING id", Long.class, phone);
		Long identityId = jdbc.queryForObject(
				"INSERT INTO identities (phone, password_hash) VALUES (?, 'x') RETURNING id", Long.class, phone);
		Long membershipId = jdbc.queryForObject(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
				Long.class, identityId, companyId);
		return new Fixture(identityId, membershipId, companyId);
	}

	@Test
	void issueThenRotateReturnsANewTokenInTheSameFamily() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken issued = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());

		var rotated = refreshTokenService.rotate(issued.rawToken());

		assertThat(rotated).isPresent();
		assertThat(rotated.get().familyId()).isEqualTo(issued.familyId());
		assertThat(rotated.get().rawToken()).isNotEqualTo(issued.rawToken());
		assertThat(rotated.get().membershipId()).isEqualTo(fixture.membershipId());
	}

	@Test
	void reusingARotatedTokenRevokesTheWholeFamily() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken issued = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());
		var rotated = refreshTokenService.rotate(issued.rawToken());

		assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
		// The newest token in the family must be dead too -- family
		// revocation, not just single-token rejection.
		assertThat(refreshTokenService.rotate(rotated.get().rawToken())).isEmpty();
	}

	@Test
	void anUnknownTokenIsRejected() {
		assertThat(refreshTokenService.rotate(OpaqueTokens.newToken())).isEmpty();
	}

	@Test
	void anExpiredTokenIsRejected() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken issued = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());
		new JdbcTemplate(flywayDataSource).update(
				"UPDATE refresh_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
				OpaqueTokens.sha256Hex(issued.rawToken()));

		assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
	}

	@Test
	void rotationFailsClosedWhenTheMembershipIsNoLongerActive() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken issued = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());
		new JdbcTemplate(flywayDataSource).update(
				"UPDATE tenant_memberships SET status = 'DISABLED' WHERE id = ?", fixture.membershipId());

		assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
	}

	@Test
	void rotationFailsClosedWhenTheIdentityIsDeactivated() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken issued = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());
		new JdbcTemplate(flywayDataSource).update(
				"UPDATE identities SET active = FALSE WHERE id = ?", fixture.identityId());

		assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
	}

	@Test
	void logoutRevokesTheFamilyAndIsIdempotent() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken issued = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());

		refreshTokenService.logout(issued.rawToken());
		assertThat(refreshTokenService.rotate(issued.rawToken())).isEmpty();
		refreshTokenService.logout(issued.rawToken());
		refreshTokenService.logout(OpaqueTokens.newToken());
	}

	@Test
	void revokeAllForIdentityKillsEverySession() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken first = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());
		IssuedRefreshToken second = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());
		assertThat(first.familyId()).isNotEqualTo(second.familyId());

		refreshTokenService.revokeAllForIdentity(fixture.identityId());

		assertThat(refreshTokenService.rotate(first.rawToken())).isEmpty();
		assertThat(refreshTokenService.rotate(second.rawToken())).isEmpty();
	}

	@Test
	void rawTokensAreNeverStored() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken issued = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());

		Integer rawMatches = new JdbcTemplate(flywayDataSource).queryForObject(
				"SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, issued.rawToken());
		Integer hashMatches = new JdbcTemplate(flywayDataSource).queryForObject(
				"SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class,
				OpaqueTokens.sha256Hex(issued.rawToken()));

		assertThat(rawMatches).isZero();
		assertThat(hashMatches).isEqualTo(1);
	}

	@Test
	void familyIdBecomesTheUuidSessionIdentity() {
		Fixture fixture = createIdentityWithMembership();
		IssuedRefreshToken issued = refreshTokenService.issue(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());
		assertThat(issued.familyId()).isInstanceOf(UUID.class);
	}

}
