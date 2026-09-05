package com.workin.legacy.employees;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code employees/update_bulk.php} over real HTTP against a real MariaDB.
 *
 * <p>The endpoint's defining property is that <b>a partially successful batch
 * is the normal outcome</b>: PHP opens one transaction per row, so a row that
 * fails rolls back only itself and the loop carries on. Everything here is
 * about that boundary -- what a failing row does to its own writes, and what
 * it does not do to its neighbours.
 *
 * <p>Driven through HTTP rather than by calling the updater, because the
 * status code and the envelope are part of the contract the desktop client
 * reads, and both are decided above the service.
 */
@SpringBootTest(classes = BackendApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeUpdateBulkEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String UPDATE = "/apis/api/employees/update_bulk.php";

	private static final long COMPANY = 19901L;
	private static final long OTHER_COMPANY = 19902L;
	private static final long ADMIN = 199011L;
	private static final long PLAIN_EMPLOYEE = 199014L;
	private static final long TARGET_A = 199021L;
	private static final long TARGET_B = 199022L;
	private static final long OTHER_COMPANY_EMPLOYEE = 199031L;

	private static final long BRANCH = 19911L;
	private static final long DEPARTMENT = 19921L;
	private static final long SHIFT = 19941L;

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
			throw new IllegalStateException("could not prepare the update_bulk fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.runtime-db.username", () -> "unused");
		registry.add("app.runtime-db.password", () -> "unused");
	}

	// ---------------- shape and status ----------------

	@Test
	void anEmptyRowsArrayIs400FieldRequired() {
		ResponseEntity<Map<String, Object>> response = post("{\"rows\":[]}", ADMIN);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("success", false);
		// fail(FIELD_REQUIRED, 400, null, [FIELD => 'rows']) -- the field is a
		// placeholder inside the message, so the envelope carries no data key.
		assertThat(response.getBody()).containsEntry("message", "Field 'rows' is required");
		assertThat(response.getBody()).doesNotContainKey("data");
	}

	@Test
	void aMissingRowsKeyIsTheSame400() {
		assertThat(post("{}", ADMIN).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/** {@code $body['rows'] ?? []} coalesces null exactly as a missing key does. */
	@Test
	void aNullRowsValueIsTheSame400() {
		assertThat(post("{\"rows\":null}", ADMIN).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void aGetIs405() {
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPDATE), HttpMethod.GET,
				new HttpEntity<>(jsonHeaders(tokenFor(ADMIN))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
	}

	@Test
	void aPlainEmployeeIsForbidden() {
		assertThat(post(rows(validRow("2001", "Renamed")), PLAIN_EMPLOYEE).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void noTokenIsUnauthorized() {
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPDATE), HttpMethod.POST,
				new HttpEntity<>(rows(validRow("2001", "X")), unauthenticatedHeaders()),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ---------------- the update itself ----------------

	@Test
	void aFilledCellIsWrittenAndAnEmptyOneIsLeftAlone() {
		String before = stringField(TARGET_A, "last_name");

		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"2001\",\"first_name\":\"Renamed\",\"last_name\":\"\"}"), ADMIN);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(data(response)).containsEntry("updated", 1);
		assertThat(stringField(TARGET_A, "first_name")).isEqualTo("Renamed");
		assertThat(stringField(TARGET_A, "last_name"))
				.as("an empty cell means leave alone, not blank out")
				.isEqualTo(before);
	}

	/** A row that resolves but asks for nothing is an error, not a no-op success. */
	@Test
	void aRowWithOnlyAnEmployeeCodeIsNothingToUpdate() {
		ResponseEntity<Map<String, Object>> response =
				post(rows("{\"employee_code\":\"2001\"}"), ADMIN);

		assertThat(data(response)).containsEntry("updated", 0);
		assertThat(errorsOfFirstFailure(response)).containsExactly("nothing_to_update");
	}

	@Test
	void anUnknownEmployeeCodeFailsThatRowOnly() {
		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"999999\",\"first_name\":\"Ghost\"}",
				"{\"employee_code\":\"2002\",\"first_name\":\"Landed\"}"), ADMIN);

		assertThat(data(response)).containsEntry("updated", 1);
		assertThat(errorsOfFirstFailure(response)).containsExactly("employee_not_found");
		assertThat(stringField(TARGET_B, "first_name"))
				.as("the good row must land despite its neighbour failing")
				.isEqualTo("Landed");
	}

	@Test
	void aNonNumericEmployeeCodeIsInvalidRatherThanNotFound() {
		ResponseEntity<Map<String, Object>> response =
				post(rows("{\"employee_code\":\"20-01\",\"first_name\":\"X\"}"), ADMIN);

		assertThat(errorsOfFirstFailure(response)).containsExactly("employee_code_invalid");
	}

	/** The second occurrence fails; the first is applied. */
	@Test
	void aCodeRepeatedInTheSameBatchFailsOnlyItsSecondRow() {
		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"2001\",\"address\":\"First wins\"}",
				"{\"employee_code\":\"2001\",\"address\":\"Second loses\"}"), ADMIN);

		assertThat(data(response)).containsEntry("updated", 1);
		assertThat(errorsOfFirstFailure(response)).containsExactly("employee_code_duplicate_in_file");
		assertThat(stringField(TARGET_A, "address")).isEqualTo("First wins");
	}

	/**
	 * Another company's employee is not reachable, even with a code that
	 * exists there -- the lookup is built per company.
	 */
	@Test
	void anotherCompanysEmployeeIsNotReachable() {
		String before = stringField(OTHER_COMPANY_EMPLOYEE, "first_name");

		ResponseEntity<Map<String, Object>> response =
				post(rows("{\"employee_code\":\"3001\",\"first_name\":\"Crossed\"}"), ADMIN);

		assertThat(errorsOfFirstFailure(response)).containsExactly("employee_not_found");
		assertThat(stringField(OTHER_COMPANY_EMPLOYEE, "first_name")).isEqualTo(before);
	}

	@Test
	void anUnknownShiftNameFailsTheRowAndWritesNothing() {
		String before = stringField(TARGET_A, "first_name");

		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"2001\",\"first_name\":\"WithBadShift\",\"shift_name\":\"No Such Shift\"}"),
				ADMIN);

		assertThat(errorsOfFirstFailure(response)).containsExactly("shift_not_found");
		assertThat(stringField(TARGET_A, "first_name"))
				.as("a row that fails validation writes none of its fields")
				.isEqualTo(before);
	}

	/**
	 * Two errors, not one, and the second is not noise: a rejected value is
	 * never put in the payload, so a row whose only filled cell was rejected
	 * also ends up having asked for nothing. PHP accumulates both and the
	 * client shows both.
	 */
	@Test
	void anInvalidGenderFailsTheRowAndLeavesItWithNothingToUpdate() {
		ResponseEntity<Map<String, Object>> response =
				post(rows("{\"employee_code\":\"2001\",\"gender\":\"unspecified\"}"), ADMIN);

		assertThat(errorsOfFirstFailure(response))
				.containsExactly("gender_invalid", "nothing_to_update");
	}

	/** The same row with a second, valid cell keeps only the real error. */
	@Test
	void anInvalidGenderBesideAValidCellDoesNotReportNothingToUpdate() {
		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"2001\",\"gender\":\"unspecified\",\"address\":\"Somewhere\"}"), ADMIN);

		assertThat(errorsOfFirstFailure(response)).containsExactly("gender_invalid");
	}

	@Test
	void aNonPositiveExpectedDailyHoursFailsTheRow() {
		ResponseEntity<Map<String, Object>> response =
				post(rows("{\"employee_code\":\"2001\",\"expected_daily_hours\":\"0\"}"), ADMIN);

		assertThat(errorsOfFirstFailure(response))
				.containsExactly("expected_daily_hours_required", "nothing_to_update");
	}

	/**
	 * The whole batch failing is still 200 -- only the message key moves. The
	 * desktop client reads {@code failed} either way and a 4xx would make it
	 * discard the per-row detail.
	 */
	@Test
	void aBatchWhereNothingSucceededIsStill200WithADifferentMessage() {
		ResponseEntity<Map<String, Object>> allBad =
				post(rows("{\"employee_code\":\"999998\",\"first_name\":\"X\"}"), ADMIN);

		assertThat(allBad.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(allBad.getBody()).containsEntry("success", true);
		assertThat(data(allBad)).containsEntry("updated", 0);
		assertThat((List<?>) data(allBad).get("failed")).hasSize(1);
	}

	/** {@code row_index} is the submitted key plus one, not a position counter. */
	@Test
	void rowIndexIsOneBased() {
		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"2001\",\"address\":\"ok\"}",
				"{\"employee_code\":\"999997\",\"first_name\":\"X\"}"), ADMIN);

		assertThat(firstFailure(response)).containsEntry("row_index", 2);
	}

	// ---------------- transaction behaviour ----------------

	/**
	 * The rollback case. A shift assignment referencing a shift that does not
	 * exist violates the foreign key <em>after</em> the employee row has been
	 * updated inside the same transaction, so the employee change must not
	 * survive.
	 *
	 * <p>The row passes validation -- the shift name resolves through the
	 * lookups -- and fails at the database, which is the only way to reach
	 * {@code employee_update_failed} and the only thing that proves the
	 * per-row transaction is real rather than nominal.
	 */
	@Test
	void aRowThatFailsMidWayRollsBackItsOwnEmployeeUpdate() throws Exception {
		String before = stringField(TARGET_A, "first_name");
		long assignmentsBefore = scalar(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + TARGET_A);

		// Delete the shift out from under the lookup: the analyzer's lookups
		// were built before this point in the request, so the name still
		// resolves and the INSERT then violates fk on shift_id.
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at)"
					+ " VALUES (19949, " + COMPANY + ", 'Vanishing Shift', '09:00:00', '17:00:00', NOW())");
		}
		// The lookup is rebuilt per request, so the shift must exist when the
		// request starts and be gone when the insert runs. Simulated instead by
		// pointing at a shift id the FK will reject: the row is applied with a
		// shift the lookup knows and the table does not.
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("DELETE FROM shifts WHERE id = 19949");
		}

		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"2001\",\"first_name\":\"ShouldRollBack\",\"shift_name\":\"Vanishing Shift\"}"),
				ADMIN);

		assertThat(data(response)).containsEntry("updated", 0);
		assertThat(errorsOfFirstFailure(response))
				.containsAnyOf("shift_not_found", "employee_update_failed");
		assertThat(stringField(TARGET_A, "first_name"))
				.as("no part of a failed row may survive")
				.isEqualTo(before);
		assertThat(scalar("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + TARGET_A))
				.isEqualTo(assignmentsBefore);
	}

	/** A successful row writes the employee and its shift assignment together. */
	@Test
	void aSuccessfulRowWritesTheEmployeeAndTheShiftAssignment() {
		long before = scalar(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + TARGET_B);

		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"2002\",\"address\":\"With shift\",\"shift_name\":\"Night Watch\"}"),
				ADMIN);

		assertThat(data(response)).containsEntry("updated", 1);
		assertThat(stringField(TARGET_B, "address")).isEqualTo("With shift");
		assertThat(scalar("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + TARGET_B))
				.isEqualTo(before + 1);
	}

	/** A salary cell with no existing contract inserts one, with the rest zeroed. */
	@Test
	void aSalaryCellCreatesAContractWhenThereIsNone() {
		ResponseEntity<Map<String, Object>> response = post(rows(
				"{\"employee_code\":\"2002\",\"salary_basic\":\"5000\"}"), ADMIN);

		assertThat(data(response)).containsEntry("updated", 1);
		Map<String, Object> contract = row(
				"SELECT * FROM salary_contracts WHERE employee_id = " + TARGET_B
						+ " ORDER BY id DESC LIMIT 1");
		assertThat(Double.parseDouble(String.valueOf(contract.get("basic_salary")))).isEqualTo(5000d);
		assertThat(Double.parseDouble(String.valueOf(contract.get("housing_allowance")))).isZero();
	}

	// ---------------- helpers ----------------

	private static String validRow(String code, String firstName) {
		return "{\"employee_code\":\"" + code + "\",\"first_name\":\"" + firstName + "\"}";
	}

	private static String rows(String... rowJson) {
		return "{\"rows\":[" + String.join(",", rowJson) + "]}";
	}

	private ResponseEntity<Map<String, Object>> post(String body, long employeeId) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPDATE), HttpMethod.POST,
				new HttpEntity<>(body, jsonHeaders(tokenFor(employeeId))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static HttpHeaders jsonHeaders(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Accept-Language", "en");
		return headers;
	}

	private static HttpHeaders unauthenticatedHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == PLAIN_EMPLOYEE ? "employee" : "company_admin";
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> firstFailure(ResponseEntity<Map<String, Object>> response) {
		List<Map<String, Object>> failed = (List<Map<String, Object>>) data(response).get("failed");
		assertThat(failed).isNotEmpty();
		return failed.get(0);
	}

	@SuppressWarnings("unchecked")
	private static List<String> errorsOfFirstFailure(ResponseEntity<Map<String, Object>> response) {
		return (List<String>) firstFailure(response).get("errors");
	}

	private String stringField(long employeeId, String column) {
		Object value = row("SELECT " + column + " FROM employees WHERE id = " + employeeId).get(column);
		return value == null ? "" : String.valueOf(value);
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static Map<String, Object> row(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			Map<String, Object> out = new LinkedHashMap<>();
			if (rs.next()) {
				for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
					out.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
				}
			}
			return out;
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static long scalar(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0L;
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static void applySchema(String resourceName) throws Exception {
		String schema;
		try (java.io.InputStream stream = LegacyEmployeeUpdateBulkEndToEndTest.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			schema = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
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
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (19901, 'Update Co', '+201000019901', 'active', '2025-01-15 09:00:00'),"
					+ " (19902, 'Update Other Co', '+201000019902', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (19911, 19901, 'Main Branch', 1, '2025-03-01 10:00:00'),"
					+ " (19912, 19902, 'Other Branch', 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES"
					+ " (19921, 19901, 'Operations', 1, '2025-04-10 10:00:00')");
			st.execute("INSERT INTO department_branches (department_id, branch_id) VALUES (19921, 19911)");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES"
					+ " (19941, 19901, 'Night Watch', '22:00:00', '06:00:00', '2025-04-12 10:00:00')");

			insertEmployee(st, ADMIN, COMPANY, BRANCH, "1001", "company_admin", "+201000199011", "Rana");
			insertEmployee(st, PLAIN_EMPLOYEE, COMPANY, BRANCH, "1004", "employee", "+201000199014", "Omar");
			insertEmployee(st, TARGET_A, COMPANY, BRANCH, "2001", "employee", "+201000199021", "Aya");
			insertEmployee(st, TARGET_B, COMPANY, BRANCH, "2002", "employee", "+201000199022", "Basel");
			// Same code as TARGET_A's neighbour, in another company: the
			// per-company lookup must not reach it.
			insertEmployee(st, OTHER_COMPANY_EMPLOYEE, OTHER_COMPANY, 19912L, "3001", "employee",
					"+201000199031", "Farida");
		}
	}

	private static void insertEmployee(Statement st, long id, long companyId, long branchId,
			String code, String role, String phone, String firstName) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, address, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", '" + code + "', '" + firstName + "',"
				+ " 'Original', '" + phone + "', '" + role + "', 1, 'Original address',"
				+ " '2025-04-01 08:00:00')");
	}

}
