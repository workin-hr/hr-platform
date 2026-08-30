package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@SuppressWarnings({"rawtypes", "unchecked"})
class LegacyLoginEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
	private static final String KNOWN_PASSWORD = "Secret123!";
	private static final String LOGIN = "/apis/api/auth/login_employee.php";
	private static final String SECRET = "test-only-secret-not-used-in-production-000000000000";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyPhpJwtService jwtService;

	@Autowired
	private JwtService transitionalJwtService;

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
		registry.add("app.jwt.secret", () -> SECRET);
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
			// One row per company, distinguishable by name. Without a row for the
			// *other* company these tests cannot tell correct tenant re-derivation
			// from none at all: an empty list is the same 200 either way.
			st.execute("""
					INSERT INTO exception_types (id, company_id, name, is_active, created_at) VALUES
					  (9201, 9001, 'E2E Type Of Company 9001', 1, '2025-05-01 08:00:00'),
					  (9202, 9002, 'E2E Type Of Company 9002', 1, '2025-05-01 08:00:00')
					""");
		}
	}

	@Test
	void successfulLoginIsWireCompatibleWithFrozenPhp() throws Exception {
		ResponseEntity<Map> response = login("+201100090011", KNOWN_PASSWORD);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);

		Map data = (Map) response.getBody().get("data");
		assertThat(data.keySet()).containsExactlyInAnyOrder("token", "employee");
		assertThat(data).doesNotContainKey("refresh_token");

		Map employee = (Map) data.get("employee");
		assertThat(employee).doesNotContainKeys("password_hash", "token_version");
		assertThat(((Number) employee.get("id")).longValue()).isEqualTo(90011L);
		assertThat(((Number) employee.get("company_id")).longValue()).isEqualTo(9001L);

		String rawToken = (String) data.get("token");
		LegacyPhpJwtService.DecodedToken decoded = jwtService.decode(rawToken);
		assertThat(decoded).isNotNull();
		assertThat(decoded.type()).isEqualTo("employee");
		assertThat(decoded.employeeId()).isEqualTo(90011L);
		assertThat(decoded.companyId()).isEqualTo(9001L);
		assertThat(decoded.role()).isEqualTo("employee");
		assertThat(decoded.tokenVersion()).isEqualTo(readTokenVersion(90011L));
		assertThat(decoded.payload().keySet()).isEqualTo(
				Set.of("type", "employee_id", "company_id", "role", "token_version", "exp"));
	}

	@Test
	void frozenPhpCompanyAdminTokenAuthenticatesAProtectedMigratedRoute() {
		String companyToken = jwtService.issueCompanyToken(9001L, "company_admin");
		LegacyPhpJwtService.DecodedToken decoded = jwtService.decode(companyToken);
		assertThat(decoded.payload().keySet()).isEqualTo(Set.of("type", "company_id", "role", "exp"));

		ResponseEntity<Map> response = listExceptionTypesWith(companyToken);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);
	}

	/**
	 * {@code decode()} had no shape check before this fix, so a transitional Java token --
	 * signed with the same {@code app.jwt.secret} HS256 key {@code issueEmployeeToken()}/
	 * {@code issueCompanyToken()} use, but shaped completely differently ({@code sub}/{@code
	 * membership_id}/{@code tenant_id}/{@code token_version}, no {@code type}) -- passed
	 * signature and {@code exp} verification and decoded to a non-null {@link
	 * LegacyPhpJwtService.DecodedToken} with every field empty or zero. {@link
	 * LegacyPhpJwtAuthenticationFilter} took that as a PHP-authenticated principal
	 * (identity/company id 0, type {@code ""}) instead of falling through to {@code
	 * setTransitionalAuthentication()} for the real transitional principal (PR #120 review).
	 */
	@Test
	void aTransitionalJavaTokenDoesNotDecodeAsAZeroIdentityPhpToken() {
		String transitionalToken = transitionalJwtService.issueAccessToken(
				90011L, 90011L, 9001L, "test-session", Map.of("role", "company_admin", "token_version", 1L));

		assertThat(jwtService.decode(transitionalToken)).isNull();
	}

	@Test
	void unknownPhoneAndWrongPasswordPreservePhp401Outcomes() {
		ResponseEntity<Map> unknown = login("+201199999999", KNOWN_PASSWORD);
		assertThat(unknown.getStatusCode().value()).isEqualTo(401);
		assertThat(unknown.getBody().get("success")).isEqualTo(false);

		ResponseEntity<Map> wrong = login("+201100090011", "not the password");
		assertThat(wrong.getStatusCode().value()).isEqualTo(401);
		assertThat(wrong.getBody().get("success")).isEqualTo(false);
	}

	@Test
	void suspendedCompanyPreservesPhp403Outcome() {
		ResponseEntity<Map> response = login("+201100090041", KNOWN_PASSWORD);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().get("success")).isEqualTo(false);
	}

	@Test
	void wrongMethodIsRejectedBeforeAuthenticationExactlyLikePhp() {
		ResponseEntity<Map> response = restTemplate.exchange(LOGIN, HttpMethod.GET, HttpEntity.EMPTY, Map.class);
		assertThat(response.getStatusCode().value()).isEqualTo(405);
		assertThat(response.getBody().get("success")).isEqualTo(false);
	}

	@Test
	void missingPasswordUsesPhpFieldRequiredEnvelope() {
		ResponseEntity<Map> response = restTemplate.postForEntity(
				LOGIN, Map.of("phone", "+201100090011"), Map.class);
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		assertThat(String.valueOf(response.getBody().get("message"))).contains("password");
	}

	@Test
	void secondLoginInvalidatesFirstTokenAndNewPhpTokenAuthenticatesProtectedRoute() {
		String firstToken = token(login("+201100090011", KNOWN_PASSWORD));
		String secondToken = token(login("+201100090011", KNOWN_PASSWORD));
		assertThat(firstToken).isNotEqualTo(secondToken);

		ResponseEntity<Map> rejected = listExceptionTypesWith(firstToken);
		assertThat(rejected.getStatusCode().value()).isEqualTo(401);
		assertThat(rejected.getBody().get("success")).isEqualTo(false);

		ResponseEntity<Map> accepted = listExceptionTypesWith(secondToken);
		assertThat(accepted.getStatusCode().value()).isEqualTo(200);
	}

	/**
	 * The rollback direction, over the real path rather than the codec.
	 *
	 * <p>{@code LegacyPhpJwtWireCompatibilityTest} proves the codec accepts a
	 * PHP-minted token. That is not sufficient for G11's claim: a live request
	 * also traverses {@code LegacyPhpJwtAuthenticationFilter}, tenant
	 * re-derivation and {@code LegacyRequestGuard}'s token-version and role
	 * checks. A regression in any of those would leave the codec test green
	 * while every pre-cutover session broke.
	 *
	 * <p>The token here is encoded by {@link PhpJwtOracle}, never by the
	 * production service, so this is a genuine PHP token as far as the
	 * application can tell -- exactly what a user holds when the cutover
	 * happens mid-session.
	 */
	@Test
	void aPhpMintedEmployeeTokenAuthenticatesAProtectedRouteOverRealHttp() throws Exception {
		String phpToken = PhpJwtOracle.encode(
				PhpJwtOracle.employeePayload(
						90011L, 9001L, "employee", readTokenVersion(90011L),
						PhpJwtOracle.tenYearsFromNow()),
				SECRET);

		ResponseEntity<Map> response = listExceptionTypesWith(phpToken);
		assertThat(response.getStatusCode().value())
				.as("a session PHP issued before cutover must keep working after it")
				.isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);
		assertThat(namesIn(response))
				.as("the company must be re-derived from the token, not guessed or dropped")
				.containsExactly("E2E Type Of Company 9001");
	}

	@Test
	void aPhpMintedCompanyTokenAuthenticatesAProtectedRouteOverRealHttp() {
		String phpToken = PhpJwtOracle.encode(
				PhpJwtOracle.companyPayload(9001L, "company_admin", PhpJwtOracle.tenYearsFromNow()),
				SECRET);

		ResponseEntity<Map> response = listExceptionTypesWith(phpToken);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);
		assertThat(namesIn(response)).containsExactly("E2E Type Of Company 9001");
	}

	/**
	 * The claim is not the tenant. Employee 90011 belongs to company 9001, so
	 * every other test here has a token whose {@code company_id} claim happens
	 * to agree with the database — which means none of them can tell tenant
	 * re-derivation from simply trusting the claim.
	 *
	 * <p>This one forges the disagreement: a validly signed token for employee
	 * 90011 asserting company 9002. If the guard trusted the claim it would
	 * serve 9002's data; because {@code LegacyTenantContextService} re-derives
	 * the company from {@code employee_id} and cross-checks it, the request is
	 * refused instead.
	 */
	@Test
	void aForgedCompanyClaimIsRefusedRatherThanTrusted() throws Exception {
		String forged = PhpJwtOracle.encode(
				PhpJwtOracle.employeePayload(
						90011L, 9002L, "employee", readTokenVersion(90011L),
						PhpJwtOracle.tenYearsFromNow()),
				SECRET);

		ResponseEntity<Map> response = listExceptionTypesWith(forged);
		assertThat(response.getStatusCode().value())
				.as("the company must come from the database, never from the token")
				.isNotEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(false);
	}

	/**
	 * Falsifies the two tests above: if the guard were not actually engaged on
	 * this route, they would pass for the wrong reason. A stale
	 * {@code token_version} must still be rejected even though the signature is
	 * valid, which is what makes their 200 evidence of anything.
	 */
	@Test
	void aPhpMintedTokenWithAStaleTokenVersionIsStillRejected() throws Exception {
		String stale = PhpJwtOracle.encode(
				PhpJwtOracle.employeePayload(
						90011L, 9001L, "employee", readTokenVersion(90011L) - 1,
						PhpJwtOracle.tenYearsFromNow()),
				SECRET);

		ResponseEntity<Map> response = listExceptionTypesWith(stale);
		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody().get("success")).isEqualTo(false);
	}

	private ResponseEntity<Map> login(String phone, String password) {
		return restTemplate.postForEntity(LOGIN, Map.of("phone", phone, "password", password), Map.class);
	}

	private static String token(ResponseEntity<Map> response) {
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		Map data = (Map) response.getBody().get("data");
		return (String) data.get("token");
	}

	private static java.util.List<String> namesIn(ResponseEntity<Map> response) {
		Object data = response.getBody().get("data");
		java.util.List<Map> rows = data instanceof Map map
				? (java.util.List<Map>) map.get("items")
				: (java.util.List<Map>) data;
		return rows.stream().map(row -> String.valueOf(row.get("name"))).toList();
	}

	private ResponseEntity<Map> listExceptionTypesWith(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return restTemplate.exchange(
				"/apis/api/attendance_exception_types/list.php", HttpMethod.GET,
				new HttpEntity<>(headers), Map.class);
	}

	private static long readTokenVersion(long employeeId) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT token_version FROM employees WHERE id = " + employeeId)) {
			rs.next();
			return rs.getLong(1);
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
}
