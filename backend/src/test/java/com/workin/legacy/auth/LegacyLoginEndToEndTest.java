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

@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyLoginEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
	private static final String KNOWN_PASSWORD = "Secret123!";
	private static final String LOGIN = "/apis/api/auth/login_employee.php";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyPhpJwtService jwtService;

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

	@Test
	void successfulLoginIsWireCompatibleWithFrozenPhp() throws Exception {
		ResponseEntity<Map> response = login("+201100090011", KNOWN_PASSWORD);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);

		Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
		assertThat(data.keySet()).containsExactlyInAnyOrder("token", "employee");
		assertThat(data).doesNotContainKey("refresh_token");

		Map<?, ?> employee = (Map<?, ?>) data.get("employee");
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

	private ResponseEntity<Map> login(String phone, String password) {
		return restTemplate.postForEntity(LOGIN, Map.of("phone", phone, "password", password), Map.class);
	}

	private static String token(ResponseEntity<Map> response) {
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
		return (String) data.get("token");
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
