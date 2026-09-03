package com.workin.backend.platformadmin;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;

import io.jsonwebtoken.Claims;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0015 prerequisite 4, the half that bounds API tokens: "a test that
 * advances a family beyond the cap and asserts both the refusal and the clamp".
 *
 * <p>The family is advanced by moving its origin backwards in the database
 * rather than by waiting a week. That is the honest way to test a seven-day
 * bound, and it exercises the real column the real code reads.
 */
class PlatformAdminFamilyCapTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct horse battery staple";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PlatformAdminJwtService jwtService;

	@Test
	void aFamilyPastItsAbsoluteCapCannotRotate() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);
		PlatformAdminAuthResponse session = login(phone);

		ageFamilyOrigin(phone, PlatformAdminSessionService.FAMILY_ABSOLUTE_CAP.plusHours(1));

		ResponseEntity<String> refreshed = this.restTemplate.postForEntity("/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(session.refreshToken()), String.class);

		assertThat(refreshed.getStatusCode())
			.as("a family that can rotate forever is not bounded by anything")
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void aFamilyInsideItsCapStillRotates() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);
		PlatformAdminAuthResponse session = login(phone);

		ageFamilyOrigin(phone, PlatformAdminSessionService.FAMILY_ABSOLUTE_CAP.minusHours(1));

		ResponseEntity<PlatformAdminAuthResponse> refreshed = this.restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(session.refreshToken()), PlatformAdminAuthResponse.class);

		assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void anAccessTokenIssuedNearTheCapIsClampedToIt() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);
		PlatformAdminAuthResponse session = login(phone);

		// Leave five minutes of family life. The access-token TTL is 15 minutes,
		// so an unclamped token would outlive the family by ten.
		ageFamilyOrigin(phone, PlatformAdminSessionService.FAMILY_ABSOLUTE_CAP.minusMinutes(5));

		ResponseEntity<PlatformAdminAuthResponse> refreshed = this.restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(session.refreshToken()), PlatformAdminAuthResponse.class);
		assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);

		Claims claims = this.jwtService.parseAndValidate(refreshed.getBody().accessToken());
		Instant expiry = claims.getExpiration().toInstant();

		assertThat(expiry)
			.as("an access token minted near the cap must not outlive it -- otherwise the cap "
					+ "bounds refreshing while leaving a live credential behind it")
			.isBeforeOrEqualTo(Instant.now().plus(Duration.ofMinutes(5)).plusSeconds(30));
	}

	@Test
	void theSuccessorRefreshTokenIsAlsoClampedToTheCap() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);
		PlatformAdminAuthResponse session = login(phone);

		ageFamilyOrigin(phone, PlatformAdminSessionService.FAMILY_ABSOLUTE_CAP.minusMinutes(5));

		this.restTemplate.postForEntity("/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(session.refreshToken()), PlatformAdminAuthResponse.class);

		// The ACTIVE row is the successor. The original is ROTATED and keeps its
		// pre-cap expiry, which is harmless: the cap is enforced when a token is
		// presented, before its status is even considered.
		Instant successorExpiry = new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT expires_at FROM platform_admin_refresh_tokens WHERE status = 'ACTIVE' "
						+ "AND platform_admin_id = (SELECT id FROM platform_admins WHERE phone = ?)",
				Date.class, phone).toInstant();

		assertThat(successorExpiry)
			.as("enforcing the cap on rotation and then handing out a successor that outlives it "
					+ "would simply move the problem one token along")
			.isBeforeOrEqualTo(Instant.now().plus(Duration.ofMinutes(5)).plusSeconds(30));
	}

	// --- helpers ------------------------------------------------------------

	private PlatformAdminAuthResponse login(String phone) {
		ResponseEntity<PlatformAdminAuthResponse> response = this.restTemplate.postForEntity(
				"/api/platform-admin/login", new PlatformAdminLoginRequest(phone, PASSWORD),
				PlatformAdminAuthResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	/** Moves the family's origin into the past, so "now" is {@code age} into it. */
	private void ageFamilyOrigin(String phone, Duration age) {
		new JdbcTemplate(this.flywayDataSource).update(
				"UPDATE platform_admin_refresh_tokens SET family_started_at = ? "
						+ "WHERE platform_admin_id = (SELECT id FROM platform_admins WHERE phone = ?)",
				java.sql.Timestamp.from(Instant.now().minus(age)), phone);
	}

	private void createPlatformAdmin(String phone) {
		new JdbcTemplate(this.flywayDataSource).update(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, true)",
				phone, this.passwordEncoder.encode(PASSWORD));
	}

	private static String uniquePhone() {
		return "+97" + System.nanoTime();
	}

}
