package com.workin.backend.platformadmin.mfa;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.workin.backend.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-152's enrolment ceremony and ADR-0015 prerequisite 12's single-use rule.
 *
 * <p>Each test names the hole its step closes, because the ceremony reads like
 * ceremony until you ask what happens without each part.
 */
class PlatformAdminMfaServiceTest extends AbstractIntegrationTest {

	/**
	 * A fresh key per run, generated rather than written down.
	 *
	 * <p>A base64 key literal in a source file is indistinguishable from a real
	 * leaked one -- the repository's secret scanner flagged exactly that, and it
	 * was right to. Generating it here removes the literal, and it is better
	 * practice anyway: no test can come to depend on a particular key, and
	 * production's comes from the deployment's secret store.
	 */
	@DynamicPropertySource
	static void mfaEncryptionKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("app.platform-admin.mfa.encryption-key",
				() -> Base64.getEncoder().encodeToString(key));
	}

	@Autowired
	private PlatformAdminMfaService mfaService;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	// --- D-152: a password alone must not bind the factor -------------------

	@Test
	void enrolmentWithoutABootstrapTokenIsRefused() {
		long admin = createPlatformAdmin();

		assertThat(this.mfaService.beginEnrolment(admin, "not-a-real-token"))
			.as("whoever reaches the login first with a stolen password would otherwise "
					+ "bind their own authenticator and lock the real administrator out")
			.isEmpty();
		assertThat(this.mfaService.beginEnrolment(admin, null)).isEmpty();
	}

	@Test
	void aBootstrapTokenIssuedForOneAdministratorDoesNotEnrolAnother() {
		long intended = createPlatformAdmin();
		long other = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(intended, intended);

		assertThat(this.mfaService.beginEnrolment(other, token)).isEmpty();
	}

	@Test
	void anExpiredBootstrapTokenIsRefused() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);
		expireBootstrapTokens(admin);

		assertThat(this.mfaService.beginEnrolment(admin, token)).isEmpty();
	}

	@Test
	void issuingAFreshTokenRevokesTheOutstandingOne() {
		long admin = createPlatformAdmin();
		String first = this.mfaService.issueBootstrapToken(admin, admin);

		this.mfaService.issueBootstrapToken(admin, admin);

		assertThat(this.mfaService.beginEnrolment(admin, first))
			.as("two live tokens mean two people can each complete an enrolment, "
					+ "and the second silently replaces the first's factor")
			.isEmpty();
	}

	@Test
	void anExplicitlyRevokedTokenIsRefused() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);

		this.mfaService.revokeBootstrapTokens(admin, admin);

		assertThat(this.mfaService.beginEnrolment(admin, token)).isEmpty();
	}

	// --- D-152 step 5: enrolment is not binding -----------------------------

	@Test
	void handingOverTheSeedDoesNotBindTheFactor() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);

		assertThat(this.mfaService.beginEnrolment(admin, token)).isPresent();

		assertThat(this.mfaService.isBound(admin))
			.as("D-152 grants access only after a successful verification, "
					+ "not merely after enrolment")
			.isFalse();
	}

	@Test
	void aVerifiedCodeBindsTheFactorAndSpendsTheToken() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);
		String seed = this.mfaService.beginEnrolment(admin, token).orElseThrow();

		assertThat(this.mfaService.confirmEnrolment(admin, codeFor(seed))).isTrue();

		assertThat(this.mfaService.isBound(admin)).isTrue();
		assertThat(this.mfaService.beginEnrolment(admin, token))
			.as("the token is single use; a spent one must not enrol again")
			.isEmpty();
	}

	@Test
	void aWrongCodeNeitherBindsTheFactorNorSpendsTheToken() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);
		String seed = this.mfaService.beginEnrolment(admin, token).orElseThrow();

		assertThat(this.mfaService.confirmEnrolment(admin, "000000")).isFalse();
		assertThat(this.mfaService.isBound(admin)).isFalse();

		// The token survives, so a mistyped code does not strand the administrator.
		assertThat(this.mfaService.confirmEnrolment(admin, codeFor(seed))).isTrue();
	}

	@Test
	void verificationIsRefusedWhileTheFactorIsUnbound() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);
		String seed = this.mfaService.beginEnrolment(admin, token).orElseThrow();

		assertThat(this.mfaService.verify(admin, codeFor(seed)))
			.as("a seed that has never been confirmed is not a second factor")
			.isFalse();
	}

	// --- prerequisite 12: the code itself is single use ---------------------

	@Test
	void anAcceptedCodeCannotBeReplayedInsideItsOwnWindow() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);
		String seed = this.mfaService.beginEnrolment(admin, token).orElseThrow();
		String code = codeFor(seed);
		assertThat(this.mfaService.confirmEnrolment(admin, code)).isTrue();

		assertThat(this.mfaService.verify(admin, code))
			.as("within one window the same six digits would otherwise mint several "
					+ "individually-single-use approvals, or be replayed between steps")
			.isFalse();
	}

	@Test
	void enrolmentIsAudited() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);
		String seed = this.mfaService.beginEnrolment(admin, token).orElseThrow();
		this.mfaService.confirmEnrolment(admin, codeFor(seed));

		assertThat(auditTypesFor(admin))
			.as("the token's issuance and use are auditable events in their own right (D-152)")
			.contains("MFA_BOOTSTRAP_TOKEN_ISSUED", "MFA_BOOTSTRAP_TOKEN_USED", "MFA_ENROLLED");
	}

	@Test
	void theSeedIsNeverStoredInTheClear() {
		long admin = createPlatformAdmin();
		String token = this.mfaService.issueBootstrapToken(admin, admin);
		String seed = this.mfaService.beginEnrolment(admin, token).orElseThrow();

		byte[] stored = new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT seed_ciphertext FROM platform_admin_mfa WHERE platform_admin_id = ?",
				byte[].class, admin);

		assertThat(new String(stored, java.nio.charset.StandardCharsets.US_ASCII))
			.as("database access alone must not be enough to mint codes")
			.doesNotContain(seed);
	}

	// --- helpers ------------------------------------------------------------

	/** The code an authenticator app would show right now for this seed. */
	private static String codeFor(String base32Seed) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(Instant.now()));
	}

	private static byte[] fromBase32(String encoded) {
		final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bitsLeft = 0;
		for (char c : encoded.toCharArray()) {
			buffer = (buffer << 5) | alphabet.indexOf(c);
			bitsLeft += 5;
			if (bitsLeft >= 8) {
				out.write((buffer >> (bitsLeft - 8)) & 0xFF);
				bitsLeft -= 8;
			}
		}
		return out.toByteArray();
	}

	private List<String> auditTypesFor(long adminId) {
		return new JdbcTemplate(this.flywayDataSource).queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE platform_admin_id = ?",
				String.class, adminId);
	}

	private void expireBootstrapTokens(long adminId) {
		new JdbcTemplate(this.flywayDataSource).update(
				"UPDATE platform_admin_mfa_bootstrap_tokens SET expires_at = ? WHERE platform_admin_id = ?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(60)), adminId);
	}

	private long createPlatformAdmin() {
		return new JdbcTemplate(this.flywayDataSource).queryForObject(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, true) RETURNING id",
				Long.class, "+95" + System.nanoTime(), this.passwordEncoder.encode("irrelevant"));
	}

}
