package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.AbstractIntegrationTest;

class PlatformAdminAuditTest extends AbstractIntegrationTest {


	@Autowired
	private com.workin.backend.platformadmin.mfa.PlatformAdminMfaService mfaServiceForTests;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PlatformAdminSessionService platformAdminSessionService;

	private Long createPlatformAdmin(String phone, String password) {
		Long id = new JdbcTemplate(flywayDataSource).queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, TRUE) RETURNING id",
				Long.class, phone, passwordEncoder.encode(password));
		// ADR-0015 prerequisite 8: the bearer surface refuses an administrator
		// with no bound factor, so a fixture that intends to log in must enrol.
		this.seeds.put(phone, PlatformAdminMfaTestSupport.enrol(this.mfaServiceForTests, id));
		return id;
	}

	/** Seeds of the administrators this test enrolled, by phone. */
	private final java.util.Map<String, String> seeds = new java.util.HashMap<>();

	private String codeFor(String phone) {
		return PlatformAdminMfaTestSupport.freshCode(this.seeds.get(phone));
	}

	/**
	 * The events this test is about.
	 *
	 * <p>Enrolment now writes its own attribution -- token issued, token used,
	 * factor bound -- before any login happens, because prerequisite 8 requires
	 * a bound factor before the bearer surface issues anything. Those events have
	 * their own test; filtering them here keeps these assertions exact about
	 * login attribution rather than loosening them to "contains".
	 */
	private List<String> loginEventTypesFor(Long adminId) {
		return eventTypesFor(adminId).stream().filter(type -> !type.startsWith("MFA_")).toList();
	}

	private List<String> eventTypesFor(Long adminId) {
		return new JdbcTemplate(flywayDataSource).queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE platform_admin_id = ? ORDER BY id",
				String.class, adminId);
	}

	private PlatformAdminAuthResponse login(String phone, String password) {
		return restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, password, codeFor(phone)),
				PlatformAdminAuthResponse.class).getBody();
	}

	@Test
	void successfulLoginIsAttributed() {
		String phone = uniquePhone();
		Long adminId = createPlatformAdmin(phone, "correct horse battery staple");
		login(phone, "correct horse battery staple");

		assertThat(loginEventTypesFor(adminId)).containsExactly("LOGIN");
	}

	@Test
	void failedLoginAgainstAKnownAdminIsAttributedButUnknownPhonesAreNot() {
		String phone = uniquePhone();
		Long adminId = createPlatformAdmin(phone, "correct horse battery staple");

		restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, "wrong password", codeFor(phone)), String.class);
		restTemplate.postForEntity(
				"/api/platform-admin/login",
				new PlatformAdminLoginRequest("+20000000000", "whatever"), String.class);

		assertThat(loginEventTypesFor(adminId)).containsExactly("LOGIN_FAILED");
		Integer unattributable = new JdbcTemplate(flywayDataSource).queryForObject(
				"SELECT count(*) FROM platform_admin_audit_events WHERE platform_admin_id NOT IN "
						+ "(SELECT id FROM platform_admins)",
				Integer.class);
		assertThat(unattributable).isZero();
	}

	@Test
	void logoutReuseRevocationAndRevokeAllAreAttributed() {
		String phone = uniquePhone();
		Long adminId = createPlatformAdmin(phone, "correct horse battery staple");
		PlatformAdminAuthResponse first = login(phone, "correct horse battery staple");

		// Rotate once, then present the stale token -> reuse revocation.
		PlatformAdminAuthResponse rotated = restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(first.refreshToken()),
				PlatformAdminAuthResponse.class).getBody();
		restTemplate.postForEntity(
				"/api/platform-admin/refresh",
				new PlatformAdminRefreshTokenRequest(first.refreshToken()), String.class);

		// A fresh session, ended by explicit logout. The window has to advance
		// first: one code is usable per 30 seconds, and without this the test
		// passes or fails depending on whether it happens to straddle a step
		// boundary.
		PlatformAdminMfaTestSupport.allowAnotherCode(new JdbcTemplate(flywayDataSource), adminId);
		PlatformAdminAuthResponse second = login(phone, "correct horse battery staple");
		restTemplate.postForEntity(
				"/api/platform-admin/logout",
				new PlatformAdminRefreshTokenRequest(second.refreshToken()), Void.class);

		platformAdminSessionService.revokeAllForPlatformAdmin(adminId);

		assertThat(loginEventTypesFor(adminId)).containsExactly(
				"LOGIN", "SESSION_REUSE_REVOKED", "LOGIN", "LOGOUT", "ALL_SESSIONS_REVOKED");
		assertThat(rotated.refreshToken()).isNotBlank();
	}

	private static String uniquePhone() {
		return "+2088" + System.nanoTime() % 100_000_000L;
	}

}
