package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Proves {@code LegacyHrPermissionEnforcer} (punch-list item #11,
 * D-044) actually gates a real HTTP request through
 * {@code legacySecurityFilterChain}, over real MariaDB -- the same
 * "minimal test-scoped protected resource" move
 * {@code LegacyTenantContextIsolationTest} made for item #10, extended
 * here to {@link LegacyIsolationProbeController#permissionGatedEmployeeIds()}.
 *
 * <p>D-044 requires this to reproduce {@code hr_session_has_permission()}'s
 * real deny shape: a missing {@code hr_permissions} row denies by
 * default, exactly as covered at the unit level by
 * {@code com.workin.legacy.authorization.LegacyHrPermissionEnforcerTest}.
 * This class adds the one thing a unit test cannot: proof the check is
 * actually reached from a real request, with a real
 * {@code AuthenticatedPrincipal} supplied by the real chain, not a
 * hand-constructed one.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyHrPermissionEnforcerEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String PROBE_PATH = "/api/legacy/test/probe/permission-gated";

	private static final long COMPANY = 9401L;
	private static final long EMPLOYEE_WITH_PERMISSION = 94011L;
	private static final long EMPLOYEE_WITHOUT_PERMISSION = 94012L;

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
			throw new IllegalStateException("could not prepare the hr_permissions end-to-end fixture", ex);
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

	/**
	 * One company, two employees: one with {@code can_employees} granted,
	 * one with no {@code hr_permissions} row at all -- the real
	 * {@code hr_session_has_permission()} deny-by-default shape, not a
	 * flag explicitly set to zero.
	 */
	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (9401, 'Perm E2E Co', '+201000009401', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (9501, 9401, 'HQ', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, first_name, last_name, phone, role,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (94011, 9401, 9501, 'Granted', 'HR', '+201100094011', 'hr', 1, 1, 0,
					   'accepted', 1, '2025-04-01 08:00:00'),
					  (94012, 9401, 9501, 'Ungranted', 'HR', '+201100094012', 'hr', 1, 1, 0,
					   'accepted', 1, '2025-04-01 08:00:00')
					""");
			st.execute("""
					INSERT INTO hr_permissions
					  (id, employee_id, can_dashboard, can_recent_activities, can_branches,
					   can_departments, can_job_titles, can_shifts, can_leave_balances,
					   can_assets, can_advances, can_workforce_planning, can_salary_calculator,
					   can_company_settings, can_employees, can_attendance, can_requests,
					   can_payroll, can_penalties)
					VALUES
					  (9601, 94011, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0)
					""");
			// 94012 deliberately gets no hr_permissions row.
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyHrPermissionEnforcerEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void anEmployeeWithTheGrantedPermissionReads200() {
		String token = jwtService.issueAccessToken(
				EMPLOYEE_WITH_PERMISSION, EMPLOYEE_WITH_PERMISSION, COMPANY, "test-session");

		ResponseEntity<Long[]> response = probeWith(token);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).contains(EMPLOYEE_WITH_PERMISSION, EMPLOYEE_WITHOUT_PERMISSION);
	}

	/**
	 * {@code hr-legacy#8}'s real gap shape reproduced honestly: this is
	 * not a malformed or forged token -- it is a genuine, correctly
	 * tenant-scoped employee who simply has no {@code hr_permissions} row,
	 * exactly as legacy would deny them.
	 */
	@Test
	void anEmployeeWithNoHrPermissionsRowAtAllReads403() {
		String token = jwtService.issueAccessToken(
				EMPLOYEE_WITHOUT_PERMISSION, EMPLOYEE_WITHOUT_PERMISSION, COMPANY, "test-session");

		ResponseEntity<String> response = restTemplate.exchange(
				PROBE_PATH, HttpMethod.GET, new HttpEntity<>(headersFor(token)), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
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
