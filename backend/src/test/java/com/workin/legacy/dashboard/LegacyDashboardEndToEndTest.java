package com.workin.legacy.dashboard;

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
 * {@code dashboard/stats.php} -- twenty response keys, and the ones worth
 * asserting are the ones whose shape or quirk a reasonable refactor would
 * change.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyDashboardEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String STATS = "/apis/api/dashboard/stats.php";

	private static final long COMPANY = 25001L;
	private static final long EMPTY_COMPANY = 25002L;
	private static final long ADMIN = 250011L;
	private static final long EMPLOYEE = 250012L;
	private static final long EMPTY_ADMIN = 250021L;
	private static final long BRANCH = 25011L;
	private static final long DEPT_A = 25021L;
	private static final long DEPT_B = 25022L;

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
			throw new IllegalStateException("could not prepare the dashboard fixture", ex);
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
	void theResponseCarriesTheTwentyKeysInTheOrderPhpBuildsThem() {
		assertThat(stats(ADMIN).keySet()).containsExactly(
				"total_employees", "total_branches", "total_gross_salaries", "total_basic_salaries",
				"salaries_by_department", "employees_by_branch", "employees_by_department",
				"workforce_planning_stats", "daily_attendance_stats", "attendance_by_department_stats",
				"resignations_count", "monthly_turnover_rate", "new_employee_turnover_rate",
				"annual_turnover_rate", "total_penalties", "total_penalties_amount",
				"penalties_by_department", "employees_by_gender", "employees_by_age_bracket",
				"new_employees_by_month");
	}

	@Test
	void onlyCompanyAdminAndHrMayReadIt() {
		assertThat(get(token(EMPLOYEE, "employee")).getStatusCode().value()).isEqualTo(403);
		assertThat(get(null).getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void aNonGetMethodIsRejectedBeforeAuthentication() {
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + STATS), HttpMethod.POST,
				new HttpEntity<>(new HttpHeaders()), new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).isEqualTo(405);
	}

	@Test
	@SuppressWarnings("unchecked")
	void headcountAndBranchCountsFollowTheirOwnActiveFilters() {
		Map<String, Object> stats = stats(ADMIN);

		assertThat(stats).containsEntry("total_employees", 3);
		assertThat(stats)
				.as("branches are NOT filtered by is_active here, unlike employees")
				.containsEntry("total_branches", 2);
		assertThat((Map<String, Object>) stats.get("employees_by_department"))
				.containsEntry("Engineering", 2).containsEntry("Sales", 1);
	}

	/**
	 * Every contract row for an active employee is summed, not the effective
	 * one -- so an employee with contract history is counted more than once.
	 */
	@Test
	void salaryTotalsSumEveryContractRowNotTheEffectiveOne() {
		Map<String, Object> stats = stats(ADMIN);

		// Engineering: 1000 + 2000 (two contracts, same employee) + 3000; Sales: 4000.
		assertThat((Double) stats.get("total_basic_salaries")).isEqualTo(10000.0);
	}

	/**
	 * The two list-valued keys stay {@code []} when empty while every
	 * map-valued key becomes {@code {}} -- PHP's {@code (object)[]} cast, which
	 * exists precisely so the type does not change under a client.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void anEmptyCompanyAnswersEmptyObjectsForMapsAndEmptyArraysForLists() {
		Map<String, Object> stats = stats(EMPTY_ADMIN);

		// Every map-valued key stays an object even with nothing in it. This is
		// the assertion PHP's `(object)[]` cast exists for: without it an empty
		// PHP array encodes as `[]` and the value's *type* changes under the
		// client between one request and the next.
		List<String> mapKeys = List.of("salaries_by_department", "employees_by_branch",
				"employees_by_department", "daily_attendance_stats", "attendance_by_department_stats",
				"penalties_by_department", "employees_by_gender", "employees_by_age_bracket");
		for (String key : mapKeys) {
			assertThat(stats.get(key)).as("%s must be an object, not an array", key)
					.isInstanceOf(Map.class);
		}

		// This company has one branch holding its single admin, so the two keys
		// derived from that employee are populated; everything else is empty.
		for (String key : List.of("salaries_by_department", "employees_by_department",
				"daily_attendance_stats", "attendance_by_department_stats", "penalties_by_department")) {
			assertThat((Map<String, Object>) stats.get(key)).as("%s", key).isEmpty();
		}
		assertThat((Map<String, Object>) stats.get("employees_by_branch"))
				.as("the one branch, with its one active employee")
				.containsExactly(Map.entry("Only", 1));
		assertThat(stats.get("workforce_planning_stats")).isInstanceOf(List.class);
		assertThat((List<Object>) stats.get("workforce_planning_stats")).isEmpty();
		assertThat((List<Object>) stats.get("new_employees_by_month"))
				.as("the month series is generated, so it is twelve entries even with no data")
				.hasSize(12);
	}

	/**
	 * The month series is always twelve points ending on the current month,
	 * with absent months defaulted to zero -- unlike the daily series, which is
	 * sparse.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void theMonthSeriesIsAlwaysTwelveEntriesEndingThisMonthWhileTheDailySeriesIsSparse() {
		Map<String, Object> stats = stats(ADMIN);

		List<Map<String, Object>> months = (List<Map<String, Object>>) stats.get("new_employees_by_month");
		assertThat(months).hasSize(12);
		assertThat(months.get(0).keySet()).containsExactly("month", "count");
		assertThat((String) months.get(11).get("month"))
				.as("last entry is the current month, YYYY-MM")
				.matches("\\d{4}-\\d{2}");
		assertThat(months).allSatisfy(entry -> assertThat(entry.get("count")).isNotNull());

		assertThat((Map<String, Object>) stats.get("daily_attendance_stats"))
				.as("only days with attendance appear; there is no zero-filling")
				.hasSizeLessThanOrEqualTo(7);
	}

	/**
	 * A department with no active employees is {@code 0}, not absent and not
	 * null -- PHP's guard yields an integer zero rather than skipping the key.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void attendanceSharesAreRoundedPercentagesAndZeroForAnEmptyDepartment() {
		Map<String, Object> shares =
				(Map<String, Object>) stats(ADMIN).get("attendance_by_department_stats");

		assertThat(shares).containsKeys("Engineering", "Sales");
		assertThat(((Number) shares.get("Sales")).doubleValue())
				.as("no attendance seeded for Sales today")
				.isEqualTo(0.0);
		assertThat(((Number) shares.get("Engineering")).doubleValue())
				.as("a percentage in range")
				.isBetween(0.0, 100.0);
	}

	@Test
	@SuppressWarnings("unchecked")
	void workforcePlanningIsAListSoDuplicateDepartmentNamesSurvive() {
		List<Map<String, Object>> rows =
				(List<Map<String, Object>>) stats(ADMIN).get("workforce_planning_stats");

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).keySet()).containsExactly("department_name", "planned", "actual");
		assertThat(rows).extracting(row -> row.get("department_name"))
				.containsExactlyInAnyOrder("Engineering", "Sales");
	}

	@Test
	@SuppressWarnings("unchecked")
	void genderAndAgeBucketsUseTheirLiteralUnknownKeys() {
		Map<String, Object> stats = stats(ADMIN);

		assertThat((Map<String, Object>) stats.get("employees_by_gender"))
				.as("a blank gender collapses to the literal key 'unknown'")
				.containsKeys("male", "unknown");
		assertThat((Map<String, Object>) stats.get("employees_by_age_bracket"))
				.as("a null birth_date is 'unknown'")
				.containsKey("unknown");
	}

	@Test
	void theThreeTurnoverRatesArePresentAndNonNegative() {
		Map<String, Object> stats = stats(ADMIN);

		for (String key : List.of("monthly_turnover_rate", "new_employee_turnover_rate",
				"annual_turnover_rate")) {
			assertThat(stats.get(key)).as("%s", key).isInstanceOf(Number.class);
			assertThat(((Number) stats.get(key)).doubleValue()).as("%s", key).isGreaterThanOrEqualTo(0.0);
		}
	}

	// ---------------- fixture ----------------

	@SuppressWarnings("unchecked")
	private Map<String, Object> stats(long actor) {
		ResponseEntity<Map<String, Object>> response = get(token(actor, "company_admin"));
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return (Map<String, Object>) response.getBody().get("data");
	}

	private ResponseEntity<Map<String, Object>> get(String token) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + STATS), HttpMethod.GET,
				new HttpEntity<>(headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String token(long employeeId, String role) {
		long company = employeeId == EMPTY_ADMIN ? EMPTY_COMPANY : COMPANY;
		return jwtService.issueAccessToken(employeeId, employeeId, company, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Dash Co', '+201000025001', 'active', '2019-01-15 09:00:00'),"
					+ " (" + EMPTY_COMPANY + ", 'Empty Co', '+201000025002', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00'),"
					+ " (" + (BRANCH + 1) + ", " + COMPANY + ", 'Closed', 0, '2019-03-01 10:00:00'),"
					+ " (" + (BRANCH + 2) + ", " + EMPTY_COMPANY + ", 'Only', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO departments (id, company_id, name, created_at) VALUES"
					+ " (" + DEPT_A + ", " + COMPANY + ", 'Engineering', '2019-03-01 10:00:00'),"
					+ " (" + DEPT_B + ", " + COMPANY + ", 'Sales', '2019-03-01 10:00:00')");

			// Three active employees: two Engineering (one with no gender, one with
			// no birth_date), one Sales.
			employee(st, ADMIN, DEPT_A, "company_admin", "'male'", "'1990-05-05'", 1);
			employee(st, EMPLOYEE, DEPT_A, "employee", "''", "NULL", 1);
			employee(st, ADMIN + 5, DEPT_B, "employee", "'male'", "'1985-01-01'", 1);
			// One inactive, for resignations/turnover.
			employee(st, ADMIN + 6, DEPT_B, "employee", "'male'", "'1980-01-01'", 0);
			// The empty company's admin.
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + EMPTY_ADMIN + ", "
					+ EMPTY_COMPANY + ", " + (BRANCH + 2) + ", '2502', 'Empty', 'Admin', '+201000250021',"
					+ " 'company_admin', 1, '2019-04-01 08:00:00')");

			// Two contracts for ADMIN (history), one each for the other two actives.
			contract(st, ADMIN, 1000, "'2019-01-01'");
			contract(st, ADMIN, 2000, "'2020-01-01'");
			contract(st, EMPLOYEE, 3000, "'2019-01-01'");
			contract(st, ADMIN + 5, 4000, "'2019-01-01'");

			st.execute("INSERT INTO workforce_planning (id, company_id, branch_id, department_id,"
					+ " job_title_id, planned_count) VALUES"
					+ " (1, " + COMPANY + ", " + BRANCH + ", " + DEPT_A + ", 0, 5),"
					+ " (2, " + COMPANY + ", " + BRANCH + ", " + DEPT_B + ", 0, 3)");

			st.execute("INSERT INTO attendance (employee_id, check_in) VALUES"
					+ " (" + ADMIN + ", CONCAT(CURRENT_DATE, ' 09:00:00'))");

			st.execute("INSERT INTO penalties (id, employee_id, penalty_type, penalty_days, reason,"
					+ " penalty_date, applied_to_payroll, created_at) VALUES"
					+ " (1, " + ADMIN + ", 'late', 1.0, 'r', '2021-06-10', 0, '2021-06-10 08:00:00')");
		}
	}

	private static void employee(Statement st, long id, long departmentId, String role,
			String gender, String birthDate, int active) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, department_id, employee_code,"
				+ " first_name, last_name, phone, role, gender, birth_date, is_active, hire_date, created_at)"
				+ " VALUES (" + id + ", " + COMPANY + ", " + BRANCH + ", " + departmentId + ", '" + id + "',"
				+ " 'F', 'L', '+2010000" + id + "', '" + role + "', " + gender + ", " + birthDate + ", "
				+ active + ", '2019-04-01', '2019-04-01 08:00:00')");
	}

	private static void contract(Statement st, long employeeId, int basic, String effectiveFrom)
			throws Exception {
		st.execute("INSERT INTO salary_contracts (employee_id, basic_salary, transport_allowance,"
				+ " food_allowance, risk_allowance, incentives, effective_from) VALUES ("
				+ employeeId + ", " + basic + ", 0, 0, 0, 0, " + effectiveFrom + ")");
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
		try (InputStream in = LegacyDashboardEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
