package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
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
 * {@code employees/create.php} over real HTTP against real MariaDB.
 *
 * <p>The assertions follow PHP's own order of operations, because that order is
 * observable: a request that is wrong in two ways must get the error legacy
 * would have produced first. Database state is checked directly for every
 * write, since most of what create does -- the salary contract, the leave
 * balance, the shift assignment -- never appears in the response.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyEmployeeCreateEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String CREATE = "/apis/api/employees/create.php";

	private static final long COMPANY_1 = 19601L;
	private static final long COMPANY_2 = 19602L;
	private static final long BRANCH_DEFAULT = 19611L;
	private static final long BRANCH_SECOND = 19612L;
	private static final long BRANCH_INACTIVE = 19613L;
	private static final long BRANCH_OTHER_COMPANY = 19621L;
	private static final long DEPARTMENT_LINKED = 19641L;
	private static final long DEPARTMENT_UNLINKED = 19642L;
	private static final long DEPARTMENT_OTHER_COMPANY = 19643L;
	private static final long JOB_TITLE_IN_DEPARTMENT = 19651L;
	private static final long JOB_TITLE_OTHER_DEPARTMENT = 19652L;
	private static final long JOB_TITLE_OTHER_COMPANY = 19653L;
	private static final long SHIFT = 19661L;
	private static final long SHIFT_OTHER_COMPANY = 19662L;
	private static final long ADMIN_1 = 196011L;
	private static final long ADMIN_2 = 196021L;
	private static final long REJECTED_PHONE_HOLDER = 196012L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	/**
	 * A spy, not a mock: every call runs for real except the one a test stubs,
	 * so the rollback case exercises the same inserts as the happy path.
	 */
	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	private LegacyEmployeeStore storeSpy;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the Wave 12.4 create fixture", ex);
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
	void aFullCreateWritesEveryRowAndAnswers201() throws Exception {
		Map<String, Object> body = validBody("5001", "01012340001");
		body.put("department_id", DEPARTMENT_LINKED);
		body.put("job_title_id", JOB_TITLE_IN_DEPARTMENT);
		body.put("hire_date", "2024-03-01");
		body.put("national_id", "29001011200011");
		body.put("birth_date", "1990-01-01");
		body.put("gender", "female");
		body.put("address", "Cairo");
		body.put("contract_duration_months", 12);
		body.put("password", "s3cret-pass");
		body.put("salary", Map.of(
				"basic", 12000.50, "transport", 250.25, "food_allowance", 100,
				"insurance_deduction", 30.5));

		ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().get("success")).isEqualTo(true);
		assertThat(response.getBody().get("message")).isEqualTo("Employee created");

		@SuppressWarnings("unchecked")
		Map<String, Object> employee = (Map<String, Object>) response.getBody().get("data");
		long id = ((Number) employee.get("id")).longValue();
		assertThat(employee.get("company_id")).isEqualTo((int) COMPANY_1);
		assertThat(employee.get("branch_id")).isEqualTo((int) BRANCH_DEFAULT);
		assertThat(employee.get("role")).isEqualTo("employee");
		assertThat(employee.get("is_active")).isEqualTo(1);
		assertThat(employee.get("employee_code")).isEqualTo("5001");
		assertThat(employee.get("phone")).isEqualTo("01012340001");
		assertThat(employee.get("country_code")).isEqualTo("+20");
		assertThat(employee.get("branch_name")).isEqualTo("Default Branch");
		assertThat(employee).doesNotContainKeys("password_hash", "token_version");
		// The attach helpers ran after the commit, so these are present.
		assertThat(employee.get("basic_salary")).isEqualTo("12000.50");
		assertThat(employee.get("assigned_shift_name")).isEqualTo("Morning");
		assertThat(employee.get("assigned_shift_effective_from")).isEqualTo("2024-03-01");

		// The salary row: housing is hard-coded to 0, effective_from is the
		// hire date rather than today, and unnamed amounts default to 0.
		Map<String, Object> contract = single(
				"SELECT basic_salary, housing_allowance, transport_allowance, food_allowance,"
				+ " insurance_deduction, penalty_deduction, effective_from FROM salary_contracts"
				+ " WHERE employee_id = " + id);
		assertThat(contract.get("basic_salary")).isEqualTo("12000.50");
		assertThat(contract.get("housing_allowance")).isEqualTo("0.00");
		assertThat(contract.get("transport_allowance")).isEqualTo("250.25");
		assertThat(contract.get("food_allowance")).isEqualTo("100.00");
		assertThat(contract.get("insurance_deduction")).isEqualTo("30.50");
		assertThat(contract.get("penalty_deduction")).isEqualTo("0.00");
		assertThat(contract.get("effective_from")).isEqualTo("2024-03-01");

		// The leave balance: year from the hire date, 21 days, nothing used.
		Map<String, Object> leave = single(
				"SELECT year, total_days, used_days, period_from_month, period_to_month, monthly_cap_days"
				+ " FROM leave_balance WHERE employee_id = " + id);
		assertThat(leave.get("year")).isEqualTo("2024");
		assertThat(leave.get("total_days")).isEqualTo("21.0");
		assertThat(leave.get("used_days")).isEqualTo("0.0");
		assertThat(leave.get("period_from_month")).isEqualTo("1");
		assertThat(leave.get("period_to_month")).isEqualTo("12");
		assertThat(leave.get("monthly_cap_days")).isNull();

		assertThat(count("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id)).isOne();
		// A password plus a phone means a stored bcrypt hash.
		assertThat((String) single("SELECT password_hash FROM employees WHERE id = " + id).get("password_hash"))
				.startsWith("$2");
	}

	@Test
	void requiredFieldsAreCheckedInPhpsOrder() {
		Map<String, Object> body = new LinkedHashMap<>();
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Field 'first_name' is required");
		body.put("first_name", "Nour");
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Field 'last_name' is required");
		body.put("last_name", "Adel");
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Field 'employee_code' is required");
		body.put("employee_code", "5100");
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Field 'shift_id' is required");
		body.put("shift_id", SHIFT);
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Field 'expected_daily_hours' is required");

		// required() is isset() plus the exact empty string, so '0' and 0 pass
		// it -- an empty string does not.
		body.put("expected_daily_hours", "");
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Field 'expected_daily_hours' is required");
	}

	@Test
	void phoneIsValidatedBeforeTheEmployeeCode() {
		// Both are wrong; PHP resolves the phone first, so that is the error.
		Map<String, Object> body = validBody("not-digits", "01012340002");
		body.put("phone", "01212");
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Phone number is not valid for the selected country");
	}

	@Test
	void aPhoneNeedsACountryCodeAndAValidNumber() {
		Map<String, Object> body = validBody("5200", "01012340003");
		body.remove("country_code");
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Field 'country_code' is required");

		body.put("country_code", "+20");
		body.put("phone", "01312340003");
		assertThat(message(post(body, ADMIN_1), 400)).isEqualTo("Phone number is not valid for the selected country");
	}

	@Test
	void aPhoneIsStoredNormalisedAndIsGloballyUnique() throws Exception {
		// 1012340004 has no leading zero; PHP stores 01012340004.
		Map<String, Object> body = validBody("5300", "1012340004");
		ResponseEntity<Map<String, Object>> created = post(body, ADMIN_1);
		assertThat(created.getStatusCode().value()).isEqualTo(201);
		assertThat(data(created).get("phone")).isEqualTo("01012340004");

		// Any spelling of the same number is now taken -- and taken globally,
		// so the other company cannot have it either.
		Map<String, Object> duplicate = validBody("5301", "201012340004");
		assertThat(message(post(duplicate, ADMIN_1), 409)).isEqualTo("Phone already exists");
		Map<String, Object> otherCompany = validBody("5302", "01012340004");
		otherCompany.put("shift_id", SHIFT_OTHER_COMPANY);
		assertThat(message(post(otherCompany, ADMIN_2), 409)).isEqualTo("Phone already exists");
	}

	@Test
	void aRejectedJoinRequestDoesNotReserveThePhoneButTheUniqueIndexStillDoes() throws Exception {
		// employee_phone_exists_globally() ignores rejected rows, so validation
		// passes -- and then the database's own unique index rejects the insert.
		// PHP documents this as a race; here it is deterministic, and it is the
		// cleanest proof that the transaction rolls back completely.
		long before = count("SELECT COUNT(*) FROM employees WHERE company_id = " + COMPANY_1);
		Map<String, Object> body = validBody("5400", "01099990000");
		body.put("salary", Map.of("basic", 5000));

		ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		// The catalog entry carries a {error} placeholder, but PHP passes the
		// exception text as $data (the third argument), not as $replace (the
		// fourth) -- so the placeholder is never substituted and the message
		// reaches the client literally, with the detail alongside it in data.
		assertThat(response.getBody().get("message")).isEqualTo("Failed to create employee: {error}");
		assertThat(response.getBody().get("data")).isInstanceOf(String.class);
		assertThat((String) response.getBody().get("data")).isNotBlank();

		// Nothing survived: no employee, and therefore no salary or leave rows.
		assertThat(count("SELECT COUNT(*) FROM employees WHERE company_id = " + COMPANY_1)).isEqualTo(before);
		assertThat(count("SELECT COUNT(*) FROM employees WHERE employee_code = '5400'")).isZero();
		assertThat(count("SELECT COUNT(*) FROM salary_contracts WHERE basic_salary = 5000.00")).isZero();
	}

	@Test
	void anErrorInsideTheTransactionRollsEverythingBackToo() throws Exception {
		// PHP catches Throwable around the transaction, so the rollback does not
		// depend on the failure being an ordinary exception. The shift
		// assignment is the last write, which means the employee, the salary
		// contract and the leave balance are all already inserted when this
		// blows up -- and all three have to disappear.
		Mockito.doThrow(new NoClassDefFoundError("a driver class went missing mid-transaction"))
				.when(storeSpy).insertShiftAssignment(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
		try {
			Map<String, Object> body = validBody("6400", "01012340040");
			body.put("salary", Map.of("basic", 7777));

			ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
			assertThat(response.getStatusCode().value()).isEqualTo(500);
			assertThat(response.getBody().get("success")).isEqualTo(false);
			assertThat(response.getBody().get("message")).isEqualTo("Failed to create employee: {error}");
			assertThat((String) response.getBody().get("data"))
					.contains("a driver class went missing mid-transaction");

			assertThat(count("SELECT COUNT(*) FROM employees WHERE employee_code = '6400'")).isZero();
			assertThat(count("SELECT COUNT(*) FROM salary_contracts WHERE basic_salary = 7777.00")).isZero();
			assertThat(count(
					"SELECT COUNT(*) FROM leave_balance lb"
					+ " LEFT JOIN employees e ON e.id = lb.employee_id WHERE e.id IS NULL")).isZero();
			assertThat(count(
					"SELECT COUNT(*) FROM employee_shift_assignments esa"
					+ " LEFT JOIN employees e ON e.id = esa.employee_id WHERE e.id IS NULL")).isZero();
		} finally {
			Mockito.reset(storeSpy);
		}
	}

	@Test
	void theLeaveYearReproducesPhpsDateOfStrtotime() throws Exception {
		// LegacyPhpDateYear's grammar, proven end to end and in the database.
		assertThat(leaveYearFor("6500", "01012340041", "2026-08-21")).isEqualTo("2026");
		assertThat(leaveYearFor("6501", "01012340042", "2026-08-21 12:30:00")).isEqualTo("2026");
		assertThat(leaveYearFor("6502", "01012340043", "2026/08/21")).isEqualTo("2026");
		assertThat(leaveYearFor("6503", "01012340044", "2026-8-1")).isEqualTo("2026");

		// '0000-00-00' is year -1 in PHP, which a YEAR(4) column stores as 0000.
		assertThat(leaveYearFor("6504", "01012340045", "0000-00-00")).isEqualTo("0000");
		assertThat(hireDateFor("6504")).isEqualTo("0000-00-00");

		// The forms MariaDB will not store as a date still produce a real leave
		// year, because strtotime() reads them. The two columns disagree, and
		// that disagreement is what PHP does.
		assertThat(leaveYearFor("6505", "01012340046", "21 Aug 2026")).isEqualTo("2026");
		assertThat(hireDateFor("6505")).isEqualTo("0000-00-00");
		assertThat(leaveYearFor("6506", "01012340047", "08/21/2026")).isEqualTo("2026");
		assertThat(leaveYearFor("6507", "01012340048", "21-08-2026")).isEqualTo("2026");
		// Day overflow rolls forward rather than failing.
		assertThat(leaveYearFor("6508", "01012340049", "2026-02-30")).isEqualTo("2026");
	}

	@Test
	void theRelativeKeywordsFollowTheLegacyClock() throws Exception {
		String currentYear = String.valueOf(java.time.LocalDate.now(java.time.ZoneOffset.ofHours(2)).getYear());
		String tomorrowYear = String.valueOf(
				java.time.LocalDate.now(java.time.ZoneOffset.ofHours(2)).plusDays(1).getYear());
		String yesterdayYear = String.valueOf(
				java.time.LocalDate.now(java.time.ZoneOffset.ofHours(2)).minusDays(1).getYear());

		assertThat(leaveYearFor("6520", "01012340060", "now")).isEqualTo(currentYear);
		assertThat(leaveYearFor("6521", "01012340061", "today")).isEqualTo(currentYear);
		assertThat(leaveYearFor("6522", "01012340062", "tomorrow")).isEqualTo(tomorrowYear);
		assertThat(leaveYearFor("6523", "01012340063", "yesterday")).isEqualTo(yesterdayYear);
	}

	@Test
	void anUnparseableHireDateRollsTheWholeCreateBackWithPhpsTypeError() throws Exception {
		// strtotime() returns false, date('Y', false) raises a TypeError under
		// strict_types=1, and because the expression sits inside the
		// transaction the employee, salary and leave rows all disappear.
		for (String rejected : List.of("invalid text", "2026-13-45", "2026-12-32", "1")) {
			Map<String, Object> body = validBody("66" + Math.abs(rejected.hashCode() % 90 + 10),
					"010123401" + String.format("%02d", Math.abs(rejected.hashCode() % 90 + 10)));
			body.put("hire_date", rejected);
			body.put("salary", Map.of("basic", 8888));

			ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
			assertThat(response.getStatusCode().value())
					.describedAs("hire_date %s", rejected).isEqualTo(500);
			assertThat(response.getBody().get("message")).isEqualTo("Failed to create employee: {error}");
			assertThat((String) response.getBody().get("data"))
					.contains("date(): Argument #2 ($timestamp) must be of type ?int, false given");
		}
		// Nothing was written by any of them.
		assertThat(count("SELECT COUNT(*) FROM salary_contracts WHERE basic_salary = 8888.00")).isZero();
		assertThat(count(
				"SELECT COUNT(*) FROM employees WHERE company_id = " + COMPANY_1
				+ " AND employee_code LIKE '66%'")).isZero();
	}

	@Test
	void theEmployeeCodeIsNormalisedValidatedAndCompanyScoped() throws Exception {
		Map<String, Object> body = validBody("  5500  ", "01012340005");
		assertThat(data(post(body, ADMIN_1)).get("employee_code")).isEqualTo("5500");

		// Digits only, 1..64.
		Map<String, Object> invalid = validBody("55A0", "01012340006");
		assertThat(message(post(invalid, ADMIN_1), 400)).isEqualTo("Employee code must contain digits only (at least one digit)");
		Map<String, Object> blank = validBody("   ", "01012340007");
		assertThat(message(post(blank, ADMIN_1), 400)).isEqualTo("Field 'employee_code' is required");

		// Taken in this company...
		Map<String, Object> duplicate = validBody("5500", "01012340008");
		assertThat(message(post(duplicate, ADMIN_1), 409)).isEqualTo("Employee code is already used in this company");
		// ...but free in another one, because the check is company-scoped.
		Map<String, Object> otherCompany = validBody("5500", "01012340009");
		otherCompany.put("shift_id", SHIFT_OTHER_COMPANY);
		assertThat(post(otherCompany, ADMIN_2).getStatusCode().value()).isEqualTo(201);
	}

	@Test
	void anOmittedBranchTakesTheLowestIdActiveBranchAndAForeignOneIs404() {
		Map<String, Object> body = validBody("5600", "01012340010");
		body.remove("branch_id");
		assertThat(data(post(body, ADMIN_1)).get("branch_id")).isEqualTo((int) BRANCH_DEFAULT);

		Map<String, Object> foreign = validBody("5601", "01012340011");
		foreign.put("branch_id", BRANCH_OTHER_COMPANY);
		assertThat(message(post(foreign, ADMIN_1), 404)).isEqualTo("Branch not found");

		// An explicit inactive branch is accepted: PHP checks company only.
		Map<String, Object> inactive = validBody("5602", "01012340012");
		inactive.put("branch_id", BRANCH_INACTIVE);
		assertThat(data(post(inactive, ADMIN_1)).get("branch_id")).isEqualTo((int) BRANCH_INACTIVE);

		// A zero-like branch means "not supplied" and takes the default too.
		Map<String, Object> zero = validBody("5603", "01012340013");
		zero.put("branch_id", 0);
		assertThat(data(post(zero, ADMIN_1)).get("branch_id")).isEqualTo((int) BRANCH_DEFAULT);
	}

	@Test
	void theDepartmentIsValidatedThroughTheBranchJunctionAndFailsClosedAcrossTenants() {
		Map<String, Object> linked = validBody("5700", "01012340014");
		linked.put("department_id", DEPARTMENT_LINKED);
		assertThat(post(linked, ADMIN_1).getStatusCode().value()).isEqualTo(201);

		// Same company, but no department_branches row for this branch.
		Map<String, Object> unlinked = validBody("5701", "01012340015");
		unlinked.put("department_id", DEPARTMENT_UNLINKED);
		assertThat(message(post(unlinked, ADMIN_1), 404)).isEqualTo("Department not found");

		// D-075: another company's department fails closed, with the same 404.
		Map<String, Object> foreign = validBody("5702", "01012340016");
		foreign.put("department_id", DEPARTMENT_OTHER_COMPANY);
		assertThat(message(post(foreign, ADMIN_1), 404)).isEqualTo("Department not found");
	}

	@Test
	void theJobTitleIsOnlyValidatedWhenADepartmentIsPresent() throws Exception {
		// With a department: it must be active and in that department.
		Map<String, Object> mismatched = validBody("5800", "01012340017");
		mismatched.put("department_id", DEPARTMENT_LINKED);
		mismatched.put("job_title_id", JOB_TITLE_OTHER_DEPARTMENT);
		assertThat(message(post(mismatched, ADMIN_1), 404)).isEqualTo("Job title not found");

		// Without a department: PHP skips the check entirely and stores the id
		// as given, even though it belongs to a different department.
		Map<String, Object> unchecked = validBody("5801", "01012340018");
		unchecked.put("job_title_id", JOB_TITLE_OTHER_DEPARTMENT);
		ResponseEntity<Map<String, Object>> stored = post(unchecked, ADMIN_1);
		assertThat(stored.getStatusCode().value()).isEqualTo(201);
		assertThat(data(stored).get("job_title_id")).isEqualTo((int) JOB_TITLE_OTHER_DEPARTMENT);

		// D-075 still closes the cross-tenant case when the check does run.
		Map<String, Object> foreign = validBody("5802", "01012340019");
		foreign.put("department_id", DEPARTMENT_LINKED);
		foreign.put("job_title_id", JOB_TITLE_OTHER_COMPANY);
		assertThat(message(post(foreign, ADMIN_1), 404)).isEqualTo("Job title not found");
	}

	@Test
	void shiftZeroPassesRequiredButCreatesNoAssignment() throws Exception {
		// required() accepts 0; !empty() then turns it into null.
		Map<String, Object> body = validBody("5900", "01012340020");
		body.put("shift_id", 0);
		ResponseEntity<Map<String, Object>> response = post(body, ADMIN_1);
		assertThat(response.getStatusCode().value()).isEqualTo(201);

		long id = ((Number) data(response).get("id")).longValue();
		assertThat(count("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id)).isZero();
		// The attach helper found nothing, so the keys are absent entirely.
		assertThat(data(response)).doesNotContainKeys("assigned_shift_id", "assigned_shift_name");

		Map<String, Object> foreign = validBody("5901", "01012340021");
		foreign.put("shift_id", SHIFT_OTHER_COMPANY);
		assertThat(message(post(foreign, ADMIN_1), 404)).isEqualTo("Shift not found");
	}

	@Test
	void expectedDailyHoursMustBePositive() {
		Map<String, Object> zero = validBody("6000", "01012340022");
		zero.put("expected_daily_hours", 0);
		assertThat(message(post(zero, ADMIN_1), 400)).isEqualTo("Invalid input");

		Map<String, Object> negative = validBody("6001", "01012340023");
		negative.put("expected_daily_hours", "-1");
		assertThat(message(post(negative, ADMIN_1), 400)).isEqualTo("Invalid input");

		// A non-numeric string casts to 0.0 and is rejected the same way.
		Map<String, Object> text = validBody("6002", "01012340024");
		text.put("expected_daily_hours", "abc");
		assertThat(message(post(text, ADMIN_1), 400)).isEqualTo("Invalid input");
	}

	@Test
	void aPasswordIsHashedOnlyWhenThereIsAPhoneToUseIt() throws Exception {
		Map<String, Object> withoutPhone = validBody("6100", null);
		withoutPhone.put("password", "s3cret-pass");
		ResponseEntity<Map<String, Object>> response = post(withoutPhone, ADMIN_1);
		assertThat(response.getStatusCode().value()).isEqualTo(201);

		long id = ((Number) data(response).get("id")).longValue();
		Map<String, Object> row = single(
				"SELECT password_hash, phone, country_code FROM employees WHERE id = " + id);
		assertThat(row.get("password_hash")).isNull();
		assertThat(row.get("phone")).isNull();
		// Country code is nulled with the phone: the two are resolved as a pair.
		assertThat(row.get("country_code")).isNull();

		// A blank password with a phone is also no hash.
		Map<String, Object> blank = validBody("6101", "01012340025");
		blank.put("password", "   ");
		long blankId = ((Number) data(post(blank, ADMIN_1)).get("id")).longValue();
		assertThat(single("SELECT password_hash FROM employees WHERE id = " + blankId).get("password_hash"))
				.isNull();
	}

	@Test
	void theBooleanFlagsUseAnExactTruthSetRatherThanACast() throws Exception {
		assertThat(mobileFlagFor("6200", "01012340026", true)).isEqualTo("1");
		assertThat(mobileFlagFor("6201", "01012340027", 1)).isEqualTo("1");
		assertThat(mobileFlagFor("6202", "01012340028", "1")).isEqualTo("1");
		assertThat(mobileFlagFor("6203", "01012340029", "true")).isEqualTo("1");
		// Everything else present is off, including values a cast would accept.
		assertThat(mobileFlagFor("6204", "01012340030", "yes")).isEqualTo("0");
		assertThat(mobileFlagFor("6205", "01012340031", "TRUE")).isEqualTo("0");
		assertThat(mobileFlagFor("6206", "01012340032", 2)).isEqualTo("0");
		assertThat(mobileFlagFor("6207", "01012340033", false)).isEqualTo("0");

		// Absent: the column default stands (1 for mobile attendance).
		Map<String, Object> absent = validBody("6208", "01012340034");
		long id = ((Number) data(post(absent, ADMIN_1)).get("id")).longValue();
		Map<String, Object> row = single(
				"SELECT is_mobile_attendance_enabled, can_check_in_any_branch FROM employees WHERE id = " + id);
		assertThat(row.get("is_mobile_attendance_enabled")).isEqualTo("1");
		assertThat(row.get("can_check_in_any_branch")).isEqualTo("0");
	}

	@Test
	void theLeaveBalanceHonoursItsOverrides() throws Exception {
		Map<String, Object> body = validBody("6300", "01012340035");
		body.put("hire_date", "2023-07-15");
		body.put("leave_opening_year", 2025);
		body.put("leave_opening_days", 30);
		body.put("period_from_month", 4);
		body.put("period_to_month", 9);
		body.put("monthly_cap_days", 2.5);

		long id = ((Number) data(post(body, ADMIN_1)).get("id")).longValue();
		Map<String, Object> leave = single(
				"SELECT year, total_days, period_from_month, period_to_month, monthly_cap_days"
				+ " FROM leave_balance WHERE employee_id = " + id);
		assertThat(leave.get("year")).isEqualTo("2025");
		assertThat(leave.get("total_days")).isEqualTo("30.0");
		assertThat(leave.get("period_from_month")).isEqualTo("4");
		assertThat(leave.get("period_to_month")).isEqualTo("9");
		assertThat(leave.get("monthly_cap_days")).isEqualTo("2.50");

		// Without a hire date the year comes from today, and without a salary
		// block there is no contract at all.
		Map<String, Object> defaults = validBody("6301", "01012340036");
		long defaultId = ((Number) data(post(defaults, ADMIN_1)).get("id")).longValue();
		assertThat(single("SELECT year FROM leave_balance WHERE employee_id = " + defaultId).get("year"))
				.isEqualTo(String.valueOf(java.time.LocalDate.now(java.time.ZoneOffset.ofHours(2)).getYear()));
		assertThat(count("SELECT COUNT(*) FROM salary_contracts WHERE employee_id = " + defaultId)).isZero();
	}

	@Test
	void theGuardStackAppliesBeforeAnyOfIt() {
		ResponseEntity<Map<String, Object>> unauthenticated = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + CREATE), HttpMethod.POST,
				new HttpEntity<>(Map.of(), jsonHeaders(null)),
				new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(unauthenticated.getStatusCode().value()).isEqualTo(401);
		assertThat(unauthenticated.getBody().get("message")).isEqualTo("Unauthorized — no token");

		// The method guard runs before authentication.
		ResponseEntity<Map<String, Object>> wrongMethod = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + CREATE), HttpMethod.GET,
				new HttpEntity<>(jsonHeaders(null)),
				new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
		assertThat(wrongMethod.getBody().get("message")).isEqualTo("Invalid method");
	}

	private String leaveYearFor(String code, String phone, String hireDate) throws Exception {
		Map<String, Object> body = validBody(code, phone);
		body.put("hire_date", hireDate);
		long id = ((Number) data(post(body, ADMIN_1)).get("id")).longValue();
		return (String) single("SELECT year FROM leave_balance WHERE employee_id = " + id).get("year");
	}

	private static String hireDateFor(String employeeCode) throws Exception {
		return (String) single("SELECT hire_date FROM employees WHERE employee_code = '" + employeeCode + "'")
				.get("hire_date");
	}

	private String mobileFlagFor(String code, String phone, Object flag) throws Exception {
		Map<String, Object> body = validBody(code, phone);
		body.put("is_mobile_attendance_enabled", flag);
		long id = ((Number) data(post(body, ADMIN_1)).get("id")).longValue();
		return (String) single("SELECT is_mobile_attendance_enabled FROM employees WHERE id = " + id)
				.get("is_mobile_attendance_enabled");
	}

	/** The minimum PHP accepts, plus the branch this fixture defaults to. */
	private static Map<String, Object> validBody(String employeeCode, String phone) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("first_name", "Nour");
		body.put("last_name", "Adel");
		body.put("employee_code", employeeCode);
		body.put("shift_id", SHIFT);
		body.put("expected_daily_hours", 8);
		body.put("branch_id", BRANCH_DEFAULT);
		if (phone != null) {
			body.put("phone", phone);
			body.put("country_code", "+20");
		}
		return body;
	}

	private ResponseEntity<Map<String, Object>> post(Map<String, Object> body, long employeeId) {
		Map<String, Object> payload = new HashMap<>(body);
		if (employeeId == ADMIN_2) {
			payload.putIfAbsent("branch_id", BRANCH_OTHER_COMPANY);
			payload.put("branch_id", BRANCH_OTHER_COMPANY);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + CREATE), HttpMethod.POST,
				new HttpEntity<>(payload, jsonHeaders(tokenFor(employeeId))),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		return (Map<String, Object>) response.getBody().get("data");
	}

	private static String message(ResponseEntity<Map<String, Object>> response, int expectedStatus) {
		assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		return (String) response.getBody().get("message");
	}

	private String tokenFor(long employeeId) {
		long companyId = employeeId == ADMIN_2 ? COMPANY_2 : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session",
				Map.of("role", "company_admin", "token_version", 1L));
	}

	private static HttpHeaders jsonHeaders(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return headers;
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
					  (19601, 'Create Co 1', '+201000019601', 'active', '2025-01-15 09:00:00'),
					  (19602, 'Create Co 2', '+201000019602', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (19611, 19601, 'Default Branch', 1, '2025-03-01 10:00:00'),
					  (19612, 19601, 'Second Branch', 1, '2025-03-01 10:00:00'),
					  (19613, 19601, 'Inactive Branch', 0, '2025-03-01 10:00:00'),
					  (19621, 19602, 'Other Company Branch', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES
					  (19641, 19601, 'Linked Department', 1, '2025-04-10 10:00:00'),
					  (19642, 19601, 'Unlinked Department', 1, '2025-04-10 10:00:00'),
					  (19643, 19602, 'Other Company Department', 1, '2025-04-10 10:00:00')
					""");
			// Only the linked department has a junction row for the default branch.
			st.execute("""
					INSERT INTO department_branches (department_id, branch_id) VALUES
					  (19641, 19611), (19643, 19621)
					""");
			st.execute("""
					INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at) VALUES
					  (19651, 19601, 19641, 'Agent', 1, '2025-04-11 10:00:00'),
					  (19652, 19601, 19642, 'Other Department Agent', 1, '2025-04-11 10:00:00'),
					  (19653, 19602, 19643, 'Other Company Agent', 1, '2025-04-11 10:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES
					  (19661, 19601, 'Morning', '09:00:00', '17:00:00', '2025-04-12 10:00:00'),
					  (19662, 19602, 'Other Company Shift', '09:00:00', '17:00:00', '2025-04-12 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, employee_code, first_name, last_name, phone, country_code,
					   password_hash, token_version, role, is_active, join_request_status, created_at)
					VALUES
					  (196011, 19601, 19611, '1001', 'Create', 'Admin', '+201000196011', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'company_admin', 1, 'accepted', '2025-05-01 09:00:00'),
					  (196021, 19602, 19621, '2001', 'Other', 'Admin', '+201000196021', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'company_admin', 1, 'accepted', '2025-05-01 09:00:00'),
					  (196012, 19601, 19611, '1002', 'Rejected', 'Applicant', '01099990000', '+20',
					   '$2y$10$abcdefghijklmnopqrstuv', 1, 'employee', 1, 'rejected', '2025-05-01 09:00:00')
					""");
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream =
				LegacyEmployeeCreateEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
