package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Proves {@code LegacyHrPermissionEnforcer} (punch-list item #11, D-044)
 * actually gates a real HTTP request through {@code
 * legacySecurityFilterChain}, over real MariaDB.
 *
 * <p>Re-pointed for PR 12.1 (D-045's Follow-up): the deleted {@code
 * LegacyIsolationProbeController} gated an invented {@code
 * CAN_EMPLOYEES} check no legacy endpoint actually enforces. {@code
 * POST attendance_exception_types/create.php} gates on {@code
 * can_company_settings} for real -- legacy's own {@code create.php}
 * calls {@code require_company_settings_access()}
 * (D-046/hr-legacy evidence) -- so this now proves the enforcer against
 * evidence-backed behaviour instead.
 *
 * <p>Re-pointed again for Wave 12.R (D-107): {@code
 * attendance_exception_types} moved off {@code /api/legacy/**} onto its
 * literal {@code /apis/api/attendance_exception_types/*.php} routes with
 * the D-074 envelope -- this still uses that module as its "any real
 * guarded business endpoint" example, just at the new address and shape.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyHrPermissionEnforcerEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String CREATE_PATH = "/apis/api/attendance_exception_types/create.php";

	private static final long COMPANY = 9401L;
	private static final long EMPLOYEE_WITH_PERMISSION = 94011L;
	private static final long EMPLOYEE_WITHOUT_PERMISSION = 94012L;

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
	 * One company, two HR employees: one with {@code can_company_settings}
	 * granted, one with no {@code hr_permissions} row at all -- the real
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
					  (9601, 94011, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
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
	@SuppressWarnings("unchecked")
	void anEmployeeWithTheGrantedPermissionCreates201() {
		String token = employeeToken(EMPLOYEE_WITH_PERMISSION);
		HttpHeaders headers = headersFor(token);
		headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				CREATE_PATH, org.springframework.http.HttpMethod.POST,
				new HttpEntity<>(Map.of("name", "Granted Path Type"), headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		assertThat(data.get("name")).isEqualTo("Granted Path Type");
	}

	/**
	 * {@code hr-legacy#8}'s real gap shape reproduced honestly: this is
	 * not a malformed or forged token -- it is a genuine, correctly
	 * tenant-scoped HR employee who simply has no {@code hr_permissions}
	 * row, exactly as legacy would deny them.
	 */
	@Test
	void anEmployeeWithNoHrPermissionsRowAtAllReads403() {
		String token = employeeToken(EMPLOYEE_WITHOUT_PERMISSION);

		ResponseEntity<String> response = restTemplate.exchange(
				CREATE_PATH, org.springframework.http.HttpMethod.POST,
				new HttpEntity<>(Map.of("name", "Denied Path Type"), headersFor(token)), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(403);
	}

	/**
	 * Regression for {@code hr_session_has_permission()}'s company-type
	 * bypass ({@code hr_permissions.php:143-153}): a {@code type=company}
	 * session is always allowed, no {@code hr_permissions} lookup at all --
	 * unlike either employee fixture above, both of which go through the
	 * lookup. Company 9401 has no {@code hr_permissions} row referencing
	 * its own id at all, so this also proves the enforcer isn't accidentally
	 * looking one up under the company id (see the class javadoc's
	 * "Company-type bypass" note on {@code identityId} carrying a company
	 * id, not an employee id, for this token type).
	 */
	@Test
	@SuppressWarnings("unchecked")
	void aCompanyTypeTokenBypassesTheHrPermissionsLookupEntirely() {
		String token = legacyPhpJwtService.issueCompanyToken(COMPANY, "company_admin");
		HttpHeaders headers = headersFor(token);
		headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				CREATE_PATH, org.springframework.http.HttpMethod.POST,
				new HttpEntity<>(Map.of("name", "Company Session Path Type"), headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode().value()).isEqualTo(201);
	}

	private String employeeToken(long employeeId) {
		return jwtService.issueAccessToken(
				employeeId, employeeId, COMPANY, "test-session", Map.of("role", "hr", "token_version", 1L));
	}

	private HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

}
