package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
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
import org.springframework.core.ParameterizedTypeReference;
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

/**
 * {@code payroll_batches/create.php} then {@code calculate.php}: the full
 * pipeline (fiscal bounds, attendance figures, the calculation formula, and
 * the DB write) exercised over real HTTP, not just each piece in isolation.
 *
 * <p>The fiscal period is deliberately narrowed to five days (a custom
 * {@code month_start_day}/{@code month_end_day} of 1/5) rather than a full
 * calendar month, so a hand-verifiable scenario needs five attendance rows,
 * not thirty. January 2020 is used so the period is always in the past --
 * {@code payroll_compute_employee_payslip}'s in-progress proration never
 * applies, regardless of when the suite runs.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyPayrollBatchCalculateEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String CREATE = "/apis/api/payroll_batches/create.php";
	private static final String CALCULATE = "/apis/api/payroll_batches/calculate.php";
	private static final String FINALIZE = "/apis/api/payroll_batches/finalize.php";
	private static final String REOPEN = "/apis/api/payroll_batches/reopen.php";
	private static final String STATS = "/apis/api/payroll_batches/stats.php";

	private static final long COMPANY_1 = 21401L;
	private static final long ADMIN_1 = 214011L;
	private static final long EMPLOYEE_1 = 214012L;
	private static final long BRANCH_1 = 21411L;

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
			throw new IllegalStateException("could not prepare the payroll calculate fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	/**
	 * Five full attendance days (no absence, no overtime, no weekly-off
	 * setting configured) over a five-day fiscal period gives an exact,
	 * hand-verifiable net salary: gross 3000.00, day rate 100.00 (still the
	 * fixed 30-day divisor, not the 5-day period length), zero absence
	 * cost, zero overtime -> net salary 3000.00, days_present 5.
	 */
	@Test
	void calculateProducesTheHandVerifiedPayslipForACompletePresentPeriod() throws Exception {
		Map<String, Object> createBody = dataOf(send(CREATE, ADMIN_1, HttpMethod.POST,
				"{\"month\":1,\"year\":2020}", 201));
		long batchId = number(createBody.get("id"));

		Map<String, Object> calcBody = send(CALCULATE, ADMIN_1, HttpMethod.POST, null, 200, "?id=" + batchId);
		assertThat(calcBody.get("message")).isEqualTo("Payroll calculated for 1 employees");
		assertThat(calcBody).doesNotContainKey("meta");

		// calculate.php's response is the batch row plus payslip aggregates
		// (get_payroll_batch_with_stats()), not an individual payslip row.
		Map<String, Object> batchRow = dataOf(calcBody);
		assertThat(batchRow.get("period_from")).isEqualTo("2020-01-01");
		assertThat(batchRow.get("period_to")).isEqualTo("2020-01-05");
		assertThat(number(batchRow.get("employees_count"))).isEqualTo(1L);
		assertThat(decimalString(batchRow.get("total_net_salary"))).isEqualTo("3000.00");

		Map<String, Object> payslip = queryPayslip(batchId);
		assertThat(decimalString(payslip.get("basic_salary"))).isEqualTo("3000.00");
		assertThat(decimalString(payslip.get("gross_salary"))).isEqualTo("3000.00");
		assertThat(decimalString(payslip.get("total_entitlements"))).isEqualTo("3000.00");
		assertThat(decimalString(payslip.get("total_deductions"))).isEqualTo("0.00");
		assertThat(decimalString(payslip.get("net_salary"))).isEqualTo("3000.00");
		assertThat(number(payslip.get("days_present"))).isEqualTo(5L);
		assertThat(number(payslip.get("days_absent"))).isEqualTo(0L);
		assertThat(decimalString(payslip.get("overtime_pay"))).isEqualTo("0.00");

		Map<String, Object> stats = dataOf(send(STATS, ADMIN_1, HttpMethod.GET, null, 200, "?id=" + batchId));
		assertThat(number(stats.get("total_employees"))).isEqualTo(1L);
		assertThat(decimalString(stats.get("total_net_salary"))).isEqualTo("3000.00");
	}

	/**
	 * Exercises the transactional side effects end to end: {@code finalize.php}
	 * applies the advance payment (its {@code remaining} drops) and marks the
	 * penalty {@code applied_to_payroll}; {@code reopen.php} reverses both.
	 * This is the real proof the {@code SingleConnectionDataSource}-scoped
	 * transaction actually commits real writes, not just that it compiles.
	 */
	@Test
	void finalizeAppliesAdvanceAndPenaltySideEffectsAndReopenReversesThem() throws Exception {
		Map<String, Object> createBody = dataOf(send(CREATE, ADMIN_1, HttpMethod.POST,
				"{\"month\":2,\"year\":2020}", 201));
		long batchId = number(createBody.get("id"));
		send(CALCULATE, ADMIN_1, HttpMethod.POST, null, 200, "?id=" + batchId);

		assertThat(decimalString(queryScalar("SELECT remaining FROM advances WHERE id=2140100")))
				.isEqualTo("200.00");
		assertThat(appliedToPayroll()).isFalse();

		Map<String, Object> finalized = dataOf(send(FINALIZE, ADMIN_1, HttpMethod.PUT, null, 200, "?id=" + batchId));
		assertThat(finalized.get("status")).isEqualTo("finalized");
		// The advance's single_payroll_month plan (200.00, year=2020/month=2) is
		// fully applied since it is well within the employee's remaining balance.
		assertThat(decimalString(queryScalar("SELECT remaining FROM advances WHERE id=2140100")))
				.isEqualTo("0.00");
		assertThat(appliedToPayroll()).isTrue();

		Map<String, Object> reopened = dataOf(send(REOPEN, ADMIN_1, HttpMethod.PUT, null, 200, "?id=" + batchId));
		assertThat(reopened.get("status")).isEqualTo("draft");
		assertThat(decimalString(queryScalar("SELECT remaining FROM advances WHERE id=2140100")))
				.isEqualTo("200.00");
		assertThat(appliedToPayroll()).isFalse();
	}

	/** {@code tinyint(1)} reads back as a JDBC {@code Boolean} via {@code getObject()}, not 0/1. */
	private boolean appliedToPayroll() throws Exception {
		Object raw = queryScalar("SELECT applied_to_payroll FROM penalties WHERE id=2140100");
		return raw instanceof Boolean bool ? bool : ((Number) raw).intValue() != 0;
	}

	private Object queryScalar(String sql) throws Exception {
		try (Connection connection = connect();
				Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(sql)) {
			assertThat(rs.next()).as("query returned a row: %s", sql).isTrue();
			return rs.getObject(1);
		}
	}

	private Map<String, Object> queryPayslip(long batchId) throws Exception {
		try (Connection connection = connect();
				Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(
						"SELECT * FROM payslips WHERE batch_id=" + batchId + " AND employee_id=" + EMPLOYEE_1)) {
			assertThat(rs.next()).as("payslip row for employee %s in batch %s", EMPLOYEE_1, batchId).isTrue();
			Map<String, Object> row = new java.util.LinkedHashMap<>();
			java.sql.ResultSetMetaData meta = rs.getMetaData();
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				row.put(meta.getColumnLabel(i), rs.getObject(i));
			}
			return row;
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private String decimalString(Object raw) {
		return raw == null ? null : new java.math.BigDecimal(raw.toString()).setScale(2).toString();
	}

	private static long number(Object raw) {
		return ((Number) raw).longValue();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	private Map<String, Object> send(
			String path, long actor, HttpMethod method, String json, int expectedStatus) {
		return send(path, actor, method, json, expectedStatus, "");
	}

	private Map<String, Object> send(
			String path, long actor, HttpMethod method, String json, int expectedStatus, String query) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(actor));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path + query), method,
				new HttpEntity<>(json, headers), mapType());
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(expectedStatus);
		return response.getBody();
	}

	private static ParameterizedTypeReference<Map<String, Object>> mapType() {
		return new ParameterizedTypeReference<Map<String, Object>>() { };
	}

	private String tokenFor(long employeeId) {
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY_1, "test-session",
				Map.of("role", "company_admin", "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("SET time_zone = '+00:00'");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (21401, 'Payroll Calc Co', '+201000021401', 'active', '2019-01-15 09:00:00')
					""");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH_1 + ", " + COMPANY_1 + ", 'Main', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + ADMIN_1 + ", " + COMPANY_1
					+ ", " + BRANCH_1 + ", " + ADMIN_1 + ", 'Admin', 'One', '+201000214011', 'company_admin',"
					+ " 1, '2019-04-01 08:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + EMPLOYEE_1 + ", " + COMPANY_1
					+ ", " + BRANCH_1 + ", " + EMPLOYEE_1 + ", 'Emp', 'One', '+201000214012', 'employee',"
					+ " 1, '2019-04-01 08:00:00')");

			// A narrow five-day fiscal period: month_start_day=1, month_end_day=5.
			st.execute("INSERT INTO setting_definitions (id, setting_key, is_multi) VALUES"
					+ " (2140100, 'month_start_day', 0), (2140101, 'month_end_day', 0)");
			st.execute("INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES"
					+ " (2140100, " + COMPANY_1 + ", 2140100), (2140101, " + COMPANY_1 + ", 2140101)");
			st.execute("INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order) VALUES"
					+ " (2140100, 2140100, '1', 0), (2140101, 2140101, '5', 0)");
			st.execute("INSERT INTO company_setting_values (company_setting_id, setting_allowed_value_id) VALUES"
					+ " (2140100, 2140100), (2140101, 2140101)");

			st.execute("INSERT INTO salary_contracts (id, employee_id, salary_mode, basic_salary,"
					+ " effective_from, created_at) VALUES (2140100, " + EMPLOYEE_1
					+ ", 'monthly', 3000.00, '2019-06-01', '2019-06-01 08:00:00')");

			for (int day = 1; day <= 5; day++) {
				String date = String.format("2020-01-%02d", day);
				st.execute("INSERT INTO attendance (employee_id, check_in, check_out, method, created_at) VALUES ("
						+ EMPLOYEE_1 + ", '" + date + " 09:00:00', '" + date + " 17:00:00', 'app',"
						+ " '" + date + " 09:00:00')");
			}
			// February attendance for the finalize/reopen scenario -- same
			// employee, a different batch month.
			for (int day = 1; day <= 5; day++) {
				String date = String.format("2020-02-%02d", day);
				st.execute("INSERT INTO attendance (employee_id, check_in, check_out, method, created_at) VALUES ("
						+ EMPLOYEE_1 + ", '" + date + " 09:00:00', '" + date + " 17:00:00', 'app',"
						+ " '" + date + " 09:00:00')");
			}
			st.execute("INSERT INTO advances (id, employee_id, amount, remaining, deduction_mode,"
					+ " deduction_payroll_year, deduction_payroll_month, status, request_date, created_at) VALUES"
					+ " (2140100, " + EMPLOYEE_1 + ", 200.00, 200.00, 'single_payroll_month',"
					+ " 2020, 2, 'approved', '2020-01-20', '2020-01-20 08:00:00')");
			st.execute("INSERT INTO penalties (id, employee_id, penalty_type, penalty_days, penalty_date,"
					+ " applied_to_payroll, created_at) VALUES (2140100, " + EMPLOYEE_1
					+ ", 'late', 1.0, '2020-02-03', 0, '2020-02-03 08:00:00')");
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
		try (InputStream stream = LegacyPayrollBatchCalculateEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
