package com.workin.legacy.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import com.workin.backend.i18n.ApiErrorBody;
import com.workin.backend.identity.JwtService;

/**
 * Wave 12.3b's HTTP-to-real-MariaDB parity proof. The five department endpoints run through the real
 * security/tenant stack; assertions cover guards, response aggregation, validation, write state,
 * rollback, soft deletion, D-055, and D-057's verified no-permission-gate negative.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyDepartmentEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String DEPARTMENTS = "/api/legacy/departments";

	private static final long COMPANY_1 = 18801L;
	private static final long COMPANY_2 = 18802L;
	private static final long COMPANY_SUSPENDED = 18803L;
	private static final long ADMIN_1 = 188011L;
	private static final long HR_1 = 188012L;
	private static final long MANAGER_1 = 188013L;
	private static final long EMPLOYEE_1 = 188014L;
	private static final long ADMIN_2 = 188021L;
	private static final long ADMIN_SUSPENDED = 188031L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the Wave 12.3b department fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
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
					  (18801, 'Organization E2E Co 1', '+201000018801', 'active', '2025-01-15 09:00:00'),
					  (18802, 'Organization E2E Co 2', '+201000018802', 'active', '2025-01-15 09:00:00'),
					  (18803, 'Organization E2E Suspended', '+201000018803', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (18811, 18801, 'Alpha Branch', 1, '2025-03-01 10:00:00'),
					  (18812, 18801, 'Beta Branch', 1, '2025-03-01 10:00:00'),
					  (18813, 18801, 'Inactive Branch', 0, '2025-03-01 10:00:00'),
					  (18821, 18802, 'Other Company Branch', 1, '2025-03-01 10:00:00'),
					  (18831, 18803, 'Suspended Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, manager_id, is_active, created_at) VALUES
					  (18841, 18801, 'Engineering', 188012, 1, '2025-04-10 10:00:00'),
					  (18842, 18801, 'Branchless', NULL, 1, '2025-04-09 10:00:00'),
					  (18843, 18801, 'Inactive Department', NULL, 0, '2025-04-08 10:00:00'),
					  (18844, 18802, 'Other Company Department', NULL, 1, '2025-04-07 10:00:00'),
					  (18845, 18801, 'Inactive Branch Only', NULL, 1, '2025-04-06 10:00:00'),
					  (18846, 18801, 'Manager Null Target', 188012, 1, '2025-04-05 10:00:00'),
					  (18847, 18801, 'Replace Links Target', NULL, 1, '2025-04-04 10:00:00'),
					  (18848, 18801, 'Rollback Original', NULL, 1, '2025-04-03 10:00:00'),
					  (18849, 18801, 'Delete Target', NULL, 1, '2025-04-02 10:00:00'),
					  (18850, 18801, 'Mixed Active Links', NULL, 1, '2025-04-01 10:00:00'),
					  (18851, 18801, 'Inactive Job Department', NULL, 0, '2025-03-31 10:00:00'),
					  (18852, 18801, 'Job Branchless Department', NULL, 1, '2025-03-30 10:00:00'),
					  (18853, 18801, 'Job Move Target', NULL, 1, '2025-03-29 10:00:00')
					""");
			st.execute("""
					INSERT INTO department_branches (department_id, branch_id) VALUES
					  (18841, 18811), (18841, 18812), (18843, 18811), (18844, 18821),
					  (18845, 18813), (18846, 18811), (18847, 18811), (18848, 18811),
					  (18849, 18811), (18850, 18811), (18850, 18813), (18851, 18811), (18853, 18812)
					""");
			// Valid existing link, but any replacement for this one fixture department fails
			// during insert. This forces the rollback proof past the native DELETE.
			st.execute("""
					ALTER TABLE department_branches
					ADD CONSTRAINT chk_wave123_rollback_probe
					CHECK (department_id <> 18848 OR branch_id = 18811)
					""");
			// MyISAM makes this trigger marker survive the surrounding InnoDB rollback,
			// proving the native DELETE actually executed before the forced insert failure.
			st.execute("""
					CREATE TABLE wave123_department_branch_delete_audit (
					  department_id BIGINT NOT NULL, branch_id BIGINT NOT NULL
					) ENGINE=MyISAM
					""");
			st.execute("""
					CREATE TRIGGER wave123_department_branch_delete_probe
					BEFORE DELETE ON department_branches FOR EACH ROW
					INSERT INTO wave123_department_branch_delete_audit (department_id, branch_id)
					VALUES (OLD.department_id, OLD.branch_id)
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, first_name, last_name, phone, role,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (188011, 18801, 18811, 'Admin', 'One', '+201100188011', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188012, 18801, 18811, 'Hr', 'Manager', '+201100188012', 'hr', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188013, 18801, 18811, 'Manager', 'One', '+201100188013', 'manager', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188014, 18801, 18811, 'Employee', 'One', '+201100188014', 'employee', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188021, 18802, 18821, 'Admin', 'Two', '+201100188021', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188031, 18803, 18831, 'Admin', 'Suspended', '+201100188031', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00')
					""");
		}
	}

	@Test
	void applicationConnectionsUseProductionEquivalentNonStrictSqlMode() throws Exception {
		try (Connection first = legacyDataSource.getConnection();
				Connection second = legacyDataSource.getConnection()) {
			assertThat(sessionSqlMode(first)).isEmpty();
			assertThat(sessionSqlMode(second)).isEmpty();
		}
	}

	// ---------- shared guard behavior ----------

	@Test
	void plainEmployeeCannotReadDepartments() {
		ResponseEntity<ApiErrorBody> response = get(DEPARTMENTS, EMPLOYEE_1, ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().code()).isEqualTo("forbidden_insufficient_role");
	}

	@Test
	void managerCanReadDepartments() {
		assertThat(get(DEPARTMENTS, MANAGER_1, LegacyDepartmentView[].class).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void inactiveCompanyCannotReadDepartments() {
		ResponseEntity<ApiErrorBody> response = get(DEPARTMENTS, ADMIN_SUSPENDED, ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody().code()).isEqualTo("company_account_not_active");
	}


	// ---------- departments list / one ----------

	@Test
	void departmentListReproducesInnerJoinAndActiveBranchRules() {
		LegacyDepartmentView[] rows = get(DEPARTMENTS, ADMIN_1, LegacyDepartmentView[].class).getBody();
		assertThat(rows).extracting(LegacyDepartmentView::name)
				.contains("Engineering", "Mixed Active Links")
				.doesNotContain("Branchless", "Inactive Department", "Inactive Branch Only", "Other Company Department");
	}

	@Test
	void departmentListAggregatesSortedIdsNamesAndManagerName() {
		LegacyDepartmentView row = java.util.Arrays.stream(
				get(DEPARTMENTS, ADMIN_1, LegacyDepartmentView[].class).getBody())
				.filter(candidate -> candidate.name().equals("Engineering"))
				.findFirst()
				.orElseThrow();
		assertThat(row.name()).isEqualTo("Engineering");
		assertThat(row.branchIds()).isEqualTo("18811,18812");
		assertThat(row.branchNames()).isEqualTo("Alpha Branch, Beta Branch");
		assertThat(row.managerName()).isEqualTo("Hr Manager");
	}

	@Test
	void branchIdFilterMatchesAnInactiveLinkButResponseStillContainsOnlyActiveBranches() {
		LegacyDepartmentView[] rows = get(
				DEPARTMENTS + "?branch_id=18813", ADMIN_1, LegacyDepartmentView[].class).getBody();
		assertThat(rows).extracting(LegacyDepartmentView::name).containsExactly("Mixed Active Links");
		assertThat(rows[0].branchNames()).isEqualTo("Alpha Branch");
	}

	@Test
	void branchIdQueryUsesPhpIntegerCoercionBeforeFiltering() {
		List<String> unfiltered = departmentNames(DEPARTMENTS);
		for (String query : List.of(
				"?branch_id=abc",
				"?branch_id=0",
				"?branch_id=",
				"?branch_id=-18811branch",
				"?branch_id=-9223372036854775809overflow")) {
			assertThat(departmentNames(DEPARTMENTS + query)).containsExactlyElementsOf(unfiltered);
		}

		List<String> branch18811 = departmentNames(DEPARTMENTS + "?branch_id=18811");
		assertThat(departmentNames(DEPARTMENTS + "?branch_id=18811branch"))
				.containsExactlyElementsOf(branch18811);
		assertThat(departmentNames(DEPARTMENTS + "?branch_id=%20%2018811branch"))
				.containsExactlyElementsOf(branch18811);
		assertThat(departmentNames(DEPARTMENTS + "?branch_id=%2B18811branch"))
				.containsExactlyElementsOf(branch18811);

		assertThat(departmentNames(DEPARTMENTS + "?branch_id=9223372036854775808overflow"))
				.isEmpty();
	}

	@Test
	void departmentOneIncludesInactiveLinkedBranchesUnlikeList() {
		LegacyDepartmentView row = get(DEPARTMENTS + "/18850", ADMIN_1, LegacyDepartmentView.class).getBody();
		assertThat(row.branchNames()).isEqualTo("Alpha Branch, Inactive Branch");
	}

	@Test
	void branchlessDepartmentIsNotReadableById() {
		ResponseEntity<ApiErrorBody> response = get(DEPARTMENTS + "/18842", ADMIN_1, ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("forbidden");
	}

	@Test
	void anotherCompanysDepartmentIsNotReadable() {
		assertThat(get(DEPARTMENTS + "/18844", ADMIN_1, ApiErrorBody.class).getStatusCode().value()).isEqualTo(404);
	}

	// ---------- departments create / update / delete ----------

	@Test
	void adminWithNoHrPermissionsRowCanCreateDepartmentUsingLegacySnakeCaseKeys() throws Exception {
		assertThat(queryLong("SELECT COUNT(*) FROM hr_permissions WHERE employee_id = " + ADMIN_1)).isZero();
		ResponseEntity<LegacyDepartmentView> response = post(
				DEPARTMENTS, ADMIN_1,
				Map.of("name", "Created Department", "branch_ids", java.util.List.of(18812, 18811, 18811)),
				LegacyDepartmentView.class);
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().companyId()).isNull();
		assertThat(response.getBody().branchIds()).isEqualTo("18811,18812");
		assertThat(linkCount(response.getBody().id())).isEqualTo(2);
	}

	@Test
	void departmentCreateResponseUsesLegacySnakeCaseAndOmitsCompanyId() {
		ResponseEntity<String> response = post(
				DEPARTMENTS, ADMIN_1, Map.of("name", "Wire Shape Department", "branch_ids", java.util.List.of(18811)),
				String.class);
		assertThat(response.getBody()).contains("\"branch_ids\"", "\"branch_names\"", "\"manager_id\"")
				.doesNotContain("\"company_id\"");
	}

	@Test
	void createRejectsEmptyOrInvalidBranchSetsWithLegacyStatuses() {
		ResponseEntity<ApiErrorBody> empty = post(
				DEPARTMENTS, ADMIN_1, Map.of("name", "No Branch", "branch_ids", java.util.List.of()), ApiErrorBody.class);
		assertThat(empty.getStatusCode().value()).isEqualTo(400);
		assertThat(empty.getBody().code()).isEqualTo("invalid_branch_ids");

		ResponseEntity<ApiErrorBody> inactive = post(
				DEPARTMENTS, ADMIN_1, Map.of("name", "Bad Branch", "branch_ids", java.util.List.of(18813)), ApiErrorBody.class);
		assertThat(inactive.getStatusCode().value()).isEqualTo(404);
		assertThat(inactive.getBody().code()).isEqualTo("branch_not_found");
	}

	@Test
	void createRejectsManagerFromAnotherCompany() {
		ResponseEntity<ApiErrorBody> response = post(
				DEPARTMENTS, ADMIN_1,
				Map.of("name", "Bad Manager", "branch_ids", java.util.List.of(18811), "manager_id", ADMIN_2),
				ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("employee_not_found");
	}

	@Test
	void createUsesPhpEmptyAndAssociativeArrayValuesForJsonObjects() throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "PHP Object Shapes");
		body.put("manager_id", Map.of());
		body.put("branch_ids", Map.of("primary", "18811branch", "secondary", 18812));

		ResponseEntity<LegacyDepartmentView> created = post(
				DEPARTMENTS, ADMIN_1, body, LegacyDepartmentView.class);
		assertThat(created.getStatusCode().value()).isEqualTo(201);
		assertThat(created.getBody().managerId()).isNull();
		assertThat(created.getBody().branchIds()).isEqualTo("18811,18812");
		assertThat(departmentString(created.getBody().id(), "manager_id")).isNull();
		assertThat(linkCount(created.getBody().id())).isEqualTo(2);

		ResponseEntity<ApiErrorBody> nonEmptyManager = post(
				DEPARTMENTS, ADMIN_1,
				Map.of(
						"name", "PHP Nonempty Manager Object",
						"branch_ids", java.util.List.of(18811),
						"manager_id", Map.of("value", "ignored-by-php-array-cast")),
				ApiErrorBody.class);
		assertThat(nonEmptyManager.getStatusCode().value()).isEqualTo(404);
		assertThat(nonEmptyManager.getBody().code()).isEqualTo("employee_not_found");
	}

	@Test
	void departmentNumericInputsFollowPhpIntegerCastValidationPaths() throws Exception {
		ResponseEntity<LegacyDepartmentView> created = post(
				DEPARTMENTS, ADMIN_1,
				Map.of("name", "PHP Cast Manager", "branch_ids", java.util.List.of(18811), "manager_id", "abc"),
				LegacyDepartmentView.class);
		assertThat(created.getStatusCode().value()).isEqualTo(201);
		assertThat(created.getBody().managerId()).isZero();
		assertThat(departmentLong(created.getBody().id(), "manager_id")).isZero();

		ResponseEntity<ApiErrorBody> manager = put(
				DEPARTMENTS + "/18846", ADMIN_1, Map.of("manager_id", "abc"), ApiErrorBody.class);
		assertThat(manager.getStatusCode().value()).isEqualTo(404);
		assertThat(manager.getBody().code()).isEqualTo("employee_not_found");
		assertThat(departmentLong(18846, "manager_id")).isEqualTo(HR_1);

		ResponseEntity<ApiErrorBody> branchIds = put(
				DEPARTMENTS + "/18847", ADMIN_1,
				Map.of("branch_ids", java.util.List.of("abc")), ApiErrorBody.class);
		assertThat(branchIds.getStatusCode().value()).isEqualTo(400);
		assertThat(branchIds.getBody().code()).isEqualTo("invalid_branch_ids");

		LegacyDepartmentView leadingManager = put(
				DEPARTMENTS + "/18846", ADMIN_1, Map.of("manager_id", " 188012employee"),
				LegacyDepartmentView.class).getBody();
		assertThat(leadingManager.managerId()).isEqualTo(HR_1);

		LegacyDepartmentView leadingBranch = put(
				DEPARTMENTS + "/18847", ADMIN_1, Map.of("branch_ids", java.util.List.of("18812branch")),
				LegacyDepartmentView.class).getBody();
		assertThat(leadingBranch.branchIds()).isEqualTo("18812");
	}

	@Test
	void explicitNullManagerDoesNotClearExistingManagerD055() throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("manager_id", null);
		LegacyDepartmentView row = put(DEPARTMENTS + "/18846", ADMIN_1, body, LegacyDepartmentView.class).getBody();
		assertThat(row.managerId()).isEqualTo(HR_1);
		assertThat(departmentLong(18846, "manager_id")).isEqualTo(HR_1);
	}

	@Test
	void updateReplacesTheWholeBranchSet() throws Exception {
		LegacyDepartmentView row = put(
				DEPARTMENTS + "/18847", ADMIN_1, Map.of("branch_ids", java.util.List.of(18812)),
				LegacyDepartmentView.class).getBody();
		assertThat(row.branchIds()).isEqualTo("18812");
		assertThat(linkCount(18847)).isEqualTo(1);
	}

	@Test
	void updateReturnsMariaDbPersistedTruncatedName() throws Exception {
		String submitted = "N".repeat(300);
		String persisted = "N".repeat(255);

		LegacyDepartmentView row = put(
				DEPARTMENTS + "/18847", ADMIN_1, Map.of("name", submitted), LegacyDepartmentView.class)
				.getBody();

		assertThat(row.name()).isEqualTo(persisted);
		assertThat(departmentString(18847, "name")).isEqualTo(persisted);
	}

	@Test
	void insertFailureAfterNativeDeleteRollsBackDepartmentMutationAndLinks() throws Exception {
		ResponseEntity<ApiErrorBody> response = put(
				DEPARTMENTS + "/18848", ADMIN_1,
				Map.of("name", "Must Roll Back", "branch_ids", java.util.List.of(18812)), ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().code()).isEqualTo("invalid_branch_ids");
		assertThat(queryLong("SELECT COUNT(*) FROM wave123_department_branch_delete_audit "
				+ "WHERE department_id = 18848 AND branch_id = 18811"))
				.describedAs("the test must reach the native delete before insert failure")
				.isEqualTo(1);
		assertThat(departmentString(18848, "name")).isEqualTo("Rollback Original");
		assertThat(linkCount(18848)).isEqualTo(1);
		assertThat(queryLong("SELECT branch_id FROM department_branches WHERE department_id = 18848"))
				.isEqualTo(18811);
	}

	@Test
	void branchlessDepartmentUpdateCommitsThenFailsItsLegacyResponseJoin() throws Exception {
		ResponseEntity<String> response = put(
				DEPARTMENTS + "/18842", ADMIN_1, Map.of("name", "Branchless Updated"), String.class);
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(departmentString(18842, "name")).isEqualTo("Branchless Updated");
		assertThat(linkCount(18842)).isZero();
	}

	@Test
	void departmentDeleteIsSoftAndPreservesJunctionRows() throws Exception {
		assertThat(delete(DEPARTMENTS + "/18849", ADMIN_1, Void.class).getStatusCode().value()).isEqualTo(200);
		assertThat(departmentLong(18849, "is_active")).isZero();
		assertThat(linkCount(18849)).isEqualTo(1);
	}

	@Test
	void departmentWritesCannotTargetAnotherTenant() {
		ResponseEntity<ApiErrorBody> response = put(
				DEPARTMENTS + "/18844", ADMIN_1, Map.of("name", "Hijack"), ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("department_not_found");
	}

	// ---------- helpers ----------

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyDepartmentEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static long linkCount(long departmentId) throws Exception {
		return queryLong("SELECT COUNT(*) FROM department_branches WHERE department_id = " + departmentId);
	}

	private static long departmentLong(long id, String column) throws Exception {
		return queryLong("SELECT " + column + " FROM departments WHERE id = " + id);
	}

	private static String departmentString(long id, String column) throws Exception {
		return queryString("SELECT " + column + " FROM departments WHERE id = " + id);
	}

	private static String sessionSqlMode(Connection connection) throws Exception {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
			result.next();
			return result.getString(1);
		}
	}


	private static long queryLong(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static String queryString(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getString(1);
		}
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == HR_1 ? "hr" : employeeId == MANAGER_1 ? "manager"
				: employeeId == EMPLOYEE_1 ? "employee" : "company_admin";
		long companyId = employeeId == ADMIN_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private List<String> departmentNames(String path) {
		ResponseEntity<LegacyDepartmentView[]> response = get(
				path, ADMIN_1, LegacyDepartmentView[].class);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		return java.util.Arrays.stream(response.getBody()).map(LegacyDepartmentView::name).toList();
	}

	private <T> ResponseEntity<T> get(String path, long employeeId, Class<T> type) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.GET,
				new HttpEntity<>(headersFor(tokenFor(employeeId))), type);
	}

	private <T> ResponseEntity<T> post(String path, long employeeId, Map<String, Object> body, Class<T> type) {
		return restTemplate.exchange(
				path, HttpMethod.POST, new HttpEntity<>(body, headersFor(tokenFor(employeeId))), type);
	}

	private <T> ResponseEntity<T> put(String path, long employeeId, Map<String, Object> body, Class<T> type) {
		return restTemplate.exchange(
				path, HttpMethod.PUT, new HttpEntity<>(body, headersFor(tokenFor(employeeId))), type);
	}

	private <T> ResponseEntity<T> delete(String path, long employeeId, Class<T> type) {
		return restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headersFor(tokenFor(employeeId))), type);
	}

	private static HttpHeaders headersFor(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

}
