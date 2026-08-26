package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

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
 * Punch-list item #10, re-pointed for PR 12.1 (D-045's Follow-up):
 * {@code LegacyIsolationProbeController} (test-only, never shipped) is
 * deleted now that {@code attendance_exception_types} is a real,
 * guarded business endpoint -- this proves the same forged-claim
 * guarantee against it instead.
 *
 * <p><b>Why the forged-claim tests assert 401 {@code
 * unauthorized_invalid_token}, not 200-with-empty and not {@code
 * session_replaced}.</b> {@code LegacyRequestGuard.requireAuth()}'s
 * {@code token_version} check (P-7) is deliberately tenant-blind (see
 * that class's javadoc) -- it matches legacy's own {@code
 * requireEmployeeSessionValid()}, which carries no {@code company_id}
 * predicate, so a forged-tenant-claim request with an otherwise genuine,
 * current session passes that specific check. Tenant-claim forgery is
 * caught by a separate, later check in the same method: {@code
 * requireAuth} also requires {@code TenantScope.isEstablished()} --
 * true only when {@code LegacyTenantContextService.validate} already
 * re-derived and cross-checked this exact claim during {@code
 * SecurityConfig}'s resolver -- before it will hand back a company id
 * for the controller to use. A forged claim never establishes scope, so
 * it is refused here, before the query ever runs -- stricter than the
 * old probe-based 200-with-empty (which relied solely on the {@code
 * NO_TENANT}-bound filter), and the reason a business endpoint needed
 * its own guard rather than the probe's bare tenant-filtered read.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyTenantContextIsolationTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String JWT_SECRET = "test-only-secret-not-used-in-production-000000000000";
	// Wave 12.R (D-107) re-point: attendance_exception_types moved off /api/legacy/** onto its
	// literal /apis/api/attendance_exception_types/*.php routes with the D-074 envelope.
	private static final String EXCEPTION_TYPES_PATH = "/apis/api/attendance_exception_types/list.php";

	private static final long COMPANY_A = 9201L;
	private static final long COMPANY_B = 9202L;
	private static final long EMPLOYEE_A = 92011L;
	private static final long EMPLOYEE_B = 92021L;

	private final SecretKey testSigningKey = Keys.hmacShaKeyFor(JWT_SECRET.getBytes());

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private LegacyPhpJwtService legacyPhpJwtService;

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

	/** Two companies, one employee each -- the attacker (A) and the victim (B); A also owns one exception type, so the honest-path test proves real data, not just a non-empty page. */
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
			st.execute("""
					INSERT INTO exception_types (id, company_id, name, is_active, created_at, updated_at) VALUES
					  (9701, 9201, 'Sick Leave A', 1, '2025-04-01 08:00:00', '2025-04-01 08:00:00')
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
	 * membership_id} claim honest but its {@code tenant_id} claim
	 * pointing at company B.
	 */
	@Test
	void aTokenClaimingAnotherCompanysTenancyIsRejected() {
		String forgedToken = employeeToken(EMPLOYEE_A, EMPLOYEE_A, COMPANY_B, 1L);

		ResponseEntity<Map> response = restTemplate.exchange(
				EXCEPTION_TYPES_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(forgedToken)), Map.class);

		assertThat(response.getStatusCode().value())
				.describedAs("the forged tenant claim must not be trusted -- denied before the query runs")
				.isEqualTo(401);
		// D-107: attendance_exception_types is now a D-074-retrofitted module, so requireAuth()'s
		// ApiException renders through LegacyWireExceptionHandler's PHP envelope ({success,message}),
		// not the platform's {code,message} ApiErrorBody -- the message text is the wire contract now.
		assertThat(response.getBody().get("message")).isEqualTo("Unauthorized — invalid or expired token");
	}

	/** The independent guard clause: {@code sub} is employee A, but {@code membership_id} claims employee B's row. */
	@Test
	void aTokenClaimingSomeoneElsesEmployeeRowAsItsOwnMembershipIsRejected() {
		String forgedToken = employeeToken(EMPLOYEE_A, EMPLOYEE_B, COMPANY_B, 1);

		ResponseEntity<Map> response = restTemplate.exchange(
				EXCEPTION_TYPES_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(forgedToken)), Map.class);

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().get("message")).isEqualTo("Unauthorized — invalid or expired token");
	}

	/**
	 * {@code JwtService} is shared, unforked code -- re-proves {@code
	 * legacySecurityFilterChain} wires {@code apiSecurityErrorHandler}
	 * rather than falling back to Spring Security's default (issue #70).
	 * Unaffected by P-7: this token never reaches {@code
	 * JwtAuthenticationFilter}'s success path at all.
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
				EXCEPTION_TYPES_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(malformedToken)), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(401);
	}

	/**
	 * The control case: an honest, unforged token with a genuine {@code
	 * token_version} claim reads exactly its own company's data.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void aGenuineTokenReadsOnlyItsOwnCompanysData() {
		String honestToken = employeeToken(EMPLOYEE_A, EMPLOYEE_A, COMPANY_A, 1L);

		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				EXCEPTION_TYPES_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(honestToken)),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) response.getBody().get("data");
		assertThat(rows).extracting(row -> row.get("name")).containsExactly("Sick Leave A");
	}

	/**
	 * Regression: every test above forges/signs a transitional (jjwt)
	 * token via {@link JwtService}. With this suite's 52-byte test secret,
	 * {@code Keys.hmacShaKeyFor} selects HS384, so those tokens are decoded
	 * by {@code LegacyPhpJwtAuthenticationFilter}'s transitional branch,
	 * never by {@link LegacyPhpJwtService#decode} (always HMAC-SHA256) --
	 * meaning the tenant-isolation guarantee was previously proven against
	 * a token type real mobile/desktop/admin clients do not send. These
	 * two pin the same guarantees against a genuine frozen-PHP token.
	 */
	@Test
	void aForgedPhpEmployeeTokenClaimingAnotherCompanysTenancyIsRejected() {
		String forgedToken = legacyPhpJwtService.issueEmployeeToken(EMPLOYEE_A, COMPANY_B, "employee", 1L);

		ResponseEntity<Map> response = restTemplate.exchange(
				EXCEPTION_TYPES_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(forgedToken)), Map.class);

		assertThat(response.getStatusCode().value())
				.describedAs("a genuine PHP-signed token with a forged company_id claim must not be trusted")
				.isEqualTo(401);
	}

	@Test
	@SuppressWarnings("unchecked")
	void aGenuinePhpEmployeeTokenReadsOnlyItsOwnCompanysData() {
		String honestToken = legacyPhpJwtService.issueEmployeeToken(EMPLOYEE_A, COMPANY_A, "employee", 1L);

		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				EXCEPTION_TYPES_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(honestToken)),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) response.getBody().get("data");
		assertThat(rows).extracting(row -> row.get("name")).containsExactly("Sick Leave A");
	}

	/**
	 * A genuine {@code type=company} PHP token has no separate ground-truth
	 * identity to forge against (unlike an employee token's {@code
	 * membership_id}) -- its tenant *is* its claimed {@code company_id} --
	 * so the equivalent guarantee to pin is that it derives scope from,
	 * and is confined to, that company like any other token type.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void aGenuinePhpCompanyTokenReadsOnlyItsOwnCompanysData() {
		String companyToken = legacyPhpJwtService.issueCompanyToken(COMPANY_A, "company_admin");

		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				EXCEPTION_TYPES_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(companyToken)),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) response.getBody().get("data");
		assertThat(rows).extracting(row -> row.get("name")).containsExactly("Sick Leave A");
	}

	private String employeeToken(long identityId, long membershipId, long companyId, long tokenVersion) {
		return jwtService.issueAccessToken(
				identityId, membershipId, companyId, "test-session",
				Map.of("role", "employee", "token_version", tokenVersion));
	}

	private HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

}
