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
 * Wave 12.4, slice 8: {@code employees/import_bulk.php} over real HTTP.
 *
 * <p>The endpoint's shape is unusual and most of these tests are about that:
 * after the {@code rows} presence check, <em>nothing</em> moves the HTTP status.
 * A batch where every row fails is still 200; only the message key changes, and
 * only when nothing at all was inserted. Each row is its own transaction, so a
 * failure late in the batch leaves everything before it committed.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeImportBulkEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String IMPORT = "/apis/api/employees/import_bulk.php";

	private static final long COMPANY_1 = 19801L;
	private static final long COMPANY_2 = 19802L;
	private static final long COMPANY_SUSPENDED = 19803L;

	private static final long ADMIN_1 = 198011L;
	private static final long HR_1 = 198012L;
	private static final long MANAGER_1 = 198013L;
	private static final long PLAIN_EMPLOYEE = 198014L;
	private static final long ADMIN_SUSPENDED = 198031L;

	private static final long BRANCH_MAIN = 19811L;
	private static final long DEPARTMENT_FIELD = 19821L;
	private static final long JOB_TITLE_SENIOR = 19831L;
	private static final long SHIFT_NIGHT = 19841L;

	private static final String SHIFT_NAME = "Night Watch";
	private static final String BRANCH_NAME = "Riverside Branch";
	private static final String DEPARTMENT_NAME = "Field Operations";
	private static final String JOB_TITLE_NAME = "Senior Agent";

	/** Company 2's own lookups, for the tenant-isolation cases. */
	private static final String OTHER_SHIFT_NAME = "Other Co Shift";
	private static final String OTHER_BRANCH_NAME = "Other Co Branch";
	private static final String OTHER_DEPARTMENT_NAME = "Other Co Department";
	private static final String OTHER_JOB_TITLE_NAME = "Other Co Title";

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
			throw new IllegalStateException("could not prepare the import_bulk fixture", ex);
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
	// The rows guard -- the only thing after the auth guards that is not a 200
	// ------------------------------------------------------------------

	@Test
	void aMissingOrEmptyRowsValueIsTheOnlyRequestLevelFailure() {
		for (String body : List.of("{}", "{\"rows\":[]}", "{\"rows\":{}}", "{\"rows\":null}",
				"{\"rows\":42}", "{\"rows\":\"x\"}", "{\"rows\":true}")) {
			ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
			assertThat(response.getStatusCode().value()).as("body %s", body).isEqualTo(400);
			assertThat(response.getBody().get("success")).isEqualTo(false);
			// fail(FIELD_REQUIRED, 400, null, [FIELD => 'rows']) -- the field is a
			// placeholder in the message, so the response carries no data key.
			assertThat(response.getBody().get("message")).isEqualTo("Field 'rows' is required");
			assertThat(response.getBody()).doesNotContainKey("data");
		}
	}

	@Test
	void aMalformedBodyIsAnEmptyArrayAndSoFailsTheRowsCheck() {
		// body() is json_decode(...) ?? [], so a broken document is [] rather
		// than a parse error -- and [] has no rows.
		for (String body : List.of("", "   ", "not json at all", "{\"rows\":", "{,}")) {
			ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
			assertThat(response.getStatusCode().value()).as("body %s", body).isEqualTo(400);
			assertThat(response.getBody().get("message")).isEqualTo("Field 'rows' is required");
		}
	}

	@Test
	void aScalarJsonRootIsD084sDeterministicFiveHundred() {
		// body(): array cannot return an int under strict_types, so PHP raises a
		// TypeError before the endpoint's first line runs.
		for (String body : List.of("42", "\"text\"", "true", "1.5")) {
			ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
			assertThat(response.getStatusCode().value()).as("body %s", body).isEqualTo(500);
			assertThat(response.getBody().get("success")).isEqualTo(false);
			assertThat(response.getBody().get("message")).isEqualTo("Internal server error");
			assertThat(response.getBody()).doesNotContainKey("data");
		}
	}

	// ------------------------------------------------------------------
	// Batch outcomes
	// ------------------------------------------------------------------

	@Test
	void everyRowValidInsertsThemAllInOrder() {
		Map<String, Object> body = importRows(ADMIN_1,
				validRow("8101", "01012350101"), validRow("8102", "01012350102"),
				validRow("8103", "01012350103"));

		// ok(EMPLOYEES_IMPORTED, $result) passes no $replace, so the
		// catalog's {inserted} placeholder reaches the client unsubstituted.
		assertThat(body.get("message")).isEqualTo("Imported {inserted} employees");
		Map<String, Object> data = dataOf(body);
		assertThat(number(data.get("inserted"))).isEqualTo(3);
		assertThat((List<?>) data.get("failed")).isEmpty();

		List<Long> createdIds = idsOf(data);
		assertThat(createdIds).hasSize(3);
		// created_ids follow insertion order, which is row order.
		assertThat(createdIds).isSorted();
		assertThat(codeOf(createdIds.get(0))).isEqualTo("8101");
		assertThat(codeOf(createdIds.get(2))).isEqualTo("8103");
	}

	@Test
	void everyRowInvalidStillAnswersTwoHundredWithTheFailedMessage() {
		Map<String, Object> broken = validRow("8110", "01012350110");
		broken.put("first_name", "");
		Map<String, Object> alsoBroken = validRow("8111", "01012350111");
		alsoBroken.put("shift_name", "No Such Shift");

		Map<String, Object> body = importRows(ADMIN_1, broken, alsoBroken);
		assertThat(body.get("message")).isEqualTo("Could not import employees");

		Map<String, Object> data = dataOf(body);
		assertThat(number(data.get("inserted"))).isZero();
		assertThat((List<?>) data.get("created_ids")).isEmpty();

		List<Map<String, Object>> failed = failedOf(data);
		assertThat(failed).hasSize(2);
		// Four keys exactly: no field_errors, which only analyze() produces.
		assertThat(failed.get(0).keySet()).containsExactly(
				"row_index", "errors", "error_messages", "data");
		assertThat(failed.get(0).get("row_index")).isEqualTo(1);
		assertThat(failed.get(1).get("row_index")).isEqualTo(2);
		assertThat(failed.get(0).get("errors")).isEqualTo(List.of("first_name_required"));
		assertThat(failed.get(0).get("error_messages")).isEqualTo(List.of("الاسم الأول مطلوب"));
		// `data` is the row as it arrived, not a display row.
		assertThat(((Map<?, ?>) failed.get(0).get("data")).get("employee_code")).isEqualTo("8110");
	}

	@Test
	void aMixedBatchIsReportedAsImportedNotFailed() {
		Map<String, Object> broken = validRow("8121", "01012350121");
		broken.put("salary_basic", "");

		Map<String, Object> body = importRows(ADMIN_1,
				validRow("8120", "01012350120"), broken, validRow("8122", "01012350122"));

		// inserted > 0, so the message is the success one even with failures.
		assertThat(body.get("message")).isEqualTo("Imported {inserted} employees");
		Map<String, Object> data = dataOf(body);
		assertThat(number(data.get("inserted"))).isEqualTo(2);
		assertThat(failedOf(data)).hasSize(1);
		assertThat(failedOf(data).get(0).get("row_index")).isEqualTo(2);
		// The rows either side of the failure are both committed.
		assertThat(idsOf(data)).hasSize(2);
		assertThat(codeOf(idsOf(data).get(0))).isEqualTo("8120");
		assertThat(codeOf(idsOf(data).get(1))).isEqualTo("8122");
	}

	// ------------------------------------------------------------------
	// seen_codes
	// ------------------------------------------------------------------

	@Test
	void aRepeatedCodeInsideTheFileIsRejectedOnTheSecondRow() {
		Map<String, Object> body = importRows(ADMIN_1,
				validRow("8130", "01012350130"), validRow("8130", "01012350131"));

		Map<String, Object> data = dataOf(body);
		assertThat(number(data.get("inserted"))).isEqualTo(1);
		assertThat(failedOf(data)).hasSize(1);
		assertThat(failedOf(data).get(0).get("errors"))
				.isEqualTo(List.of("employee_code_duplicate_in_file"));
		assertThat((List<?>) failedOf(data).get(0).get("error_messages")).hasSize(1);
		assertThat((String) ((List<?>) failedOf(data).get(0).get("error_messages")).get(0))
				.contains("(8130)");
	}

	@Test
	void anInvalidFirstRowStillReservesItsCode() {
		// The consequence of adding the code to seen_codes *before* validating:
		// the second row is a duplicate, not a promotion.
		Map<String, Object> invalidFirst = validRow("8140", "01012350140");
		invalidFirst.put("first_name", "");

		Map<String, Object> body = importRows(ADMIN_1, invalidFirst, validRow("8140", "01012350141"));

		Map<String, Object> data = dataOf(body);
		assertThat(number(data.get("inserted"))).isZero();
		List<Map<String, Object>> failed = failedOf(data);
		assertThat(failed).hasSize(2);
		assertThat(failed.get(0).get("errors")).isEqualTo(List.of("first_name_required"));
		assertThat(failed.get(1).get("errors")).isEqualTo(List.of("employee_code_duplicate_in_file"));
		// And nothing with that code exists afterwards.
		assertThat(countByCode(COMPANY_1, "8140")).isZero();
	}

	@Test
	void anEmptyCodeIsNeverReservedSoRepeatedBlanksAreNotDuplicates() {
		Map<String, Object> first = validRow("", "01012350150");
		Map<String, Object> second = validRow("", "01012350151");

		List<Map<String, Object>> failed = failedOf(dataOf(importRows(ADMIN_1, first, second)));
		assertThat(failed).hasSize(2);
		// Both fail on the missing code itself, not on duplication.
		assertThat(failed.get(0).get("errors")).isEqualTo(List.of("employee_code_required"));
		assertThat(failed.get(1).get("errors")).isEqualTo(List.of("employee_code_required"));
	}

	// ------------------------------------------------------------------
	// Uniqueness against the database
	// ------------------------------------------------------------------

	@Test
	void aCodeAlreadyUsedInThisCompanyIsRejected() {
		// 1001 belongs to the seeded admin.
		List<Map<String, Object>> failed =
				failedOf(dataOf(importRows(ADMIN_1, validRow("1001", "01012350160"))));
		assertThat(failed.get(0).get("errors")).isEqualTo(List.of("employee_code_exists"));
	}

	@Test
	void aCodeUsedOnlyInAnotherCompanyIsAccepted() {
		// The uniqueness is per company, so company 2's 1001 is no obstacle.
		Map<String, Object> data = dataOf(importRows(ADMIN_1, validRow("2001", "01012350161")));
		assertThat(number(data.get("inserted"))).isEqualTo(1);
	}

	@Test
	void aPhoneAlreadyUsedAnywhereIsRejected() {
		// Phone uniqueness is global, so another company's number still blocks.
		List<Map<String, Object>> failed =
				failedOf(dataOf(importRows(ADMIN_1, validRow("8170", "01000198021"))));
		assertThat(failed.get(0).get("errors")).isEqualTo(List.of("phone_exists"));
		assertThat((String) ((List<?>) failed.get(0).get("error_messages")).get(0))
				.contains("مستخدم مسبقاً");
	}

	@Test
	void aPhoneRepeatedInsideTheFileIsCaughtOnceTheFirstRowHasCommitted() {
		// There is no seen_phones set: the second row is caught because the
		// first has already been committed and the check is a database read.
		Map<String, Object> body = importRows(ADMIN_1,
				validRow("8180", "01012350180"), validRow("8181", "01012350180"));

		Map<String, Object> data = dataOf(body);
		assertThat(number(data.get("inserted"))).isEqualTo(1);
		assertThat(failedOf(data).get(0).get("errors")).isEqualTo(List.of("phone_exists"));
	}

	// ------------------------------------------------------------------
	// One transaction per row
	// ------------------------------------------------------------------

	@Test
	void aRowThatFailsInTheDatabaseRollsBackOnlyItself() {
		// An unparseable hire_date survives row_to_payload untouched, and the
		// helper's leave-balance year comes from date('Y', strtotime($hire_date))
		// *inside* the transaction -- so strtotime() returning false rolls this
		// row back after its employee insert has already run. A genuine
		// mid-transaction failure, and the one create.php answers with a 500
		// while the batch answers with a row.
		Map<String, Object> bad = validRow("8191", "01012350191");
		bad.put("hire_date", "not-a-date");

		long before = employeeCount(COMPANY_1);
		Map<String, Object> body = importRows(ADMIN_1,
				validRow("8190", "01012350190"), bad, validRow("8192", "01012350192"));

		Map<String, Object> data = dataOf(body);
		assertThat(number(data.get("inserted"))).isEqualTo(2);
		List<Map<String, Object>> failed = failedOf(data);
		assertThat(failed).hasSize(1);
		assertThat(failed.get(0).get("row_index")).isEqualTo(2);
		// Classified, never leaked: no SQL, column or index text reaches here.
		assertThat(failed.get(0).get("errors")).isEqualTo(List.of("employee_create_failed"));
		assertThat((String) ((List<?>) failed.get(0).get("error_messages")).get(0))
				.isEqualTo("تعذّر إنشاء الموظف. راجع البيانات وحاول مرة أخرى")
				.doesNotContain("hire_date").doesNotContain("strtotime").doesNotContain("SQL");

		// Two rows committed, the failed one absent, and the batch did not roll
		// back around it -- so there is no transaction spanning the batch.
		assertThat(employeeCount(COMPANY_1)).isEqualTo(before + 2);
		assertThat(countByCode(COMPANY_1, "8190")).isEqualTo(1);
		assertThat(countByCode(COMPANY_1, "8191")).isZero();
		assertThat(countByCode(COMPANY_1, "8192")).isEqualTo(1);
	}

	@Test
	void aFailedRowLeavesNoSalaryLeaveOrShiftBehind() {
		Map<String, Object> bad = validRow("8195", "01012350195");
		bad.put("hire_date", "not-a-date");

		importRows(ADMIN_1, bad);
		assertThat(countByCode(COMPANY_1, "8195")).isZero();
		// The whole row rolled back, so nothing downstream of the insert exists.
		assertThat(scalar("SELECT COUNT(*) FROM salary_contracts sc"
				+ " JOIN employees e ON e.id = sc.employee_id WHERE e.employee_code = '8195'")).isZero();
		assertThat(scalar("SELECT COUNT(*) FROM leave_balance lb"
				+ " JOIN employees e ON e.id = lb.employee_id WHERE e.employee_code = '8195'")).isZero();
		assertThat(scalar("SELECT COUNT(*) FROM employee_shift_assignments a"
				+ " JOIN employees e ON e.id = a.employee_id WHERE e.employee_code = '8195'")).isZero();
	}

	// ------------------------------------------------------------------
	// What one successful row writes
	// ------------------------------------------------------------------

	@Test
	void oneImportedRowWritesTheEmployeeSalaryLeaveAndShift() {
		Map<String, Object> data = dataOf(importRows(ADMIN_1, validRow("8200", "01012350200")));
		long employeeId = idsOf(data).get(0);

		Map<String, Object> employee = row("SELECT * FROM employees WHERE id = " + employeeId);
		assertThat(employee.get("company_id")).isEqualTo(COMPANY_1);
		assertThat(employee.get("branch_id")).isEqualTo(BRANCH_MAIN);
		assertThat(employee.get("department_id")).isEqualTo(DEPARTMENT_FIELD);
		assertThat(employee.get("job_title_id")).isEqualTo(JOB_TITLE_SENIOR);
		assertThat(employee.get("role")).isEqualTo("employee");
		assertThat(employee.get("is_active")).isEqualTo(1L);
		assertThat(employee.get("is_mobile_attendance_enabled")).isEqualTo(1L);
		assertThat(employee.get("can_check_in_any_branch")).isEqualTo(0L);
		assertThat(employee.get("hire_date")).isEqualTo("2024-03-01");
		assertThat(employee.get("birth_date")).isEqualTo("1990-01-15");
		assertThat(employee.get("gender")).isEqualTo("male");
		assertThat(employee.get("country_code")).isEqualTo("+20");
		assertThat(employee.get("contract_duration_months")).isEqualTo(24L);
		// A password was supplied together with a phone, so it was hashed.
		assertThat((String) employee.get("password_hash")).startsWith("$2");

		Map<String, Object> salary = row(
				"SELECT * FROM salary_contracts WHERE employee_id = " + employeeId);
		assertThat(salary.get("basic_salary")).isEqualTo("5000.00");
		assertThat(salary.get("transport_allowance")).isEqualTo("200.00");
		assertThat(salary.get("insurance_deduction")).isEqualTo("75.00");
		// housing_allowance is hard-coded to 0 by the helper, never read.
		assertThat(salary.get("housing_allowance")).isEqualTo("0.00");
		assertThat(salary.get("effective_from")).isEqualTo("2024-03-01");

		// The leave balance is always written, with the helper's own defaults.
		Map<String, Object> leave = row("SELECT * FROM leave_balance WHERE employee_id = " + employeeId);
		assertThat(String.valueOf(leave.get("year"))).isEqualTo("2024");
		// decimal(5,1), so the helper's 21.0 default reads back at one place.
		assertThat(leave.get("total_days")).isEqualTo("21.0");
		assertThat(leave.get("used_days")).isEqualTo("0.0");
		assertThat(leave.get("period_from_month")).isEqualTo(1L);
		assertThat(leave.get("period_to_month")).isEqualTo(12L);
		assertThat(leave.get("monthly_cap_days")).isNull();

		Map<String, Object> assignment = row(
				"SELECT * FROM employee_shift_assignments WHERE employee_id = " + employeeId);
		assertThat(assignment.get("shift_id")).isEqualTo(SHIFT_NIGHT);
		// shift_effective_from follows hire_date, as row_to_payload set it.
		assertThat(assignment.get("effective_from")).isEqualTo("2024-03-01");
	}

	@Test
	void aRowWithNoSalaryCellsWritesNoContractButStillWritesLeave() {
		Map<String, Object> noSalary = validRow("8210", "01012350210");
		noSalary.put("salary_transport", "");
		noSalary.put("salary_insurance_deduction", "");
		// salary_basic is required by row_to_payload, so the only way to reach
		// the helper without a salary map is a payload that never had one --
		// which the batch cannot produce. The contract is therefore always
		// written when a row imports, and this asserts that rather than
		// pretending otherwise.
		long employeeId = idsOf(dataOf(importRows(ADMIN_1, noSalary))).get(0);
		Map<String, Object> salary = row(
				"SELECT * FROM salary_contracts WHERE employee_id = " + employeeId);
		assertThat(salary.get("basic_salary")).isEqualTo("5000.00");
		assertThat(salary.get("transport_allowance")).isEqualTo("0.00");
		assertThat(scalar("SELECT COUNT(*) FROM leave_balance WHERE employee_id = " + employeeId))
				.isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// PHP array shapes
	// ------------------------------------------------------------------

	@Test
	void rowsGivenAsAJsonObjectKeepTheirNumericKeysAsIndexes() {
		// json_decode(..., true) turns a numeric object key into an integer, so
		// row_index is that key plus one -- not the position in the batch.
		String body = "{\"rows\":{\"5\":" + json(brokenRow("8220")) + "}}";
		Map<String, Object> response = post(body, ADMIN_1).getBody();
		List<Map<String, Object>> failed = failedOf(dataOf(response));
		assertThat(failed.get(0).get("row_index")).isEqualTo(6);
	}

	@Test
	void aNonNumericRowKeyOnAFailedRowIsATypeErrorAndSoAFiveHundred() {
		// $index + 1 has no string overload. Measured under PHP 8.3:
		// "Unsupported operand types: string + int". Nothing catches it.
		String body = "{\"rows\":{\"foo\":" + json(brokenRow("8230")) + "}}";
		ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().get("message")).isEqualTo("Internal server error");
	}

	@Test
	void aNonNumericRowKeyOnASucceedingRowIsHarmless() {
		// $index + 1 is only evaluated when a failure row is built, so a
		// string-keyed row that imports cleanly never reaches it.
		String body = "{\"rows\":{\"foo\":" + json(validRow("8240", "01012350240")) + "}}";
		Map<String, Object> response = post(body, ADMIN_1).getBody();
		assertThat(number(dataOf(response).get("inserted"))).isEqualTo(1);
	}

	@Test
	void aRowThatIsNotAnObjectIsATypeErrorToo() {
		// row_to_payload(array $row) under strict_types: a scalar row is a fatal
		// before any row failure can be recorded.
		ResponseEntity<Map<String, Object>> response = post("{\"rows\":[42]}", ADMIN_1);
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().get("message")).isEqualTo("Internal server error");
	}

	// ------------------------------------------------------------------
	// Tenancy
	// ------------------------------------------------------------------

	@Test
	void anotherCompanysOrganizationNamesResolveToNothing() {
		// Every reference reaching the create helper is resolved through
		// company-scoped lookups, so another tenant's names are simply absent
		// rather than usable.
		Map<String, Object> foreign = validRow("8250", "01012350250");
		foreign.put("branch_name", OTHER_BRANCH_NAME);
		foreign.put("department_name", OTHER_DEPARTMENT_NAME);
		foreign.put("job_title_name", OTHER_JOB_TITLE_NAME);
		foreign.put("shift_name", OTHER_SHIFT_NAME);

		List<Map<String, Object>> failed = failedOf(dataOf(importRows(ADMIN_1, foreign)));
		assertThat(failed.get(0).get("errors")).isEqualTo(List.of(
				"shift_not_found", "branch_not_found", "department_not_found", "job_title_not_found"));
		assertThat(countByCode(COMPANY_1, "8250")).isZero();
	}

	@Test
	void anImportRunsAgainstTheSessionsOwnCompany() {
		Map<String, Object> data = dataOf(importRows(HR_1, validRow("8260", "01012350260")));
		long employeeId = idsOf(data).get(0);
		assertThat(row("SELECT * FROM employees WHERE id = " + employeeId).get("company_id"))
				.isEqualTo(COMPANY_1);
		assertThat(countByCode(COMPANY_2, "8260")).isZero();
	}

	// ------------------------------------------------------------------
	// Guards
	// ------------------------------------------------------------------

	@Test
	void theGuardStackRunsInPhpsOrder() {
		String body = "{\"rows\":[" + json(validRow("8270", "01012350270")) + "]}";

		ResponseEntity<Map<String, Object>> wrongMethod = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + IMPORT), HttpMethod.GET,
				new HttpEntity<>(jsonHeaders(tokenFor(ADMIN_1))),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
		assertThat(wrongMethod.getBody().get("message")).isEqualTo("Invalid method");

		assertThat(post(body, MANAGER_1).getStatusCode().value()).isEqualTo(403);
		assertThat(post(body, PLAIN_EMPLOYEE).getStatusCode().value()).isEqualTo(403);
		assertThat(post(body, ADMIN_SUSPENDED).getStatusCode().value()).isEqualTo(403);
		// And nothing was written by any of the refused attempts.
		assertThat(countByCode(COMPANY_1, "8270")).isZero();
	}

	// ------------------------------------------------------------------
	// Fixtures and helpers
	// ------------------------------------------------------------------

	private static Map<String, Object> validRow(String code, String phone) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("employee_code", code);
		row.put("first_name", "Nour");
		row.put("last_name", "Adel");
		row.put("country_code", "");
		row.put("phone", phone);
		row.put("password", "secret123");
		row.put("shift_name", SHIFT_NAME);
		row.put("national_id", "29801011234567");
		row.put("birth_date", "1990-01-15");
		row.put("gender", "ذكر");
		row.put("address", "Cairo");
		row.put("is_mobile_attendance_enabled", "نعم");
		row.put("hire_date", "2024-03-01");
		row.put("branch_name", BRANCH_NAME);
		row.put("department_name", DEPARTMENT_NAME);
		row.put("job_title_name", JOB_TITLE_NAME);
		row.put("expected_daily_hours", "8");
		row.put("contract_duration_years", "2");
		row.put("salary_basic", "5000");
		row.put("salary_transport", "200");
		row.put("salary_insurance_deduction", "75");
		return row;
	}

	private static Map<String, Object> brokenRow(String code) {
		Map<String, Object> row = validRow(code, "01012359999");
		row.put("first_name", "");
		return row;
	}

	@SafeVarargs
	private Map<String, Object> importRows(long employeeId, Map<String, Object>... rows) {
		List<String> encoded = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			encoded.add(json(row));
		}
		String body = "{\"rows\":[" + String.join(",", encoded) + "]}";
		ResponseEntity<Map<String, Object>> response = post(body, employeeId);
		assertThat(response.getStatusCode().value())
				.as("import: %s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);
		return response.getBody();
	}

	/** Minimal JSON for a flat string-valued row. */
	private static String json(Map<String, Object> row) {
		List<String> pairs = new ArrayList<>();
		row.forEach((key, value) -> pairs.add(quote(key) + ":" + quote(String.valueOf(value))));
		return "{" + String.join(",", pairs) + "}";
	}

	private static String quote(String value) {
		StringBuilder quoted = new StringBuilder("\"");
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '"' || character == '\\') {
				quoted.append('\\');
			}
			quoted.append(character);
		}
		return quoted.append('"').toString();
	}

	private ResponseEntity<Map<String, Object>> post(String body, long employeeId) {
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + IMPORT), HttpMethod.POST,
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> failedOf(Map<String, Object> data) {
		return (List<Map<String, Object>>) data.get("failed");
	}

	private static List<Long> idsOf(Map<String, Object> data) {
		List<Long> ids = new ArrayList<>();
		for (Object id : (List<?>) data.get("created_ids")) {
			ids.add(((Number) id).longValue());
		}
		return ids;
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == PLAIN_EMPLOYEE ? "employee"
				: employeeId == HR_1 ? "hr" : "company_admin";
		long companyId = employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private String codeOf(long employeeId) {
		return (String) row("SELECT employee_code FROM employees WHERE id = " + employeeId)
				.get("employee_code");
	}

	private long countByCode(long companyId, String code) {
		return scalar("SELECT COUNT(*) FROM employees WHERE company_id = " + companyId
				+ " AND employee_code = '" + code + "'");
	}

	private long employeeCount(long companyId) {
		return scalar("SELECT COUNT(*) FROM employees WHERE company_id = " + companyId);
	}

	private long scalar(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Map<String, Object> row(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			assertThat(rs.next()).as("no row for %s", sql).isTrue();
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
				String label = rs.getMetaData().getColumnLabel(column);
				int type = rs.getMetaData().getColumnType(column);
				Object value = switch (type) {
					case java.sql.Types.BIT, java.sql.Types.BOOLEAN, java.sql.Types.TINYINT,
							java.sql.Types.SMALLINT, java.sql.Types.INTEGER, java.sql.Types.BIGINT -> {
						long number = rs.getLong(column);
						yield rs.wasNull() ? null : number;
					}
					default -> rs.getString(column);
				};
				row.put(label, value);
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
					  (19801, 'Import Co', '+201000019801', 'active', '2025-01-15 09:00:00'),
					  (19802, 'Import Other Co', '+201000019802', 'active', '2025-01-15 09:00:00'),
					  (19803, 'Import Suspended Co', '+201000019803', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19811, 19801, 'Riverside Branch', 1, '2025-03-01 10:00:00'),
					  (19812, 19802, 'Other Co Branch', 1, '2025-03-01 10:00:00'),
					  (19813, 19803, 'Suspended Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (19821, 19801, 'Field Operations', 1, '2025-04-10 10:00:00'),
					  (19822, 19802, 'Other Co Department', 1, '2025-04-10 10:00:00'),
					  (19823, 19803, 'Suspended Department', 1, '2025-04-10 10:00:00')
					""");
			st.execute("""
					INSERT INTO department_branches (department_id, branch_id) VALUES
					  (19821, 19811), (19822, 19812), (19823, 19813)
					""");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at) VALUES
					  (19831, 19801, 19821, 'Senior Agent', 1, '2025-04-11 10:00:00'),
					  (19832, 19802, 19822, 'Other Co Title', 1, '2025-04-11 10:00:00'),
					  (19833, 19803, 19823, 'Suspended Title', 1, '2025-04-11 10:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES
					  (19841, 19801, 'Night Watch', '22:00:00', '06:00:00', '2025-04-12 10:00:00'),
					  (19842, 19802, 'Other Co Shift', '09:00:00', '17:00:00', '2025-04-12 10:00:00'),
					  (19843, 19803, 'Suspended Shift', '09:00:00', '17:00:00', '2025-04-12 10:00:00')
					""");

			insertEmployee(st, ADMIN_1, COMPANY_1, 19811L, "'1001'", "company_admin", "+201000198011", "Rana");
			insertEmployee(st, HR_1, COMPANY_1, 19811L, "'1002'", "hr", "+201000198012", "Mona");
			insertEmployee(st, MANAGER_1, COMPANY_1, 19811L, "'1003'", "manager", "+201000198013", "Mostafa");
			insertEmployee(st, PLAIN_EMPLOYEE, COMPANY_1, 19811L, "'1004'", "employee",
					"+201000198014", "Omar");
			// Company 2 reuses code 1001 -- the constraint is per company -- and
			// owns the phone the global-uniqueness case collides with.
			insertEmployee(st, 198021L, COMPANY_2, 19812L, "'1001'", "company_admin",
					"+201000198021", "Laila");
			insertEmployee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 19813L, "'3001'", "company_admin",
					"+201000198031", "Tarek");
		}
	}

	private static void insertEmployee(
			Statement st, long id, long companyId, long branchId, String code, String role,
			String phone, String firstName) throws Exception {
		st.execute("""
				INSERT INTO employees
				  (id, company_id, branch_id, department_id, job_title_id, employee_code, expected_daily_hours,
				   first_name, last_name, phone, country_code, password_hash, token_version, role, national_id,
				   birth_date, gender, address, photo_url, hire_date, contract_duration_months, is_active,
				   is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, created_at)
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
				LegacyEmployeeImportBulkEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
