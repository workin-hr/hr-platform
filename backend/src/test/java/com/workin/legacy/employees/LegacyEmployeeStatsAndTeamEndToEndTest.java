package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Wave 12.4: {@code employees/stats.php} and {@code employees/my_team.php}.
 *
 * <p>Both were missed by the first pass of this wave, which is why the route
 * inventory is now asserted literally rather than enumerated.
 *
 * <p><b>Neither closes D-083.</b> {@code stats.php} evaluates {@code CURDATE()}
 * and {@code my_team.php} evaluates {@code CURRENT_DATE} in the database, on
 * the connection's timezone -- which Phase 1 does not yet set.
 *
 * <p>So this test never consults the application clock. Every date it seeds and
 * every date it asserts comes from the database's own calendar, because that is
 * the calendar the two endpoints query. Mixing the two would not have measured
 * the gap; it would only have produced a test that fails for a few hours around
 * a month boundary.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeStatsAndTeamEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String STATS = "/apis/api/employees/stats.php";
	private static final String MY_TEAM = "/apis/api/employees/my_team.php";

	private static final long COMPANY_1 = 20101L;
	private static final long COMPANY_2 = 20102L;
	private static final long COMPANY_SUSPENDED = 20103L;

	private static final long BRANCH_MAIN = 20111L;
	private static final long BRANCH_OTHER = 20112L;
	private static final long DEPARTMENT_OPS = 20121L;
	private static final long JOB_TITLE_AGENT = 20131L;

	private static final long ADMIN_1 = 201011L;
	private static final long MANAGER_MAIN = 201012L;
	private static final long HR_1 = 201013L;
	private static final long STAFF_MALE = 201014L;
	private static final long STAFF_FEMALE = 201015L;
	private static final long STAFF_NO_GENDER = 201016L;
	private static final long STAFF_INACTIVE = 201017L;
	private static final long STAFF_PENDING = 201018L;
	private static final long STAFF_OTHER_BRANCH = 201019L;
	private static final long STAFF_NEW_THIS_MONTH = 201020L;
	private static final long PLAIN_EMPLOYEE = 201021L;
	private static final long ADMIN_2 = 201022L;
	private static final long ADMIN_SUSPENDED = 201031L;

	/** The first of the database's current month, in its own calendar. */
	private static String databaseFirstOfMonth() {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(
						"SELECT DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')")) {
			rs.next();
			return rs.getString(1);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** {@code CURRENT_DATE} as the database evaluates it, on its own session timezone. */
	private static String databaseCurrentDate() {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery("SELECT CURRENT_DATE")) {
			rs.next();
			return rs.getString(1);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

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
			throw new IllegalStateException("could not prepare the stats/my_team fixture", ex);
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
	// stats.php
	// ------------------------------------------------------------------

	@Test
	void statsReturnsThePhpEnvelopeWithItsFiveKeys() {
		Map<String, Object> body = get(STATS, ADMIN_1, 200);

		assertThat(body.keySet()).containsExactly("success", "message", "data");
		assertThat(body.get("success")).isEqualTo(true);
		// ok(LangKey::SUCCESS, ...) -- the generic key, not an employees one.
		assertThat(body.get("message")).isEqualTo("Operation successful");

		Map<String, Object> data = dataOf(body);
		assertThat(data.keySet()).containsExactly(
				"total_employees", "male_count", "female_count", "new_this_month", "avg_tenure_months");
		// The four counts are integers; the average is a float even when whole.
		assertThat(data.get("total_employees")).isInstanceOf(Number.class);
		assertThat(data.get("avg_tenure_months")).isInstanceOf(Double.class);
	}

	@Test
	void statsCountsTheAcceptedRosterOfThisCompanyOnly() {
		Map<String, Object> data = dataOf(get(STATS, ADMIN_1, 200));

		// Company 1's accepted roster: admin, manager, hr, male, female,
		// no-gender, inactive, other-branch, new-this-month, plain employee.
		// The pending row and company 2 are both out.
		assertThat(number(data.get("total_employees"))).isEqualTo(10);
		assertThat(number(data.get("male_count"))).isEqualTo(2);
		assertThat(number(data.get("female_count"))).isEqualTo(7);
	}

	@Test
	void aNullGenderIsCountedInNeitherGenderTotal() {
		Map<String, Object> data = dataOf(get(STATS + "?branch_id=" + BRANCH_MAIN, ADMIN_1, 200));
		assertThat(number(data.get("male_count"))).isPositive();
		// The two counts do not sum to the total, because a null gender is
		// neither -- a client cannot derive one from the other two.
		assertThat(number(data.get("male_count")) + number(data.get("female_count")))
				.isLessThan(number(data.get("total_employees")));

		// The SQL wraps the column in LOWER(TRIM(...)), which this schema cannot
		// exercise: gender is enum('male','female','other'), so a padded or
		// upper-case value is not storable in the first place -- it becomes ''
		// on insert. The wrapper is reproduced because it is in the source, not
		// because any row can reach it.
		assertThat(number(data.get("male_count")) + number(data.get("female_count")))
				.isPositive();
	}

	@Test
	void statsAppliesTheSameFiltersToEveryAggregate() {
		// The predicate is shared, so a filter narrows the gender counts too --
		// they are not company-wide snapshots.
		Map<String, Object> filtered = dataOf(get(STATS + "?branch_id=" + BRANCH_OTHER, ADMIN_1, 200));
		assertThat(number(filtered.get("total_employees"))).isEqualTo(1);
		assertThat(number(filtered.get("male_count"))).isZero();
		assertThat(number(filtered.get("female_count"))).isEqualTo(1);

		assertThat(number(dataOf(get(STATS + "?department_id=" + DEPARTMENT_OPS, ADMIN_1, 200))
				.get("total_employees"))).isPositive();
		assertThat(number(dataOf(get(STATS + "?job_title_id=" + JOB_TITLE_AGENT, ADMIN_1, 200))
				.get("total_employees"))).isPositive();
	}

	@Test
	void isActiveUsesIssetWhileTheIdFiltersUseEmpty() {
		// isset(): is_active=0 is a real filter...
		assertThat(number(dataOf(get(STATS + "?is_active=0", ADMIN_1, 200)).get("total_employees")))
				.isEqualTo(1);
		assertThat(number(dataOf(get(STATS + "?is_active=1", ADMIN_1, 200)).get("total_employees")))
				.isEqualTo(9);
		// ...while !empty() means a zero-valued id filter is no filter at all.
		assertThat(number(dataOf(get(STATS + "?branch_id=0", ADMIN_1, 200)).get("total_employees")))
				.isEqualTo(number(dataOf(get(STATS, ADMIN_1, 200)).get("total_employees")));
	}

	@Test
	void theDateFiltersAreNamedFromAndToAndFallBackToCreatedAt() {
		// DATE(COALESCE(hire_date, created_at)) on both sides.
		assertThat(number(dataOf(get(STATS + "?from=2030-01-01", ADMIN_1, 200)).get("total_employees")))
				.isZero();
		assertThat(number(dataOf(get(STATS + "?to=2000-01-01", ADMIN_1, 200)).get("total_employees")))
				.isZero();
		// The row with a null hire_date is matched through created_at.
		assertThat(number(dataOf(get(STATS + "?from=2019-01-01&to=2019-12-31", ADMIN_1, 200))
				.get("total_employees"))).isEqualTo(1);
	}

	@Test
	void newThisMonthCountsTodaysMonthFromEitherDateColumn() {
		Map<String, Object> data = dataOf(get(STATS, ADMIN_1, 200));
		// One employee is seeded on the first of the database's current month,
		// which is the calendar CURDATE() reads. Nothing here asserts that the
		// database and the application clock agree -- they need not, and D-083
		// is the open blocker for that. This endpoint does not close it.
		assertThat(number(data.get("new_this_month"))).isEqualTo(1);
	}

	@Test
	void theAverageTenureIsRoundedAndZeroWhenThereIsNothingToAverage() {
		Map<String, Object> data = dataOf(get(STATS, ADMIN_1, 200));
		double tenure = ((Number) data.get("avg_tenure_months")).doubleValue();
		assertThat(tenure).isPositive();
		// round($raw, 1): at most one decimal place survives.
		assertThat(Math.abs(tenure * 10 - Math.round(tenure * 10))).isLessThan(0.0001);

		// AVG over an empty set is SQL NULL, which PHP renders as 0.0 rather
		// than omitting the key or sending null.
		Map<String, Object> empty = dataOf(get(STATS + "?from=2030-01-01", ADMIN_1, 200));
		assertThat(empty.get("avg_tenure_months")).isEqualTo(0.0);
	}

	@Test
	void aManagerIsScopedToItsOwnBranchOnStats() {
		Map<String, Object> data = dataOf(get(STATS, MANAGER_MAIN, 200));
		// The same-branch scope applies to the manager only; the other branch's
		// employee is outside it.
		assertThat(number(data.get("total_employees")))
				.isLessThan(number(dataOf(get(STATS, ADMIN_1, 200)).get("total_employees")));
		assertThat(number(data.get("total_employees"))).isEqualTo(9);
	}

	@Test
	void statsIsReachableByAdminHrAndManagerAndNobodyElse() {
		assertThat(get(STATS, HR_1, 200).get("success")).isEqualTo(true);
		assertThat(get(STATS, PLAIN_EMPLOYEE, 403).get("message"))
				.isEqualTo("Forbidden — insufficient role");
		assertThat(get(STATS, ADMIN_SUSPENDED, 403).get("message"))
				.isEqualTo("Company account is not active");

		ResponseEntity<Map<String, Object>> wrongMethod = exchange(STATS, HttpMethod.POST, ADMIN_1);
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
		assertThat(wrongMethod.getBody().get("message")).isEqualTo("Invalid method");
	}

	@Test
	void anotherTenantSeesOnlyItsOwnNumbers() {
		assertThat(number(dataOf(get(STATS, ADMIN_2, 200)).get("total_employees"))).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// my_team.php
	// ------------------------------------------------------------------

	@Test
	void myTeamIsTheManagersOwnBranchWithoutTheManager() {
		Map<String, Object> body = get(MY_TEAM, MANAGER_MAIN, 200);
		assertThat(body.get("message")).isEqualTo("My team");

		List<Long> ids = idsOf(body);
		assertThat(ids).contains(STAFF_MALE, STAFF_FEMALE, STAFF_NO_GENDER);
		// e.id <> ? excludes the manager itself.
		assertThat(ids).doesNotContain(MANAGER_MAIN);
		// is_active = 1 excludes the deactivated row...
		assertThat(ids).doesNotContain(STAFF_INACTIVE);
		// ...and the branch subselect excludes the other branch.
		assertThat(ids).doesNotContain(STAFF_OTHER_BRANCH);
	}

	@Test
	void myTeamHasNoRosterPredicateSoAPendingJoinRequestIsOnIt() {
		// list.php hides a pending join request; my_team.php has no such
		// clause at all. The asymmetry is legacy's, and it is reproduced.
		assertThat(idsOf(get(MY_TEAM, MANAGER_MAIN, 200))).contains(STAFF_PENDING);
	}

	@Test
	void myTeamIsOrderedByFirstThenLastName() {
		List<String> names = new ArrayList<>();
		for (Object row : (List<?>) get(MY_TEAM, MANAGER_MAIN, 200).get("data")) {
			Map<?, ?> employee = (Map<?, ?>) row;
			names.add(employee.get("first_name") + " " + employee.get("last_name"));
		}
		List<String> sorted = new ArrayList<>(names);
		sorted.sort(String::compareTo);
		assertThat(names).isEqualTo(sorted);
	}

	@Test
	void myTeamCarriesTheOrgLabelsAndTheCheckedInMarker() {
		Map<String, Object> employee = rowFor(get(MY_TEAM, MANAGER_MAIN, 200), STAFF_MALE);

		assertThat(employee.get("branch_name")).isEqualTo("Main Branch");
		assertThat(employee.get("department_name")).isEqualTo("Operations");
		assertThat(employee.get("job_title_name")).isEqualTo("Agent");
		// public_rows() still strips these two.
		assertThat(employee.keySet()).doesNotContain("password_hash", "token_version");

		// The subselect returns DATE(check_in), so it is a date string for the
		// employee who checked in today and null for everybody else.
		//
		// It is compared against the *database's* CURRENT_DATE, not the
		// application clock, and that is not a convenience: the two genuinely
		// disagree here. LegacyClock resolves today under legacy's +02:00
		// default while the database evaluates CURRENT_DATE on its own session
		// timezone, so around midnight they name different days. That gap is
		// D-083 exactly, it is open, and this endpoint does not close it --
		// asserting against the application clock would have hidden it behind a
		// flaky test instead.
		assertThat(employee.get("checked_in_today")).isEqualTo(databaseCurrentDate());
		assertThat(rowFor(get(MY_TEAM, MANAGER_MAIN, 200), STAFF_FEMALE).get("checked_in_today")).isNull();
	}

	@Test
	void myTeamIsManagerOnly() {
		// The one endpoint in this module a company admin cannot reach.
		assertThat(get(MY_TEAM, ADMIN_1, 403).get("message")).isEqualTo("Forbidden — insufficient role");
		assertThat(get(MY_TEAM, HR_1, 403).get("message")).isEqualTo("Forbidden — insufficient role");
		assertThat(get(MY_TEAM, PLAIN_EMPLOYEE, 403).get("message"))
				.isEqualTo("Forbidden — insufficient role");

		ResponseEntity<Map<String, Object>> wrongMethod = exchange(MY_TEAM, HttpMethod.POST, MANAGER_MAIN);
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
	}

	@Test
	void aManagerOfAnotherTenantGetsItsOwnBranchOnly() {
		// The branch subselect is scoped by company as well as by id, so a
		// manager can never resolve another tenant's branch.
		assertThat(idsOf(get(MY_TEAM, MANAGER_MAIN, 200)))
				.doesNotContain(ADMIN_2, 201023L);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> get(String path, long employeeId, int expectedStatus) {
		ResponseEntity<Map<String, Object>> response = exchange(path, HttpMethod.GET, employeeId);
		assertThat(response.getStatusCode().value()).as("%s: %s", path, response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private ResponseEntity<Map<String, Object>> exchange(String path, HttpMethod method, long employeeId) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		headers.set("Accept-Language", "en");
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	private static List<Long> idsOf(Map<String, Object> body) {
		List<Long> ids = new ArrayList<>();
		for (Object row : (List<?>) body.get("data")) {
			ids.add(((Number) ((Map<?, ?>) row).get("id")).longValue());
		}
		return ids;
	}

	private static Map<String, Object> rowFor(Map<String, Object> body, long employeeId) {
		for (Object row : (List<?>) body.get("data")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> employee = (Map<String, Object>) row;
			if (((Number) employee.get("id")).longValue() == employeeId) {
				return employee;
			}
		}
		throw new IllegalStateException("no row for " + employeeId);
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_MAIN ? "manager"
				: employeeId == HR_1 ? "hr"
				: employeeId == PLAIN_EMPLOYEE ? "employee" : "company_admin";
		long companyId = employeeId == ADMIN_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static void seed() throws Exception {
		// Seeded from the database's own calendar, not the application clock:
		// stats.php counts new_this_month with CURDATE(), so the fixture has to
		// be placed in the month that query will ask about. Deriving it from
		// LegacyClock's +02:00 instead would make the test fail for real in the
		// hours where the two calendars name different months.
		String currentMonthHire = databaseFirstOfMonth();
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20101, 'Stats Co', '+201000020101', 'active', '2025-01-15 09:00:00'),
					  (20102, 'Stats Other Co', '+201000020102', 'active', '2025-01-15 09:00:00'),
					  (20103, 'Stats Suspended Co', '+201000020103', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20111, 20101, 'Main Branch', 1, '2025-03-01 10:00:00'),
					  (20112, 20101, 'Second Branch', 1, '2025-03-01 10:00:00'),
					  (20113, 20102, 'Other Co Branch', 1, '2025-03-01 10:00:00'),
					  (20114, 20103, 'Suspended Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (20121, 20101, 'Operations', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at)
					VALUES (20131, 20101, 20121, 'Agent', 1, '2025-04-11 10:00:00')
					""");

			// company 1, main branch, accepted
			insert(st, ADMIN_1, COMPANY_1, BRANCH_MAIN, "'1001'", "company_admin", "'female'",
					"'2020-01-15'", 1, "'accepted'", "+201000201011", "Rana", "2020-01-15 09:00:00");
			insert(st, MANAGER_MAIN, COMPANY_1, BRANCH_MAIN, "'1002'", "manager", "'male'",
					"'2021-03-01'", 1, "'accepted'", "+201000201012", "Mostafa", "2021-03-01 09:00:00");
			insert(st, HR_1, COMPANY_1, BRANCH_MAIN, "'1003'", "hr", "'female'",
					"'2021-06-01'", 1, "'accepted'", "+201000201013", "Mona", "2021-06-01 09:00:00");
			insert(st, STAFF_MALE, COMPANY_1, BRANCH_MAIN, "'1004'", "employee", "'male'",
					"'2022-01-10'", 1, "'accepted'", "+201000201014", "Adel", "2022-01-10 09:00:00");
			insert(st, STAFF_FEMALE, COMPANY_1, BRANCH_MAIN, "'1005'", "employee", "'female'",
					"'2022-02-10'", 1, "'accepted'", "+201000201015", "Basma", "2022-02-10 09:00:00");
			// A null gender: counted in the total, in neither gender count.
			insert(st, STAFF_NO_GENDER, COMPANY_1, BRANCH_MAIN, "'1006'", "employee", "NULL",
					"'2022-03-10'", 1, "'accepted'", "+201000201016", "Carim", "2022-03-10 09:00:00");
			insert(st, STAFF_INACTIVE, COMPANY_1, BRANCH_MAIN, "'1007'", "employee", "'female'",
					"'2022-04-10'", 0, "'accepted'", "+201000201017", "Dalia", "2022-04-10 09:00:00");
			// Pending: out of stats, but on my_team, which has no roster clause.
			insert(st, STAFF_PENDING, COMPANY_1, BRANCH_MAIN, "'1008'", "employee", "'female'",
					"'2022-05-10'", 1, "'pending'", "+201000201018", "Eman", "2022-05-10 09:00:00");
			// A null hire_date, so created_at carries the date filters.
			insert(st, STAFF_OTHER_BRANCH, COMPANY_1, BRANCH_OTHER, "'1009'", "employee", "'female'",
					"NULL", 1, "'accepted'", "+201000201019", "Farida", "2019-07-10 09:00:00");
			insert(st, STAFF_NEW_THIS_MONTH, COMPANY_1, BRANCH_MAIN, "'1010'", "employee", "'female'",
					"'" + currentMonthHire + "'", 1, "'accepted'", "+201000201020", "Ghada",
					currentMonthHire + " 09:00:00");
			insert(st, PLAIN_EMPLOYEE, COMPANY_1, BRANCH_MAIN, "'1011'", "employee", "'female'",
					"'2022-06-10'", 1, "'accepted'", "+201000201021", "Hala", "2022-06-10 09:00:00");

			insert(st, ADMIN_2, COMPANY_2, 20113L, "'2001'", "company_admin", "'female'",
					"'2022-01-01'", 1, "'accepted'", "+201000201022", "Laila", "2022-01-01 09:00:00");
			insert(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 20114L, "'3001'", "company_admin", "'female'",
					"'2022-01-01'", 1, "'accepted'", "+201000201031", "Tarek", "2022-01-01 09:00:00");

			// One check-in today, so checked_in_today has something to find.
			st.execute("INSERT INTO attendance (employee_id, check_in) VALUES ("
					+ STAFF_MALE + ", CONCAT(CURRENT_DATE, ' 09:00:00'))");
		}
	}

	private static void insert(
			Statement st, long id, long companyId, long branchId, String code, String role, String gender,
			String hireDate, int active, String joinStatus, String phone, String firstName,
			String createdAt) throws Exception {
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, department_id, job_title_id, employee_code,
				   expected_daily_hours, first_name, last_name, phone, country_code, password_hash,
				   token_version, role, national_id, birth_date, gender, address, photo_url, hire_date,
				   contract_duration_months, is_active, is_mobile_attendance_enabled,
				   can_check_in_any_branch, join_request_status, created_at)
				VALUES (%d, %d, %d, 20121, 20131, %s, 8.00, '%s', 'Zaki', '%s', '+20',
				   '$2y$10$abcdefghijklmnopqrstuv', 1, '%s', '29001011200011', '0000-00-00', %s,
				   'Cairo', NULL, %s, 12, %d, 1, 0, %s, '%s')
				""".formatted(id, companyId, branchId, code, firstName, phone, role, gender, hireDate,
						active, joinStatus, createdAt));
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
		try (InputStream stream = LegacyEmployeeStatsAndTeamEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
