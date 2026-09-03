package com.workin.backend.platformadmin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0015 prerequisite 3, end to end.
 *
 * <p>The prerequisite is specific about what a real test has to show, so each
 * of its clauses gets one: that the budget is spent by attempts against
 * <em>unknown</em> identifiers too, that it lives in shared state rather than a
 * worker's heap, and that spending it refuses a subsequently-correct password.
 */
class PlatformAdminLoginThrottleTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct horse battery staple";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PlatformAdminLoginAttemptCleanup cleanup;

	@Test
	void spendingTheBudgetRefusesEvenTheCorrectPassword() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);

		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS; attempt++) {
			assertThat(login(phone, "wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		assertThat(login(phone, PASSWORD).getStatusCode())
			.as("the budget must bound guessing regardless of whether the guess is finally right")
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void attemptsAgainstAnUnknownIdentifierConsumeTheSameBudget() {
		String phone = uniquePhone();

		// No administrator exists yet: every one of these is a "miss".
		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS; attempt++) {
			assertThat(login(phone, "wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		// The administrator appears afterwards with the correct password. If
		// misses had been free, the budget would be untouched and this would
		// succeed -- which is exactly the hole the prerequisite describes.
		createPlatformAdmin(phone);

		assertThat(login(phone, PASSWORD).getStatusCode())
			.as("attempts against an identifier that is not an administrator must not be free")
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void theBudgetIsSharedStateNotOneWorkersMemory() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);

		// Written directly, as another worker or an earlier process would have.
		// This instance has served no request for this identifier.
		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);
		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS; attempt++) {
			jdbc.update("INSERT INTO platform_admin_login_attempts (identifier_hash, attempted_at) "
					+ "VALUES (?, ?)", sha256(phone), java.sql.Timestamp.from(Instant.now()));
		}

		assertThat(login(phone, PASSWORD).getStatusCode())
			.as("a budget held in a worker's heap would not see these and would let the login through")
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void attemptsOlderThanTheWindowDoNotCount() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);

		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);
		Instant expired = Instant.now().minus(PlatformAdminLoginThrottle.WINDOW).minusSeconds(60);
		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS * 2; attempt++) {
			jdbc.update("INSERT INTO platform_admin_login_attempts (identifier_hash, attempted_at) "
					+ "VALUES (?, ?)", sha256(phone), java.sql.Timestamp.from(expired));
		}

		assertThat(login(phone, PASSWORD).getStatusCode())
			.as("a window that never forgets is a permanent lockout, not a throttle")
			.isEqualTo(HttpStatus.OK);
	}

	@Test
	void aSuccessfulLoginClearsTheBudget() {
		String phone = uniquePhone();
		createPlatformAdmin(phone);

		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS - 1; attempt++) {
			login(phone, "wrong");
		}
		assertThat(login(phone, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(recordedAttempts(phone))
			.as("a caller who proved the password was not guessing; leaving the count "
					+ "standing locks them out after a few typos")
			.isZero();
	}

	@Test
	void theIdentifierIsNotStoredInPlaintext() {
		String phone = uniquePhone();

		login(phone, "wrong");

		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);
		Integer plaintext = jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_login_attempts WHERE identifier_hash = ?",
				Integer.class, phone);
		assertThat(plaintext)
			.as("an unauthenticated caller chooses this value; it must not be stored verbatim")
			.isZero();
		assertThat(recordedAttempts(phone)).isOne();
	}

	@Test
	void expiredAttemptsArePurgedRatherThanAccumulating() {
		String phone = uniquePhone();
		JdbcTemplate jdbc = new JdbcTemplate(this.flywayDataSource);
		Instant expired = Instant.now().minus(PlatformAdminLoginThrottle.WINDOW).minusSeconds(60);
		jdbc.update("INSERT INTO platform_admin_login_attempts (identifier_hash, attempted_at) "
				+ "VALUES (?, ?)", sha256(phone), java.sql.Timestamp.from(expired));
		assertThat(recordedAttempts(phone)).isOne();

		this.cleanup.purgeExpiredAttempts();

		assertThat(recordedAttempts(phone))
			.as("an unauthenticated caller controls how many identifiers appear here, "
					+ "so rows that can no longer affect a decision must not be kept")
			.isZero();
	}

	// --- helpers ------------------------------------------------------------

	private ResponseEntity<String> login(String phone, String password) {
		return this.restTemplate.postForEntity("/api/platform-admin/login",
				new PlatformAdminLoginRequest(phone, password), String.class);
	}

	private int recordedAttempts(String phone) {
		Integer count = new JdbcTemplate(this.flywayDataSource).queryForObject(
				"SELECT COUNT(*) FROM platform_admin_login_attempts WHERE identifier_hash = ?",
				Integer.class, sha256(phone));
		return count == null ? 0 : count;
	}

	private void createPlatformAdmin(String phone) {
		new JdbcTemplate(this.flywayDataSource).update(
				"INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, true)",
				phone, this.passwordEncoder.encode(PASSWORD));
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.strip().toLowerCase().getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String uniquePhone() {
		return "+98" + System.nanoTime();
	}

}
