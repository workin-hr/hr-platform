package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
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
 * G6 evidence for {@code attendance/overall_report.php} (Wave 12.6.6c), measured
 * against real MariaDB rather than asserted about.
 *
 * <p>The cases are the endpoint's branch matrix, not one happy path: the guard
 * order and its two refusals, the envelope, the internal keys that must not
 * reach the client, the elapsed-day clamp that decides whether a row is computed
 * or zeroed, the coverage gate on holiday credit, and the role scoping applied
 * in the WHERE clause. Each is a branch whose failure would still produce a
 * plausible-looking report, which is why it is pinned.
 *
 * <p>Dates are fixed in the past so that {@code as_of} is always the period end
 * and the arithmetic is deterministic; the one open-period case selects a future
 * period deliberately.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyOverallReportEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String REPORT = "/apis/api/attendance/overall_report.php";

	private static final long COMPANY = 21701L;
	private static final long BRANCH_A = 21711L;
	private static final long BRANCH_B = 21712L;
	private static final long ADMIN = 217011L;
	private static final long EMPLOYEE_A = 217012L;
	private static final long EMPLOYEE_B = 217013L;
	private static final long MANAGER_B = 217014L;
	private static final long SHIFT = 217021L;

	private static final String FROM = "2026-04-01";
	private static final String TO = "2026-04-30";

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
			throw new IllegalStateException("could not prepare the overall report fixture", ex);
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
	void aNonGetMethodIsRefusedBeforeAnythingElse() {
		ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, ADMIN, "company_admin", "");
		assertThat(response.getStatusCode().value()).isEqualTo(405);
	}

	/**
	 * The role list is {@code [COMPANY_ADMIN, HR, MANAGER]}. An EMPLOYEE
	 * authenticates and is then refused, which is why the builder's own employee
	 * branch is unreachable through this endpoint even though it exists.
	 */
	@Test
	void anEmployeeIsRefusedEvenThoughTheBuilderHasAnEmployeeBranch() {
		ResponseEntity<Map<String, Object>> response =
				call(HttpMethod.GET, EMPLOYEE_A, "employee", "?from=" + FROM + "&to=" + TO);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
	}

	@Test
	void theReportAnswersTheD074EnvelopeWithoutTheInternalPeriodKeys() {
		Map<String, Object> body = ok(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO);

		assertThat(body).containsEntry("success", true);
		List<Map<String, Object>> rows = rows(body);
		assertThat(rows).isNotEmpty();
		assertThat(rows).allSatisfy(row -> assertThat(row)
				.as("_period_from/_period_to are internal and stripped before the envelope")
				.doesNotContainKeys("_period_from", "_period_to"));
	}

	@Test
	void aComputedRowCarriesTheAttendanceDerivedFigures() {
		Map<String, Object> row = rowFor(
				ok(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO + "&employee_id=" + EMPLOYEE_A),
				EMPLOYEE_A);

		assertThat(row).containsEntry("employee_name", "Emp A");
		assertThat(row).containsEntry("branch_name", "Main");
		assertThat((Integer) row.get("total_days_in_month")).isEqualTo(30);
		assertThat((Integer) row.get("present_days"))
				.as("04-01, 04-02 and 04-03 were punched")
				.isEqualTo(3);
		assertThat(row).containsKeys(
				"present_details", "absent_details", "exception_details", "paid_rest_details",
				"official_holiday_days", "paid_leave_days", "earned_weekly_rest_days",
				"void_weekly_rest_days", "effective_present_days", "absent_days",
				"total_duration_minutes", "paid_rest_days", "paid_rest_minutes", "overtime_minutes");
	}

	/**
	 * {@code paid_rest_details} is three labelled entries in a fixed order, and
	 * a client reads them positionally -- so the keys and the order are contract,
	 * not presentation.
	 */
	@Test
	void paidRestDetailsCarryThreeLabelledEntriesInOrder() {
		Map<String, Object> row = rowFor(
				ok(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO + "&employee_id=" + EMPLOYEE_A),
				EMPLOYEE_A);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> paidRest = (List<Map<String, Object>>) row.get("paid_rest_details");
		assertThat(paidRest).hasSize(3);
		assertThat(paidRest.stream().map(entry -> entry.get("label_key")).toList())
				.containsExactly("official_holiday_days", "paid_leave_days", "earned_weekly_rest_days");
		assertThat(paidRest).allSatisfy(entry -> assertThat(entry).containsKeys("label_key", "label", "value"));
	}

	/**
	 * A period entirely in the future has nothing elapsed, so every employee
	 * still gets a row -- zeroed, with the same key set as a computed one. The
	 * builder does not skip the employee, and a client diffing shapes must not
	 * see two different ones.
	 */
	@Test
	void aFuturePeriodEmitsZeroedRowsWithTheSameKeysAsComputedOnes() {
		Map<String, Object> computed = rowFor(
				ok(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO + "&employee_id=" + EMPLOYEE_A),
				EMPLOYEE_A);
		Map<String, Object> future = rowFor(
				ok(ADMIN, "company_admin", "?from=2099-01-01&to=2099-01-31&employee_id=" + EMPLOYEE_A),
				EMPLOYEE_A);

		assertThat(future.keySet())
				.as("the zeroed shape and the computed shape carry identical keys")
				.containsExactlyInAnyOrderElementsOf(computed.keySet());
		assertThat(future).containsEntry("total_days_in_month", 0)
				.containsEntry("present_days", 0)
				.containsEntry("absent_days", 0)
				.containsEntry("effective_present_days", 0)
				.containsEntry("overtime_minutes", 0);
		assertThat((List<?>) future.get("present_details")).isEmpty();
		assertThat((List<?>) future.get("paid_rest_details")).hasSize(3);
	}

	/**
	 * The holiday credit is gated on {@code WEEKLY_REST_MIN_COVERED_WORKDAYS}
	 * (three): employee B attended once in a month holding an unattended
	 * holiday, so the credit that employee A earns is withheld from B.
	 */
	@Test
	void theHolidayCreditIsWithheldBelowTheCoverageThreshold() {
		Map<String, Object> body = ok(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO);

		assertThat((Integer) rowFor(body, EMPLOYEE_A).get("official_holiday_days"))
				.as("A punched three days, so the 04-20 holiday is credited")
				.isEqualTo(1);
		assertThat((Integer) rowFor(body, EMPLOYEE_B).get("official_holiday_days"))
				.as("B punched once, below the three-workday coverage gate")
				.isZero();
	}

	/** A manager sees only their own branch, in addition to any filter. */
	@Test
	void aManagerIsScopedToTheirOwnBranch() {
		List<Map<String, Object>> rows = rows(ok(MANAGER_B, "manager", "?from=" + FROM + "&to=" + TO));

		assertThat(rows.stream().map(row -> ((Number) row.get("employee_id")).longValue()).toList())
				.as("branch B holds employee B and the manager; branch A's employees are out of scope")
				.containsExactlyInAnyOrder(EMPLOYEE_B, MANAGER_B)
				.doesNotContain(EMPLOYEE_A, ADMIN);
	}

	@Test
	void anInvertedPeriodIsRefusedAsAnInvalidDate() {
		ResponseEntity<Map<String, Object>> response =
				call(HttpMethod.GET, ADMIN, "company_admin", "?from=" + TO + "&to=" + FROM);
		assertThat(response.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	void anEmptyBranchNameReachesTheClientAsNullNotAnEmptyString() {
		Map<String, Object> row = rowFor(
				ok(ADMIN, "company_admin", "?from=" + FROM + "&to=" + TO + "&employee_id=" + ADMIN), ADMIN);

		assertThat(row).containsEntry("department_name", null);
		assertThat(row.get("department_name")).isNull();
	}

	private Map<String, Object> ok(long employeeId, String role, String query) {
		ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, employeeId, role, query);
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		return response.getBody();
	}

	private ResponseEntity<Map<String, Object>> call(
			HttpMethod method, long employeeId, String role, String query) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(jwtService.issueAccessToken(employeeId, employeeId, COMPANY, "test-session",
				Map.of("role", role, "token_version", 1L)));
		headers.set("Accept-Language", "en");
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + REPORT + query), method,
				new HttpEntity<>(headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rows(Map<String, Object> body) {
		return (List<Map<String, Object>>) body.get("data");
	}

	private static Map<String, Object> rowFor(Map<String, Object> body, long employeeId) {
		return rows(body).stream()
				.filter(row -> ((Number) row.get("employee_id")).longValue() == employeeId)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no report row for employee " + employeeId));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Overall Report Co', '+201000021701', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH_A + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00'),"
					+ " (" + BRANCH_B + ", " + COMPANY + ", 'Satellite', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active,"
					+ " created_at) VALUES (" + SHIFT + ", " + COMPANY + ", 'Day', '09:00:00', '17:00:00', '', 1,"
					+ " '2019-05-01 08:00:00')");

			employee(st, ADMIN, BRANCH_A, "Admin", "One", "company_admin");
			employee(st, EMPLOYEE_A, BRANCH_A, "Emp", "A", "employee");
			employee(st, EMPLOYEE_B, BRANCH_B, "Emp", "B", "employee");
			employee(st, MANAGER_B, BRANCH_B, "Mgr", "B", "manager");

			st.execute("INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES"
					+ " (" + EMPLOYEE_A + ", " + SHIFT + ", '2019-05-01'),"
					+ " (" + EMPLOYEE_B + ", " + SHIFT + ", '2019-05-01')");

			// Employee A: three punched days, clearing the coverage gate.
			for (String day : List.of("2026-04-01", "2026-04-02", "2026-04-03")) {
				st.execute("INSERT INTO attendance (employee_id, check_in, check_out, created_at) VALUES"
						+ " (" + EMPLOYEE_A + ", '" + day + " 09:00:00', '" + day + " 17:00:00', '" + day + " 17:00:00')");
			}
			// Employee B: one punched day, below it.
			st.execute("INSERT INTO attendance (employee_id, check_in, check_out, created_at) VALUES"
					+ " (" + EMPLOYEE_B + ", '2026-04-01 09:00:00', '2026-04-01 17:00:00', '2026-04-01 17:00:00')");

			// An official holiday neither attended, so only the coverage gate
			// separates A's credit from B's.
			st.execute("INSERT INTO company_official_holidays (company_id, holiday_date, name, created_at) VALUES"
					+ " (" + COMPANY + ", '2026-04-20', 'Spring Day', '2026-01-01 00:00:00')");
		}
	}

	private static void employee(Statement st, long id, long branch, String first, String last, String role)
			throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
				+ " phone, role, is_active, expected_daily_hours, created_at) VALUES (" + id + ", " + COMPANY
				+ ", " + branch + ", " + id + ", '" + first + "', '" + last + "', '+2010002" + id + "', '"
				+ role + "', 1, 8, '2019-04-01 08:00:00')");
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
		try (InputStream in = LegacyOverallReportEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
