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
 * {@code employees/update.php} over real HTTP against real MariaDB.
 *
 * <p>Weighted towards the cases that are easy to get subtly wrong: the two
 * different "nothing to update" decisions, the keys that are removed rather
 * than written, the ones that may be cleared, and the writes that are skipped
 * while the request still succeeds. Each test seeds its own employee so the
 * order it runs in cannot matter.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeUpdateEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String UPDATE = "/apis/api/employees/update.php";

	private static final long COMPANY_1 = 19801L;
	private static final long COMPANY_2 = 19802L;
	private static final long BRANCH_MAIN = 19811L;
	private static final long BRANCH_SECOND = 19812L;
	private static final long BRANCH_OTHER_COMPANY = 19821L;
	private static final long DEPARTMENT_LINKED = 19841L;
	private static final long DEPARTMENT_OTHER_COMPANY = 19842L;
	private static final long JOB_TITLE_IN_DEPARTMENT = 19851L;
	private static final long JOB_TITLE_OTHER = 19852L;
	private static final long SHIFT = 19861L;
	private static final long SHIFT_OTHER_COMPANY = 19862L;
	private static final long ADMIN = 198011L;
	private static final long HR_WITH_PERMISSION = 198012L;
	private static final long HR_WITHOUT_PERMISSION = 198013L;
	private static final long OTHER_COMPANY_EMPLOYEE = 198021L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	private com.workin.legacy.notifications.LegacyNotifications notificationsSpy;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the Wave 12.4 update fixture", ex);
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
	void anEmptyBodyIsNothingToUpdateBeforeAnythingElseHappens() throws Exception {
		long id = employee(7001, "01019000001");
		// The first check is empty($body) -- it fires even for an id that does
		// not exist, because the employee is not looked up until after it.
		assertThat(message(put(id, Map.of()), 400)).isEqualTo("Nothing to update");
		assertThat(message(put(9_999_999L, Map.of()), 400)).isEqualTo("Nothing to update");

		// Unknown keys are dropped by the allowed-column filter, so a body full
		// of them reaches the *second* check and fails there instead.
		assertThat(message(put(id, Map.of("not_a_column", "x")), 400)).isEqualTo("Nothing to update");
	}

	@Test
	void aMalformedOrEmptyBodyIsNothingToUpdateRatherThanAFrameworkError() throws Exception {
		long id = employee(7010, "01019000005");
		// json_decode(...) ?? [] then empty($body): all of these stop at the
		// endpoint's own first check.
		for (String raw : List.of("{ not json at all", "", "null", "{}", "[]")) {
			ResponseEntity<Map<String, Object>> response = rawPut(id, raw);
			assertThat(response.getStatusCode().value()).describedAs("body %s", raw).isEqualTo(400);
			assertThat(response.getBody().get("message")).isEqualTo("Nothing to update");
		}

		// A non-empty JSON array is a PHP array with numeric keys: it survives
		// empty($body), then loses every named lookup and reaches the second
		// nothing-to-update check.
		ResponseEntity<Map<String, Object>> array = rawPut(id, "[\"address\", \"Cairo\"]");
		assertThat(array.getStatusCode().value()).isEqualTo(400);
		assertThat(array.getBody().get("message")).isEqualTo("Nothing to update");

		// A scalar root is the TypeError case.
		ResponseEntity<Map<String, Object>> scalar = rawPut(id, "\"just a string\"");
		assertThat(scalar.getStatusCode().value()).isEqualTo(500);
		assertThat(scalar.getBody().get("message")).isEqualTo("Internal server error");
	}

	@Test
	void aBranchThatNormalisesToNothingIsRemovedRatherThanCleared() throws Exception {
		java.util.List<Object> emptyishValues = java.util.Arrays.asList(null, 0, "0", "", -5);
		for (int index = 0; index < emptyishValues.size(); index++) {
			Object emptyish = emptyishValues.get(index);
			long id = employee(7100 + index, "010190009" + String.format("%02d", index));
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("branch_id", emptyish);
			body.put("address", "Alexandria");

			assertThat(put(id, body).getStatusCode().value())
					.describedAs("branch_id %s", emptyish).isEqualTo(200);
			// The key was unset, so the column keeps its value -- update cannot
			// clear a branch, only move it.
			assertThat(single("SELECT branch_id, address FROM employees WHERE id = " + id))
					.containsEntry("branch_id", String.valueOf(BRANCH_MAIN))
					.containsEntry("address", "Alexandria");
		}
	}

	@Test
	void aDepartmentMayBeClearedEvenThoughABranchMayNot() throws Exception {
		long id = employee(7200, "01019000010", DEPARTMENT_LINKED, JOB_TITLE_IN_DEPARTMENT);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("department_id", null);

		assertThat(put(id, body).getStatusCode().value()).isEqualTo(200);
		assertThat(single("SELECT department_id FROM employees WHERE id = " + id).get("department_id")).isNull();

		// A zero-like department is the same "no department" value, not a 404.
		long second = employee(7201, "01019000011", DEPARTMENT_LINKED, JOB_TITLE_IN_DEPARTMENT);
		assertThat(put(second, Map.of("department_id", 0)).getStatusCode().value()).isEqualTo(200);
		assertThat(single("SELECT department_id FROM employees WHERE id = " + second).get("department_id")).isNull();
	}

	@Test
	void clearingAJobTitleWhileADepartmentRemainsIsA404() throws Exception {
		// $target_job_title_id is an (int) cast, never null, so a null job title
		// validates 0 against the department -- and 0 belongs to no department.
		long id = employee(7300, "01019000020", DEPARTMENT_LINKED, JOB_TITLE_IN_DEPARTMENT);
		Map<String, Object> withDepartment = new LinkedHashMap<>();
		withDepartment.put("job_title_id", null);
		assertThat(message(put(id, withDepartment), 404)).isEqualTo("Job title not found");

		// With the department cleared in the same request there is no target
		// department, the check is skipped, and the raw null is stored.
		Map<String, Object> both = new LinkedHashMap<>();
		both.put("job_title_id", null);
		both.put("department_id", null);
		assertThat(put(id, both).getStatusCode().value()).isEqualTo(200);
		Map<String, Object> row = single("SELECT department_id, job_title_id FROM employees WHERE id = " + id);
		assertThat(row.get("department_id")).isNull();
		assertThat(row.get("job_title_id")).isNull();
	}

	@Test
	void aJobTitleIsUncheckedWhenTheEmployeeHasNoDepartment() throws Exception {
		// No department on the row and none in the body: PHP validates nothing
		// and stores whatever came in, even a job title from another department.
		long id = employee(7400, "01019000030");
		assertThat(put(id, Map.of("job_title_id", JOB_TITLE_OTHER)).getStatusCode().value()).isEqualTo(200);
		assertThat(single("SELECT job_title_id FROM employees WHERE id = " + id).get("job_title_id"))
				.isEqualTo(String.valueOf(JOB_TITLE_OTHER));
	}

	@Test
	void shiftZeroAloneIsASuccessfulNoOp() throws Exception {
		// array_key_exists(shift_id) gets past "nothing to update", isset() plus
		// the positive cast then skips the insert, and the empty update set
		// writes nothing -- so this is a 200 that changes precisely nothing.
		long id = employee(7500, "01019000040");
		String before = single("SELECT updated_at FROM employees WHERE id = " + id).get("updated_at").toString();

		ResponseEntity<Map<String, Object>> response = put(id, Map.of("shift_id", 0));
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("message")).isEqualTo("Employee updated");
		assertThat(count("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id)).isZero();
		assertThat(single("SELECT updated_at FROM employees WHERE id = " + id).get("updated_at")).isEqualTo(before);
	}

	@Test
	void aScalarSalaryAloneIsAlsoASuccessfulNoOp() throws Exception {
		// !empty($body['salary']) is true for a non-empty scalar, so the second
		// nothing-to-update check passes; is_array() then fails, so no contract
		// is written. PHP answers 200 and so does this.
		long id = employee(7600, "01019000050");
		ResponseEntity<Map<String, Object>> response = put(id, Map.of("salary", "12000"));
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT COUNT(*) FROM salary_contracts WHERE employee_id = " + id)).isZero();

		// An empty salary does not get past the check at all.
		assertThat(message(put(id, Map.of("salary", "")), 400)).isEqualTo("Nothing to update");
		assertThat(message(put(id, Map.of("salary", Map.of())), 400)).isEqualTo("Nothing to update");
	}

	@Test
	void aSalaryIsInsertedOnlyForAnEmployeeWithNoContractAtAll() throws Exception {
		long id = employee(7700, "01019000060");
		assertThat(put(id, Map.of("salary", Map.of("basic", 9000, "transport", 500))).getStatusCode().value())
				.isEqualTo(200);
		assertThat(count("SELECT COUNT(*) FROM salary_contracts WHERE employee_id = " + id)).isOne();
		assertThat(single("SELECT basic_salary, housing_allowance FROM salary_contracts WHERE employee_id = " + id))
				.containsEntry("basic_salary", "9000.00")
				.containsEntry("housing_allowance", "0.00");

		// A second attempt is a no-op: update never adds another contract.
		assertThat(put(id, Map.of("salary", Map.of("basic", 15000))).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT COUNT(*) FROM salary_contracts WHERE employee_id = " + id)).isOne();
		assertThat(single("SELECT basic_salary FROM salary_contracts WHERE employee_id = " + id))
				.containsEntry("basic_salary", "9000.00");
	}

	@Test
	void aPositiveShiftIsAppendedEveryTimeWithoutReplacingAnything() throws Exception {
		long id = employee(7800, "01019000070");
		assertThat(put(id, Map.of("shift_id", SHIFT)).getStatusCode().value()).isEqualTo(200);
		assertThat(put(id, Map.of("shift_id", SHIFT)).getStatusCode().value()).isEqualTo(200);
		// Two identical assignments, because PHP appends unconditionally.
		assertThat(count("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id)).isEqualTo(2);

		assertThat(message(put(id, Map.of("shift_id", SHIFT_OTHER_COMPANY)), 404)).isEqualTo("Shift not found");
	}

	@Test
	void aBlankPasswordLeavesTheStoredHashAlone() throws Exception {
		long id = employee(7900, "01019000080");
		String before = (String) single("SELECT password_hash FROM employees WHERE id = " + id).get("password_hash");

		assertThat(put(id, Map.of("password", "   ", "address", "Giza")).getStatusCode().value()).isEqualTo(200);
		assertThat(single("SELECT password_hash FROM employees WHERE id = " + id).get("password_hash"))
				.isEqualTo(before);

		// A real password does replace it.
		assertThat(put(id, Map.of("password", "a-new-password")).getStatusCode().value()).isEqualTo(200);
		assertThat((String) single("SELECT password_hash FROM employees WHERE id = " + id).get("password_hash"))
				.isNotEqualTo(before).startsWith("$2");
	}

	@Test
	void thePhoneUpdatePathOnlyStripsToDigits() throws Exception {
		// normalize_employee_phone(), not create's resolver: no country
		// normalisation and no validity check, so update stores numbers create
		// would have rejected outright.
		long id = employee(8000, "01019000090");
		assertThat(put(id, Map.of("phone", "+20 (10) 1234-5000", "country_code", "+20"))
				.getStatusCode().value()).isEqualTo(200);
		assertThat(single("SELECT phone FROM employees WHERE id = " + id).get("phone"))
				.isEqualTo("201012345000");

		long invalidNumber = employee(8001, "01019000091");
		assertThat(put(invalidNumber, Map.of("phone", "013999", "country_code", "+20"))
				.getStatusCode().value()).isEqualTo(200);
		assertThat(single("SELECT phone FROM employees WHERE id = " + invalidNumber).get("phone"))
				.isEqualTo("013999");

		// A country code is required only while the phone stays non-null...
		Map<String, Object> withoutCountry = new LinkedHashMap<>();
		withoutCountry.put("phone", "01019000099");
		assertThat(message(put(invalidNumber, withoutCountry), 400)).isEqualTo("Field 'country_code' is required");

		// ...and clearing the phone nulls the country code with it.
		Map<String, Object> cleared = new LinkedHashMap<>();
		cleared.put("phone", null);
		assertThat(put(invalidNumber, cleared).getStatusCode().value()).isEqualTo(200);
		Map<String, Object> row = single("SELECT phone, country_code FROM employees WHERE id = " + invalidNumber);
		assertThat(row.get("phone")).isNull();
		assertThat(row.get("country_code")).isNull();
	}

	@Test
	void hrNeedsCanEmployeesForSomebodyElseButNotForItself() throws Exception {
		long target = employee(8100, "01019000100");

		assertThat(message(put(target, Map.of("address", "Cairo"), HR_WITHOUT_PERMISSION), 403))
				.isEqualTo("Forbidden");
		assertThat(put(target, Map.of("address", "Cairo"), HR_WITH_PERMISSION).getStatusCode().value())
				.isEqualTo(200);

		// Its own row needs no flag -- and the body is reduced to the six self
		// fields first, so is_active never reaches the update set.
		Map<String, Object> selfBody = new LinkedHashMap<>();
		selfBody.put("first_name", "Renamed");
		selfBody.put("is_active", 0);
		assertThat(put(HR_WITHOUT_PERMISSION, selfBody, HR_WITHOUT_PERMISSION).getStatusCode().value())
				.isEqualTo(200);
		assertThat(single("SELECT first_name, is_active FROM employees WHERE id = " + HR_WITHOUT_PERMISSION))
				.containsEntry("first_name", "Renamed")
				.containsEntry("is_active", "1");

		// A self-update whose only field is outside the whitelist has nothing
		// left after the filter.
		assertThat(message(put(HR_WITHOUT_PERMISSION, Map.of("is_active", 0), HR_WITHOUT_PERMISSION), 400))
				.isEqualTo("Nothing to update");
	}

	@Test
	void bothNotificationsAreSentAfterTheCommitAndCarryTheirReferences() throws Exception {
		long id = employee(8200, "01019000110", DEPARTMENT_LINKED, null);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("job_title_id", JOB_TITLE_IN_DEPARTMENT);
		body.put("shift_id", SHIFT);

		assertThat(put(id, body).getStatusCode().value()).isEqualTo(200);

		Map<String, Object> jobTitle = single(
				"SELECT notification_type, title, body, reference_type, reference_id FROM notifications"
				+ " WHERE to_employee_id = " + id + " AND notification_type = 'job_title_changed'");
		assertThat(jobTitle.get("title")).isEqualTo("Job title updated");
		// The {title} placeholder is filled from the post-commit re-read.
		assertThat((String) jobTitle.get("body")).contains("Agent");
		assertThat(jobTitle.get("reference_type")).isNull();

		Map<String, Object> schedule = single(
				"SELECT notification_type, title, reference_type, reference_id FROM notifications"
				+ " WHERE to_employee_id = " + id + " AND notification_type = 'schedule_assigned'");
		assertThat(schedule.get("title")).isEqualTo("Work schedule updated");
		assertThat(schedule.get("reference_type")).isEqualTo("shift");
		assertThat(schedule.get("reference_id")).isEqualTo(String.valueOf(SHIFT));

		// An unchanged job title notifies nobody.
		long unchanged = employee(8201, "01019000111", DEPARTMENT_LINKED, JOB_TITLE_IN_DEPARTMENT);
		assertThat(put(unchanged, Map.of("job_title_id", JOB_TITLE_IN_DEPARTMENT)).getStatusCode().value())
				.isEqualTo(200);
		assertThat(count("SELECT COUNT(*) FROM notifications WHERE to_employee_id = " + unchanged)).isZero();
	}

	@Test
	void aNotificationFailureCannotUndoTheCommittedUpdate() throws Exception {
		long id = employee(8300, "01019000120");
		org.mockito.Mockito.doThrow(new IllegalStateException("notification storage is down"))
				.when(notificationsSpy).toEmployee(
						org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
						org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
						org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
						org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("shift_id", SHIFT);
			body.put("address", "Aswan");

			ResponseEntity<Map<String, Object>> response = put(id, body);
			// The notification runs after the commit, outside the transaction,
			// so its failure surfaces as a 500 with the work already durable.
			assertThat(response.getStatusCode().value()).isEqualTo(500);
			assertThat(single("SELECT address FROM employees WHERE id = " + id)).containsEntry("address", "Aswan");
			assertThat(count("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id)).isOne();
		} finally {
			org.mockito.Mockito.reset(notificationsSpy);
		}
	}

	@Test
	void theGuardsAndTheCrossTenantBoundaryHold() throws Exception {
		long id = employee(8400, "01019000130");

		// PUT only, and the method guard runs before authentication.
		ResponseEntity<Map<String, Object>> wrongMethod = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPDATE + "?id=" + id), HttpMethod.POST,
				new HttpEntity<>(Map.of("address", "x"), jsonHeaders(null)),
				new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);

		// The id is a required query parameter.
		ResponseEntity<Map<String, Object>> noId = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPDATE), HttpMethod.PUT,
				new HttpEntity<>(Map.of("address", "x"), jsonHeaders(tokenFor(ADMIN))),
				new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(noId.getStatusCode().value()).isEqualTo(400);
		assertThat(noId.getBody().get("message")).isEqualTo("Field 'id' is required");

		// Another tenant's employee is a 404, and D-075 closes the department
		// hole the junction lookup would otherwise leave open.
		assertThat(message(put(OTHER_COMPANY_EMPLOYEE, Map.of("address", "x")), 404))
				.isEqualTo("Employee not found");
		assertThat(message(put(id, Map.of("branch_id", BRANCH_OTHER_COMPANY)), 404)).isEqualTo("Branch not found");
		assertThat(message(put(id, Map.of("department_id", DEPARTMENT_OTHER_COMPANY)), 404))
				.isEqualTo("Department not found");
	}

	@Test
	void expectedHoursAndTheBooleanFlagsKeepTheirOwnRules() throws Exception {
		long id = employee(8500, "01019000140");
		assertThat(message(put(id, Map.of("expected_daily_hours", 0)), 400)).isEqualTo("Invalid input");
		assertThat(message(put(id, Map.of("expected_daily_hours", "-2")), 400)).isEqualTo("Invalid input");

		assertThat(put(id, Map.of("is_active", "true", "is_mobile_attendance_enabled", "yes"))
				.getStatusCode().value()).isEqualTo(200);
		// 'true' is in the truth set; 'yes' is not.
		assertThat(single("SELECT is_active, is_mobile_attendance_enabled FROM employees WHERE id = " + id))
				.containsEntry("is_active", "1")
				.containsEntry("is_mobile_attendance_enabled", "0");
	}

	/** Sends a raw body to update, for the shapes a Map cannot express. */
	private ResponseEntity<Map<String, Object>> rawPut(long employeeId, String rawBody) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPDATE + "?id=" + employeeId), HttpMethod.PUT,
				new HttpEntity<>(rawBody, jsonHeaders(tokenFor(ADMIN))),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private ResponseEntity<Map<String, Object>> put(long employeeId, Map<String, Object> body) {
		return put(employeeId, body, ADMIN);
	}

	private ResponseEntity<Map<String, Object>> put(long employeeId, Map<String, Object> body, long actor) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPDATE + "?id=" + employeeId), HttpMethod.PUT,
				new HttpEntity<>(body, jsonHeaders(tokenFor(actor))),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static String message(ResponseEntity<Map<String, Object>> response, int expectedStatus) {
		assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		return (String) response.getBody().get("message");
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == ADMIN ? "company_admin" : "hr";
		long companyId = employeeId == OTHER_COMPANY_EMPLOYEE ? COMPANY_2 : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static HttpHeaders jsonHeaders(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return headers;
	}

	/** Seeds a fresh employee so no test depends on another's leftovers. */
	private static long employee(int code, String phone) throws Exception {
		return employee(code, phone, null, null);
	}

	private static long employee(int code, String phone, Long departmentId, Long jobTitleId) throws Exception {
		long id = 198100L + code;
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, department_id, job_title_id, employee_code, first_name, last_name,
					   phone, country_code, password_hash, token_version, role, is_active, join_request_status,
					   hire_date, created_at)
					VALUES (%d, %d, %d, %s, %s, '%d', 'Update', 'Subject', '%s', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, 'accepted', '2024-03-01',
					   '2025-05-01 09:00:00')
					""".formatted(
					id, COMPANY_1, BRANCH_MAIN,
					departmentId == null ? "NULL" : departmentId.toString(),
					jobTitleId == null ? "NULL" : jobTitleId.toString(),
					code, phone));
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
					  (19801, 'Update Co 1', '+201000019801', 'active', '2025-01-15 09:00:00'),
					  (19802, 'Update Co 2', '+201000019802', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19811, 19801, 'Main Branch', 1, '2025-03-01 10:00:00'),
					  (19812, 19801, 'Second Branch', 1, '2025-03-01 10:00:00'),
					  (19821, 19802, 'Other Company Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (19841, 19801, 'Operations', 1, '2025-04-10 10:00:00'),
					  (19842, 19802, 'Other Company Department', 1, '2025-04-10 10:00:00')
					""");
			st.execute("INSERT INTO department_branches (department_id, branch_id) VALUES (19841, 19811)");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at) VALUES
					  (19851, 19801, 19841, 'Agent', 1, '2025-04-11 10:00:00'),
					  (19852, 19801, NULL, 'Unassigned Title', 1, '2025-04-11 10:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES
					  (19861, 19801, 'Morning', '09:00:00', '17:00:00', '2025-04-12 10:00:00'),
					  (19862, 19802, 'Other Company Shift', '09:00:00', '17:00:00', '2025-04-12 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
					   password_hash, token_version, role, is_active, join_request_status, created_at)
					VALUES
					  (198011, 19801, 19811, '1001', 'Update', 'Admin', '+201000198011', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'company_admin', 1, 'accepted', '2025-05-01 09:00:00'),
					  (198012, 19801, 19811, '1002', 'Permitted', 'Hr', '+201000198012', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'hr', 1, 'accepted', '2025-05-01 09:00:00'),
					  (198013, 19801, 19811, '1003', 'Unpermitted', 'Hr', '+201000198013', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'hr', 1, 'accepted', '2025-05-01 09:00:00'),
					  (198021, 19802, 19821, '2001', 'Other', 'Company', '+201000198021', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, 'accepted', '2025-05-01 09:00:00')
					""");
			// Only one of the two HR sessions carries can_employees.
			st.execute("INSERT INTO hr_permissions (employee_id, can_employees) VALUES (198012, 1), (198013, 0)");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream =
				LegacyEmployeeUpdateEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
