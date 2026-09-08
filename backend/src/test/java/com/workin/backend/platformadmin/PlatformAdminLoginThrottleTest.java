package com.workin.backend.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;

/**
 * The login's miss budget (ADR-0015 prerequisite 4, kept under ADR-0018).
 *
 * <p>Charged per client rather than per account, because there is one account:
 * a budget on the identifier would let anyone lock the administrator out for
 * fifteen minutes at a time, indefinitely, from anywhere. Kept in the database
 * rather than a worker's memory, because a budget one worker cannot see is a
 * budget the next worker does not enforce.
 */
class PlatformAdminLoginThrottleTest extends AbstractIntegrationTest {

	@Autowired
	private PlatformAdminLoginService loginService;

	@Autowired
	private PlatformAdminLoginThrottle throttle;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	@BeforeEach
	void freshBudget() {
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM platform_admin_login_attempts");
	}

	private static String client() {
		return "203.0.113." + (System.nanoTime() % 200);
	}

	@Test
	void spendingTheBudgetRefusesEvenTheCorrectPassword() {
		String client = client();
		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS; attempt++) {
			assertThat(this.loginService.login("wrong", client)).isEmpty();
		}
		assertThat(this.loginService.login(TEST_ADMIN_PASSWORD, client))
			.as("a client that has spent its budget is refused whatever it sends")
			.isEmpty();
	}

	@Test
	void theBudgetIsPerClientNotPerAdministrator() {
		String guesser = client();
		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS; attempt++) {
			this.loginService.login("wrong", guesser);
		}
		assertThat(this.loginService.login(TEST_ADMIN_PASSWORD, "198.51.100.7"))
			.as("somebody else's misses must not lock the administrator out from everywhere")
			.isPresent();
	}

	@Test
	void aSuccessfulLoginClearsTheBudget() {
		String client = client();
		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS - 1; attempt++) {
			this.loginService.login("wrong", client);
		}
		assertThat(this.loginService.login(TEST_ADMIN_PASSWORD, client)).isPresent();
		assertThat(recordedAttempts(client)).as("cleared on success").isZero();
	}

	@Test
	void attemptsOlderThanTheWindowDoNotCount() {
		String client = client();
		Instant expired = Instant.now().minus(PlatformAdminLoginThrottle.WINDOW).minusSeconds(60);
		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS * 2; attempt++) {
			this.jdbc.update("INSERT INTO platform_admin_login_attempts (identifier_hash, attempted_at) "
					+ "VALUES (?, ?)", sha256("web:" + client), storedAs(expired));
		}
		assertThat(this.loginService.login(TEST_ADMIN_PASSWORD, client))
			.as("a window that never forgets is a permanent lockout, not a throttle")
			.isPresent();
	}

	@Test
	void theBudgetIsSharedStateNotOneWorkersMemory() {
		String client = client();
		// Rows written directly, as another worker would have written them.
		for (int attempt = 0; attempt < PlatformAdminLoginThrottle.MAX_ATTEMPTS; attempt++) {
			this.jdbc.update("INSERT INTO platform_admin_login_attempts (identifier_hash, attempted_at) "
					+ "VALUES (?, ?)", sha256("web:" + client), storedAs(Instant.now()));
		}
		assertThat(this.loginService.login(TEST_ADMIN_PASSWORD, client))
			.as("a budget held in a worker's heap would not see these and would let the login through")
			.isEmpty();
	}

	@Test
	void theClientIsNotStoredInPlaintext() {
		String client = client();
		this.loginService.login("wrong", client);
		assertThat(this.jdbc.queryForList(
				"SELECT identifier_hash FROM platform_admin_login_attempts", String.class))
			.isNotEmpty()
			.allSatisfy(stored -> assertThat(stored).doesNotContain(client));
	}

	@Test
	void expiredAttemptsArePurgedRatherThanAccumulating() {
		String client = client();
		Instant expired = Instant.now().minus(PlatformAdminLoginThrottle.WINDOW).minusSeconds(60);
		this.jdbc.update("INSERT INTO platform_admin_login_attempts (identifier_hash, attempted_at) "
				+ "VALUES (?, ?)", sha256("web:" + client), storedAs(expired));

		this.throttle.purgeExpired();

		assertThat(recordedAttempts(client))
			.as("an unauthenticated caller controls how many identifiers appear here, so rows that "
					+ "can no longer affect a decision must not be kept")
			.isZero();
	}

	private int recordedAttempts(String client) {
		Integer count = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_login_attempts WHERE identifier_hash = ?",
				Integer.class, sha256("web:" + client));
		return count == null ? 0 : count;
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

}
