package com.workin.backend;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

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
	protected static final String TEST_RUNTIME_DB_USERNAME = "app_runtime_test";
	protected static final String TEST_RUNTIME_DB_PASSWORD = "app_runtime_test_password";

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

	@ServiceConnection
	protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> TEST_JWT_SECRET);
		registry.add("app.runtime-db.username", () -> TEST_RUNTIME_DB_USERNAME);
		registry.add("app.runtime-db.password", () -> TEST_RUNTIME_DB_PASSWORD);
		registry.add("app.platform-admin.mfa.encryption-key", () -> TEST_MFA_ENCRYPTION_KEY);
	}

}
