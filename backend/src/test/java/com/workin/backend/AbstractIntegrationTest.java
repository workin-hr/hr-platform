package com.workin.backend;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.workin.legacy.LegacyMariaDb;

/**
 * Shared real-Postgres integration test base, mirroring the H2 spike's
 * proven setup (docs/migration/technical-spike-plan.md's "Full Spike
 * Findings"). {@code @ServiceConnection} populates the same
 * {@code JdbcConnectionDetails} abstraction PostgresPersistenceConfig reads in
 * production, wired here to the ephemeral test container instead.
 *
 * <p>Deliberately the "singleton container" pattern, not
 * {@code @Testcontainers}/{@code @Container}: those annotations stop the
 * container after <em>each test class</em> finishes, even for a static
 * field inherited from this common base -- with four independent
 * subclasses that produced repeated, hard-to-diagnose
 * {@code ConnectException}s once a later class tried to reuse an
 * already-stopped container. Starting it once, here, and never calling
 * {@code stop()} lets Testcontainers' own Ryuk reaper clean it up at JVM
 * exit instead, exactly as the official singleton-container guidance
 * recommends for a container meant to be shared across multiple test
 * classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class AbstractIntegrationTest {

	protected static final String TEST_JWT_SECRET = "test-only-secret-not-used-in-production-000000000000";

	/**
	 * The platform-admin TOTP seed key, generated once per JVM run.
	 *
	 * <p>Generated rather than written down: a base64 key literal in source is
	 * indistinguishable from a leaked one, and the repository's secret scanner
	 * flags it. Registered here rather than per test class so every context
	 * shares one value -- a per-class key gives each class its own property set,
	 * which means its own application context, which means its own connection
	 * pools, and enough of those exhaust Postgres and fail with an unrelated
	 * "unable to determine dialect".
	 */
	protected static final String TEST_MFA_ENCRYPTION_KEY = generateMfaKey();

	private static String generateMfaKey() {
		byte[] key = new byte[32];
		new java.security.SecureRandom().nextBytes(key);
		return java.util.Base64.getEncoder().encodeToString(key);
	}

	/** A database of this class's own, inside the suite's one MariaDB. */
	protected static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> TEST_JWT_SECRET);
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.mfa.encryption-key", () -> TEST_MFA_ENCRYPTION_KEY);
	}


	/**
	 * An instant as the application stores it in a MariaDB {@code DATETIME}.
	 *
	 * <p>Hibernate maps an {@code Instant} field to the column as its UTC
	 * wall-clock text. A test that writes {@code java.sql.Timestamp.from(instant)}
	 * through a raw {@code JdbcTemplate} gets the driver's conversion instead,
	 * which uses the JVM's zone -- so a row the test meant to date sixty
	 * seconds ago landed two or three hours in the future, and every "expired"
	 * fixture was still valid. Passing a {@code LocalDateTime} sends the text
	 * as-is, in the zone the application will read it back in.
	 */
	protected static LocalDateTime storedAs(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
