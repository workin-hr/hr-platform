package com.workin.legacy.auth;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.i18n.ApiErrorBody;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proof ADR-0013 / D-043 exists for: a real Spring Boot context,
 * booted with {@code phase1-mysql} active, against real MariaDB, over
 * real HTTP -- not the hand-wired {@code EntityManagerFactory} pattern
 * every legacy-side test before this one used, because there was no
 * live Spring context to boot into
 * ({@code LegacyEmployeeAdapterTest}'s own javadoc named this as
 * deferred work).
 *
 * <p>Covers punch-list item #9's login-controller contract end to end:
 * a successful login issues real tokens; representative
 * {@link LegacyLoginOutcome} failures (401 twice, 403) produce their
 * documented status. <b>Not covered: 409 {@code MULTIPLE_ACCOUNTS_SAME_PHONE}.</b>
 * The vendored schema's own {@code ADD UNIQUE KEY `phone` (`phone`)}
 * (`mysql_workin.schema.sql:1120`) makes two {@code employees} rows
 * sharing one phone value impossible to seed against real MariaDB --
 * confirmed empirically, not assumed: the attempt raises
 * {@code SQLIntegrityConstraintViolationException}. This does not mean
 * the outcome is wrong or unreachable in production (a constraint added
 * after historical duplicates existed would not retroactively remove
 * them), only that it cannot be honestly exercised against a freshly
 * seeded fixture here. {@code LegacyLoginResolverTest} already covers
 * this outcome at the pure decision-function level, which does not need
 * the database at all.
 *
 * <p><b>Also not covered, for a stronger reason: an employee referencing
 * a nonexistent {@code company_id}.</b> A code review flagged
 * {@code LegacyLoginController.toCandidate()}'s original unchecked
 * {@code IllegalStateException} on a missing company as a potential
 * unhandled-500 risk and proposed testing it by seeding a dangling
 * reference. Attempting that seed here failed with
 * {@code SQLIntegrityConstraintViolationException}:
 * {@code employees} carries a real, enforced foreign key,
 * {@code fk_employee_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE}
 * (`mysql_workin.schema.sql:1622-1624`) -- added via a later
 * {@code ALTER TABLE}, the same pattern as the phone unique key above,
 * which is why an earlier grep restricted to the {@code CREATE TABLE}
 * block missed it. The scenario is therefore not merely hard to seed
 * but structurally impossible against this schema: MariaDB refuses the
 * insert, and {@code ON DELETE CASCADE} means even a later-deleted
 * company takes its employees with it rather than orphaning them.
 * {@code toCandidate()} was still hardened to skip-and-log rather than
 * throw -- defensive depth against a defect class this schema happens
 * to rule out today, not a fix for a reachable production bug -- and
 * {@link LegacyLoginControllerTest} proves that defensive behaviour
 * with a mocked repository, where no such constraint applies.
 *
 * <p>It deliberately does <b>not</b> attempt a
 * subsequent authenticated request through {@code legacySecurityFilterChain}
 * against a protected resource -- no protected legacy business endpoint
 * exists yet (that is punch-list items #11-13), so there is nothing
 * genuine to call. {@code TenantScopeFilter}/{@code JwtAuthenticationFilter}/
 * {@code LegacyTenantContextService} composing correctly is proven at
 * the unit/component level already
 * ({@code TenantBindingEndToEndTest}, {@code LegacyTenantContextServiceTest});
 * asserting it again here against a manufactured endpoint would be a
 * lesser proof dressed up as the real one.
 *
 * <p>Item #10 (the forged-claim isolation attack) is the next step that
 * actually needs an authenticated call site to attack, and remains its
 * own follow-on.
 */
// classes = BackendApplication.class is required, not stylistic: this
// test lives under com.workin.legacy.auth, a sibling of com.workin.backend
// rather than a descendant, so @SpringBootTest's default upward package
// search never finds BackendApplication on its own.
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyLoginEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
	private static final String KNOWN_PASSWORD = "Secret123!";

	@Autowired
	private TestRestTemplate restTemplate;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the phase1-mysql end-to-end fixture", ex);
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

	/** Companies/employees for each {@link LegacyLoginOutcome} this test asserts. */
	private static void seed() throws Exception {
		String hash = PASSWORD_ENCODER.encode(KNOWN_PASSWORD);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (9001, 'E2E Active Co', '+201000009001', 'active', '2025-01-15 09:00:00'),
					  (9002, 'E2E Suspended Co', '+201000009002', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (9101, 9001, 'E2E HQ', 1, '2025-03-01 10:00:00'),
					  (9102, 9002, 'E2E HQ 2', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, first_name, last_name, phone, role, password_hash,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (90011, 9001, 9101, 'Login', 'Success', '+201100090011', 'employee', '%1$s',
					   1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00'),
					  (90041, 9002, 9102, 'Suspended', 'Co', '+201100090041', 'employee', '%1$s',
					   1, 1, 0, 'accepted', 1, '2025-04-01 08:00:00')
					""".formatted(hash));
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyLoginEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void asuccessfulLoginReturns200WithRealTokens() {
		ResponseEntity<LegacyAuthResponse> response = restTemplate.postForEntity(
				"/api/legacy/auth/login_employee",
				new LegacyLoginRequest("+201100090011", KNOWN_PASSWORD),
				LegacyAuthResponse.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		LegacyAuthResponse body = response.getBody();
		assertThat(body.accessToken()).isNotBlank();
		assertThat(body.refreshToken()).isNotBlank();
		assertThat(body.employeeId()).isEqualTo(90011L);
		assertThat(body.companyId()).isEqualTo(9001L);
	}

	@Test
	void anUnknownPhoneReturns401UserNotFound() {
		ResponseEntity<ApiErrorBody> response = restTemplate.postForEntity(
				"/api/legacy/auth/login_employee",
				new LegacyLoginRequest("+201199999999", KNOWN_PASSWORD),
				ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().code()).isEqualTo("user_not_found");
	}

	@Test
	void awrongPasswordReturns401IncorrectPassword() {
		ResponseEntity<ApiErrorBody> response = restTemplate.postForEntity(
				"/api/legacy/auth/login_employee",
				new LegacyLoginRequest("+201100090011", "not the password"),
				ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().code()).isEqualTo("incorrect_password");
	}

	@Test
	void anEmployeeOfASuspendedCompanyReturns403CompanyAccountNotActive() {
		ResponseEntity<ApiErrorBody> response = restTemplate.postForEntity(
				"/api/legacy/auth/login_employee",
				new LegacyLoginRequest("+201100090041", KNOWN_PASSWORD),
				ApiErrorBody.class);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().code()).isEqualTo("company_account_not_active");
	}

}
