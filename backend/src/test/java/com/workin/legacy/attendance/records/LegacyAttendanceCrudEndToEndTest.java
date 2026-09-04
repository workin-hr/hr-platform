package com.workin.legacy.attendance.records;

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

/**
 * Wave 12.6 slice 1a-i: `attendance/one.php`, `delete.php` and
 * `delete_range.php`.
 *
 * <p>Weighted towards what a clean-room port would change: the per-row 403
 * that fires only after the record is found, three different missing-id
 * answers across three modules, and `delete_range`'s query keys -- which are
 * `from`/`to`, the exact class of mistake that produced the Wave 12.5 holiday
 * regression.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyAttendanceCrudEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String ONE = "/apis/api/attendance/one.php";
	private static final String DELETE = "/apis/api/attendance/delete.php";
	private static final String DELETE_RANGE = "/apis/api/attendance/delete_range.php";

	private static final long COMPANY_1 = 20801L;
	private static final long COMPANY_2 = 20802L;
	private static final long COMPANY_SUSPENDED = 20803L;

	private static final long ADMIN_1 = 208011L;
	private static final long HR_1 = 208012L;
	private static final long MANAGER_1 = 208013L;
	private static final long EMPLOYEE_1 = 208014L;
	private static final long EMPLOYEE_2 = 208015L;
	private static final long ADMIN_2 = 208021L;
	private static final long EMPLOYEE_OTHER_CO = 208022L;
	private static final long ADMIN_SUSPENDED = 208031L;

	private static final long ATT_E1_JAN = 208101L;
	private static final long ATT_E1_FEB = 208102L;
	private static final long ATT_E2_JAN = 208103L;
	private static final long ATT_OTHER_COMPANY = 208104L;
	private static final long ATT_EXCEPTION_ONLY = 208105L;

	private static final long EXCEPTION_TYPE_1 = 208201L;
	private static final long BRANCH_1 = 20811L;
	private static final long DEPARTMENT_1 = 20821L;
	private static final long JOB_TITLE_1 = 20831L;

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
			throw new IllegalStateException("could not prepare the attendance fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ------------------------------------------------------------------
	// one.php
	// ------------------------------------------------------------------

	@Test
	void oneReturnsTheFullJoinedRow() {
		Map<String, Object> data = dataOf(get(ONE + "?id=" + ATT_E1_JAN, ADMIN_1, 200));

		// a.* then the six derived columns, in attendance_record_full()'s order.
		assertThat(data.keySet()).containsExactly(
				"id", "employee_id", "check_in", "check_out", "method", "exception_type_id",
				"latitude", "longitude", "created_at", "updated_at",
				"exception_type_name", "employee_name", "duration_minutes",
				"branch_name", "department_name", "job_title_name");

		assertThat(number(data.get("id"))).isEqualTo(ATT_E1_JAN);
		assertThat(data.get("employee_name")).isEqualTo("Ellie One");
		assertThat(data.get("branch_name")).isEqualTo("Main");
		assertThat(data.get("department_name")).isEqualTo("Ops");
		assertThat(data.get("job_title_name")).isEqualTo("Agent");
		// TIMESTAMPDIFF over a 9-hour session.
		assertThat(number(data.get("duration_minutes"))).isEqualTo(540);
	}

	@Test
	void nativeTypesFollowMysqlndNotJavaGuesses() {
		Map<String, Object> data = dataOf(get(ONE + "?id=" + ATT_E1_JAN, ADMIN_1, 200));

		assertThat(data.get("id")).isInstanceOf(Number.class);
		assertThat(data.get("employee_id")).isInstanceOf(Number.class);
		assertThat(data.get("check_in")).isInstanceOf(String.class);
		// decimal(10,7) is a STRING to mysqlnd, so it must not be a JSON number.
		assertThat(data.get("latitude")).isInstanceOf(String.class);
		assertThat(data.get("longitude")).isInstanceOf(String.class);
	}

	@Test
	void aNullCheckOutLeavesDurationNull() {
		Map<String, Object> data = dataOf(get(ONE + "?id=" + ATT_EXCEPTION_ONLY, ADMIN_1, 200));
		assertThat(data.get("check_out")).isNull();
		// TIMESTAMPDIFF against NULL is NULL, not zero.
		assertThat(data.get("duration_minutes")).isNull();
		assertThat(data.get("exception_type_name")).isEqualTo("Late Arrival");
	}

	@Test
	void anEmployeeSeesOnlyTheirOwnRowAndOnlyAfterItIsFound() {
		// Own row: 200.
		assertThat(number(dataOf(get(ONE + "?id=" + ATT_E1_JAN, EMPLOYEE_1, 200)).get("id")))
				.isEqualTo(ATT_E1_JAN);

		// A colleague's row in the same company: 403, NOT 404 -- the record is
		// resolved first and the role check runs after, so the response does
		// disclose that the row exists. Legacy's ordering, deliberately kept.
		get(ONE + "?id=" + ATT_E2_JAN, EMPLOYEE_1, 403);

		// A row in another company: 404, because the scoped lookup misses
		// before the role check is ever reached.
		get(ONE + "?id=" + ATT_OTHER_COMPANY, EMPLOYEE_1, 404);
	}

	@Test
	void everyOtherRoleReadsAnyRowOfItsOwnCompany() {
		for (long actor : new long[] { ADMIN_1, HR_1, MANAGER_1 }) {
			get(ONE + "?id=" + ATT_E2_JAN, actor, 200);
		}
		get(ONE + "?id=" + ATT_OTHER_COMPANY, ADMIN_1, 404);
		get(ONE + "?id=" + ATT_E1_JAN, ADMIN_2, 404);
	}

	@Test
	void theMissingIdAnswerIsInvalidIdNotTheOtherModulesKeys() {
		// (int) cast first, then falsy -> invalid_id. Different from shifts'
		// id_required and from request_types'/holidays' field_required.
		for (String raw : new String[] { "", "0", "abc", "0.4" }) {
			Map<String, Object> body = get(ONE + "?id=" + raw, ADMIN_1, 400);
			assertThat(body).doesNotContainKey("data");
			assertThat(String.valueOf(body.get("message"))).doesNotContain("{field}");
		}
		get(ONE, ADMIN_1, 400);

		// "12abc" casts to 12 and gets past the guard.
		assertThat(number(dataOf(get(ONE + "?id=" + ATT_E1_JAN + "abc", ADMIN_1, 200)).get("id")))
				.isEqualTo(ATT_E1_JAN);
		// A negative id passes the falsy guard and then misses, because
		// attendance_record_full() refuses a non-positive id before querying.
		get(ONE + "?id=-5", ADMIN_1, 404);
	}

	// ------------------------------------------------------------------
	// delete.php
	// ------------------------------------------------------------------

	@Test
	void deleteIsHardAndReturnsNoDataKey() {
		long id = insertAttendance(EMPLOYEE_1, "2027-05-01 09:00:00", "2027-05-01 17:00:00");

		Map<String, Object> body = send(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN_1, null).getBody();
		assertThat(body.keySet()).containsExactly("success", "message");
		assertThat(body.get("success")).isEqualTo(true);

		assertThat(attendanceRow(id)).isNull();
	}

	@Test
	void onlyCompanyAdminAndHrMayDelete() {
		long id = insertAttendance(EMPLOYEE_1, "2027-05-02 09:00:00", null);

		// The role test is inside the endpoint, after requireCompanyActive, so
		// a MANAGER authenticates successfully and is then refused.
		assertThat(status(send(DELETE + "?id=" + id, HttpMethod.DELETE, MANAGER_1, null))).isEqualTo(403);
		assertThat(status(send(DELETE + "?id=" + id, HttpMethod.DELETE, EMPLOYEE_1, null))).isEqualTo(403);
		assertThat(attendanceRow(id)).isNotNull();

		assertThat(status(send(DELETE + "?id=" + id, HttpMethod.DELETE, HR_1, null))).isEqualTo(200);
		assertThat(attendanceRow(id)).isNull();
	}

	@Test
	void theRoleCheckBeatsAMissingOrForeignId() {
		// PHP places the role test above the id read, so a MANAGER never
		// learns whether the id was usable.
		assertThat(status(send(DELETE, HttpMethod.DELETE, MANAGER_1, null))).isEqualTo(403);
		assertThat(status(send(DELETE + "?id=999999", HttpMethod.DELETE, MANAGER_1, null))).isEqualTo(403);
	}

	@Test
	void deleteOfAForeignOrMissingRowIsNotFound() {
		assertThat(status(send(DELETE + "?id=" + ATT_OTHER_COMPANY, HttpMethod.DELETE, ADMIN_1, null)))
				.isEqualTo(404);
		assertThat(attendanceRow(ATT_OTHER_COMPANY)).isNotNull();
		assertThat(status(send(DELETE + "?id=999999", HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(404);
		assertThat(status(send(DELETE + "?id=0", HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(400);
	}

	// ------------------------------------------------------------------
	// delete_range.php -- the query keys
	// ------------------------------------------------------------------

	@Test
	void theRangeKeysAreFromAndTo() {
		long keep = insertAttendance(EMPLOYEE_1, "2028-03-10 09:00:00", null);
		long drop = insertAttendance(EMPLOYEE_1, "2028-01-15 09:00:00", null);

		// Request::DATE_FROM is the literal 'from' (apis/config/request.php:26).
		Map<String, Object> body = send(
				DELETE_RANGE + "?from=2028-01-01&to=2028-01-31", HttpMethod.DELETE, ADMIN_1, null).getBody();

		assertThat(body.get("success")).isEqualTo(true);
		Map<String, Object> data = dataOf(body);
		assertThat(data.keySet()).containsExactly("count", "from", "to");
		assertThat(number(data.get("count"))).isEqualTo(1);
		assertThat(data.get("from")).isEqualTo("2028-01-01");
		assertThat(data.get("to")).isEqualTo("2028-01-31");

		assertThat(attendanceRow(drop)).isNull();
		assertThat(attendanceRow(keep)).isNotNull();
	}

	@Test
	void dateFromAndDateToAreNotAliases() {
		// The Wave 12.5 holiday regression was exactly this: a plausible name
		// nobody resolved against request.php. These are unknown parameters,
		// so the required-field guard fires as though no range was given.
		long survivor = insertAttendance(EMPLOYEE_1, "2029-01-15 09:00:00", null);

		assertThat(status(send(DELETE_RANGE + "?date_from=2029-01-01&date_to=2029-01-31",
				HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(400);
		assertThat(status(send(DELETE_RANGE + "?date_from=2029-01-01&to=2029-01-31",
				HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(400);

		assertThat(attendanceRow(survivor)).isNotNull();
	}

	@Test
	void aBlankBoundNamesTheFieldPhpNames() {
		// `$from_raw === '' ? DATE_FROM : DATE_TO` -- so both blank reports
		// only `from`.
		assertThat(status(send(DELETE_RANGE + "?to=2028-01-31", HttpMethod.DELETE, ADMIN_1, null)))
				.isEqualTo(400);
		assertThat(status(send(DELETE_RANGE + "?from=2028-01-01", HttpMethod.DELETE, ADMIN_1, null)))
				.isEqualTo(400);
		assertThat(status(send(DELETE_RANGE + "?from=&to=", HttpMethod.DELETE, ADMIN_1, null)))
				.isEqualTo(400);
		assertThat(status(send(DELETE_RANGE, HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(400);

		// trim() runs first, so a whitespace-only bound is blank too.
		assertThat(status(send(DELETE_RANGE + "?from=%20%20&to=2028-01-31",
				HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(400);
	}

	@Test
	void anUnparseableOrInvertedRangeIsInvalidDate() {
		assertThat(status(send(DELETE_RANGE + "?from=nonsense&to=2028-01-31",
				HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(400);
		// from > to
		assertThat(status(send(DELETE_RANGE + "?from=2028-02-01&to=2028-01-31",
				HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(400);
	}

	@Test
	void aRangeMatchingNothingStillSucceedsWithZero() {
		Map<String, Object> data = dataOf(send(
				DELETE_RANGE + "?from=2035-01-01&to=2035-01-31", HttpMethod.DELETE, ADMIN_1, null).getBody());
		assertThat(number(data.get("count"))).isZero();
		assertThat(data.get("from")).isEqualTo("2035-01-01");
	}

	@Test
	void theRangeIsCompanyScopedThroughTheEmployeeJoin() {
		// attendance has no company_id; the predicate reaches
		// employees.company_id through the join. Company 2's row shares the
		// date window and must survive.
		long mine = insertAttendance(EMPLOYEE_1, "2030-06-15 09:00:00", null);
		long theirs = insertAttendanceFor(EMPLOYEE_OTHER_CO, "2030-06-15 09:00:00");

		Map<String, Object> data = dataOf(send(
				DELETE_RANGE + "?from=2030-06-01&to=2030-06-30", HttpMethod.DELETE, ADMIN_1, null).getBody());

		assertThat(number(data.get("count"))).isEqualTo(1);
		assertThat(attendanceRow(mine)).isNull();
		assertThat(attendanceRow(theirs)).isNotNull();
	}

	@Test
	void onlyCompanyAdminAndHrMayDeleteARange() {
		assertThat(status(send(DELETE_RANGE + "?from=2028-01-01&to=2028-01-31",
				HttpMethod.DELETE, MANAGER_1, null))).isEqualTo(403);
		assertThat(status(send(DELETE_RANGE + "?from=2028-01-01&to=2028-01-31",
				HttpMethod.DELETE, EMPLOYEE_1, null))).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// Guards
	// ------------------------------------------------------------------

	@Test
	void theMethodGuardRunsBeforeAuthentication() {
		assertThat(anonymous(ONE, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(DELETE, HttpMethod.GET)).isEqualTo(405);
		assertThat(anonymous(DELETE_RANGE, HttpMethod.GET)).isEqualTo(405);
		assertThat(anonymous(ONE, HttpMethod.GET)).isEqualTo(401);
	}

	@Test
	void aSuspendedCompanyIsRefusedAfterAuthentication() {
		get(ONE + "?id=" + ATT_E1_JAN, ADMIN_SUSPENDED, 403);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> get(String path, long employeeId, int expectedStatus) {
		ResponseEntity<Map<String, Object>> response = send(path, HttpMethod.GET, employeeId, null);
		assertThat(response.getStatusCode().value()).as("%s: %s", path, response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, long employeeId, String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		headers.set("Accept-Language", "en");
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(body, headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private int anonymous(String path, HttpMethod method) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>("{}", headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
				.getStatusCode().value();
	}

	private static int status(ResponseEntity<Map<String, Object>> response) {
		return response.getStatusCode().value();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
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
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static Map<String, Object> attendanceRow(long id) {
		return queryOne("SELECT id, employee_id, check_in, check_out FROM attendance WHERE id = " + id);
	}

	private static long insertAttendance(long employeeId, String checkIn, String checkOut) {
		return insert(employeeId, checkIn, checkOut);
	}

	private static long insertAttendanceFor(long employeeId, String checkIn) {
		return insert(employeeId, checkIn, null);
	}

	private static long insert(long employeeId, String checkIn, String checkOut) {
		String out = checkOut == null ? "NULL" : "'" + checkOut + "'";
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.executeUpdate("INSERT INTO attendance (employee_id, check_in, check_out, method)"
					+ " VALUES (" + employeeId + ", '" + checkIn + "', " + out + ", 'app')",
					Statement.RETURN_GENERATED_KEYS);
			try (ResultSet keys = st.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Map<String, Object> queryOne(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
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

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20801, 'Attendance Co', '+201000020801', 'active', '2025-01-15 09:00:00'),
					  (20802, 'Attendance Other Co', '+201000020802', 'active', '2025-01-15 09:00:00'),
					  (20803, 'Attendance Suspended', '+201000020803', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20811, 20801, 'Main', 1, '2025-03-01 10:00:00'),
					  (20812, 20802, 'Other', 1, '2025-03-01 10:00:00'),
					  (20813, 20803, 'Suspended', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (20821, 20801, 'Ops', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at)
					VALUES (20831, 20801, 20821, 'Agent', 1, '2025-04-11 10:00:00')
					""");
			st.execute("""
					INSERT INTO exception_types (id, company_id, name, is_active, created_at) VALUES
					  (208201, 20801, 'Late Arrival', 1, '2025-02-01 09:00:00')
					""");

			employee(st, ADMIN_1, COMPANY_1, BRANCH_1, "company_admin", "+201000208011", "Adam", "Admin");
			employee(st, HR_1, COMPANY_1, BRANCH_1, "hr", "+201000208012", "Hana", "Hr");
			employee(st, MANAGER_1, COMPANY_1, BRANCH_1, "manager", "+201000208013", "Maged", "Manager");
			employee(st, EMPLOYEE_1, COMPANY_1, BRANCH_1, "employee", "+201000208014", "Ellie", "One");
			employee(st, EMPLOYEE_2, COMPANY_1, BRANCH_1, "employee", "+201000208015", "Emad", "Two");
			employee(st, ADMIN_2, COMPANY_2, 20812L, "company_admin", "+201000208021", "Other", "Admin");
			employee(st, EMPLOYEE_OTHER_CO, COMPANY_2, 20812L, "employee", "+201000208022", "Other", "Staff");
			employee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 20813L, "company_admin",
					"+201000208031", "Susp", "Admin");

			// EMPLOYEE_1 is the only one with a department and job title, so the
			// derived name columns are exercised as both present and NULL.
			st.execute("UPDATE employees SET department_id = " + DEPARTMENT_1
					+ ", job_title_id = " + JOB_TITLE_1 + " WHERE id = " + EMPLOYEE_1);

			attendance(st, ATT_E1_JAN, EMPLOYEE_1, "'2026-01-15 08:00:00'", "'2026-01-15 17:00:00'", "NULL");
			attendance(st, ATT_E1_FEB, EMPLOYEE_1, "'2026-02-15 08:00:00'", "'2026-02-15 16:00:00'", "NULL");
			attendance(st, ATT_E2_JAN, EMPLOYEE_2, "'2026-01-15 09:00:00'", "'2026-01-15 18:00:00'", "NULL");
			attendance(st, ATT_OTHER_COMPANY, EMPLOYEE_OTHER_CO, "'2026-01-15 09:00:00'",
					"'2026-01-15 18:00:00'", "NULL");
			// exception-only: midnight anchor, no check-out
			attendance(st, ATT_EXCEPTION_ONLY, EMPLOYEE_1, "'2026-04-01 00:00:00'", "NULL",
					String.valueOf(EXCEPTION_TYPE_1));

			st.execute("UPDATE attendance SET latitude = 30.0444444, longitude = 31.2357000 WHERE id = "
					+ ATT_E1_JAN);
		}
	}

	private static void employee(
			Statement st, long id, long companyId, long branchId, String role, String phone,
			String first, String last) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, role, is_active,"
				+ " join_request_status, phone, first_name, last_name, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", '" + id + "', '" + role + "', 1,"
				+ " 'accepted', '" + phone + "', '" + first + "', '" + last + "', '2025-01-20 09:00:00')");
	}

	private static void attendance(
			Statement st, long id, long employeeId, String checkIn, String checkOut, String exceptionTypeId)
			throws Exception {
		st.execute("INSERT INTO attendance (id, employee_id, check_in, check_out, method, exception_type_id)"
				+ " VALUES (" + id + ", " + employeeId + ", " + checkIn + ", " + checkOut + ", 'app', "
				+ exceptionTypeId + ")");
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
		try (InputStream stream = LegacyAttendanceCrudEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
