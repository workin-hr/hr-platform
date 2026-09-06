package com.workin.legacy.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * {@code workforce_planning/*.php} (Wave 13.4b).
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyWorkforcePlanningEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/workforce_planning/list.php";
	private static final String SUMMARY = "/apis/api/workforce_planning/summary.php";
	private static final String ONE = "/apis/api/workforce_planning/one.php";
	private static final String CREATE = "/apis/api/workforce_planning/create.php";
	private static final String SAVE_TARGET = "/apis/api/workforce_planning/save_target.php";
	private static final String UPDATE = "/apis/api/workforce_planning/update.php";
	private static final String DELETE = "/apis/api/workforce_planning/delete.php";

	private static final long COMPANY = 28001L;
	private static final long VICTIM_COMPANY = 28002L;
	private static final long ADMIN = 280011L;
	private static final long MANAGER = 280012L;
	private static final long STAFF = 280013L;
	private static final long BRANCH = 28011L;
	private static final long VICTIM_BRANCH = 28012L;
	private static final long DEPARTMENT = 28021L;
	private static final long JOB_TITLE = 28031L;
	private static final long INACTIVE_JOB_TITLE = 28032L;

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
			throw new IllegalStateException("could not prepare the planning fixture", ex);
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
	@Order(1)
	@SuppressWarnings("unchecked")
	void summaryIsTheSameEndpointAsListRatherThanASecondImplementation() {
		Map<String, Object> viaList = send(LIST, HttpMethod.GET, token(ADMIN, "company_admin"), null)
				.getBody();
		Map<String, Object> viaSummary =
				send(SUMMARY, HttpMethod.GET, token(ADMIN, "company_admin"), null).getBody();

		assertThat(viaSummary)
				.as("summary.php is `require __DIR__ . '/list.php'`, so the two must not diverge")
				.isEqualTo(viaList);
		assertThat((List<Map<String, Object>>) viaList.get("data")).hasSize(1);
	}

	@Test
	@Order(2)
	@SuppressWarnings("unchecked")
	void theRowCarriesTheJoinedNamesAndTheCorrelatedActualCount() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(
				send(LIST, HttpMethod.GET, token(ADMIN, "company_admin"), null));

		assertThat(rows.get(0))
				.containsEntry("branch_name", "Main")
				.containsEntry("department_name", "Engineering")
				.containsEntry("job_title_name", "Engineer")
				.containsEntry("planned_count", 5);
		assertThat(((Number) rows.get(0).get("actual_count")).intValue())
				.as("all three active employees share this branch, department and job title, so the "
						+ "correlated subquery counts every one of them -- including the admin and the "
						+ "manager, because it filters on the tuple and not on any role")
				.isEqualTo(3);
	}

	@Test
	@Order(3)
	void readsAdmitManagerAndWritesDoNot() {
		assertThat(send(LIST, HttpMethod.GET, token(MANAGER, "manager"), null).getStatusCode().value())
				.isEqualTo(200);
		assertThat(send(DELETE + "?id=1", HttpMethod.DELETE, token(MANAGER, "manager"), null)
				.getStatusCode().value()).isEqualTo(403);
		assertThat(send(LIST, HttpMethod.GET, token(STAFF, "employee"), null).getStatusCode().value())
				.as("an ordinary employee cannot read planning at all")
				.isEqualTo(403);
	}

	/** {@code create.php} validates all three foreign ids against the caller's company. */
	@Test
	@Order(4)
	void createRejectsForeignBranchesDepartmentsAndJobTitles() {
		assertThat(create("{\"branch_id\":" + VICTIM_BRANCH + ",\"department_id\":" + DEPARTMENT
				+ ",\"job_title_id\":" + JOB_TITLE + ",\"planned_count\":1").getStatusCode().value())
				.as("branch_not_found")
				.isEqualTo(404);
		assertThat(create("{\"branch_id\":" + BRANCH + ",\"department_id\":9999"
				+ ",\"job_title_id\":" + JOB_TITLE + ",\"planned_count\":1").getStatusCode().value())
				.as("department_not_found")
				.isEqualTo(404);
		assertThat(create("{\"branch_id\":" + BRANCH + ",\"department_id\":" + DEPARTMENT
				+ ",\"job_title_id\":" + INACTIVE_JOB_TITLE + ",\"planned_count\":1")
				.getStatusCode().value())
				.as("job_title_belongs_to_company() also requires is_active = 1")
				.isEqualTo(404);
	}

	/**
	 * <h2>Cross-tenant disclosure, demonstrated rather than described</h2>
	 *
	 * <p>{@code save_target.php} performs <b>no</b> ownership validation on
	 * {@code branch_id}, and {@code list.php}'s {@code LEFT JOIN branches}
	 * matches on id alone with no tenant predicate. So a company admin can
	 * write another company's branch id into their own planning row and read
	 * that branch's <b>name</b> straight back out. Iterating ids enumerates a
	 * competitor's organizational structure.
	 *
	 * <p>This asserts the <em>vulnerable</em> behaviour on purpose: Phase 1's
	 * contract is parity (D-058), the defect exists in production today, and
	 * fixing it in Java alone would be a silent divergence. It is filed
	 * upstream (hr-legacy) and recorded in D-131. <b>When legacy is fixed, this
	 * test must be inverted, not deleted.</b>
	 */
	@Test
	@Order(5)
	@SuppressWarnings("unchecked")
	void saveTargetLeaksAnotherCompanysBranchNameThroughTheUntenantedJoin() {
		ResponseEntity<Map<String, Object>> saved = send(SAVE_TARGET, HttpMethod.POST,
				token(ADMIN, "company_admin"),
				"{\"branch_id\":" + VICTIM_BRANCH + ",\"department_id\":0,"
						+ "\"job_title_id\":" + JOB_TITLE + ",\"planned_count\":1}");
		assertThat(saved.getStatusCode().value())
				.as("accepted with no ownership check, unlike create.php")
				.isEqualTo(200);

		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(send(
				LIST + "?branch_id=" + VICTIM_BRANCH, HttpMethod.GET, token(ADMIN, "company_admin"), null));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0))
				.as("the victim company's branch NAME is disclosed to a different tenant")
				.containsEntry("branch_name", "Victim HQ");
	}

	/** The same gap is reachable through {@code update.php}'s whitelist. */
	@Test
	@Order(6)
	@SuppressWarnings("unchecked")
	void updateAlsoWritesAForeignBranchIdWithoutValidating() {
		Map<String, Object> updated = (Map<String, Object>) data(send(UPDATE + "?id=1",
				HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"branch_id\":" + VICTIM_BRANCH + "}"));

		assertThat(updated).containsEntry("branch_name", "Victim HQ");

		// Restore, so the remaining assertions read the intended fixture.
		data(send(UPDATE + "?id=1", HttpMethod.PUT, token(ADMIN, "company_admin"),
				"{\"branch_id\":" + BRANCH + "}"));
	}

	@Test
	@Order(7)
	void saveTargetUpsertsOnTheUniqueKeyAndAnswersSavedRatherThanTheRow() {
		ResponseEntity<Map<String, Object>> first = send(SAVE_TARGET, HttpMethod.POST,
				token(ADMIN, "company_admin"),
				"{\"branch_id\":" + BRANCH + ",\"department_id\":" + DEPARTMENT
						+ ",\"job_title_id\":" + JOB_TITLE + ",\"planned_count\":9}");
		assertThat(first.getBody().get("data")).isEqualTo(Map.of("saved", true));

		// The same tuple again updates rather than inserting.
		send(SAVE_TARGET, HttpMethod.POST, token(ADMIN, "company_admin"),
				"{\"branch_id\":" + BRANCH + ",\"department_id\":" + DEPARTMENT
						+ ",\"job_title_id\":" + JOB_TITLE + ",\"planned_count\":11}");

		assertThat(countRows("SELECT COUNT(*) FROM workforce_planning WHERE company_id=" + COMPANY
				+ " AND branch_id=" + BRANCH + " AND department_id=" + DEPARTMENT
				+ " AND job_title_id=" + JOB_TITLE))
				.as("uq_workforce_target makes the second call an update")
				.isEqualTo(1);
		assertThat(countRows("SELECT planned_count FROM workforce_planning WHERE company_id=" + COMPANY
				+ " AND branch_id=" + BRANCH + " AND department_id=" + DEPARTMENT
				+ " AND job_title_id=" + JOB_TITLE)).isEqualTo(11);
	}

	@Test
	@Order(8)
	void aNegativePlannedCountIsFlooredToZeroRatherThanRejected() {
		send(SAVE_TARGET, HttpMethod.POST, token(ADMIN, "company_admin"),
				"{\"branch_id\":" + BRANCH + ",\"department_id\":0,"
						+ "\"job_title_id\":" + JOB_TITLE + ",\"planned_count\":-5}");

		assertThat(countRows("SELECT planned_count FROM workforce_planning WHERE company_id=" + COMPANY
				+ " AND branch_id=" + BRANCH + " AND department_id=0 AND job_title_id=" + JOB_TITLE))
				.as("max(0, (int) ...)")
				.isZero();
	}

	@Test
	@Order(9)
	void everyRouteChecksItsMethodFirst() {
		assertThat(send(LIST, HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(SUMMARY, HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(CREATE, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(SAVE_TARGET, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
	}

	@Test
	@Order(10)
	void anotherCompanysRowIsNotFound() {
		assertThat(send(ONE + "?id=99", HttpMethod.GET, token(ADMIN, "company_admin"), null)
				.getStatusCode().value()).isEqualTo(404);
		assertThat(send(DELETE + "?id=99", HttpMethod.DELETE, token(ADMIN, "company_admin"), null)
				.getStatusCode().value()).isEqualTo(404);
	}

	// ---------------- fixture ----------------

	private static Object data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return response.getBody().get("data");
	}

	private ResponseEntity<Map<String, Object>> create(String body) {
		return send(CREATE, HttpMethod.POST, token(ADMIN, "company_admin"), body + "}");
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, String token, String body) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String token(long employeeId, String role) {
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	private static long countRows(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0L;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Planner Co', '+201000028001', 'active', '2019-01-15 09:00:00'),"
					+ " (" + VICTIM_COMPANY + ", 'Victim Co', '+201000028002', 'active',"
					+ " '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00'),"
					+ " (" + VICTIM_BRANCH + ", " + VICTIM_COMPANY + ", 'Victim HQ', 1,"
					+ " '2019-03-01 10:00:00')");
			st.execute("INSERT INTO departments (id, company_id, name, created_at) VALUES"
					+ " (" + DEPARTMENT + ", " + COMPANY + ", 'Engineering', '2019-03-01 10:00:00')");
			st.execute("INSERT INTO job_titles (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + JOB_TITLE + ", " + COMPANY + ", 'Engineer', 1, '2019-03-01 10:00:00'),"
					+ " (" + INACTIVE_JOB_TITLE + ", " + COMPANY + ", 'Retired', 0,"
					+ " '2019-03-01 10:00:00')");

			for (long[] person : new long[][] {{ADMIN, 0}, {MANAGER, 0}, {STAFF, 1}}) {
				st.execute("INSERT INTO employees (id, company_id, branch_id, department_id,"
						+ " job_title_id, employee_code, first_name, last_name, phone, role, is_active,"
						+ " created_at) VALUES (" + person[0] + ", " + COMPANY + ", " + BRANCH + ", "
						+ DEPARTMENT + ", " + JOB_TITLE + ", '" + person[0] + "', 'F', 'L',"
						+ " '+2010000" + person[0] + "', '"
						+ (person[0] == ADMIN ? "company_admin" : person[0] == MANAGER ? "manager" : "employee")
						+ "', 1, '2019-04-01 08:00:00')");
			}

			st.execute("INSERT INTO workforce_planning (id, company_id, branch_id, department_id,"
					+ " job_title_id, planned_count) VALUES"
					+ " (1, " + COMPANY + ", " + BRANCH + ", " + DEPARTMENT + ", " + JOB_TITLE + ", 5),"
					+ " (99, " + VICTIM_COMPANY + ", " + VICTIM_BRANCH + ", 0, 0, 3)");
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
		try (InputStream in = LegacyWorkforcePlanningEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
