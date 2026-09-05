package com.workin.legacy.employees;

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
 * Wave 12.4, slice 9: {@code hr_employees/{list,create,update_permissions}.php}.
 *
 * <h2>D-076 is the point of most of this</h2>
 * <p>Legacy lets any HR session create HR users and rewrite anyone's
 * permissions, its own included. D-076 declines to reproduce that, and the
 * check runs before any target or body is inspected -- so the HR cases below
 * assert not only the 403 but that <em>nothing was written</em>, and that the
 * answer is identical whether the target exists, is a peer, is the caller
 * itself, or is another tenant's.
 *
 * <p>Everything the company admin does is measured against the PHP instead,
 * including the two behaviours that surprise people: permissions are a full
 * replacement on every call, and the listing shows only {@code hr}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyHrEmployeeEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/hr_employees/list.php";
	private static final String CREATE = "/apis/api/hr_employees/create.php";
	private static final String UPDATE = "/apis/api/hr_employees/update_permissions.php";

	private static final long COMPANY_1 = 20001L;
	private static final long COMPANY_2 = 20002L;
	private static final long COMPANY_SUSPENDED = 20003L;

	private static final long ADMIN_1 = 200011L;
	private static final long HR_A = 200012L;
	private static final long HR_B = 200013L;
	private static final long MANAGER_1 = 200014L;
	private static final long PLAIN_EMPLOYEE = 200015L;
	/** Holds no hr_permissions row and is mutated by no test, so the zero-flag case stays true. */
	private static final long HR_UNTOUCHED = 200016L;
	private static final long ADMIN_2 = 200021L;
	private static final long HR_COMPANY_2 = 200022L;
	private static final long ADMIN_SUSPENDED = 200031L;

	private static final long BRANCH_MAIN = 20011L;
	private static final long DEPARTMENT_FIELD = 20021L;
	/** Same company as the caller, but linked to no branch. */
	private static final long DEPARTMENT_UNLINKED = 20023L;
	/** Company 2 own department, dirtily linked to company 1 branch -- D-075 exists for this. */
	private static final long DEPARTMENT_FOREIGN_DIRTY = 20024L;

	/** {@code hr_permission_keys()} in source order -- the order responses carry. */
	private static final List<String> PERMISSION_KEYS = List.of(
			"can_dashboard", "can_recent_activities", "can_branches", "can_departments", "can_job_titles",
			"can_shifts", "can_employees", "can_requests", "can_leave_balances", "can_penalties",
			"can_assets", "can_advances", "can_workforce_planning", "can_salary_calculator",
			"can_attendance", "can_payroll", "can_company_settings");

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
			throw new IllegalStateException("could not prepare the hr_employees fixture", ex);
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
	// D-076: HR keeps the read, loses the mutations
	// ------------------------------------------------------------------

	@Test
	void anHrSessionWithNoPermissionFlagsCanStillList() {
		// list.php is unchanged by D-076, and legacy never gated it on a flag --
		// this session holds no hr_permissions row at all.
		assertThat(scalar("SELECT COUNT(*) FROM hr_permissions WHERE employee_id = " + HR_UNTOUCHED))
				.isZero();

		Map<String, Object> body = get(LIST, HR_UNTOUCHED, 200);
		assertThat(body.get("success")).isEqualTo(true);
		assertThat(body.get("message")).isEqualTo("HR users");
		assertThat(idsOf(body)).contains(HR_A, HR_B, HR_UNTOUCHED);
	}

	@Test
	void anHrSessionCannotCreateAnHrUser() {
		long before = employeeCount(COMPANY_1);
		Map<String, Object> body = post(CREATE, HR_A, createBody("hr", "01012370101"), 403);

		assertThat(body.get("success")).isEqualTo(false);
		// Legacy's own key, not an invented one.
		assertThat(body.get("message")).isEqualTo("Forbidden");
		assertThat(employeeCount(COMPANY_1)).isEqualTo(before);
	}

	@Test
	void anHrSessionCannotCreateAManagerEither() {
		long before = employeeCount(COMPANY_1);
		assertThat(post(CREATE, HR_A, createBody("manager", "01012370102"), 403).get("message"))
				.isEqualTo("Forbidden");
		assertThat(employeeCount(COMPANY_1)).isEqualTo(before);
	}

	@Test
	void anHrSessionCannotUpdateItsOwnPermissions() {
		// The escalation D-076 exists to stop.
		Map<String, Object> before = permissionsOf(HR_A);
		assertThat(put(UPDATE + "?id=" + HR_A, HR_A, allPermissions(), 403).get("message"))
				.isEqualTo("Forbidden");
		assertThat(permissionsOf(HR_A)).isEqualTo(before);
	}

	@Test
	void anHrSessionCannotUpdateAPeerOrAManager() {
		Map<String, Object> peerBefore = permissionsOf(HR_B);
		assertThat(put(UPDATE + "?id=" + HR_B, HR_A, allPermissions(), 403).get("message"))
				.isEqualTo("Forbidden");
		assertThat(permissionsOf(HR_B)).isEqualTo(peerBefore);

		Map<String, Object> managerBefore = permissionsOf(MANAGER_1);
		assertThat(put(UPDATE + "?id=" + MANAGER_1, HR_A, allPermissions(), 403).get("message"))
				.isEqualTo("Forbidden");
		assertThat(permissionsOf(MANAGER_1)).isEqualTo(managerBefore);
	}

	@Test
	void everyPrivilegedMutationAnHrAttemptsGetsTheSameAnswer() {
		// The check runs before the target or the body is looked at, so none of
		// these can be told apart -- including the foreign id, which must not
		// become a 404 and confirm the row exists.
		List<String> attempts = List.of(
				UPDATE + "?id=" + HR_A,
				UPDATE + "?id=" + PLAIN_EMPLOYEE,
				UPDATE + "?id=" + ADMIN_1,
				UPDATE + "?id=" + HR_COMPANY_2,
				UPDATE + "?id=99999999",
				UPDATE + "?id=",
				UPDATE);
		for (String attempt : attempts) {
			ResponseEntity<Map<String, Object>> response = exchange(
					attempt, HttpMethod.PUT, HR_A, allPermissions());
			assertThat(response.getStatusCode().value()).as("%s", attempt).isEqualTo(403);
			assertThat(response.getBody().get("message")).as("%s", attempt).isEqualTo("Forbidden");
		}
		// And a malformed body is the same answer, because the body is never read.
		ResponseEntity<Map<String, Object>> malformed = exchange(
				UPDATE + "?id=" + HR_B, HttpMethod.PUT, HR_A, "{not json");
		assertThat(malformed.getStatusCode().value()).isEqualTo(403);
		assertThat(malformed.getBody().get("message")).isEqualTo("Forbidden");

		ResponseEntity<Map<String, Object>> malformedCreate = exchange(
				CREATE, HttpMethod.POST, HR_A, "{not json");
		assertThat(malformedCreate.getStatusCode().value()).isEqualTo(403);
		assertThat(malformedCreate.getBody().get("message")).isEqualTo("Forbidden");
	}

	// ------------------------------------------------------------------
	// The company admin follows PHP
	// ------------------------------------------------------------------

	@Test
	void aCompanyAdminCreatesAnHrUserWithItsPermissions() {
		Map<String, Object> body = post(CREATE, ADMIN_1, createBody("hr", "01012370110"), 201);
		assertThat(body.get("message")).isEqualTo("HR user created");

		@SuppressWarnings("unchecked")
		Map<String, Object> user = (Map<String, Object>) body.get("data");
		assertThat(user.get("role")).isEqualTo("hr");
		assertThat(((Number) user.get("company_id")).longValue()).isEqualTo(COMPANY_1);
		assertThat(((Number) user.get("branch_id")).longValue()).isEqualTo(BRANCH_MAIN);
		assertThat(user.get("is_active")).isEqualTo(1);
		// public_row() still strips these two.
		assertThat(user).doesNotContainKeys("password_hash", "token_version");
		// The seventeen columns are lifted into `permissions` and removed.
		assertThat(user).doesNotContainKeys(PERMISSION_KEYS.toArray(new String[0]));

		@SuppressWarnings("unchecked")
		Map<String, Object> permissions = (Map<String, Object>) user.get("permissions");
		assertThat(permissions.keySet()).containsExactlyElementsOf(PERMISSION_KEYS);
		assertThat(permissions.get("can_payroll")).isEqualTo(1);
		assertThat(permissions.get("can_dashboard")).isEqualTo(0);

		long employeeId = ((Number) user.get("id")).longValue();
		Map<String, Object> stored = row("SELECT * FROM employees WHERE id = " + employeeId);
		// The narrow INSERT: none of these is written by this endpoint.
		assertThat(stored.get("job_title_id")).isNull();
		assertThat(stored.get("contract_duration_months")).isNull();
		// Never written by this endpoint, and the column is nullable, so it
		// stays NULL rather than taking a zero -- an HR user created here has
		// no working-hours expectation at all.
		assertThat(stored.get("expected_daily_hours")).isNull();
		// And no leave balance and no shift assignment, unlike either create path.
		assertThat(scalar("SELECT COUNT(*) FROM leave_balance WHERE employee_id = " + employeeId)).isZero();
		assertThat(scalar("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = "
				+ employeeId)).isZero();
		assertThat(scalar("SELECT COUNT(*) FROM salary_contracts WHERE employee_id = " + employeeId)).isZero();
	}

	/**
	 * {@code create.php:136-149} reads the new row back through three LEFT JOINs
	 * and returns {@code branch_name}, {@code department_name} and
	 * {@code job_title_name}; {@code update_permissions.php:41} reads
	 * {@code e.*} and returns none of them.
	 *
	 * <p>Both halves are asserted together because they are only correct
	 * relative to each other: one projection serving both endpoints is what
	 * produced the defect, and making them uniform in either direction brings it
	 * back — {@code create} would drop three keys clients receive today, or
	 * {@code update_permissions} would gain three PHP never sends. Same shape as
	 * the advance projections in D-153(b). Measured against the running legacy
	 * PHP with the parity harness.
	 */
	@Test
	void createReturnsTheJoinedNamesAndUpdatePermissionsDoesNot() {
		@SuppressWarnings("unchecked")
		Map<String, Object> created = (Map<String, Object>) post(
				CREATE, ADMIN_1, createBody("hr", "01012370119"), 201).get("data");

		assertThat(created).containsKeys("branch_name", "department_name", "job_title_name");
		assertThat(created.get("branch_name")).isEqualTo("Main Branch");
		assertThat(created.get("department_name")).isEqualTo("Operations");
		// The endpoint never writes job_title_id, so the join finds nothing --
		// the key is still present, carrying null.
		assertThat(created.get("job_title_name")).isNull();

		@SuppressWarnings("unchecked")
		Map<String, Object> updated = (Map<String, Object>) exchange(
				UPDATE + "?id=" + HR_B, HttpMethod.PUT, ADMIN_1, allPermissions())
				.getBody().get("data");

		assertThat(updated).doesNotContainKeys("branch_name", "department_name", "job_title_name");
	}

	@Test
	void aCompanyAdminCreatesAManagerToo() {
		Map<String, Object> body = post(CREATE, ADMIN_1, createBody("manager", "01012370111"), 201);
		@SuppressWarnings("unchecked")
		Map<String, Object> user = (Map<String, Object>) body.get("data");
		assertThat(user.get("role")).isEqualTo("manager");
		// A manager carries permissions even though list.php will never show it.
		assertThat(user).containsKey("permissions");
	}

	@Test
	void onlyHrAndManagerCanBeCreated() {
		for (String role : List.of("company_admin", "employee", "", "HR", "admin")) {
			Map<String, Object> body = post(CREATE, ADMIN_1, createBody(role, "01012370112"),
					role.isEmpty() ? 400 : 400);
			assertThat(body.get("message"))
					.as("role %s", role)
					.isEqualTo(role.isEmpty() ? "Field 'role' is required" : "Invalid role");
		}
		// A non-string role fails the strict comparison rather than coercing.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":1,\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"X\"}", 400)
				.get("message")).isEqualTo("Invalid role");
	}

	@Test
	void createValidatesTheBranchDepartmentCodeAndPhoneAsPhpDoes() {
		// A branch from another company is not found, not forbidden.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + 20012L + ",\"first_name\":\"X\"}", 404)
				.get("message")).isEqualTo("Branch not found");

		// A department that does not belong to the branch.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"X\","
						+ "\"department_id\":" + 20022L + "}", 404)
				.get("message")).isEqualTo("Department not found");

		// An employee code already used in this company.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"X\","
						+ "\"employee_code\":\"1001\"}", 409)
				.get("message")).isEqualTo("Employee code is already used in this company");

		// A phone already used anywhere.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"X\","
						+ "\"phone\":\"01000200011\",\"country_code\":\"+20\"}", 409)
				.get("message")).isEqualTo("Phone already exists");
	}

	@Test
	void aSingleNameIsSplitIntoFirstAndLast() {
		// preg_split(..., 2): everything after the first space is the last name.
		Map<String, Object> body = post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"name\":\"Mona  Adel Fathy\"}", 201);
		@SuppressWarnings("unchecked")
		Map<String, Object> user = (Map<String, Object>) body.get("data");
		assertThat(user.get("first_name")).isEqualTo("Mona");
		assertThat(user.get("last_name")).isEqualTo("Adel Fathy");

		// With neither a first name nor a name, the field is required.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + "}", 400).get("message"))
				.isEqualTo("Field 'first_name' is required");
	}

	@Test
	void aCompanyAdminUpdatesAnHrAndAManager() {
		Map<String, Object> hrBody = put(UPDATE + "?id=" + HR_B, ADMIN_1, allPermissions(), 200);
		assertThat(hrBody.get("message")).isEqualTo("Permissions updated");
		assertThat(permissionsOf(HR_B).values()).allMatch(value -> ((Number) value).intValue() == 1);

		put(UPDATE + "?id=" + MANAGER_1, ADMIN_1, allPermissions(), 200);
		assertThat(permissionsOf(MANAGER_1).values()).allMatch(value -> ((Number) value).intValue() == 1);
	}

	@Test
	void aTargetThatIsNotHrOrManagerIsForbidden() {
		// The role check is on the *existing* role, so both of these are 403 --
		// and a company admin cannot have its permissions edited at all.
		assertThat(put(UPDATE + "?id=" + PLAIN_EMPLOYEE, ADMIN_1, allPermissions(), 403).get("message"))
				.isEqualTo("Forbidden");
		assertThat(put(UPDATE + "?id=" + ADMIN_1, ADMIN_1, allPermissions(), 403).get("message"))
				.isEqualTo("Forbidden");
	}

	@Test
	void aMissingOrForeignTargetIsUserNotFound() {
		assertThat(put(UPDATE + "?id=99999999", ADMIN_1, allPermissions(), 404).get("message"))
				.isEqualTo("User not found");
		// Scoped by company, so another tenant's HR is indistinguishable from
		// one that does not exist.
		assertThat(put(UPDATE + "?id=" + HR_COMPANY_2, ADMIN_1, allPermissions(), 404).get("message"))
				.isEqualTo("User not found");
		// And nothing was written to that row.
		assertThat(scalar("SELECT COUNT(*) FROM hr_permissions WHERE employee_id = " + HR_COMPANY_2))
				.isZero();
	}

	// ------------------------------------------------------------------
	// The typed-array boundary on `permissions`
	// ------------------------------------------------------------------

	@Test
	void aJsonArrayOfPermissionsIsAnArrayAndClearsEveryFlag() {
		// json_decode(..., true) turns a JSON array into a numeric-keyed PHP
		// array. It satisfies `array $permissions`, so there is no TypeError --
		// every named lookup simply misses and all seventeen come out as zeros.
		put(UPDATE + "?id=" + HR_B, ADMIN_1, allPermissions(), 200);
		put(UPDATE + "?id=" + HR_B, ADMIN_1, "{\"permissions\":[1,1,1]}", 200);
		assertThat(permissionsOf(HR_B).values()).allMatch(value -> ((Number) value).intValue() == 0);

		// An empty array is the same thing.
		put(UPDATE + "?id=" + HR_B, ADMIN_1, allPermissions(), 200);
		put(UPDATE + "?id=" + HR_B, ADMIN_1, "{\"permissions\":[]}", 200);
		assertThat(permissionsOf(HR_B).values()).allMatch(value -> ((Number) value).intValue() == 0);
	}

	@Test
	void anExplicitNullPermissionsIsTheEmptyArrayNotAFailure() {
		// `$body['permissions'] ?? []` coalesces null before the typed call.
		put(UPDATE + "?id=" + HR_B, ADMIN_1, allPermissions(), 200);
		put(UPDATE + "?id=" + HR_B, ADMIN_1, "{\"permissions\":null}", 200);
		assertThat(permissionsOf(HR_B).values()).allMatch(value -> ((Number) value).intValue() == 0);
	}

	@Test
	void aScalarPermissionsOnUpdateIsATypeErrorAndWritesNothing() {
		// hr_permissions_upsert_sql(int, array $permissions) is typed, and
		// strict_types means a scalar is a TypeError rather than an empty set.
		// Treating it as empty would silently clear all seventeen flags on input
		// PHP refuses outright. Nothing catches it here, so it is D-084's 500.
		put(UPDATE + "?id=" + HR_B, ADMIN_1, allPermissions(), 200);
		Map<String, Object> before = permissionsOf(HR_B);

		for (String scalar : List.of("\"yes\"", "1", "1.5", "true", "false")) {
			Map<String, Object> body = put(
					UPDATE + "?id=" + HR_B, ADMIN_1, "{\"permissions\":" + scalar + "}", 500);
			assertThat(body.get("success")).as("permissions=%s", scalar).isEqualTo(false);
			// D-084 exactly: the generic message, and no data key.
			assertThat(body.get("message")).as("permissions=%s", scalar)
					.isEqualTo("Internal server error");
			assertThat(body).as("permissions=%s", scalar).doesNotContainKey("data");
			// And the upsert never ran.
			assertThat(permissionsOf(HR_B)).as("permissions=%s", scalar).isEqualTo(before);
		}
	}

	@Test
	void aScalarPermissionsOnCreateRollsBackTheEmployeeItHadAlreadyInserted() {
		// In create.php the permissions upsert is inside the transaction and
		// after the employee INSERT, so the TypeError is raised with a written
		// row -- and create.php's own catch(Throwable) rolls it back and answers
		// employee_create_failed with the exception text as data.
		long before = employeeCount(COMPANY_1);
		Map<String, Object> body = post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"Scalar\","
						+ "\"employee_code\":\"7777\",\"permissions\":\"yes\"}", 500);

		assertThat(body.get("success")).isEqualTo(false);
		// Not D-084: PHP catches this one, so it keeps its own key and detail.
		// The catalog message carries an unsubstituted {error} placeholder:
		// fail() passes the detail as $data, never as a $replace.
		assertThat(body.get("message")).isEqualTo("Failed to create employee: {error}");
		assertThat((String) body.get("data"))
				.contains("hr_permissions_upsert_sql()")
				.contains("Argument #2 ($permissions)")
				.contains("must be of type array, string given");

		// The employee that was inserted before the failure is gone.
		assertThat(employeeCount(COMPANY_1)).isEqualTo(before);
		assertThat(scalar("SELECT COUNT(*) FROM employees WHERE company_id = " + COMPANY_1
				+ " AND employee_code = '7777'")).isZero();
	}

	@Test
	void theCaughtCreateFailureNamesThePhpTypeForEachScalar() {
		// PHP renders true/false rather than bool, and int/float rather than
		// integer/double. Measured under 8.3.
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("1", "int given");
		expected.put("1.5", "float given");
		expected.put("true", "true given");
		expected.put("false", "false given");
		int code = 7800;
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			Map<String, Object> body = post(CREATE, ADMIN_1,
					"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"S\","
							+ "\"employee_code\":\"" + code++ + "\","
							+ "\"permissions\":" + entry.getKey() + "}", 500);
			assertThat((String) body.get("data")).as("permissions=%s", entry.getKey())
					.contains(entry.getValue());
		}
	}

	// ------------------------------------------------------------------
	// The typed-string boundary on `employee_code`
	// ------------------------------------------------------------------

	@Test
	void aNonStringEmployeeCodeIsATypeErrorBeforeAnythingIsWritten() {
		// normalize_employee_code(?string $code) is typed, and the call sits
		// before beginTransaction(), so the TypeError is uncaught -- D-084's
		// generic 500 with nothing written. The raw value must not be coerced to
		// a string first, which would accept input legacy rejects.
		long before = employeeCount(COMPANY_1);
		for (String code : List.of("7001", "70.5", "true", "[1,2]", "{\"a\":1}")) {
			Map<String, Object> body = post(CREATE, ADMIN_1,
					"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"X\","
							+ "\"employee_code\":" + code + "}", 500);
			assertThat(body.get("message")).as("employee_code=%s", code)
					.isEqualTo("Internal server error");
			assertThat(body).as("employee_code=%s", code).doesNotContainKey("data");
		}
		assertThat(employeeCount(COMPANY_1)).isEqualTo(before);
	}

	@Test
	void aStringOrAbsentEmployeeCodeStillBehavesNormally() {
		// A real string is trimmed and stored...
		Map<String, Object> body = post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"Coded\","
						+ "\"employee_code\":\"  7002  \"}", 201);
		@SuppressWarnings("unchecked")
		Map<String, Object> user = (Map<String, Object>) body.get("data");
		assertThat(user.get("employee_code")).isEqualTo("7002");

		// ...and one with internal whitespace normalizes to a single space,
		// which then fails the digits-only format check rather than being
		// stored. Normalization and validation are separate steps, in that
		// order, and only the second one rejects.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"Spaced\","
						+ "\"employee_code\":\"  70   02  \"}", 400).get("message"))
				.isEqualTo("Employee code must contain digits only (at least one digit)");

		// ...an explicit null is isset()-absent, so the column stays null...
		Map<String, Object> withNull = post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"NoCode\","
						+ "\"employee_code\":null}", 201);
		@SuppressWarnings("unchecked")
		Map<String, Object> nullCoded = (Map<String, Object>) withNull.get("data");
		assertThat(nullCoded.get("employee_code")).isNull();

		// ...and a blank string is the required-field failure, not a TypeError.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"X\","
						+ "\"employee_code\":\"   \"}", 400).get("message"))
				.isEqualTo("Field 'employee_code' is required");
	}

	// ------------------------------------------------------------------
	// D-075 on the department path
	// ------------------------------------------------------------------

	@Test
	void aForeignCompanysDepartmentIsRefusedEvenThroughADirtyJunctionRow() {
		// The PHP check is department_belongs_to_branch(), which reads only the
		// junction table -- no company predicate. The fixture seeds exactly the
		// dirty row that exploits it: company 2's department linked to company 1's
		// branch. D-075 fails that closed, with the same 404 the missing case
		// gives, and writes nothing.
		assertThat(scalar("SELECT COUNT(*) FROM department_branches WHERE department_id = "
				+ DEPARTMENT_FOREIGN_DIRTY + " AND branch_id = " + BRANCH_MAIN)).isEqualTo(1);

		long employeesBefore = employeeCount(COMPANY_1);
		long permissionsBefore = scalar("SELECT COUNT(*) FROM hr_permissions");

		Map<String, Object> body = post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"Dirty\","
						+ "\"employee_code\":\"7901\",\"department_id\":"
						+ DEPARTMENT_FOREIGN_DIRTY + "}", 404);
		assertThat(body.get("message")).isEqualTo("Department not found");

		assertThat(employeeCount(COMPANY_1)).isEqualTo(employeesBefore);
		assertThat(scalar("SELECT COUNT(*) FROM hr_permissions")).isEqualTo(permissionsBefore);
	}

	@Test
	void sameTenantDepartmentBehaviourIsUnchanged() {
		// D-075 narrows nothing inside the tenant: a linked department still
		// works, and an unlinked same-company one still fails exactly as legacy
		// makes it fail.
		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"Linked\","
						+ "\"employee_code\":\"7902\",\"department_id\":" + DEPARTMENT_FIELD + "}", 201)
				.get("message")).isEqualTo("HR user created");

		assertThat(post(CREATE, ADMIN_1,
				"{\"role\":\"hr\",\"branch_id\":" + BRANCH_MAIN + ",\"first_name\":\"Unlinked\","
						+ "\"employee_code\":\"7903\",\"department_id\":" + DEPARTMENT_UNLINKED + "}", 404)
				.get("message")).isEqualTo("Department not found");
	}

	// ------------------------------------------------------------------
	// Permissions are a replacement, not a patch
	// ------------------------------------------------------------------

	@Test
	void aPartialPermissionsBodyRevokesEveryFlagItOmits() {
		put(UPDATE + "?id=" + HR_B, ADMIN_1, allPermissions(), 200);
		assertThat(permissionsOf(HR_B).values()).allMatch(value -> ((Number) value).intValue() == 1);

		// One flag named; the other sixteen are written as 0.
		put(UPDATE + "?id=" + HR_B, ADMIN_1, "{\"permissions\":{\"can_payroll\":1}}", 200);
		Map<String, Object> after = permissionsOf(HR_B);
		assertThat(((Number) after.get("can_payroll")).intValue()).isEqualTo(1);
		for (String key : PERMISSION_KEYS) {
			if (!"can_payroll".equals(key)) {
				assertThat(((Number) after.get(key)).intValue()).as("%s", key).isZero();
			}
		}
	}

	@Test
	void aBodyWithNoPermissionsKeyClearsAllSeventeen() {
		put(UPDATE + "?id=" + HR_B, ADMIN_1, allPermissions(), 200);
		put(UPDATE + "?id=" + HR_B, ADMIN_1, "{}", 200);
		assertThat(permissionsOf(HR_B).values()).allMatch(value -> ((Number) value).intValue() == 0);
	}

	@Test
	void aNonNumericPermissionValueRevokesRatherThanGrants() {
		// (int) "yes" is 0 in PHP, so a truthy-looking string turns the flag off.
		put(UPDATE + "?id=" + HR_B, ADMIN_1, allPermissions(), 200);
		put(UPDATE + "?id=" + HR_B, ADMIN_1,
				"{\"permissions\":{\"can_payroll\":\"yes\",\"can_assets\":\"1\",\"can_shifts\":true}}", 200);
		Map<String, Object> after = permissionsOf(HR_B);
		assertThat(((Number) after.get("can_payroll")).intValue()).isZero();
		// "1" and true both cast to 1.
		assertThat(((Number) after.get("can_assets")).intValue()).isEqualTo(1);
		assertThat(((Number) after.get("can_shifts")).intValue()).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// list.php
	// ------------------------------------------------------------------

	@Test
	void theListingShowsHrOnlyAndNeverManagers() {
		List<Long> ids = idsOf(get(LIST, ADMIN_1, 200));
		assertThat(ids).contains(HR_A, HR_B, HR_UNTOUCHED);
		// Managers can be created here and can hold permissions, but the listing
		// filters role = 'hr'. Deliberately not broadened.
		assertThat(ids).doesNotContain(MANAGER_1, ADMIN_1, PLAIN_EMPLOYEE);
		// And never another tenant's.
		assertThat(ids).doesNotContain(HR_COMPANY_2);
	}

	@Test
	void theListingIsNewestFirstWithPhpsPaginationMeta() {
		Map<String, Object> body = get(LIST, ADMIN_1, 200);
		List<Long> ids = idsOf(body);
		// ORDER BY e.id DESC, and nothing else.
		List<Long> descending = new ArrayList<>(ids);
		descending.sort((left, right) -> Long.compare(right, left));
		assertThat(ids).isEqualTo(descending);

		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) body.get("meta");
		assertThat(meta.keySet())
				.containsExactly("page", "limit", "total", "total_pages", "has_next", "has_previous");
		assertThat(meta.get("limit")).isEqualTo(20);

		assertThat(((Map<?, ?>) get(LIST + "?limit=1", ADMIN_1, 200).get("meta")).get("limit"))
				.isEqualTo(1);
		assertThat(idsOf(get(LIST + "?limit=1", ADMIN_1, 200))).hasSize(1);
	}

	@Test
	void theListingSearchesNameCodeAndPhoneTogether() {
		// One LIKE across all three, with no digits-only branch.
		assertThat(idsOf(get(LIST + "?search=Salma", ADMIN_1, 200))).containsExactly(HR_A);
		assertThat(idsOf(get(LIST + "?search=1002", ADMIN_1, 200))).containsExactly(HR_A);
		assertThat(idsOf(get(LIST + "?search=200013", ADMIN_1, 200))).containsExactly(HR_B);
		assertThat(idsOf(get(LIST + "?search=nobody", ADMIN_1, 200))).isEmpty();
	}

	@Test
	void aFormFeedStaysInTheHrSearchNeedleToo() {
		// hr_employees/list.php shares the same search helper, so it shares
		// the same charlist rule: PHP leaves form feed (U+000C) in place while
		// Java String.trim() would strip it.
		assertThat(idsOf(get(LIST + "?search=Salma", ADMIN_1, 200))).containsExactly(HR_A);

		assertThat(idsOf(get(LIST + "?search=%0CSalma", ADMIN_1, 200)))
				.as("a leading form feed must survive into the LIKE needle")
				.isEmpty();
		assertThat(idsOf(get(LIST + "?search=Salma%0C", ADMIN_1, 200)))
				.as("a trailing form feed must survive into the LIKE needle")
				.isEmpty();

		// ...while the characters PHP does trim are still trimmed.
		assertThat(idsOf(get(LIST + "?search=%20Salma%20", ADMIN_1, 200))).containsExactly(HR_A);
	}

	@Test
	void everyListedRowCarriesItsPermissionsObject() {
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) get(LIST, ADMIN_1, 200).get("data");
		for (Map<String, Object> user : rows) {
			assertThat(user).containsKey("permissions").doesNotContainKeys("password_hash", "token_version");
			@SuppressWarnings("unchecked")
			Map<String, Object> permissions = (Map<String, Object>) user.get("permissions");
			assertThat(permissions.keySet()).containsExactlyElementsOf(PERMISSION_KEYS);
		}
		// A user with no hr_permissions row joins to nulls, which read as zeros.
		Map<String, Object> withoutRow = rows.stream()
				.filter(user -> ((Number) user.get("id")).longValue() == HR_B).findFirst().orElseThrow();
		@SuppressWarnings("unchecked")
		Map<String, Object> permissions = (Map<String, Object>) withoutRow.get("permissions");
		assertThat(permissions.values()).allMatch(value -> value instanceof Number);
	}

	// ------------------------------------------------------------------
	// Guards
	// ------------------------------------------------------------------

	@Test
	void theGuardStackRunsInPhpsOrderOnAllThree() {
		// The method check is the opening line of each file.
		assertThat(exchange(LIST, HttpMethod.POST, ADMIN_1, null).getStatusCode().value()).isEqualTo(405);
		assertThat(exchange(CREATE, HttpMethod.GET, ADMIN_1, null).getStatusCode().value()).isEqualTo(405);
		assertThat(exchange(UPDATE + "?id=1", HttpMethod.POST, ADMIN_1, null).getStatusCode().value())
				.isEqualTo(405);

		// requireAuth([COMPANY_ADMIN, HR]) -- a manager is on neither list.
		assertThat(get(LIST, MANAGER_1, 403).get("message")).isEqualTo("Forbidden — insufficient role");
		assertThat(get(LIST, PLAIN_EMPLOYEE, 403).get("message")).isEqualTo("Forbidden — insufficient role");
		// requireCompanyActive, which runs after the role check.
		assertThat(get(LIST, ADMIN_SUSPENDED, 403).get("message"))
				.isEqualTo("Company account is not active");
	}

	@Test
	void anUnauthenticatedRequestIsPhpsUnauthorized() {
		for (String path : List.of(LIST, CREATE, UPDATE)) {
			ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
					URI.create(restTemplate.getRootUri() + path),
					path.equals(LIST) ? HttpMethod.GET : HttpMethod.POST,
					new HttpEntity<>(new HttpHeaders()),
					new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
			// create is POST-guarded and update is PUT-guarded, so only the
			// method-correct calls reach the auth guard; both are non-200.
			assertThat(response.getStatusCode().value()).as("%s", path).isIn(401, 405);
			assertThat(response.getBody().get("success")).isEqualTo(false);
		}
	}

	@Test
	void aCompanyAdminOfAnotherTenantSeesOnlyItsOwn() {
		assertThat(idsOf(get(LIST, ADMIN_2, 200))).containsExactly(HR_COMPANY_2);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private static String createBody(String role, String phone) {
		return "{\"role\":\"" + role + "\",\"branch_id\":" + BRANCH_MAIN + ","
				+ "\"first_name\":\"Nadia\",\"last_name\":\"Samir\","
				+ "\"phone\":\"" + phone + "\",\"country_code\":\"+20\","
				+ "\"department_id\":" + DEPARTMENT_FIELD + ","
				+ "\"password\":\"secret123\","
				+ "\"permissions\":{\"can_payroll\":1,\"can_employees\":1}}";
	}

	private static String allPermissions() {
		List<String> pairs = new ArrayList<>();
		for (String key : PERMISSION_KEYS) {
			pairs.add("\"" + key + "\":1");
		}
		return "{\"permissions\":{" + String.join(",", pairs) + "}}";
	}

	private Map<String, Object> permissionsOf(long employeeId) {
		Map<String, Object> stored = new LinkedHashMap<>();
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT * FROM hr_permissions WHERE employee_id = " + employeeId)) {
			if (!rs.next()) {
				for (String key : PERMISSION_KEYS) {
					stored.put(key, 0);
				}
				return stored;
			}
			for (String key : PERMISSION_KEYS) {
				stored.put(key, rs.getInt(key));
			}
			return stored;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Map<String, Object> get(String path, long employeeId, int expectedStatus) {
		ResponseEntity<Map<String, Object>> response = exchange(path, HttpMethod.GET, employeeId, null);
		assertThat(response.getStatusCode().value()).as("%s: %s", path, response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private Map<String, Object> post(String path, long employeeId, String body, int expectedStatus) {
		ResponseEntity<Map<String, Object>> response = exchange(path, HttpMethod.POST, employeeId, body);
		assertThat(response.getStatusCode().value()).as("%s: %s", path, response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private Map<String, Object> put(String path, long employeeId, String body, int expectedStatus) {
		ResponseEntity<Map<String, Object>> response = exchange(path, HttpMethod.PUT, employeeId, body);
		assertThat(response.getStatusCode().value()).as("%s: %s", path, response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private ResponseEntity<Map<String, Object>> exchange(
			String path, HttpMethod method, long employeeId, String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Accept-Language", "en");
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static List<Long> idsOf(Map<String, Object> body) {
		List<Long> ids = new ArrayList<>();
		for (Object row : (List<?>) body.get("data")) {
			ids.add(((Number) ((Map<?, ?>) row).get("id")).longValue());
		}
		return ids;
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == PLAIN_EMPLOYEE ? "employee"
				: employeeId == HR_A || employeeId == HR_B || employeeId == HR_UNTOUCHED
						|| employeeId == HR_COMPANY_2 ? "hr"
				: "company_admin";
		long companyId = employeeId == ADMIN_2 || employeeId == HR_COMPANY_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private long employeeCount(long companyId) {
		return scalar("SELECT COUNT(*) FROM employees WHERE company_id = " + companyId);
	}

	private long scalar(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0L;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Map<String, Object> row(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			assertThat(rs.next()).as("no row for %s", sql).isTrue();
			Map<String, Object> values = new LinkedHashMap<>();
			for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
				values.put(rs.getMetaData().getColumnLabel(column), rs.getString(column));
			}
			return values;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20001, 'HR Co', '+201000020001', 'active', '2025-01-15 09:00:00'),
					  (20002, 'HR Other Co', '+201000020002', 'active', '2025-01-15 09:00:00'),
					  (20003, 'HR Suspended Co', '+201000020003', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20011, 20001, 'Main Branch', 1, '2025-03-01 10:00:00'),
					  (20012, 20002, 'Other Co Branch', 1, '2025-03-01 10:00:00'),
					  (20013, 20003, 'Suspended Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (20021, 20001, 'Operations', 1, '2025-04-10 10:00:00'),
					  (20022, 20002, 'Other Co Department', 1, '2025-04-10 10:00:00'),
					  (20023, 20001, 'Unlinked Department', 1, '2025-04-10 10:00:00'),
					  (20024, 20002, 'Foreign Dirty Department', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO department_branches (department_id, branch_id) VALUES
					  (20021, 20011), (20022, 20012), (20024, 20011)
					""");

			insertEmployee(st, ADMIN_1, COMPANY_1, "'1001'", "company_admin", "+201000200011", "Rana");
			insertEmployee(st, HR_A, COMPANY_1, "'1002'", "hr", "+201000200012", "Salma");
			insertEmployee(st, HR_B, COMPANY_1, "'1003'", "hr", "+201000200013", "Mona");
			insertEmployee(st, MANAGER_1, COMPANY_1, "'1004'", "manager", "+201000200014", "Mostafa");
			insertEmployee(st, PLAIN_EMPLOYEE, COMPANY_1, "'1005'", "employee", "+201000200015", "Omar");
			insertEmployee(st, HR_UNTOUCHED, COMPANY_1, "'1006'", "hr", "+201000200016", "Farida");
			insertEmployee(st, ADMIN_2, COMPANY_2, "'2001'", "company_admin", "+201000200021", "Laila");
			insertEmployee(st, HR_COMPANY_2, COMPANY_2, "'2002'", "hr", "+201000200022", "Yasmin");
			insertEmployee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, "'3001'", "company_admin",
					"+201000200031", "Tarek");

			// HR_A holds flags; HR_B and MANAGER_1 have no row at all, so the
			// "joins to nulls" path is covered by the fixture rather than assumed.
			st.execute("INSERT INTO hr_permissions (employee_id, can_employees, can_dashboard) "
					+ "VALUES (200012, 1, 1)");

			// The phone the create-conflict case collides with.
			st.execute("UPDATE employees SET phone = '01000200011' WHERE id = 200011");
		}
	}

	private static void insertEmployee(
			Statement st, long id, long companyId, String code, String role, String phone,
			String firstName) throws Exception {
		long branchId = companyId == COMPANY_1 ? 20011L : companyId == COMPANY_2 ? 20012L : 20013L;
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, department_id, job_title_id, employee_code,
				   expected_daily_hours, first_name, last_name, phone, country_code, password_hash,
				   token_version, role, national_id, birth_date, gender, address, photo_url, hire_date,
				   contract_duration_months, is_active, is_mobile_attendance_enabled,
				   can_check_in_any_branch, join_request_status, created_at)
				VALUES (%d, %d, %d, NULL, NULL, %s, 8.00, '%s', 'Adel', '%s', '+20',
				   '$2y$10$abcdefghijklmnopqrstuv', 1, '%s', '29001011200011', '0000-00-00', 'female',
				   'Cairo', NULL, '2024-01-01', 12, 1, 1, 0, 'accepted', '2025-05-01 09:00:00')
				""".formatted(id, companyId, branchId, code, firstName, phone, role));
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
		try (InputStream stream =
				LegacyHrEmployeeEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
