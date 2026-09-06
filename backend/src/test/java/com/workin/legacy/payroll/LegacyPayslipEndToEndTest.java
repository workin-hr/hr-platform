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
 * {@code payslips/*.php} (Wave 12.9 slice 3): {@code list.php}/{@code one.php}'s
 * live-recompute enrichment ({@code payroll_enrich_payslip_row()}), {@code
 * create.php}'s own simpler unenriched formula, {@code update.php}'s own
 * distinct recalculation formula, and the {@code EMPLOYEE} role's ownership
 * scoping -- four genuinely different code paths sharing one table, each
 * exercised over real HTTP against a hand-verified fixture.
 *
 * <p>Every scenario uses its own fiscal month on the same narrow five-day
 * custom period (1st-5th) {@link LegacyPayrollBatchCalculateEndToEndTest}
 * established, so payslips from one scenario never collide with another's
 * {@code already_exists} check.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyPayslipEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String BATCH_CREATE = "/apis/api/payroll_batches/create.php";
	private static final String BATCH_CALCULATE = "/apis/api/payroll_batches/calculate.php";
	private static final String LIST = "/apis/api/payslips/list.php";
	private static final String ONE = "/apis/api/payslips/one.php";
	private static final String CREATE = "/apis/api/payslips/create.php";
	private static final String UPDATE = "/apis/api/payslips/update.php";
	private static final String DELETE = "/apis/api/payslips/delete.php";

	private static final long COMPANY = 21501L;
	private static final long ADMIN = 215011L;
	private static final long EMPLOYEE = 215012L;
	private static final long BRANCH = 21511L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the payslips fixture", ex);
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
	 * Five full attendance days over the narrow period again gives the exact
	 * same hand-verified figures {@link LegacyPayrollBatchCalculateEndToEndTest}
	 * uses: gross 3000.00, day rate 100.00, zero absence, zero overtime -- and
	 * since the period is in the past, {@code payroll_enrich_payslip_row()}
	 * reproduces those same figures rather than proration.
	 */
	@Test
	void oneAndListReturnTheHandVerifiedEnrichedPayslip() throws Exception {
		long batchId = createAndCalculate(1, "2020-01");

		Map<String, Object> one = dataOf(send(ONE, ADMIN, HttpMethod.GET, null, 200, oneQuery(batchId)));
		assertThat(decimalString(one.get("gross_salary"))).isEqualTo("3000.00");
		assertThat(decimalString(one.get("daily_basic_rate"))).isEqualTo("100.00");
		assertThat(decimalString(one.get("absence_cost"))).isEqualTo("0.00");
		assertThat(decimalString(one.get("salary_by_present_days"))).isEqualTo("3000.00");
		assertThat(decimalString(one.get("total_entitlements"))).isEqualTo("3000.00");
		assertThat(decimalString(one.get("total_deductions"))).isEqualTo("0.00");
		assertThat(decimalString(one.get("net_salary"))).isEqualTo("3000.00");
		assertThat(number(one.get("expected_work_days"))).isEqualTo(5L);
		assertThat(number(one.get("expected_work_days_due"))).isEqualTo(5L);
		assertThat(number(one.get("days_present"))).isEqualTo(5L);
		assertThat(number(one.get("days_absent"))).isEqualTo(0L);
		assertThat(number(one.get("official_holiday_days"))).isEqualTo(0L);
		assertThat(number(one.get("earned_weekly_rest_days"))).isEqualTo(0L);
		assertThat(number(one.get("void_weekly_rest_days"))).isEqualTo(0L);
		assertThat(one.get("present_details")).isInstanceOf(java.util.List.class);
		@SuppressWarnings("unchecked")
		java.util.List<Map<String, Object>> presentDetails = (java.util.List<Map<String, Object>>) one.get("present_details");
		assertThat(presentDetails).hasSize(5);
		// Regression: attendancePresentDetails() previously labeled every real
		// worked-day row with the weekly-rest label instead of the dedicated
		// "present" label (csv_attendance_present_day / "Attendance") --
		// wire-visible text divergence on every payslip read.
		for (Map<String, Object> detail : presentDetails) {
			assertThat(detail.get("day_type")).isEqualTo("attendance");
			assertThat(detail.get("label")).isEqualTo("Attendance");
		}

		Map<String, Object> listBody = send(LIST, ADMIN, HttpMethod.GET, null, 200, "?batch_id=" + batchId);
		java.util.List<Map<String, Object>> rows = rowsOf(listBody);
		assertThat(rows).hasSize(1);
		assertThat(decimalString(rows.getFirst().get("net_salary"))).isEqualTo("3000.00");
		assertThat(listBody.get("meta")).isNotNull();
	}

	/**
	 * {@code update.php}'s own recalculation formula (plain gross minus absence
	 * cost, no elapsed-days proration) differs from enrichment's, but both agree
	 * for a closed period -- the response is the freshly-enriched row, which
	 * must reflect the just-written {@code other_deductions}.
	 */
	@Test
	void updateRecalculatesAndTheResponseReflectsTheWrite() throws Exception {
		long batchId = createAndCalculate(3, "2020-03");
		long payslipId = payslipId(batchId);

		Map<String, Object> updated = dataOf(send(
				UPDATE, ADMIN, HttpMethod.PUT, "{\"other_deductions\":50}", 200, "?id=" + payslipId));
		assertThat(decimalString(updated.get("total_deductions"))).isEqualTo("50.00");
		assertThat(decimalString(updated.get("net_salary"))).isEqualTo("2950.00");

		Map<String, Object> stored = queryPayslip(payslipId);
		assertThat(decimalString(stored.get("other_deductions"))).isEqualTo("50.00");
		assertThat(decimalString(stored.get("net_salary"))).isEqualTo("2950.00");
	}

	/**
	 * Regression: {@code update.php} previously used {@code containsKey}
	 * instead of PHP's {@code ??} null-coalescing, so a body with an explicit
	 * JSON {@code null} for a money field (a client serializing an unset
	 * optional field, not a hostile one) zeroed and persisted it instead of
	 * preserving the stored value. Basic salary going to zero also zeros
	 * gross/day-rate/net -- verify the whole chain survives.
	 */
	@Test
	void updatePreservesStoredValuesWhenTheBodyExplicitlyNullsThem() throws Exception {
		long batchId = createAndCalculate(6, "2020-06");
		long payslipId = payslipId(batchId);

		Map<String, Object> updated = dataOf(send(
				UPDATE, ADMIN, HttpMethod.PUT,
				"{\"basic_salary\":null,\"other_deductions\":null}", 200, "?id=" + payslipId));
		assertThat(decimalString(updated.get("basic_salary"))).isEqualTo("3000.00");
		assertThat(decimalString(updated.get("gross_salary"))).isEqualTo("3000.00");
		assertThat(decimalString(updated.get("net_salary"))).isEqualTo("3000.00");

		Map<String, Object> stored = queryPayslip(payslipId);
		assertThat(decimalString(stored.get("basic_salary"))).isEqualTo("3000.00");
		assertThat(decimalString(stored.get("net_salary"))).isEqualTo("3000.00");
	}

	/**
	 * {@code create.php} never enriches -- the response is exactly the inserted
	 * row, with its own simpler formula (no housing allowance, no attendance
	 * recompute). {@code delete.php} then removes it.
	 */
	@Test
	void createIsUnenrichedAndDeleteRemovesTheRow() throws Exception {
		Map<String, Object> batch = dataOf(send(BATCH_CREATE, ADMIN, HttpMethod.POST,
				"{\"month\":4,\"year\":2020}", 201));
		long batchId = number(batch.get("id"));

		String body = "{\"batch_id\":" + batchId + ",\"employee_id\":" + EMPLOYEE + ","
				+ "\"days_present\":4,\"days_absent\":1,\"days_leave\":0,"
				+ "\"overtime_hours\":2,\"overtime_pay\":80,\"penalties_total\":30,"
				+ "\"advance_deduction\":20,\"other_deductions\":10}";
		Map<String, Object> created = dataOf(send(CREATE, ADMIN, HttpMethod.POST, body, 201));
		assertThat(decimalString(created.get("basic_salary"))).isEqualTo("3000.00");
		// allowances = transport + food + risk + incentives, contract has none set -> 0; no housing.
		assertThat(decimalString(created.get("allowances"))).isEqualTo("0.00");
		// net = (basic 3000 + allowances 0 + overtime_pay 80) - (penalties 30 + advance 20 + other 10) = 3020.00.
		assertThat(decimalString(created.get("net_salary"))).isEqualTo("3020.00");
		assertThat(created).doesNotContainKey("expected_work_days");
		assertThat(created).doesNotContainKey("present_details");

		long payslipId = number(created.get("id"));
		send(DELETE, ADMIN, HttpMethod.DELETE, null, 200, "?id=" + payslipId);
		Map<String, Object> gone = send(ONE, ADMIN, HttpMethod.GET, null, 404, "?id=" + payslipId);
		assertThat(gone.get("message")).isEqualTo("payslip_not_found");
	}

	/** The {@code EMPLOYEE} role reads only its own payslip; a foreign id is {@code 403 forbidden}. */
	@Test
	void employeeRoleIsScopedToItsOwnPayslip() throws Exception {
		long batchId = createAndCalculate(5, "2020-05");
		long payslipId = payslipId(batchId);

		Map<String, Object> own = dataOf(send(ONE, EMPLOYEE, HttpMethod.GET, null, 200, "?id=" + payslipId));
		assertThat(own.get("id")).isNotNull();

		Map<String, Object> listBody = send(
				LIST, EMPLOYEE, HttpMethod.GET, null, 200, "?employee_id=999999&batch_id=" + batchId);
		java.util.List<Map<String, Object>> rows = rowsOf(listBody);
		assertThat(rows).hasSize(1);
		assertThat(number(rows.getFirst().get("employee_id"))).isEqualTo(EMPLOYEE);

		long otherEmployeeId = 215013L;
		insertForeignEmployeeAndPayslip(otherEmployeeId, batchId);
		Map<String, Object> forbidden = send(
				ONE, EMPLOYEE, HttpMethod.GET, null, 403, "?id=" + foreignPayslipId(otherEmployeeId, batchId));
		assertThat(forbidden.get("message")).isEqualTo("Forbidden");
	}

	// ------------------------------------------------------------------
	// Fixture helpers
	// ------------------------------------------------------------------

	private long createAndCalculate(int month, String yearMonth) throws Exception {
		seedFiveDayAttendance(yearMonth);
		Map<String, Object> batch = dataOf(send(BATCH_CREATE, ADMIN, HttpMethod.POST,
				"{\"month\":" + month + ",\"year\":2020}", 201));
		long batchId = number(batch.get("id"));
		send(BATCH_CALCULATE, ADMIN, HttpMethod.POST, null, 200, "?id=" + batchId);
		return batchId;
	}

	private String oneQuery(long batchId) throws Exception {
		return "?id=" + payslipId(batchId);
	}

	private long payslipId(long batchId) throws Exception {
		return number(queryScalar(
				"SELECT id FROM payslips WHERE batch_id=" + batchId + " AND employee_id=" + EMPLOYEE));
	}

	private void insertForeignEmployeeAndPayslip(long otherEmployeeId, long batchId) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT IGNORE INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + otherEmployeeId + ", " + COMPANY
					+ ", " + BRANCH + ", " + otherEmployeeId + ", 'Other', 'Employee', '+20100" + otherEmployeeId
					+ "', 'employee', 1, '2019-04-01 08:00:00')");
			st.execute("INSERT IGNORE INTO payslips (batch_id, employee_id, days_present, days_absent, days_leave,"
					+ " overtime_hours, basic_salary, allowances, overtime_pay, penalties_total, advance_deduction,"
					+ " other_deductions, net_salary) VALUES (" + batchId + ", " + otherEmployeeId
					+ ", 5, 0, 0, 0, 1000.00, 0, 0, 0, 0, 0, 1000.00)");
		}
	}

	private long foreignPayslipId(long otherEmployeeId, long batchId) throws Exception {
		return number(queryScalar(
				"SELECT id FROM payslips WHERE batch_id=" + batchId + " AND employee_id=" + otherEmployeeId));
	}

	private void seedFiveDayAttendance(String yearMonth) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			for (int day = 1; day <= 5; day++) {
				String date = String.format("%s-%02d", yearMonth, day);
				st.execute("INSERT INTO attendance (employee_id, check_in, check_out, method, created_at) VALUES ("
						+ EMPLOYEE + ", '" + date + " 09:00:00', '" + date + " 17:00:00', 'app',"
						+ " '" + date + " 09:00:00')");
			}
		}
	}

	// ------------------------------------------------------------------
	// Shared HTTP / SQL plumbing
	// ------------------------------------------------------------------

	private Object queryScalar(String sql) throws Exception {
		try (Connection connection = connect();
				Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(sql)) {
			assertThat(rs.next()).as("query returned a row: %s", sql).isTrue();
			return rs.getObject(1);
		}
	}

	private Map<String, Object> queryPayslip(long payslipId) throws Exception {
		try (Connection connection = connect();
				Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery("SELECT * FROM payslips WHERE id=" + payslipId)) {
			assertThat(rs.next()).as("payslip row for id %s", payslipId).isTrue();
			Map<String, Object> row = new java.util.LinkedHashMap<>();
			java.sql.ResultSetMetaData meta = rs.getMetaData();
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				row.put(meta.getColumnLabel(i), rs.getObject(i));
			}
			return row;
		}
	}

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

	@SuppressWarnings("unchecked")
	private static java.util.List<Map<String, Object>> rowsOf(Map<String, Object> body) {
		return (java.util.List<Map<String, Object>>) body.get("data");
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
		String role = employeeId == EMPLOYEE ? "employee" : "company_admin";
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("SET time_zone = '+00:00'");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (21501, 'Payslips Co', '+201000021501', 'active', '2019-01-15 09:00:00')
					""");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + ADMIN + ", " + COMPANY
					+ ", " + BRANCH + ", " + ADMIN + ", 'Admin', 'One', '+201000215011', 'company_admin',"
					+ " 1, '2019-04-01 08:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + EMPLOYEE + ", " + COMPANY
					+ ", " + BRANCH + ", " + EMPLOYEE + ", 'Emp', 'One', '+201000215012', 'employee',"
					+ " 1, '2019-04-01 08:00:00')");

			// A narrow five-day fiscal period: month_start_day=1, month_end_day=5.
			st.execute("INSERT INTO setting_definitions (id, setting_key, is_multi) VALUES"
					+ " (2150100, 'month_start_day', 0), (2150101, 'month_end_day', 0)");
			st.execute("INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES"
					+ " (2150100, " + COMPANY + ", 2150100), (2150101, " + COMPANY + ", 2150101)");
			st.execute("INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order) VALUES"
					+ " (2150100, 2150100, '1', 0), (2150101, 2150101, '5', 0)");
			st.execute("INSERT INTO company_setting_values (company_setting_id, setting_allowed_value_id) VALUES"
					+ " (2150100, 2150100), (2150101, 2150101)");

			st.execute("INSERT INTO salary_contracts (id, employee_id, salary_mode, basic_salary,"
					+ " effective_from, created_at) VALUES (2150100, " + EMPLOYEE
					+ ", 'monthly', 3000.00, '2019-06-01', '2019-06-01 08:00:00')");
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
		try (InputStream stream = LegacyPayslipEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
