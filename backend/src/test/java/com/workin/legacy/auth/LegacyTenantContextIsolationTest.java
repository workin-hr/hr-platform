package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Punch-list item #10: the legacy-side port of
 * {@code com.workin.backend.tenancy.TenantContextIsolationTest}'s
 * guarantee, now that item #9 gives it a real HTTP stack to attack --
 * {@code JwtAuthenticationFilter} to {@code legacySecurityFilterChain}'s
 * resolver to {@code LegacyTenantContextService} to
 * {@code TenantScopeFilter} to {@code TenantAwareJpaTransactionManager},
 * against real MariaDB.
 *
 * <p>ADR-0012 item 3 requires every {@code RlsFailClosedTest}/
 * {@code TenantContextIsolationTest} scenario ported assertion-for-
 * assertion. The RLS-fail-closed side was ported first
 * ({@code TenantFilterFailClosedTest}, an un-enabled filter restricts
 * nothing); this is the other half -- a validly-signed token whose
 * claims do not match what the authenticated identity actually owns
 * must still be refused, not trusted.
 *
 * <p>No protected legacy business endpoint exists yet (items #11-13),
 * so {@link LegacyIsolationProbeController} (test-only, {@code
 * src/test}, never shipped) stands in as the tenant-owned resource
 * behind the chain -- the same move
 * {@code com.workin.legacy.TenantBindingEndToEndTest} made to prove the
 * honest-path binding before any real module existed, extended here to
 * the adversarial case and driven over real HTTP instead of a manually
 * constructed filter invocation.
 *
 * <p>Unlike the PostgreSQL original, a rejected claim here does not
 * surface as a 404 from the controller -- {@code
 * legacySecurityFilterChain}'s resolver catches {@code
 * NoTenantScopeException} itself and resolves to {@code
 * Optional.empty()} (SecurityConfig's own javadoc), so {@code
 * TenantScopeFilter} never establishes scope and the request reaches
 * the controller anyway, authenticated but unscoped. What proves the
 * claim was never trusted is what the controller then reads: {@code
 * TenantAwareJpaTransactionManager} binds the {@code NO_TENANT}
 * sentinel to the unscoped transaction, so the query returns zero rows
 * -- not company B's data, and not company A's either, since scope was
 * never established for either. A 200 with an empty body is therefore
 * the correct assertion, not a 403/404.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyTenantContextIsolationTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String JWT_SECRET = "test-only-secret-not-used-in-production-000000000000";
	private static final String PROBE_PATH = "/api/legacy/test/probe/employee-ids";

	private static final long COMPANY_A = 9201L;
	private static final long COMPANY_B = 9202L;
	private static final long EMPLOYEE_A = 92011L;
	private static final long EMPLOYEE_B = 92021L;

	private final SecretKey testSigningKey = Keys.hmacShaKeyFor(JWT_SECRET.getBytes());

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the tenant-isolation-attack fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> JWT_SECRET);
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

	/** Two companies, one employee each -- the attacker (A) and the victim (B). */
	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (9201, 'Isolation Co A', '+201000009201', 'active', '2025-01-15 09:00:00'),
					  (9202, 'Isolation Co B', '+201000009202', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (9301, 9201, 'A HQ', 1, '2025-03-01 10:00:00'),
					  (9302, 9202, 'B HQ', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, first_name, last_name, phone, role,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (92011, 9201, 9301, 'Attacker', 'A', '+201100092011', 'employee', 1, 1, 0,
					   'accepted', 1, '2025-04-01 08:00:00'),
					  (92021, 9202, 9302, 'Victim', 'B', '+201100092021', 'employee', 1, 1, 0,
					   'accepted', 1, '2025-04-01 08:00:00')
					""");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyTenantContextIsolationTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * The primary attack this branch exists to close: a validly-signed
	 * token for a real identity (employee A), with its {@code
	 * membership_id} claim honest (matches D-042's issuance decision --
	 * a legacy token always sets it equal to the employee id at issuance)
	 * but its {@code tenant_id} claim pointing at company B -- exactly
	 * what a tampered token, or a bug that trusts the claim directly,
	 * would produce.
	 */
	@Test
	void aTokenClaimingAnotherCompanysTenancyIsRejected() {
		String forgedToken = jwtService.issueAccessToken(EMPLOYEE_A, EMPLOYEE_A, COMPANY_B, "test-session");

		ResponseEntity<Long[]> response = probeWith(forgedToken);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody())
				.describedAs("the forged tenant claim must not be trusted -- not company B's data, "
						+ "and not company A's either, since scope was never actually established")
				.isEmpty();
	}

	/**
	 * The independent guard clause: a validly-signed token whose {@code
	 * sub} is employee A, but whose {@code membership_id} claims employee
	 * B's row -- refused even though {@code tenant_id} correctly names
	 * B's own company, because the row does not belong to the
	 * authenticated identity.
	 */
	@Test
	void aTokenClaimingSomeoneElsesEmployeeRowAsItsOwnMembershipIsRejected() {
		String forgedToken = jwtService.issueAccessToken(EMPLOYEE_A, EMPLOYEE_B, COMPANY_B, "test-session");

		ResponseEntity<Long[]> response = probeWith(forgedToken);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).isEmpty();
	}

	/**
	 * {@code JwtService} is shared, unforked code -- this is the same
	 * fail-closed-not-crashing property
	 * {@code TenantContextIsolationTest} already proves on the
	 * PostgreSQL side, re-proven here to confirm
	 * {@code legacySecurityFilterChain} wires the same {@code
	 * apiSecurityErrorHandler} entry point rather than falling back to
	 * Spring Security's default (issue #70).
	 */
	@Test
	void aSignedTokenMissingATenantClaimFailsClosedInsteadOfCrashing() {
		String malformedToken = Jwts.builder()
				.subject(String.valueOf(EMPLOYEE_A))
				.claim("membership_id", EMPLOYEE_A)
				.issuer("workin-backend")
				.audience().add("workin-clients").and()
				.signWith(testSigningKey)
				.compact();

		ResponseEntity<String> response = restTemplate.exchange(
				PROBE_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(malformedToken)), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(401);
	}

	/**
	 * The control case: an honest, unforged token reads exactly its own
	 * company's data -- proving the probe endpoint and the honest path
	 * through the chain both actually work, so the adversarial tests
	 * above are proving a real guard, not an endpoint that rejects
	 * everything.
	 */
	@Test
	void aGenuineTokenReadsOnlyItsOwnCompanysData() {
		String honestToken = jwtService.issueAccessToken(EMPLOYEE_A, EMPLOYEE_A, COMPANY_A, "test-session");

		ResponseEntity<Long[]> response = probeWith(honestToken);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).containsExactly(EMPLOYEE_A);
	}

	private ResponseEntity<Long[]> probeWith(String token) {
		return restTemplate.exchange(
				PROBE_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(token)), Long[].class);
	}

	private HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

}
