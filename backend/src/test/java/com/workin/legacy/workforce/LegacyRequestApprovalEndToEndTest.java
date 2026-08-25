package com.workin.legacy.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/** Focused D-100 approval transaction regressions. */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyRequestApprovalEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final long COMPANY = 21801L;
	private static final long BRANCH = 21811L;
	private static final long HR = 218011L;
	private static final long EMPLOYEE_OK = 218012L;
	private static final long EMPLOYEE_LOW = 218013L;
	private static final long TYPE = 218101L;
	private static final long EXCEPTION_TYPE = 218111L;
	private static final long REQUEST_OK = 218201L;
	private static final long REQUEST_LOW = 218202L;

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
			throw new IllegalStateException("could not prepare approval fixture", ex);
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
	void approvalAppliesRequestBalanceAttendanceAndNotificationAtomically() {
		ResponseEntity<Map<String, Object>> response = approve(REQUEST_OK, "approved for test");
		assertThat(response.getStatusCode().value()).isEqualTo(200);

		Map<String, Object> request = queryOne(
				"SELECT status, reply, approver_id, decided_at FROM requests WHERE id=" + REQUEST_OK);
		assertThat(request.get("status")).isEqualTo("approved");
		assertThat(request.get("reply")).isEqualTo("approved for test");
		assertThat(number(request.get("approver_id"))).isEqualTo(HR);
		assertThat(request.get("decided_at")).isNotNull();

		Map<String, Object> balance = queryOne(
				"SELECT total_days, used_days FROM leave_balance WHERE employee_id=" + EMPLOYEE_OK + " AND year=2026");
		assertThat(decimal(balance.get("used_days"))).isEqualTo(3.0d);

		assertThat(count("SELECT COUNT(*) FROM attendance WHERE employee_id=" + EMPLOYEE_OK
				+ " AND exception_type_id=" + EXCEPTION_TYPE)).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM notifications WHERE to_employee_id=" + EMPLOYEE_OK
				+ " AND notification_type='request_approved' AND reference_type='request' AND reference_id=" + REQUEST_OK))
				.isEqualTo(1);
	}

	@Test
	void insufficientBalanceLeavesRequestAndSideEffectsUntouched() {
		ResponseEntity<Map<String, Object>> response = approve(REQUEST_LOW, "");
		assertThat(response.getStatusCode().value()).isEqualTo(422);

		assertThat(queryOne("SELECT status FROM requests WHERE id=" + REQUEST_LOW).get("status"))
				.isEqualTo("pending");
		assertThat(decimal(queryOne("SELECT used_days FROM leave_balance WHERE employee_id=" + EMPLOYEE_LOW
				+ " AND year=2026").get("used_days"))).isEqualTo(0.0d);
		assertThat(count("SELECT COUNT(*) FROM attendance WHERE employee_id=" + EMPLOYEE_LOW)).isZero();
		assertThat(count("SELECT COUNT(*) FROM notifications WHERE reference_type='request' AND reference_id=" + REQUEST_LOW))
				.isZero();
	}

	private ResponseEntity<Map<String, Object>> approve(long requestId, String reply) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(jwtService.issueAccessToken(
				HR, HR, COMPANY, "test-session", Map.of("role", "hr", "token_version", 1L)));
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Accept-Language", "en");
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/requests/approve.php?id=" + requestId),
				HttpMethod.POST, new HttpEntity<>(Map.of("reply", reply), headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES ("
					+ COMPANY + ", 'Approval Co', '+201000021801', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
					+ BRANCH + ", " + COMPANY + ", 'Approval Branch', 1, '2025-03-01 10:00:00')");
			employee(st, HR, "hr", "HR");
			employee(st, EMPLOYEE_OK, "employee", "Employee OK");
			employee(st, EMPLOYEE_LOW, "employee", "Employee Low");
			st.execute("INSERT INTO exception_types (id, company_id, name, is_active) VALUES ("
					+ EXCEPTION_TYPE + ", " + COMPANY + ", 'Approved Leave', 1)");
			st.execute("INSERT INTO request_types (id, company_id, name, deduct_balance, add_attendance_exception,"
					+ " exception_type_id, is_active, created_at) VALUES ("
					+ TYPE + ", " + COMPANY + ", 'Vacation', 1, 1, " + EXCEPTION_TYPE
					+ ", 1, '2025-02-01 08:00:00')");
			st.execute("INSERT INTO leave_balance (employee_id, year, total_days, used_days) VALUES "
					+ "(" + EMPLOYEE_OK + ", 2026, 5, 1), (" + EMPLOYEE_LOW + ", 2026, 1, 0)");
			st.execute("INSERT INTO requests (id, employee_id, request_type_id, from_date, to_date, status, created_at) VALUES "
					+ "(" + REQUEST_OK + ", " + EMPLOYEE_OK + ", " + TYPE + ", '2026-04-01', '2026-04-02', 'pending', '2026-03-01 09:00:00'),"
					+ "(" + REQUEST_LOW + ", " + EMPLOYEE_LOW + ", " + TYPE + ", '2026-04-01', '2026-04-03', 'pending', '2026-03-01 09:00:00')");
		}
	}

	private static void employee(Statement st, long id, String role, String name) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, role, is_active,"
				+ " join_request_status, phone, first_name, last_name, created_at) VALUES ("
				+ id + ", " + COMPANY + ", " + BRANCH + ", '" + id + "', '" + role + "', 1,"
				+ " 'accepted', '+2010" + id + "', '" + name + "', 'Test', '2025-01-20 09:00:00')");
	}

	private static int count(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Map<String, Object> queryOne(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			if (!rs.next()) {
				return null;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
				row.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
			}
			return row;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private static double decimal(Object value) {
		return ((Number) value).doubleValue();
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

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String resourceName) throws Exception {
		try (InputStream stream = LegacyRequestApprovalEndToEndTest.class.getClassLoader().getResourceAsStream(resourceName)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + resourceName);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
