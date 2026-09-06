package com.workin.legacy.schedules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * Wave 12.6 slice 2: {@code schedules/assign_employee_schedule.php}.
 *
 * <p>Weighted towards what a clean-room port gets wrong here: the guard-level
 * role list that <em>admits</em> a MANAGER where attendance refuses one, the
 * fact that {@code required()} accepts an empty array and answers success with
 * no writes, the upsert clearing a column the endpoint never sets, and the raw
 * date string reaching a {@code DATE NOT NULL} column with no parsing anywhere
 * on the path.
 *
 * <p>Every date expectation is measured against MariaDB 11.8 under the legacy
 * {@code sql_mode=''} contract, which is what
 * {@code app.legacy-db.connection-init-sql} installs on every application
 * connection.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyScheduleAssignEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String ASSIGN = "/apis/api/schedules/assign_employee_schedule.php";

	private static final long COMPANY_1 = 21001L;
	private static final long COMPANY_2 = 21002L;
	private static final long COMPANY_INACTIVE = 21003L;

	private static final long ADMIN_1 = 210011L;
	private static final long HR_1 = 210012L;
	private static final long MANAGER_1 = 210013L;
	private static final long EMPLOYEE_1 = 210014L;
	private static final long EMPLOYEE_2 = 210015L;
	private static final long ADMIN_2 = 210021L;
	private static final long EMPLOYEE_OTHER_CO = 210022L;
	private static final long ADMIN_INACTIVE = 210031L;

	private static final long BRANCH_1 = 21011L;
	private static final long BRANCH_2 = 21012L;
	private static final long BRANCH_3 = 21013L;

	private static final long SHIFT_1 = 21031L;
	private static final long SHIFT_BLANK_NAME = 21032L;
	private static final long SHIFT_OTHER_CO = 21033L;

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
			throw new IllegalStateException("could not prepare the schedule fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	@BeforeEach
	void reset() {
		execute("DELETE FROM employee_schedules");
		execute("DELETE FROM notifications");
	}

	// ------------------------------------------------------------------
	// Method, authentication and authority
	// ------------------------------------------------------------------

	@Test
	void anyMethodOtherThanPostIsInvalidMethod() {
		Map<String, Object> body = send(ADMIN_1, HttpMethod.GET, null, 405);
		assertThat(body.get("success")).isEqualTo(false);
		assertThat(body.get("message")).isEqualTo("Invalid method");
	}

	@Test
	void noTokenIsUnauthorized() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ASSIGN), HttpMethod.POST,
				new HttpEntity<>(payload(EMPLOYEE_1, SHIFT_1, "[\"2026-04-26\"]"), headers), mapType());
		assertThat(response.getStatusCode().value()).isEqualTo(401);
	}

	/**
	 * The role list is on {@code requireAuth}, so a MANAGER is <b>allowed</b>
	 * here.
	 *
	 * <p>That is the opposite of the five mutating {@code attendance}
	 * endpoints, which admit a manager through the guard and then refuse them
	 * with an in-endpoint 403. Same wave, two different authority mechanisms.
	 */
	@Test
	void aManagerIsAllowedUnlikeTheAttendanceMutations() {
		Map<String, Object> body = post(MANAGER_1, EMPLOYEE_1, SHIFT_1, "[\"2026-04-26\"]", 200);

		assertThat(body.get("success")).isEqualTo(true);
		assertThat(query("SELECT id FROM employee_schedules")).hasSize(1);
	}

	@Test
	void anOrdinaryEmployeeIsForbidden() {
		Map<String, Object> body = post(EMPLOYEE_1, EMPLOYEE_2, SHIFT_1, "[\"2026-04-26\"]", 403);

		assertThat(body.get("success")).isEqualTo(false);
		assertThat(query("SELECT id FROM employee_schedules")).isEmpty();
	}

	/** {@code requireCompanyActive()} runs after the guard and before the body. */
	@Test
	void anInactiveCompanyIsRefusedBeforeAnythingIsWritten() {
		post(ADMIN_INACTIVE, ADMIN_INACTIVE, SHIFT_1, "[\"2026-04-26\"]", 403);

		assertThat(query("SELECT id FROM employee_schedules")).isEmpty();
	}

	// ------------------------------------------------------------------
	// required() and the resource lookups
	// ------------------------------------------------------------------

	@ParameterizedTest(name = "missing {0}")
	@CsvSource({"employee_id", "shift_id", "dates"})
	void eachMissingFieldNamesItselfInTheReplacement(String field) {
		Map<String, String> parts = new LinkedHashMap<>();
		parts.put("employee_id", String.valueOf(EMPLOYEE_1));
		parts.put("shift_id", String.valueOf(SHIFT_1));
		parts.put("dates", "[\"2026-04-26\"]");
		parts.remove(field);

		StringBuilder json = new StringBuilder("{");
		parts.forEach((key, value) -> json.append(json.length() > 1 ? "," : "")
				.append('"').append(key).append("\":").append(value));
		Map<String, Object> body = post(ADMIN_1, json.append('}').toString(), 400);

		assertThat(body.get("success")).isEqualTo(false);
		// `field_required` carries the field name through the {field} placeholder.
		assertThat((String) body.get("message")).contains(field);
	}

	/** JSON null fails {@code isset()} exactly as an absent key does. */
	@Test
	void anExplicitNullIsAlsoFieldRequired() {
		Map<String, Object> body = post(ADMIN_1,
				"{\"employee_id\":" + EMPLOYEE_1 + ",\"shift_id\":" + SHIFT_1 + ",\"dates\":null}", 400);

		assertThat((String) body.get("message")).contains("dates");
	}

	@Test
	void anEmployeeOfAnotherCompanyIsNotFound() {
		Map<String, Object> body = post(ADMIN_1, EMPLOYEE_OTHER_CO, SHIFT_1, "[\"2026-04-26\"]", 404);

		assertThat(body.get("message")).isEqualTo("Employee not found");
		assertThat(query("SELECT id FROM employee_schedules")).isEmpty();
	}

	/**
	 * The employee is checked <b>before</b> the shift, so a request that is
	 * wrong about both is an employee error.
	 */
	@Test
	void theEmployeeLookupPrecedesTheShiftLookup() {
		Map<String, Object> body = post(ADMIN_1, EMPLOYEE_OTHER_CO, SHIFT_OTHER_CO, "[\"2026-04-26\"]", 404);

		assertThat(body.get("message")).isEqualTo("Employee not found");
	}

	@Test
	void aShiftOfAnotherCompanyIsNotFound() {
		Map<String, Object> body = post(ADMIN_1, EMPLOYEE_1, SHIFT_OTHER_CO, "[\"2026-04-26\"]", 404);

		assertThat(body.get("message")).isEqualTo("Shift not found");
		assertThat(query("SELECT id FROM employee_schedules")).isEmpty();
	}

	// ------------------------------------------------------------------
	// The happy path
	// ------------------------------------------------------------------

	/**
	 * Every date becomes one row holding a <b>snapshot</b> of the shift, and
	 * the response says nothing about how many.
	 */
	@Test
	void eachDateBecomesOneSnapshotRowAndTheEnvelopeCarriesNoData() {
		Map<String, Object> body = post(ADMIN_1, EMPLOYEE_1, SHIFT_1,
				"[\"2026-04-26\",\"2026-04-27\",\"2026-04-28\"]", 200);

		assertThat(body.get("success")).isEqualTo(true);
		assertThat(body.get("message")).isEqualTo("Schedule assigned");
		// `ok(SCHEDULE_ASSIGNED)` passes no $data, so the key is absent -- not null.
		assertThat(body).doesNotContainKey("data");

		List<Map<String, Object>> rows = query(
				"SELECT employee_id, schedule_date, name, start_time, end_time, exception_note"
						+ " FROM employee_schedules ORDER BY schedule_date");
		assertThat(rows).hasSize(3);
		assertThat(rows.get(0).get("schedule_date")).hasToString("2026-04-26");
		assertThat(rows.get(0).get("name")).isEqualTo("Day");
		assertThat(rows.get(0).get("start_time")).hasToString("09:00:00");
		assertThat(rows.get(0).get("end_time")).hasToString("17:00:00");
		assertThat(rows.get(0).get("exception_note")).isNull();
		assertThat(rows.get(2).get("schedule_date")).hasToString("2026-04-28");
		// The row holds no shift_id: it is a copy, so later shift edits never
		// reach an assigned day.
		assertThat(rows.get(0)).doesNotContainKey("shift_id");
	}

	/** One notification per request, not one per date, and it names the shift. */
	@Test
	void oneNotificationIsSentForTheWholeRequest() {
		post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "[\"2026-04-26\",\"2026-04-27\"]", 200);

		List<Map<String, Object>> rows = query(
				"SELECT company_id, recipient_kind, from_employee_id, to_employee_id, title, body,"
						+ " notification_type, reference_type, reference_id FROM notifications");
		assertThat(rows).hasSize(1);
		Map<String, Object> notification = rows.get(0);
		assertThat(number(notification.get("company_id"))).isEqualTo(COMPANY_1);
		assertThat(notification.get("recipient_kind")).isEqualTo("employee");
		assertThat(number(notification.get("to_employee_id"))).isEqualTo(EMPLOYEE_1);
		assertThat(number(notification.get("from_employee_id"))).isEqualTo(ADMIN_1);
		assertThat(notification.get("notification_type")).isEqualTo("schedule_assigned");
		assertThat(notification.get("reference_type")).isEqualTo("schedule");
		assertThat(number(notification.get("reference_id"))).isEqualTo(SHIFT_1);
		assertThat(notification.get("title")).isEqualTo("Work schedule updated");
		assertThat(notification.get("body"))
				.isEqualTo("A new shift/schedule was assigned to you. (Day)");
	}

	/**
	 * An empty {@code dates} array is a <b>success</b> that writes nothing and
	 * still notifies.
	 *
	 * <p>{@code required()} tests {@code isset($v) && $v !== ''}, and
	 * {@code []} satisfies both. The loop then does not run. So the employee is
	 * told their schedule changed when it did not -- legacy behaviour, and the
	 * single most likely thing for a port to "fix" by accident.
	 */
	@Test
	void anEmptyDatesArrayIsASuccessThatWritesNothingAndStillNotifies() {
		Map<String, Object> body = post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "[]", 200);

		assertThat(body.get("success")).isEqualTo(true);
		assertThat(query("SELECT id FROM employee_schedules")).isEmpty();
		assertThat(query("SELECT id FROM notifications")).hasSize(1);
	}

	/**
	 * {@code (array)} over a JSON object keeps the <em>values</em>.
	 *
	 * <p>{@code foreach} never sees the keys, so an object is as usable as an
	 * array and a client sending one gets rows rather than an error.
	 */
	@Test
	void aJsonObjectOfDatesIsCastToItsValues() {
		post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "{\"a\":\"2026-04-26\",\"b\":\"2026-04-27\"}", 200);

		assertThat(query("SELECT schedule_date FROM employee_schedules ORDER BY schedule_date"))
				.hasSize(2);
	}

	/** A bare string is a one-element array after the cast. */
	@Test
	void aScalarDateIsCastToASingleElement() {
		post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "\"2026-04-26\"", 200);

		List<Map<String, Object>> rows = query("SELECT schedule_date FROM employee_schedules");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("schedule_date")).hasToString("2026-04-26");
	}

	// ------------------------------------------------------------------
	// The upsert
	// ------------------------------------------------------------------

	/**
	 * Re-assigning a day replaces it in place and <b>clears its exception
	 * note</b>.
	 *
	 * <p>{@code exception_note} is in the {@code ON DUPLICATE KEY UPDATE} list
	 * and this endpoint always passes null for it, so assigning a shift over a
	 * day someone had marked as an exception silently erases the mark. The id
	 * is preserved, which is how the replacement is distinguishable from a
	 * delete-and-insert.
	 */
	@Test
	void reassigningADayReplacesItInPlaceAndClearsTheExceptionNote() {
		execute("INSERT INTO employee_schedules (employee_id, schedule_date, name, start_time,"
				+ " end_time, exception_note) VALUES (" + EMPLOYEE_1
				+ ", '2026-04-26', 'Old', '07:00:00', '15:00:00', 'Medical leave')");
		long originalId = number(query("SELECT id FROM employee_schedules").get(0).get("id"));

		post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "[\"2026-04-26\"]", 200);

		List<Map<String, Object>> rows = query(
				"SELECT id, name, start_time, exception_note FROM employee_schedules");
		assertThat(rows).hasSize(1);
		assertThat(number(rows.get(0).get("id"))).isEqualTo(originalId);
		assertThat(rows.get(0).get("name")).isEqualTo("Day");
		assertThat(rows.get(0).get("start_time")).hasToString("09:00:00");
		assertThat(rows.get(0).get("exception_note")).isNull();
	}

	/** The same date twice in one request collapses to one row. */
	@Test
	void aRepeatedDateInOneRequestIsStillOneRow() {
		post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "[\"2026-04-26\",\"2026-04-26\"]", 200);

		assertThat(query("SELECT id FROM employee_schedules")).hasSize(1);
	}

	/** The unique key is per employee, so two employees keep their own days. */
	@Test
	void twoEmployeesKeepSeparateRowsForTheSameDate() {
		post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "[\"2026-04-26\"]", 200);
		post(ADMIN_1, EMPLOYEE_2, SHIFT_1, "[\"2026-04-26\"]", 200);

		assertThat(query("SELECT id FROM employee_schedules")).hasSize(2);
	}

	// ------------------------------------------------------------------
	// The date string is never parsed
	// ------------------------------------------------------------------

	/**
	 * The raw string is bound into a {@code DATE NOT NULL} column and MariaDB
	 * decides.
	 *
	 * <p>Nothing on this path parses a date -- not PHP, not the helper, not the
	 * store. Under {@code sql_mode=''} every value writes a row, so an
	 * unparseable date is a <b>silent zero date</b> rather than an error. Note
	 * that {@code 26/04/2026} is rejected here while the punch parser two
	 * slices ago accepts exactly that format: the two paths have nothing in
	 * common, and a port that reused the punch parser here would accept dates
	 * legacy turns into {@code 0000-00-00}.
	 *
	 * <p>Every row measured against MariaDB 11.8.
	 */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"2026-04-26          | 2026-04-26",
		"2026/04/26          | 2026-04-26",
		"20260426            | 2026-04-26",
		"26-04-26            | 2026-04-26",
		"'  2026-04-26  '    | 2026-04-26",
		"2026-04-26 08:03:00 | 2026-04-26",
		"26/04/2026          | 0000-00-00",
		"26.04.2026          | 0000-00-00",
		"2026-13-01          | 0000-00-00",
		"2026-04-45          | 0000-00-00",
		"2026-02-30          | 0000-00-00",
		"abc                 | 0000-00-00",
		"7                   | 0000-00-00",
	})
	void theRawDateStringIsCoercedByMariaDb(String supplied, String stored) {
		post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "[\"" + supplied + "\"]", 200);

		List<Map<String, Object>> rows = query(
				"SELECT CAST(schedule_date AS CHAR) AS d FROM employee_schedules");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("d")).isEqualTo(stored);
	}

	/**
	 * A nested array reaches {@code (string)} as the literal {@code "Array"},
	 * which MariaDB then stores as a zero date.
	 */
	@Test
	void anArrayElementBecomesTheLiteralArrayAndThenAZeroDate() {
		post(ADMIN_1, EMPLOYEE_1, SHIFT_1, "[[\"a\",\"b\"]]", 200);

		List<Map<String, Object>> rows = query(
				"SELECT CAST(schedule_date AS CHAR) AS d FROM employee_schedules");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("d")).isEqualTo("0000-00-00");
	}

	// ------------------------------------------------------------------
	// The shift name is cast twice, and only one of them is trimmed
	// ------------------------------------------------------------------

	/**
	 * A whitespace-only shift name stores NULL and still appears in the
	 * notification.
	 *
	 * <p>{@code schedule_row_from_shift()} trims before deciding the stored
	 * value, so {@code "   "} becomes {@code null}. The notification body uses
	 * {@code (string) ($shift['name'] ?? '')} with no trim, so
	 * {@code "   " !== ''} is true and the suffix is appended anyway. One
	 * source column, two different answers in one request.
	 */
	@Test
	void aWhitespaceOnlyShiftNameStoresNullAndStillReachesTheNotification() {
		post(ADMIN_1, EMPLOYEE_1, SHIFT_BLANK_NAME, "[\"2026-04-26\"]", 200);

		assertThat(query("SELECT name FROM employee_schedules").get(0).get("name")).isNull();
		assertThat(query("SELECT body FROM notifications").get(0).get("body"))
				.isEqualTo("A new shift/schedule was assigned to you. (   )");
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> post(long actor, long employeeId, long shiftId, String datesJson,
			int expectedStatus) {
		return post(actor, payload(employeeId, shiftId, datesJson), expectedStatus);
	}

	private Map<String, Object> post(long actor, String json, int expectedStatus) {
		return send(actor, HttpMethod.POST, json, expectedStatus);
	}

	private static String payload(long employeeId, long shiftId, String datesJson) {
		return "{\"employee_id\":" + employeeId + ",\"shift_id\":" + shiftId
				+ ",\"dates\":" + datesJson + "}";
	}

	private Map<String, Object> send(long actor, HttpMethod method, String json, int expectedStatus) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(actor));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + ASSIGN), method,
				new HttpEntity<>(json, headers), mapType());
		assertThat(response.getStatusCode().value()).as("%s", response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private static ParameterizedTypeReference<Map<String, Object>> mapType() {
		return new ParameterizedTypeReference<Map<String, Object>>() { };
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == HR_1 ? "hr"
				: employeeId == EMPLOYEE_1 || employeeId == EMPLOYEE_2
						|| employeeId == EMPLOYEE_OTHER_CO ? "employee"
				: "company_admin";
		long companyId = employeeId == ADMIN_2 || employeeId == EMPLOYEE_OTHER_CO ? COMPANY_2
				: employeeId == ADMIN_INACTIVE ? COMPANY_INACTIVE
				: COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	private static void execute(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute(sql);
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static List<Map<String, Object>> query(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			List<Map<String, Object>> rows = new ArrayList<>();
			while (rs.next()) {
				Map<String, Object> row = new LinkedHashMap<>();
				for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
					row.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
				}
				rows.add(row);
			}
			return rows;
		} catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (21001, 'Schedule Co', '+201000021001', 'active', '2025-01-15 09:00:00'),
					  (21002, 'Schedule Other Co', '+201000021002', 'active', '2025-01-15 09:00:00'),
					  (21003, 'Schedule Inactive Co', '+201000021003', 'inactive', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (21011, 21001, 'Main', 1, '2025-03-01 10:00:00'),
					  (21012, 21002, 'Other', 1, '2025-03-01 10:00:00'),
					  (21013, 21003, 'Frozen', 1, '2025-03-01 10:00:00')
					""");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_1 + ", " + COMPANY_1
					+ ", 'Day', '09:00:00', '17:00:00', 1, '2025-03-02 10:00:00')");
			// A whitespace-only name: trimmed to NULL on the row, untrimmed in
			// the notification.
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_BLANK_NAME + ", " + COMPANY_1
					+ ", '   ', '08:00:00', '16:00:00', 1, '2025-03-02 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active,"
					+ " created_at) VALUES (" + SHIFT_OTHER_CO + ", " + COMPANY_2
					+ ", 'Day', '09:00:00', '17:00:00', 1, '2025-03-02 10:00:00')");

			employee(st, ADMIN_1, COMPANY_1, BRANCH_1, "company_admin", "+201000210011", "Adam", "Admin");
			employee(st, HR_1, COMPANY_1, BRANCH_1, "hr", "+201000210012", "Hana", "Hr");
			employee(st, MANAGER_1, COMPANY_1, BRANCH_1, "manager", "+201000210013", "Maged", "Manager");
			employee(st, EMPLOYEE_1, COMPANY_1, BRANCH_1, "employee", "+201000210014", "Ellie", "One");
			employee(st, EMPLOYEE_2, COMPANY_1, BRANCH_1, "employee", "+201000210015", "Emad", "Two");
			employee(st, ADMIN_2, COMPANY_2, BRANCH_2, "company_admin", "+201000210021", "Other", "Admin");
			employee(st, EMPLOYEE_OTHER_CO, COMPANY_2, BRANCH_2, "employee", "+201000210022",
					"Other", "Staff");
			employee(st, ADMIN_INACTIVE, COMPANY_INACTIVE, BRANCH_3, "company_admin",
					"+201000210031", "Frozen", "Admin");
		}
	}

	private static void employee(Statement st, long id, long companyId, long branchId, String role,
			String phone, String first, String last) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES (" + id + ", " + companyId
				+ ", " + branchId + ", " + id + ", '" + first + "', '" + last + "', '" + phone
				+ "', '" + role + "', 1, '2025-04-01 08:00:00')");
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
		return DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream = LegacyScheduleAssignEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
