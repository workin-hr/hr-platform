package com.workin.legacy.auth;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * D-049 Follow-up (c)'s atomicity proof, part 3: a failure forced
 * <em>after</em> {@link LegacyLoginService#login}'s {@code
 * token_version} bump has actually executed, but before the transaction
 * commits, must roll the whole transaction back -- not just the step
 * that failed.
 *
 * <p>Isolated in its own {@code @SpringBootTest} context (a distinct
 * Spring context from {@link LegacyLoginEndToEndTest}'s, because {@code
 * @MockitoSpyBean} changes the bean definition set): the {@link
 * JwtService} bean is wrapped in a Mockito spy and stubbed to throw
 * immediately after login has issued the refresh token and bumped
 * {@code token_version} -- both real writes, not mocked -- proving the
 * whole {@code @Transactional} method rolls back together rather than
 * leaving the bump committed with no usable session to match it.
 *
 * <p>{@code @MockitoSpyBean} (Spring Framework 6.2 / Spring Boot 4's
 * test-context bean override), not the older Boot-specific {@code
 * @SpyBean}: wraps the real {@link JwtService} bean so the four-argument
 * overload (unused by legacy login) and the real signing logic other
 * beans might exercise stay untouched; only the specific five-argument
 * {@code issueAccessToken} overload legacy login calls is stubbed to
 * throw.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyLoginServiceRollbackTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
	private static final String KNOWN_PASSWORD = "Secret123!";
	private static final long EMPLOYEE_ID = 90601L;
	private static final String PHONE = "+201100090601";

	@Autowired
	private TestRestTemplate restTemplate;

	@MockitoSpyBean
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the login-rollback fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	private static void applySchema(String resourceName) throws Exception {
		String schema = readResource(resourceName);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			for (String statement : schema.split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	private static void seed() throws Exception {
		String hash = PASSWORD_ENCODER.encode(KNOWN_PASSWORD);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (9601, 'Rollback E2E Co', '+201000009601', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (9611, 9601, 'Rollback HQ', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, first_name, last_name, phone, role, password_hash,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (%1$d, 9601, 9611, 'Rollback', 'Fixture', '%2$s', 'employee', '%3$s',
					   1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00')
					""".formatted(EMPLOYEE_ID, PHONE, hash));
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyLoginServiceRollbackTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static long readTokenVersion() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT token_version FROM employees WHERE id = " + EMPLOYEE_ID)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static long countRefreshTokensForEmployee() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT COUNT(*) FROM legacy_refresh_tokens WHERE employee_id = " + EMPLOYEE_ID)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	@Test
	void aFailureAfterTheTokenVersionBumpRollsBackTheBumpAndLeavesNoUsableSession() throws Exception {
		long versionBeforeAttempt = readTokenVersion();
		assertThat(versionBeforeAttempt).isEqualTo(1L);

		doThrow(new RuntimeException("simulated failure after the token_version bump, before commit"))
				.when(jwtService)
				.issueAccessToken(anyLong(), anyLong(), anyLong(), anyString(), any());

		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/legacy/auth/login_employee", new LegacyLoginRequest(PHONE, KNOWN_PASSWORD), String.class);

		assertThat(response.getStatusCode().is5xxServerError())
				.describedAs("an exception past the bump, inside the one @Transactional login method, "
						+ "must surface as a server error, not a successful login")
				.isTrue();

		assertThat(readTokenVersion())
				.describedAs("the token_version bump must roll back with the rest of the failed "
						+ "transaction -- a bumped-but-unusable session must never be left behind")
				.isEqualTo(versionBeforeAttempt);

		assertThat(countRefreshTokensForEmployee())
				.describedAs("the refresh-token insert earlier in the same transaction must roll back "
						+ "too -- no orphaned, unusable session row left behind by the failed attempt")
				.isZero();
	}

}
