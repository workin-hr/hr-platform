package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
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
 * Wave 12.4: {@code employees/deactivate.php} and {@code employees/reactivate.php}
 * over real HTTP against real MariaDB.
 *
 * <p>Separate from {@link LegacyEmployeeReadEndToEndTest} because these
 * endpoints mutate {@code is_active}, which that class's filter and roster
 * assertions depend on. Ordered, because the two endpoints are each other's
 * inverse and the notification-row counts are cumulative -- legacy inserts one
 * per deactivate call, including a repeat.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyEmployeeLifecycleEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String DEACTIVATE = "/apis/api/employees/deactivate.php";
	private static final String REACTIVATE = "/apis/api/employees/reactivate.php";

	private static final long COMPANY_1 = 19501L;
	private static final long COMPANY_2 = 19502L;
	private static final long ADMIN_1 = 195011L;
	private static final long MANAGER_1 = 195012L;
	private static final long STAFF_1 = 195013L;
	private static final long STAFF_ARABIC = 195014L;
	private static final long ADMIN_2 = 195021L;
	private static final long STAFF_COMPANY_2 = 195022L;

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
			throw new IllegalStateException("could not prepare the Wave 12.4 lifecycle fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@Test
	@Order(1)
	void deactivateWritesIsActiveZeroAndNotifiesTheEmployee() throws Exception {
		ResponseEntity<Map<String, Object>> response = call(
				DEACTIVATE + "?id=" + STAFF_1, HttpMethod.DELETE, ADMIN_1);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);
		assertThat(response.getBody().get("message")).isEqualTo("Employee deactivated");

		// The response is the post-update re-read, so it already shows 0.
		@SuppressWarnings("unchecked")
		Map<String, Object> employee = (Map<String, Object>) response.getBody().get("data");
		assertThat(employee.get("is_active")).isEqualTo(0);
		assertThat(employee.get("branch_name")).isEqualTo("Main Branch");
		assertThat(employee).doesNotContainKeys("password_hash", "token_version");
		// fetch_employee_with_org_labels() has no salary or shift columns.
		assertThat(employee).doesNotContainKeys("basic_salary", "assigned_shift_id");
		assertThat(queryLong("SELECT is_active FROM employees WHERE id = " + STAFF_1)).isZero();

		List<Map<String, Object>> rows = notificationsFor(STAFF_1);
		assertThat(rows).hasSize(1);
		Map<String, Object> notification = rows.get(0);
		assertThat(notification.get("company_id")).isEqualTo(COMPANY_1);
		assertThat(notification.get("recipient_kind")).isEqualTo("employee");
		assertThat(notification.get("from_employee_id")).isEqualTo(ADMIN_1);
		assertThat(notification.get("notification_type")).isEqualTo("employee_deactivated");
		assertThat(notification.get("title")).isEqualTo("Account deactivated");
		assertThat(notification.get("body")).isEqualTo("Your company deactivated your account.");
		assertThat(notification.get("reference_type")).isNull();
		assertThat(notification.get("reference_id")).isNull();
		assertThat(notification.get("is_read")).isEqualTo(0L);
	}

	@Test
	@Order(2)
	void deactivateIsRepeatableAndNotifiesAgain() throws Exception {
		// No prior-state guard in PHP: the UPDATE runs unconditionally.
		assertThat(call(DEACTIVATE + "?id=" + STAFF_1, HttpMethod.DELETE, ADMIN_1).getStatusCode().value())
				.isEqualTo(200);
		assertThat(notificationsFor(STAFF_1)).hasSize(2);
	}

	@Test
	@Order(3)
	void reactivateRestoresTheRowAndNotifiesNobody() throws Exception {
		ResponseEntity<Map<String, Object>> response = call(
				REACTIVATE + "?id=" + STAFF_1, HttpMethod.PUT, ADMIN_1);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("message")).isEqualTo("Employee reactivated");

		@SuppressWarnings("unchecked")
		Map<String, Object> employee = (Map<String, Object>) response.getBody().get("data");
		assertThat(employee.get("is_active")).isEqualTo(1);
		assertThat(queryLong("SELECT is_active FROM employees WHERE id = " + STAFF_1)).isOne();
		// reactivate.php has no notification call at all.
		assertThat(notificationsFor(STAFF_1)).hasSize(2);
	}

	@Test
	@Order(4)
	void theNotificationTextIsTranslatedInTheRequestLocale() throws Exception {
		// t() runs at insert time, so the stored row carries the locale of the
		// request that triggered it, not a fixed language.
		assertThat(call(DEACTIVATE + "?id=" + STAFF_ARABIC + "&lang=ar", HttpMethod.DELETE, ADMIN_1)
				.getStatusCode().value()).isEqualTo(200);
		Map<String, Object> notification = notificationsFor(STAFF_ARABIC).get(0);
		assertThat((String) notification.get("title")).isNotEqualTo("Account deactivated");
		assertThat((String) notification.get("title")).isNotBlank();
		assertThat((String) notification.get("body")).isNotBlank();
	}

	@Test
	@Order(5)
	void aForeignOrMissingEmployeeIs404AndWritesNothing() throws Exception {
		long before = queryLong("SELECT is_active FROM employees WHERE id = " + STAFF_COMPANY_2);

		ResponseEntity<Map<String, Object>> foreign = call(
				DEACTIVATE + "?id=" + STAFF_COMPANY_2, HttpMethod.DELETE, ADMIN_1);
		assertThat(foreign.getStatusCode().value()).isEqualTo(404);
		assertThat(foreign.getBody().get("message")).isEqualTo("Employee not found");

		assertThat(call(REACTIVATE + "?id=9999999", HttpMethod.PUT, ADMIN_1).getStatusCode().value())
				.isEqualTo(404);

		// The scoped UPDATE ran first and matched zero rows -- the other
		// tenant's employee is untouched, and no notification was written.
		assertThat(queryLong("SELECT is_active FROM employees WHERE id = " + STAFF_COMPANY_2)).isEqualTo(before);
		assertThat(notificationsFor(STAFF_COMPANY_2)).isEmpty();
	}

	@Test
	@Order(6)
	void theIdIsRequiredAndTheMethodGuardComesFirst() {
		Map<String, Object> missing = call(DEACTIVATE, HttpMethod.DELETE, ADMIN_1).getBody();
		assertThat(missing.get("message")).isEqualTo("Field 'id' is required");

		// deactivate is DELETE-only and reactivate is PUT-only; each rejects the
		// other's verb before authenticating.
		ResponseEntity<Map<String, Object>> wrongVerb = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + DEACTIVATE + "?id=" + STAFF_1), HttpMethod.PUT,
				new HttpEntity<>(new HttpHeaders()), new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(wrongVerb.getStatusCode().value()).isEqualTo(405);
		assertThat(wrongVerb.getBody().get("message")).isEqualTo("Invalid method");
	}

	@Test
	@Order(7)
	void managersAreNotAllowedOnTheLifecycleEndpoints() {
		// list.php admits manager; deactivate.php and reactivate.php do not.
		ResponseEntity<Map<String, Object>> response = call(
				DEACTIVATE + "?id=" + STAFF_1, HttpMethod.DELETE, MANAGER_1);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().get("message")).isEqualTo("Forbidden — insufficient role");
		assertThat(call(REACTIVATE + "?id=" + STAFF_1, HttpMethod.PUT, MANAGER_1).getStatusCode().value())
				.isEqualTo(403);
	}

	private ResponseEntity<Map<String, Object>> call(String path, HttpMethod method, long employeeId) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager" : "company_admin";
		long companyId = employeeId == ADMIN_2 ? COMPANY_2 : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static List<Map<String, Object>> notificationsFor(long employeeId) throws Exception {
		List<Map<String, Object>> rows = new ArrayList<>();
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT company_id, recipient_kind, from_employee_id, to_employee_id, title, body,"
						+ " notification_type, reference_type, reference_id, is_read FROM notifications"
						+ " WHERE to_employee_id = " + employeeId + " ORDER BY id")) {
			while (rs.next()) {
				rows.add(Map.ofEntries(
						Map.entry("company_id", rs.getLong("company_id")),
						Map.entry("recipient_kind", rs.getString("recipient_kind")),
						Map.entry("from_employee_id", rs.getLong("from_employee_id")),
						Map.entry("to_employee_id", rs.getLong("to_employee_id")),
						Map.entry("title", rs.getString("title")),
						Map.entry("body", rs.getString("body")),
						Map.entry("notification_type", rs.getString("notification_type")),
						Map.entry("is_read", rs.getLong("is_read"))));
			}
		}
		return rows;
	}

	private static long queryLong(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
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
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (19501, 'Lifecycle Co 1', '+201000019501', 'active', '2025-01-15 09:00:00'),
					  (19502, 'Lifecycle Co 2', '+201000019502', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19511, 19501, 'Main Branch', 1, '2025-03-01 10:00:00'),
					  (19521, 19502, 'Other Company Branch', 1, '2025-03-01 10:00:00')
					""");
			insertEmployee(st, ADMIN_1, COMPANY_1, 19511L, "'1001'", "company_admin", "+201000195011");
			insertEmployee(st, MANAGER_1, COMPANY_1, 19511L, "'1002'", "manager", "+201000195012");
			insertEmployee(st, STAFF_1, COMPANY_1, 19511L, "'1003'", "employee", "+201000195013");
			insertEmployee(st, STAFF_ARABIC, COMPANY_1, 19511L, "'1004'", "employee", "+201000195014");
			insertEmployee(st, ADMIN_2, COMPANY_2, 19521L, "'2001'", "company_admin", "+201000195021");
			insertEmployee(st, STAFF_COMPANY_2, COMPANY_2, 19521L, "'2002'", "employee", "+201000195022");
		}
	}

	private static void insertEmployee(
			Statement st, long id, long companyId, long branchId, String code, String role, String phone)
			throws Exception {
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
				   password_hash, token_version, role, is_active, join_request_status, created_at)
				VALUES (%d, %d, %d, %s, 'Lifecycle', 'Subject', '%s', '+20',
				   '$2y$10$abcdefghijklmnopqrstuv', 1, '%s', 1, 'accepted', '2025-05-01 09:00:00')
				""".formatted(id, companyId, branchId, code, phone, role));
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream =
				LegacyEmployeeLifecycleEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
