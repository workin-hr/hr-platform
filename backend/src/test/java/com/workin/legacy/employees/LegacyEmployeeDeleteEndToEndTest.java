package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * {@code employees/delete_preview.php} and {@code employees/delete.php} over
 * real HTTP against real MariaDB.
 *
 * <p>These are the destructive endpoints, so the assertions are mostly about
 * what is still in the database afterwards: which rows went, which stayed, and
 * -- for the rollback cases -- that a failure late in the cascade leaves
 * everything exactly as it was, including the department manager the cascade
 * had already cleared.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeDeleteEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PREVIEW = "/apis/api/employees/delete_preview.php";
	private static final String DELETE = "/apis/api/employees/delete.php";

	private static final long COMPANY_1 = 19901L;
	private static final long COMPANY_2 = 19902L;
	private static final long BRANCH = 19911L;
	private static final long BRANCH_OTHER = 19921L;
	private static final long SHIFT = 19961L;
	private static final long ADMIN = 199011L;
	private static final long OTHER_COMPANY_EMPLOYEE = 199021L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	@MockitoSpyBean
	private LegacyEmployeeStore storeSpy;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the Wave 12.4 delete fixture", ex);
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
	void thePreviewPayloadIsLiteralAndDropsEveryZeroCategory() throws Exception {
		long id = employee(9001, "01029000001");
		relatedRows(id, true);

		ResponseEntity<Map<String, Object>> response = get(PREVIEW + "?id=" + id, ADMIN);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("message")).isEqualTo("Employee delete preview");

		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) response.getBody().get("data");
		assertThat(payload.keySet())
				.containsExactly("employee_id", "has_related_records", "total_related_records", "related_records");
		assertThat(payload.get("employee_id")).isEqualTo((int) id);
		assertThat(payload.get("has_related_records")).isEqualTo(true);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("related_records");
		// The helper's own order, with every zero category absent entirely.
		assertThat(items.stream().map(item -> item.get("key")))
				.containsExactly("attendance", "shift_assignments", "salary_contracts", "notifications",
						"hr_permissions");
		assertThat(items.get(0).keySet()).containsExactly("key", "label", "count");
		assertThat(items.get(0).get("label")).isEqualTo("Attendance records");
		assertThat(items.get(0).get("count")).isEqualTo(2);

		// notifications counts rows, not sides: the row that is both from and
		// to this employee is counted once, so three rows means three.
		Map<String, Object> notifications = items.stream()
				.filter(item -> "notifications".equals(item.get("key"))).findFirst().orElseThrow();
		assertThat(notifications.get("count")).isEqualTo(3);
		assertThat(payload.get("total_related_records")).isEqualTo(2 + 1 + 1 + 3 + 1);
	}

	@Test
	void anEmployeeWithNothingAttachedPreviewsAsEmpty() throws Exception {
		long id = employee(9002, "01029000002");
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) get(PREVIEW + "?id=" + id, ADMIN).getBody().get("data");
		assertThat(payload.get("has_related_records")).isEqualTo(false);
		assertThat(payload.get("total_related_records")).isEqualTo(0);
		assertThat((List<?>) payload.get("related_records")).isEmpty();
	}

	@Test
	void theLabelsFollowTheRequestLocale() throws Exception {
		long id = employee(9003, "01029000003");
		relatedRows(id, false);
		@SuppressWarnings("unchecked")
		Map<String, Object> arabic = (Map<String, Object>)
				get(PREVIEW + "?id=" + id + "&lang=ar", ADMIN).getBody().get("data");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) arabic.get("related_records");
		assertThat((String) items.get(0).get("label")).isNotEqualTo("Attendance records").isNotBlank();
	}

	@Test
	void theForeignEmployeeIs404BeforeAnyCountIsTaken() {
		Map<String, Object> body = get(PREVIEW + "?id=" + OTHER_COMPANY_EMPLOYEE, ADMIN).getBody();
		assertThat(body.get("success")).isEqualTo(false);
		assertThat(body.get("message")).isEqualTo("Employee not found");
		// No preview payload of any kind travels with the 404.
		assertThat(body).doesNotContainKey("data");

		assertThat(get(PREVIEW, ADMIN).getBody().get("message")).isEqualTo("Field 'id' is required");
		ResponseEntity<Map<String, Object>> wrongMethod = exchange(
				PREVIEW + "?id=1", HttpMethod.DELETE, ADMIN);
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
	}

	@Test
	void cascadeIsReadWithFilterValidateBoolean() throws Exception {
		// Measured against PHP: 1, true, on, yes -- case-insensitive and
		// trimmed -- are true; everything else, including 2 and y, is false.
		List<String> truthyValues = List.of("1", "true", "TRUE", "on", "ON", "yes", "%20true%20");
		for (int index = 0; index < truthyValues.size(); index++) {
			String truthy = truthyValues.get(index);
			long id = employee(9100 + index, "010290001" + phone());
			relatedRows(id, false);
			ResponseEntity<Map<String, Object>> response = exchange(
					DELETE + "?id=" + id + "&cascade=" + truthy, HttpMethod.DELETE, ADMIN);
			assertThat(response.getStatusCode().value())
					.describedAs("cascade=%s", truthy).isEqualTo(200);
			assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + id)).isZero();
		}

		List<String> falsyValues = List.of("0", "false", "off", "no", "2", "y", "");
		for (int index = 0; index < falsyValues.size(); index++) {
			String falsy = falsyValues.get(index);
			long id = employee(9200 + index, "010290002" + phone());
			relatedRows(id, false);
			ResponseEntity<Map<String, Object>> response = exchange(
					DELETE + "?id=" + id + "&cascade=" + falsy, HttpMethod.DELETE, ADMIN);
			assertThat(response.getStatusCode().value())
					.describedAs("cascade=%s", falsy).isEqualTo(409);
			assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + id)).isOne();
		}

		// Absent behaves as false too.
		long id = employee(9299, "01029000299");
		relatedRows(id, false);
		assertThat(exchange(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN).getStatusCode().value())
				.isEqualTo(409);
	}

	@Test
	void theBlockedResponseCarriesTheWholePreviewAndWritesNothing() throws Exception {
		long id = employee(9300, "01029000300");
		relatedRows(id, true);
		Map<String, Long> before = tableCounts(id);

		ResponseEntity<Map<String, Object>> response = exchange(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN);
		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(response.getBody().get("message"))
				.isEqualTo("Cannot delete employee because there are related records");

		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) response.getBody().get("data");
		assertThat(payload.keySet())
				.containsExactly("employee_id", "has_related_records", "total_related_records", "related_records");
		assertThat(payload.get("has_related_records")).isEqualTo(true);

		assertThat(tableCounts(id)).isEqualTo(before);
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + id)).isOne();
	}

	@Test
	void theDirectPathDeletesWithoutClearingTheManagerReference() throws Exception {
		// D-077: an employee with no related records at all takes a single
		// unwrapped DELETE, and the department it manages keeps the dangling id.
		long id = employee(9400, "01029000400");
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("UPDATE departments SET manager_id = " + id + " WHERE id = 19941");
		}

		ResponseEntity<Map<String, Object>> response = exchange(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("message")).isEqualTo("Employee deleted successfully");
		// ok(EMPLOYEE_DELETED) with no second argument: no data key at all.
		assertThat(response.getBody()).doesNotContainKey("data");

		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + id)).isZero();
		assertThat(single("SELECT manager_id FROM departments WHERE id = 19941").get("manager_id"))
				.isEqualTo(String.valueOf(id));
	}

	@Test
	void theCascadeClearsEveryListedTableAndReportsThePreDeleteCounts() throws Exception {
		long id = employee(9500, "01029000500");
		relatedRows(id, true);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("UPDATE departments SET manager_id = " + id + " WHERE id = 19942");
			// Another company's department cannot hold this id in practice; the
			// manager clear is company-scoped regardless.
			st.execute("UPDATE departments SET manager_id = " + id + " WHERE id = 19943");
		}

		ResponseEntity<Map<String, Object>> response = exchange(
				DELETE + "?id=" + id + "&cascade=1", HttpMethod.DELETE, ADMIN);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("message"))
				.isEqualTo("Employee and related records deleted successfully");

		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		assertThat(data.keySet()).containsExactly("deleted_related_records");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("deleted_related_records");
		// The helper's pre-delete snapshot, not affected-row counts.
		assertThat(items.stream().map(item -> item.get("key")))
				.containsExactly("attendance", "shift_assignments", "salary_contracts", "notifications",
						"hr_permissions");
		assertThat(items.get(0).get("count")).isEqualTo(2);

		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + id)).isZero();
		assertThat(tableCounts(id).values()).allMatch(count -> count == 0L);
		assertThat(single("SELECT manager_id FROM departments WHERE id = 19942").get("manager_id")).isNull();
		// The other company's row is untouched by this company's cascade.
		assertThat(single("SELECT manager_id FROM departments WHERE id = 19943").get("manager_id"))
				.isEqualTo(String.valueOf(id));
	}

	@Test
	void aFailureLateInTheCascadeRollsBackEverythingBeforeIt() throws Exception {
		long id = employee(9600, "01029000600");
		relatedRows(id, true);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("UPDATE departments SET manager_id = " + id + " WHERE id = 19944");
		}
		Map<String, Long> before = tableCounts(id);

		// The manager clear is the second-to-last statement, so by the time this
		// throws every destructive delete has already run inside the transaction.
		Mockito.doThrow(new IllegalStateException("cascade interrupted: jdbc said no"))
				.when(storeSpy).clearDepartmentManager(Mockito.anyLong(), Mockito.anyLong());
		try {
			ResponseEntity<Map<String, Object>> response = exchange(
					DELETE + "?id=" + id + "&cascade=1", HttpMethod.DELETE, ADMIN);
			// D-084: one deterministic body, carrying nothing about the failure.
			assertThat(response.getStatusCode().value()).isEqualTo(500);
			assertThat(response.getBody()).containsExactly(
					Map.entry("success", false), Map.entry("message", "Internal server error"));
			assertThat(response.getBody().toString()).doesNotContain("jdbc said no").doesNotContain("cascade");
		} finally {
			Mockito.reset(storeSpy);
		}

		// Everything is back: the employee, its history, and the manager link.
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + id)).isOne();
		assertThat(tableCounts(id)).isEqualTo(before);
		assertThat(single("SELECT manager_id FROM departments WHERE id = 19944").get("manager_id"))
				.isEqualTo(String.valueOf(id));
	}

	@Test
	void theRowCountGuardAlsoRollsTheCascadeBack() throws Exception {
		long id = employee(9700, "01029000700");
		relatedRows(id, true);
		Map<String, Long> before = tableCounts(id);

		// rowCount() !== 1 is legacy's own guard against removing an employee's
		// history without removing the employee.
		Mockito.doReturn(0).when(storeSpy).deleteEmployeeScoped(Mockito.anyLong(), Mockito.anyLong());
		try {
			ResponseEntity<Map<String, Object>> response = exchange(
					DELETE + "?id=" + id + "&cascade=1", HttpMethod.DELETE, ADMIN);
			assertThat(response.getStatusCode().value()).isEqualTo(500);
			// employee_delete_failed is the helper's internal marker, not a
			// client-visible message.
			assertThat(response.getBody().get("message")).isEqualTo("Internal server error");
			assertThat(response.getBody().toString()).doesNotContain("employee_delete_failed");
		} finally {
			Mockito.reset(storeSpy);
		}

		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + id)).isOne();
		assertThat(tableCounts(id)).isEqualTo(before);
	}

	@Test
	void theSpecificHandlersStillOwnTheirOwnEnvelopes() throws Exception {
		// D-084 is a fallback, not a replacement: an explicit fail() equivalent
		// and a guard-stack ApiException both keep their legacy messages.
		long id = employee(9800, "01029000800");
		relatedRows(id, false);
		assertThat(exchange(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN).getBody().get("message"))
				.isEqualTo("Cannot delete employee because there are related records");
		assertThat(exchange(DELETE + "?id=" + OTHER_COMPANY_EMPLOYEE, HttpMethod.DELETE, ADMIN)
				.getBody().get("message")).isEqualTo("Employee not found");

		// LegacyRequestGuard's ApiException, translated through the catalog.
		String employeeRoleToken = jwtService.issueAccessToken(
				ADMIN, ADMIN, COMPANY_1, "test-session", Map.of("role", "employee", "token_version", 1L));
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(employeeRoleToken);
		ResponseEntity<Map<String, Object>> denied = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + DELETE + "?id=" + id), HttpMethod.DELETE,
				new HttpEntity<>(headers), new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(denied.getStatusCode().value()).isEqualTo(403);
		assertThat(denied.getBody().get("message")).isEqualTo("Forbidden — insufficient role");
	}

	@Test
	void anotherCompanysEmployeeCannotBeDeletedEitherWay() throws Exception {
		for (String query : List.of("", "&cascade=1")) {
			ResponseEntity<Map<String, Object>> response = exchange(
					DELETE + "?id=" + OTHER_COMPANY_EMPLOYEE + query, HttpMethod.DELETE, ADMIN);
			assertThat(response.getStatusCode().value()).isEqualTo(404);
			assertThat(response.getBody().get("message")).isEqualTo("Employee not found");
			assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + OTHER_COMPANY_EMPLOYEE)).isOne();
		}
		// Its related rows are untouched as well.
		assertThat(count("SELECT COUNT(*) FROM attendance WHERE employee_id = " + OTHER_COMPANY_EMPLOYEE))
				.isEqualTo(1);
	}

	private static int phoneCounter = 10;

	private static String phone() {
		return String.format("%02d", phoneCounter++);
	}

	/** Attendance x2, one shift assignment, one salary contract, three notifications, one permissions row. */
	private static void relatedRows(long employeeId, boolean full) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO attendance (employee_id, check_in) VALUES (" + employeeId
					+ ", '2025-06-01 09:00:00'), (" + employeeId + ", '2025-06-02 09:00:00')");
			if (!full) {
				return;
			}
			st.execute("INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES ("
					+ employeeId + ", " + SHIFT + ", '2025-06-01')");
			st.execute("INSERT INTO salary_contracts (employee_id, basic_salary, effective_from) VALUES ("
					+ employeeId + ", 5000, '2025-06-01')");
			st.execute("INSERT INTO notifications (company_id, recipient_kind, to_employee_id, title,"
					+ " notification_type) VALUES (" + COMPANY_1 + ", 'employee', " + employeeId
					+ ", 'To them', 'general')");
			st.execute("INSERT INTO notifications (company_id, recipient_kind, from_employee_id, to_employee_id,"
					+ " title, notification_type) VALUES (" + COMPANY_1 + ", 'employee', " + employeeId + ", "
					+ ADMIN + ", 'From them', 'general')");
			// Both sides on one row: the count must still be one for it.
			st.execute("INSERT INTO notifications (company_id, recipient_kind, from_employee_id, to_employee_id,"
					+ " title, notification_type) VALUES (" + COMPANY_1 + ", 'employee', " + employeeId + ", "
					+ employeeId + ", 'Both sides', 'general')");
			st.execute("INSERT INTO hr_permissions (employee_id, can_employees) VALUES (" + employeeId + ", 1)");
		}
	}

	/** Every table the cascade touches, so a rollback can be compared row for row. */
	private static Map<String, Long> tableCounts(long employeeId) throws Exception {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : List.of("attendance", "employee_schedules", "employee_shift_assignments",
				"salary_contracts", "payslips", "penalties", "requests", "advances", "leave_balance",
				"employee_docs", "complaints", "push_tokens", "hr_permissions")) {
			counts.put(table, count("SELECT COUNT(*) FROM " + table + " WHERE employee_id = " + employeeId));
		}
		counts.put("notifications", count(
				"SELECT COUNT(*) FROM notifications WHERE to_employee_id = " + employeeId
				+ " OR from_employee_id = " + employeeId));
		return counts;
	}

	private ResponseEntity<Map<String, Object>> get(String path, long actor) {
		return exchange(path, HttpMethod.GET, actor);
	}

	private ResponseEntity<Map<String, Object>> exchange(String path, HttpMethod method, long actor) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(actor));
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String tokenFor(long employeeId) {
		return jwtService.issueAccessToken(
				employeeId, employeeId, COMPANY_1, "test-session",
				Map.of("role", "company_admin", "token_version", 1L));
	}

	private static long employee(int code, String phone) throws Exception {
		long id = 199100L + code;
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
					   password_hash, token_version, role, is_active, join_request_status, created_at)
					VALUES (%d, %d, %d, '%d', 'Delete', 'Subject', '%s', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, 'accepted', '2025-05-01 09:00:00')
					""".formatted(id, COMPANY_1, BRANCH, code, phone));
		}
		return id;
	}

	private static Map<String, Object> single(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			assertThat(rs.next()).withFailMessage("no row for %s", sql).isTrue();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
				row.put(rs.getMetaData().getColumnLabel(column), rs.getString(column));
			}
			return row;
		}
	}

	private static long count(String sql) throws Exception {
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
					  (19901, 'Delete Co 1', '+201000019901', 'active', '2025-01-15 09:00:00'),
					  (19902, 'Delete Co 2', '+201000019902', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19911, 19901, 'Main Branch', 1, '2025-03-01 10:00:00'),
					  (19921, 19902, 'Other Company Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (19941, 19901, 'Direct Delete Department', 1, '2025-04-10 10:00:00'),
					  (19942, 19901, 'Cascade Department', 1, '2025-04-10 10:00:00'),
					  (19943, 19902, 'Other Company Department', 1, '2025-04-10 10:00:00'),
					  (19944, 19901, 'Rollback Department', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES
					  (19961, 19901, 'Morning', '09:00:00', '17:00:00', '2025-04-12 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
					   password_hash, token_version, role, is_active, join_request_status, created_at)
					VALUES
					  (199011, 19901, 19911, '1001', 'Delete', 'Admin', '+201000199011', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'company_admin', 1, 'accepted', '2025-05-01 09:00:00'),
					  (199021, 19902, 19921, '2001', 'Other', 'Company', '+201000199021', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, 'accepted', '2025-05-01 09:00:00')
					""");
			st.execute("INSERT INTO attendance (employee_id, check_in) VALUES (199021, '2025-06-01 09:00:00')");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream =
				LegacyEmployeeDeleteEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
