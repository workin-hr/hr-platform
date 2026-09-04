package com.workin.legacy.organization.php;

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
 * PR #120 review (Codex, P2): {@code LegacyJobTitleService.create()}/{@code update()} used to
 * throw the platform {@code com.workin.backend.i18n.ApiException} for a missing
 * {@code department_id}/{@code work_hours}, and {@code LegacyWireExceptionHandler.handlePlatform()}
 * always translates that with a hardcoded {@code null} replace map -- so the client saw the
 * literal, unsubstituted {@code Field '{field}' is required} text instead of naming the field.
 * {@link com.workin.legacy.organization.LegacyDepartmentService} carried the identical bug for
 * {@code name}/{@code branch_ids}. Both were fixed by switching to {@link
 * com.workin.legacy.wire.LegacyApiException}, which does carry a replace map, and are proven here
 * against the real production {@code /apis/api/{job_titles,departments}/*.php} routes -- the
 * gap this specific fix closes: neither module previously had any end-to-end coverage of its
 * production PHP-route controller at all, only the pre-D-074 regression alias under this same
 * test tree and the bidirectional route-inventory's route-exists check.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyOrganizationPhpFieldRequiredEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final long COMPANY = 21601L;
	private static final long BRANCH = 21611L;
	private static final long ADMIN = 216011L;
	private static final long DEPARTMENT = 21621L;

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
			throw new IllegalStateException("could not prepare the organization field_required fixture", ex);
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
	void jobTitleCreateNamesTheMissingFieldForEachOfNameDepartmentIdAndWorkHours() {
		Map<String, Object> missingName = send(
				"/apis/api/job_titles/create.php", HttpMethod.POST,
				"{\"department_id\":" + DEPARTMENT + ",\"work_hours\":8}", 400);
		assertThat(missingName.get("message")).isEqualTo("Field 'name' is required");

		Map<String, Object> missingDepartment = send(
				"/apis/api/job_titles/create.php", HttpMethod.POST,
				"{\"name\":\"Engineer\",\"work_hours\":8}", 400);
		assertThat(missingDepartment.get("message")).isEqualTo("Field 'department_id' is required");

		Map<String, Object> missingWorkHours = send(
				"/apis/api/job_titles/create.php", HttpMethod.POST,
				"{\"name\":\"Engineer\",\"department_id\":" + DEPARTMENT + "}", 400);
		assertThat(missingWorkHours.get("message")).isEqualTo("Field 'work_hours' is required");

		Map<String, Object> zeroWorkHours = send(
				"/apis/api/job_titles/create.php", HttpMethod.POST,
				"{\"name\":\"Engineer\",\"department_id\":" + DEPARTMENT + ",\"work_hours\":0}", 400);
		assertThat(zeroWorkHours.get("message")).isEqualTo("Field 'work_hours' is required");
	}

	@Test
	void jobTitleCreateSucceedsAndReturnsTheWireFaithfulRow() {
		Map<String, Object> body = dataOf(send(
				"/apis/api/job_titles/create.php", HttpMethod.POST,
				"{\"name\":\"Engineer\",\"department_id\":" + DEPARTMENT + ",\"work_hours\":8}", 201));
		assertThat(body.get("name")).isEqualTo("Engineer");
		assertThat(body.get("department_name")).isEqualTo("Engineering");
	}

	@Test
	void departmentCreateNamesTheMissingFieldForNameAndBranchIds() {
		Map<String, Object> missingName = send(
				"/apis/api/departments/create.php", HttpMethod.POST, "{\"branch_ids\":[1]}", 400);
		assertThat(missingName.get("message")).isEqualTo("Field 'name' is required");

		Map<String, Object> missingBranchIds = send(
				"/apis/api/departments/create.php", HttpMethod.POST, "{\"name\":\"Ops\"}", 400);
		assertThat(missingBranchIds.get("message")).isEqualTo("Field 'branch_ids' is required");
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
					  (21601, 'Org Field Required Co', '+201000021601', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (21611, 21601, 'HQ', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name, phone,
					  role, is_active, join_request_status, token_version, created_at) VALUES
					  (216011, 21601, 21611, '216011', 'Admin', 'One', '+201100216011', 'company_admin',
					   1, 'accepted', 1, '2025-04-01 08:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (21621, 21601, 'Engineering', 1, '2025-03-01 10:00:00')
					""");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream in = LegacyOrganizationPhpFieldRequiredEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private String tokenFor(long employeeId) {
		return jwtService.issueAccessToken(
				employeeId, employeeId, COMPANY, "test-session", Map.of("role", "company_admin", "token_version", 1L));
	}

	private Map<String, Object> send(String path, HttpMethod method, String json, int expectedStatus) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(ADMIN));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(json, headers), mapType());
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(expectedStatus);
		return response.getBody();
	}

	private static ParameterizedTypeReference<Map<String, Object>> mapType() {
		return new ParameterizedTypeReference<Map<String, Object>>() { };
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}
}
