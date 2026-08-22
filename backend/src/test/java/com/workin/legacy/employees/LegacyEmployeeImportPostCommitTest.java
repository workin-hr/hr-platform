package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * The post-commit reread in {@code employee_create_from_payload()}, which is the
 * one place the bulk import can report a failure over data it has already
 * written.
 *
 * <p>The helper commits, then rereads the employee it just inserted, and returns
 * {@code employee_create_failed} if that read comes back empty. By then the
 * transaction is gone: the employee, its salary contract, its leave balance and
 * its shift assignment are all committed and stay committed. PHP compensates
 * for none of it, and neither does the port -- adding a deletion here would
 * invent a rollback legacy has never performed.
 *
 * <p>Reaching it needs the reread to fail while the insert succeeds, which no
 * input can arrange. The seam used instead is the store's own bean, spied and
 * stubbed the way {@code LegacyLoginServiceRollbackTest} already does for
 * {@code JwtService}. No production code is shaped around the test.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeImportPostCommitTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String IMPORT = "/apis/api/employees/import_bulk.php";

	private static final long COMPANY = 19901L;
	private static final long ADMIN = 199011L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	@MockitoSpyBean
	private LegacyEmployeeStore store;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the post-commit fixture", ex);
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
	void aFailedPostCommitRereadIsReportedAsAFailedRowWhileTheWritesRemain() {
		// The reread comes back empty; everything before it has committed.
		doReturn(null).when(store).findByIdWithOrgLabels(anyLong());

		Map<String, Object> body = post("{\"rows\":[" + validRow() + "]}").getBody();

		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) body.get("data");
		assertThat(((Number) data.get("inserted")).longValue()).isZero();
		assertThat((List<?>) data.get("created_ids")).isEmpty();

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> failed = (List<Map<String, Object>>) data.get("failed");
		assertThat(failed).hasSize(1);
		assertThat(failed.get(0).get("errors")).isEqualTo(List.of("employee_create_failed"));
		assertThat(failed.get(0).get("row_index")).isEqualTo(1);

		// And the row the batch just called a failure is in the database, with
		// everything the transaction wrote alongside it.
		long employeeId = scalar("SELECT id FROM employees WHERE company_id = " + COMPANY
				+ " AND employee_code = '9101'");
		assertThat(employeeId).isPositive();
		assertThat(scalar("SELECT COUNT(*) FROM salary_contracts WHERE employee_id = " + employeeId))
				.isEqualTo(1);
		assertThat(scalar("SELECT COUNT(*) FROM leave_balance WHERE employee_id = " + employeeId))
				.isEqualTo(1);
		assertThat(scalar("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + employeeId))
				.isEqualTo(1);

		// Nothing compensating ran: the employee is active, not soft-deleted.
		assertThat(scalar("SELECT is_active FROM employees WHERE id = " + employeeId)).isEqualTo(1);
	}

	@Test
	void theSameRowSucceedsOnceTheRereadWorks() {
		// The control: without the stub the identical row imports cleanly, so
		// the case above is the reread and nothing else.
		Map<String, Object> body = post("{\"rows\":[" + validRow("9102", "01012360102") + "]}").getBody();

		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) body.get("data");
		assertThat(((Number) data.get("inserted")).longValue()).isEqualTo(1);
		assertThat((List<?>) data.get("failed")).isEmpty();
	}

	private static String validRow() {
		return validRow("9101", "01012360101");
	}

	private static String validRow(String code, String phone) {
		return "{"
				+ "\"employee_code\":\"" + code + "\","
				+ "\"first_name\":\"Nour\","
				+ "\"last_name\":\"Adel\","
				+ "\"phone\":\"" + phone + "\","
				+ "\"country_code\":\"\","
				+ "\"shift_name\":\"Night Watch\","
				+ "\"branch_name\":\"Riverside Branch\","
				+ "\"department_name\":\"Field Operations\","
				+ "\"job_title_name\":\"Senior Agent\","
				+ "\"is_mobile_attendance_enabled\":\"نعم\","
				+ "\"hire_date\":\"2024-03-01\","
				+ "\"expected_daily_hours\":\"8\","
				+ "\"salary_basic\":\"5000\""
				+ "}";
	}

	private ResponseEntity<Map<String, Object>> post(String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(jwtService.issueAccessToken(
				ADMIN, ADMIN, COMPANY, "test-session", Map.of("role", "company_admin", "token_version", 1L)));
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + IMPORT), HttpMethod.POST,
				new HttpEntity<>(body, headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		return response;
	}

	private long scalar(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0L;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES "
					+ "(19901, 'Post Commit Co', '+201000019901', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES "
					+ "(19911, 19901, 'Riverside Branch', 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES "
					+ "(19921, 19901, 'Field Operations', 1, '2025-04-10 10:00:00')");
			st.execute("INSERT INTO department_branches (department_id, branch_id) VALUES (19921, 19911)");
			st.execute("INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at)"
					+ " VALUES (19931, 19901, 19921, 'Senior Agent', 1, '2025-04-11 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES "
					+ "(19941, 19901, 'Night Watch', '22:00:00', '06:00:00', '2025-04-12 10:00:00')");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, department_id, job_title_id, employee_code,
					   expected_daily_hours, first_name, last_name, phone, country_code, password_hash,
					   token_version, role, national_id, birth_date, gender, address, photo_url, hire_date,
					   contract_duration_months, is_active, is_mobile_attendance_enabled,
					   can_check_in_any_branch, join_request_status, created_at)
					VALUES (199011, 19901, 19911, NULL, NULL, '1001', 8.00, 'Rana', 'Adel',
					   '+201000199011', '+20', '$2y$10$abcdefghijklmnopqrstuv', 1, 'company_admin',
					   '29001011200011', '0000-00-00', 'female', 'Cairo', NULL, '2024-01-01', 12, 1, 1, 0,
					   'accepted', '2025-05-01 09:00:00')
					""");
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

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream =
				LegacyEmployeeImportPostCommitTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
