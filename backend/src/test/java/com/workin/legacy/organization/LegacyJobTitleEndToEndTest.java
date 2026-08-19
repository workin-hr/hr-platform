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
import com.workin.backend.i18n.ApiErrorBody;
import com.workin.backend.identity.JwtService;

/** Wave 12.3c's independently runnable HTTP-to-real-MariaDB job-title parity proof. */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyJobTitleEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");
	private static final String JOB_TITLES = "/api/legacy/job_titles";

	private static final long COMPANY_1 = 18801L;
	private static final long COMPANY_2 = 18802L;
	private static final long COMPANY_SUSPENDED = 18803L;
	private static final long ADMIN_1 = 188011L;
	private static final long MANAGER_1 = 188013L;
	private static final long EMPLOYEE_1 = 188014L;
	private static final long ADMIN_2 = 188021L;
	private static final long ADMIN_SUSPENDED = 188031L;

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
			throw new IllegalStateException("could not prepare the Wave 12.3c job-title fixture", ex);
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
					  (18801, 'Job Title E2E Co 1', '+201000018801', 'active', '2025-01-15 09:00:00'),
					  (18802, 'Job Title E2E Co 2', '+201000018802', 'active', '2025-01-15 09:00:00'),
					  (18803, 'Job Title E2E Suspended', '+201000018803', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (18811, 18801, 'Alpha Branch', 1, '2025-03-01 10:00:00'),
					  (18812, 18801, 'Beta Branch', 1, '2025-03-01 10:00:00'),
					  (18821, 18802, 'Other Company Branch', 1, '2025-03-01 10:00:00'),
					  (18831, 18803, 'Suspended Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, manager_id, is_active, created_at) VALUES
					  (18841, 18801, 'Engineering', NULL, 1, '2025-04-10 10:00:00'),
					  (18844, 18802, 'Other Company Department', NULL, 1, '2025-04-07 10:00:00'),
					  (18851, 18801, 'Inactive Job Department', NULL, 0, '2025-03-31 10:00:00'),
					  (18852, 18801, 'Job Branchless Department', NULL, 1, '2025-03-30 10:00:00'),
					  (18853, 18801, 'Job Move Target', NULL, 1, '2025-03-29 10:00:00')
					""");
			st.execute("""
					INSERT INTO department_branches (department_id, branch_id) VALUES
					  (18841, 18811), (18841, 18812), (18844, 18821), (18851, 18811), (18853, 18812)
					""");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, work_hours, is_active, created_at) VALUES
					  (18901, 18801, 18841, 'Backend Engineer', 8.00, 1, '2025-05-10 10:00:00'),
					  (18902, 18801, 18841, 'Inactive Title', 8.00, 0, '2025-05-09 10:00:00'),
					  (18903, 18801, 18851, 'Title In Inactive Department', 7.50, 1, '2025-05-08 10:00:00'),
					  (18904, 18802, 18844, 'Other Company Title', 8.00, 1, '2025-05-07 10:00:00'),
					  (18905, 18801, 18852, 'Branchless Department Title', 6.00, 1, '2025-05-06 10:00:00'),
					  (18906, 18801, 18841, 'Update Target', 8.00, 1, '2025-05-05 10:00:00'),
					  (18907, 18801, 18841, 'Delete Target', 8.00, 1, '2025-05-04 10:00:00'),
					  (18908, 18801, 18841, 'Null Name Target', 8.00, 1, '2025-05-03 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, first_name, last_name, phone, role,
					   is_active, is_mobile_attendance_enabled, can_check_in_any_branch,
					   join_request_status, token_version, created_at)
					VALUES
					  (188011, 18801, 18811, 'Admin', 'One', '+201100188011', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188013, 18801, 18811, 'Manager', 'One', '+201100188013', 'manager', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188014, 18801, 18811, 'Employee', 'One', '+201100188014', 'employee', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188021, 18802, 18821, 'Admin', 'Two', '+201100188021', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00'),
					  (188031, 18803, 18831, 'Admin', 'Suspended', '+201100188031', 'company_admin', 1, 1, 0, 'accepted', 1, '2025-06-01 08:00:00')
					""");
		}
	}

	@Test
	void jobTitleGuardsMatchLegacy() {
		assertThat(get(JOB_TITLES, MANAGER_1, LegacyJobTitleView[].class).getStatusCode().value()).isEqualTo(200);
		ResponseEntity<ApiErrorBody> employee = get(JOB_TITLES, EMPLOYEE_1, ApiErrorBody.class);
		assertThat(employee.getStatusCode().value()).isEqualTo(403);
		assertThat(employee.getBody().code()).isEqualTo("forbidden_insufficient_role");
		ResponseEntity<ApiErrorBody> suspended = get(JOB_TITLES, ADMIN_SUSPENDED, ApiErrorBody.class);
		assertThat(suspended.getStatusCode().value()).isEqualTo(403);
		assertThat(suspended.getBody().code()).isEqualTo("company_account_not_active");
	}

	@Test
	void listReturnsActiveOwnedRowsWithDepartmentAndBranchSummary() {
		LegacyJobTitleView[] rows = get(JOB_TITLES, ADMIN_1, LegacyJobTitleView[].class).getBody();
		assertThat(rows).extracting(LegacyJobTitleView::name)
				.contains("Backend Engineer", "Title In Inactive Department", "Branchless Department Title")
				.doesNotContain("Inactive Title", "Other Company Title");
		LegacyJobTitleView backend = java.util.Arrays.stream(rows)
				.filter(row -> row.name().equals("Backend Engineer")).findFirst().orElseThrow();
		assertThat(backend.departmentName()).isEqualTo("Engineering");
		assertThat(backend.branchesSummary()).isEqualTo("Alpha Branch, Beta Branch");
	}

	@Test
	void inactiveAndBranchlessDepartmentsRetainLegacyReadShape() {
		LegacyJobTitleView inactive = get(JOB_TITLES + "/18903", ADMIN_1, LegacyJobTitleView.class).getBody();
		assertThat(inactive.departmentName()).isEqualTo("Inactive Job Department");
		assertThat(inactive.branchesSummary()).isEqualTo("Alpha Branch");
		LegacyJobTitleView branchless = get(JOB_TITLES + "/18905", ADMIN_1, LegacyJobTitleView.class).getBody();
		assertThat(branchless.departmentName()).isEqualTo("Job Branchless Department");
		assertThat(branchless.branchesSummary()).isNull();
	}

	@Test
	void listFiltersByBranchAndDepartment() {
		LegacyJobTitleView[] byBranch = get(
				JOB_TITLES + "?branch_id=18812", ADMIN_1, LegacyJobTitleView[].class).getBody();
		assertThat(byBranch).extracting(LegacyJobTitleView::name).contains("Backend Engineer", "Update Target");
		LegacyJobTitleView[] byDepartment = get(
				JOB_TITLES + "?department_id=18851", ADMIN_1, LegacyJobTitleView[].class).getBody();
		assertThat(byDepartment).extracting(LegacyJobTitleView::name)
				.containsExactly("Title In Inactive Department");
	}

	@Test
	void branchIdQueryUsesPhpIntegerCoercionBeforeFiltering() {
		List<String> unfiltered = jobTitleNames(JOB_TITLES);
		for (String query : List.of(
				"?branch_id=abc",
				"?branch_id=0",
				"?branch_id=",
				"?branch_id=-18812branch",
				"?branch_id=-9223372036854775809overflow")) {
			assertThat(jobTitleNames(JOB_TITLES + query)).containsExactlyElementsOf(unfiltered);
		}

		List<String> branch18812 = jobTitleNames(JOB_TITLES + "?branch_id=18812");
		assertThat(jobTitleNames(JOB_TITLES + "?branch_id=18812branch"))
				.containsExactlyElementsOf(branch18812);
		assertThat(jobTitleNames(JOB_TITLES + "?branch_id=%20%2018812branch"))
				.containsExactlyElementsOf(branch18812);
		assertThat(jobTitleNames(JOB_TITLES + "?branch_id=%2B18812branch"))
				.containsExactlyElementsOf(branch18812);
		assertThat(jobTitleNames(JOB_TITLES + "?branch_id=9223372036854775808overflow"))
				.isEmpty();
	}

	@Test
	void departmentIdQueryUsesPhpIntegerCoercionBeforeFiltering() {
		List<String> unfiltered = jobTitleNames(JOB_TITLES);
		for (String query : List.of(
				"?department_id=abc",
				"?department_id=0",
				"?department_id=",
				"?department_id=-18841department",
				"?department_id=-9223372036854775809overflow")) {
			assertThat(jobTitleNames(JOB_TITLES + query)).containsExactlyElementsOf(unfiltered);
		}

		List<String> department18841 = jobTitleNames(JOB_TITLES + "?department_id=18841");
		assertThat(jobTitleNames(JOB_TITLES + "?department_id=18841legacy"))
				.containsExactlyElementsOf(department18841);
		assertThat(jobTitleNames(JOB_TITLES + "?department_id=%20%2018841legacy"))
				.containsExactlyElementsOf(department18841);
		assertThat(jobTitleNames(JOB_TITLES + "?department_id=%2B18841legacy"))
				.containsExactlyElementsOf(department18841);
		assertThat(jobTitleNames(JOB_TITLES + "?department_id=9223372036854775808overflow"))
				.isEmpty();
	}

	@Test
	void bracketArrayNumericFiltersUsePhpArrayCastInsteadOfSpringScalarBinding() {
		for (String parameter : List.of("branch_id", "department_id")) {
			String validId = parameter.equals("branch_id") ? "18812" : "18841";
			String otherValidId = parameter.equals("branch_id") ? "18811" : "18851";
			for (String values : List.of(
					"%5B%5D=" + validId,
					"%5B%5D=" + otherValidId + "&" + parameter + "%5B%5D=" + validId,
					"%5B%5D=abc&" + parameter + "%5B%5D=" + validId + "legacy",
					"%5B%5D=")) {
				assertThat(jobTitleNames(JOB_TITLES + "?" + parameter + values)).isEmpty();
			}
		}
	}

	@Test
	void duplicateAndMixedBranchIdAssignmentsFollowPhpParseStrPrecedence() {
		List<String> branch18812 = jobTitleNames(JOB_TITLES + "?branch_id=18812");

		assertThat(jobTitleNames(JOB_TITLES + "?branch_id=18811&branch_id=18812"))
				.containsExactlyElementsOf(branch18812);
		assertThat(jobTitleNames(JOB_TITLES + "?branch_id=18811&branch_id%5B%5D=18812"))
				.isEmpty();
		assertThat(jobTitleNames(JOB_TITLES + "?branch_id%5B%5D=18811&branch_id=18812"))
				.containsExactlyElementsOf(branch18812);
	}

	@Test
	void duplicateAndMixedDepartmentIdAssignmentsFollowPhpParseStrPrecedence() {
		List<String> department18841 = jobTitleNames(JOB_TITLES + "?department_id=18841");

		assertThat(jobTitleNames(JOB_TITLES + "?department_id=18851&department_id=18841"))
				.containsExactlyElementsOf(department18841);
		assertThat(jobTitleNames(JOB_TITLES + "?department_id=18841&department_id%5B%5D=18851"))
				.isEmpty();
		assertThat(jobTitleNames(JOB_TITLES + "?department_id%5B%5D=18851&department_id=18841"))
				.containsExactlyElementsOf(department18841);
	}

	@Test
	void normalizedJobTitleQueryNamesApplyTheirCanonicalFilters() {
		assertThat(jobTitleNames(JOB_TITLES + "?branch.id=18812"))
				.containsExactlyElementsOf(jobTitleNames(JOB_TITLES + "?branch_id=18812"));
		assertThat(jobTitleNames(JOB_TITLES + "?department+id=18841"))
				.containsExactlyElementsOf(jobTitleNames(JOB_TITLES + "?department_id=18841"));
	}

	@Test
	void keyedJobTitleQueryArraysRemainArrayShapedBeforeEndpointCasts() {
		List<String> branch18812 = jobTitleNames(JOB_TITLES + "?branch_id=18812");
		List<String> department18841 = jobTitleNames(JOB_TITLES + "?department_id=18841");

		assertThat(jobTitleNames(JOB_TITLES + "?branch_id%5Bitem%5D=18812")).isEmpty();
		assertThat(jobTitleNames(
				JOB_TITLES + "?department_id%5Bitem%5D=18841&department_id%5Bother%5D=18851")).isEmpty();
		assertThat(jobTitleNames(JOB_TITLES + "?branch_id=18811&branch_id%5Bitem%5D=18812")).isEmpty();
		assertThat(jobTitleNames(JOB_TITLES + "?branch_id%5Bitem%5D=18811&branch_id=18812"))
				.containsExactlyElementsOf(branch18812);
		assertThat(jobTitleNames(JOB_TITLES + "?department_id%5Bitem%5D=18851&department_id=18841"))
				.containsExactlyElementsOf(department18841);
	}

	@Test
	void oneHidesInactiveAndForeignRows() {
		assertThat(get(JOB_TITLES + "/18902", ADMIN_1, ApiErrorBody.class).getStatusCode().value()).isEqualTo(404);
		assertThat(get(JOB_TITLES + "/18904", ADMIN_1, ApiErrorBody.class).getStatusCode().value()).isEqualTo(404);
	}

	@Test
	void adminWithNoHrPermissionsRowCanCreateUsingLegacyKeysAndPersistedDecimal() throws Exception {
		assertThat(queryLong("SELECT COUNT(*) FROM hr_permissions WHERE employee_id = " + ADMIN_1)).isZero();
		ResponseEntity<LegacyJobTitleView> response = post(
				JOB_TITLES, ADMIN_1,
				Map.of("name", "  QA Engineer  ", "department_id", 18841, "work_hours", 7.25),
				LegacyJobTitleView.class);
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().name()).isEqualTo("QA Engineer");
		assertThat(response.getBody().workHours()).isEqualByComparingTo("7.25");
		assertThat(jobTitleString(response.getBody().id(), "work_hours")).isEqualTo("7.25");
	}

	@Test
	void createUsesPhpLeadingNumericWhitespaceAndBooleanCasts() throws Exception {
		LegacyJobTitleView leadingNumeric = post(
				JOB_TITLES, ADMIN_1,
				Map.of(
						"name", "Leading Numeric", "department_id", " 18841department",
						"work_hours", " 7.5hours"),
				LegacyJobTitleView.class).getBody();
		assertThat(leadingNumeric.departmentId()).isEqualTo(18841L);
		assertThat(leadingNumeric.workHours()).isEqualByComparingTo("7.50");

		LegacyJobTitleView booleanHours = post(
				JOB_TITLES, ADMIN_1,
				Map.of("name", "Boolean Hours", "department_id", 18841, "work_hours", true),
				LegacyJobTitleView.class).getBody();
		assertThat(booleanHours.workHours()).isEqualByComparingTo("1.00");
	}

	@Test
	void createUsesPhpStringCastForFalseNameValidation() {
		ResponseEntity<ApiErrorBody> response = post(
				JOB_TITLES, ADMIN_1,
				Map.of("name", false, "department_id", 18841, "work_hours", 8),
				ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().code()).isEqualTo("field_required");
	}

	@Test
	void createPersistsPhpArrayStringForArrayAndAssociativeObjectNames() throws Exception {
		for (Object rawName : List.of(List.of(), List.of("ignored"), Map.of("key", "ignored"))) {
			ResponseEntity<LegacyJobTitleView> response = post(
					JOB_TITLES, ADMIN_1,
					Map.of("name", rawName, "department_id", 18841, "work_hours", 8),
					LegacyJobTitleView.class);
			assertThat(response.getStatusCode().value()).isEqualTo(201);
			assertThat(response.getBody().name()).isEqualTo("Array");
			assertThat(jobTitleString(response.getBody().id(), "name")).isEqualTo("Array");
		}
	}

	@Test
	void readWireShapeUsesLegacySnakeCase() {
		ResponseEntity<String> response = get(JOB_TITLES + "/18901", ADMIN_1, String.class);
		assertThat(response.getBody()).contains(
				"\"company_id\"", "\"department_id\"", "\"work_hours\"", "\"is_active\"",
				"\"created_at\"", "\"department_name\"", "\"branches_summary\"");
	}

	@Test
	void createRejectsMissingNonpositiveAndMalformedWorkHours() {
		ResponseEntity<ApiErrorBody> missing = post(
				JOB_TITLES, ADMIN_1, Map.of("name", "Missing Hours", "department_id", 18841), ApiErrorBody.class);
		assertThat(missing.getStatusCode().value()).isEqualTo(400);
		assertThat(missing.getBody().code()).isEqualTo("field_required");
		for (Object value : java.util.List.of(0, "abc", false, "", "   ")) {
			ResponseEntity<ApiErrorBody> response = post(
					JOB_TITLES, ADMIN_1,
					Map.of("name", "Bad Hours", "department_id", 18841, "work_hours", value), ApiErrorBody.class);
			assertThat(response.getStatusCode().value()).isEqualTo(400);
			assertThat(response.getBody().code()).isEqualTo("field_required");
		}
	}

	@Test
	void createRejectsInactiveForeignAndMalformedDepartmentIds() {
		for (Object value : java.util.List.of(18851, 18844, "abc")) {
			ResponseEntity<ApiErrorBody> response = post(
					JOB_TITLES, ADMIN_1,
					Map.of("name", "Bad Department", "department_id", value, "work_hours", 8), ApiErrorBody.class);
			assertThat(response.getStatusCode().value()).isEqualTo(404);
			assertThat(response.getBody().code()).isEqualTo("department_not_found");
		}
	}

	@Test
	void updateRejectsEmptyUnknownNonpositiveAndMalformedInputs() {
		assertThat(put(JOB_TITLES + "/18906", ADMIN_1, Map.of(), ApiErrorBody.class).getBody().code())
				.isEqualTo("nothing_to_update");
		assertThat(put(
				JOB_TITLES + "/18906", ADMIN_1, Map.of("is_active", 0), ApiErrorBody.class).getBody().code())
				.isEqualTo("nothing_to_update");
		for (Object value : java.util.List.of(0, "abc", false, "", "   ")) {
			ResponseEntity<ApiErrorBody> response = put(
					JOB_TITLES + "/18906", ADMIN_1, Map.of("work_hours", value), ApiErrorBody.class);
			assertThat(response.getStatusCode().value()).isEqualTo(400);
			assertThat(response.getBody().code()).isEqualTo("field_required");
		}
		Map<String, Object> nullHours = new HashMap<>();
		nullHours.put("work_hours", null);
		ResponseEntity<ApiErrorBody> nullResponse = put(
				JOB_TITLES + "/18906", ADMIN_1, nullHours, ApiErrorBody.class);
		assertThat(nullResponse.getStatusCode().value()).isEqualTo(400);
		assertThat(nullResponse.getBody().code()).isEqualTo("field_required");
		ResponseEntity<ApiErrorBody> malformedDepartment = put(
				JOB_TITLES + "/18906", ADMIN_1, Map.of("department_id", "abc"), ApiErrorBody.class);
		assertThat(malformedDepartment.getStatusCode().value()).isEqualTo(404);
		assertThat(malformedDepartment.getBody().code()).isEqualTo("department_not_found");
	}

	@Test
	void departmentCannotBeCleared() {
		Map<String, Object> clear = new HashMap<>();
		clear.put("department_id", null);
		ResponseEntity<ApiErrorBody> response = put(
				JOB_TITLES + "/18906", ADMIN_1, clear, ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("department_not_found");
	}

	@Test
	void updateReturnsMariaDbRoundedWorkHoursAndRefreshedSummary() throws Exception {
		LegacyJobTitleView row = put(
				JOB_TITLES + "/18906", ADMIN_1, Map.of("department_id", 18853, "work_hours", 6.555),
				LegacyJobTitleView.class).getBody();
		assertThat(row.departmentName()).isEqualTo("Job Move Target");
		assertThat(row.branchesSummary()).isEqualTo("Beta Branch");
		assertThat(row.workHours()).isEqualByComparingTo("6.56");
		assertThat(jobTitleString(18906, "work_hours")).isEqualTo("6.56");
	}

	@Test
	void updateUsesPhpLeadingNumericCasts() {
		LegacyJobTitleView row = put(
				JOB_TITLES + "/18906", ADMIN_1,
				Map.of("department_id", "18853department", "work_hours", " 6.5hours"),
				LegacyJobTitleView.class).getBody();
		assertThat(row.departmentId()).isEqualTo(18853L);
		assertThat(row.workHours()).isEqualByComparingTo("6.50");
	}

	@Test
	void updateUsesPhpStringCastForNullFalseAndAssociativeObjectNames() throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", null);
		LegacyJobTitleView row = put(JOB_TITLES + "/18908", ADMIN_1, body, LegacyJobTitleView.class).getBody();
		assertThat(row.name()).isEmpty();
		assertThat(jobTitleString(18908, "name")).isEmpty();

		row = put(JOB_TITLES + "/18908", ADMIN_1, Map.of("name", false), LegacyJobTitleView.class).getBody();
		assertThat(row.name()).isEmpty();
		assertThat(jobTitleString(18908, "name")).isEmpty();

		row = put(
				JOB_TITLES + "/18908", ADMIN_1, Map.of("name", Map.of("key", "ignored")),
				LegacyJobTitleView.class).getBody();
		assertThat(row.name()).isEqualTo("Array");
		assertThat(jobTitleString(18908, "name")).isEqualTo("Array");
	}

	@Test
	void deleteIsSoftAndRepeatable() throws Exception {
		assertThat(delete(JOB_TITLES + "/18907", ADMIN_1, Void.class).getStatusCode().value()).isEqualTo(200);
		assertThat(jobTitleString(18907, "is_active")).isEqualTo("0");
		assertThat(delete(JOB_TITLES + "/18907", ADMIN_1, Void.class).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void writesCannotTargetAnotherTenant() {
		ResponseEntity<ApiErrorBody> response = put(
				JOB_TITLES + "/18904", ADMIN_1, Map.of("name", "Hijack"), ApiErrorBody.class);
		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().code()).isEqualTo("job_title_not_found");
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyJobTitleEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String jobTitleString(long id, String column) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT " + column + " FROM job_titles WHERE id = " + id)) {
			rs.next();
			return rs.getString(1);
		}
	}

	private static long queryLong(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager" : employeeId == EMPLOYEE_1 ? "employee" : "company_admin";
		long companyId = employeeId == ADMIN_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private List<String> jobTitleNames(String path) {
		ResponseEntity<LegacyJobTitleView[]> response = get(path, ADMIN_1, LegacyJobTitleView[].class);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		return java.util.Arrays.stream(response.getBody()).map(LegacyJobTitleView::name).toList();
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
