package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
import com.workin.backend.platformadmin.mfa.Totp;

/**
 * {@code /admin/employees} over real HTTP against a real MariaDB.
 *
 * <p>The page behind <b>R-053</b>. Legacy's POST block has no tenant check at
 * all, and the four actions it gates are not equally harmless: {@code
 * save_edit} can write {@code password_hash}, the column
 * {@code login_employee.php} verifies, and {@code delete} is a hard delete
 * fourteen tables cascade from. Most of what is asserted here is the guard
 * legacy does not have.
 *
 * <p>The rest is parity detail that is easy to get wrong and invisible if you
 * do: a last name is not required but a first name is, a code must be digits
 * and unique within its company only, a password with no phone is dropped on
 * create but kept on edit, a new employee opens with a 21-day leave balance,
 * and the stored phone is not the string the validator approved.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminEmployeesEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
		} catch (Exception ex) {
			throw new IllegalStateException("could not apply the legacy schema", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.mfa.encryption-key", () -> {
			byte[] key = new byte[32];
			new java.security.SecureRandom().nextBytes(key);
			return java.util.Base64.getEncoder().encodeToString(key);
		});
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private PlatformAdminMfaService mfaService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private String cookie;

	private long companyA;

	private long companyB;

	private long branchA;

	private long departmentA;

	private long jobTitleA;

	private long branchB;

	private long departmentB;

	private long jobTitleB;

	private long shiftA;

	private long shiftB;



	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		// Children before parents, and every employee rather than only the
		// seeded ones: rows this page creates through the form get ordinary
		// auto-increment ids, so an "id > 990000" sweep leaves them behind to
		// block the branch delete.
		this.jdbc.update("DELETE FROM employee_shift_assignments");
		this.jdbc.update("DELETE FROM salary_contracts");
		this.jdbc.update("DELETE FROM leave_balance");
		this.jdbc.update("DELETE FROM employees");
		this.jdbc.update("DELETE FROM shifts");
		this.jdbc.update("DELETE FROM job_titles");
		this.jdbc.update("DELETE FROM departments");
		this.jdbc.update("DELETE FROM branches");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2101" + System.nanoTime() % 100_000_000L;
		this.jdbc.update("INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, 1)",
				phone, this.passwordEncoder.encode(PASSWORD));
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = ?", Long.class, phone);

		String token = this.mfaService.issueBootstrapToken(adminId, adminId);
		String seed = this.mfaService.beginEnrolment(adminId, token).orElseThrow();
		assertThat(this.mfaService.confirmEnrolment(adminId, code(seed))).isTrue();

		Page login = page("/admin/login", null);
		String pending = cookieOf(post("/admin/login", login.cookie(), login.csrf(),
				"phone", phone, "password", PASSWORD));
		this.jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL"
				+ " WHERE platform_admin_id = ?", adminId);
		this.cookie = cookieOf(post("/admin/mfa", pending,
				page("/admin/mfa", pending).csrf(), "code", code(seed)));

		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");
		this.shiftA = createShift(this.companyA, "Alpha Day", true);
		this.shiftB = createShift(this.companyB, "Beta Day", true);
		this.branchA = createBranch(this.companyA, "Alpha HQ", true);
		this.departmentA = createDepartment(this.companyA, "Alpha Ops", true);
		this.jobTitleA = createJobTitle(this.companyA, "Alpha Fitter", true);
		this.branchB = createBranch(this.companyB, "Beta HQ", true);
		this.departmentB = createDepartment(this.companyB, "Beta Ops", true);
		this.jobTitleB = createJobTitle(this.companyB, "Beta Fitter", true);


	}



	@Test
	void theListShowsAnEmployeeWithItsOrgRowsAndLatestShiftAndSalary() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET branch_id = ?, department_id = ?, job_title_id = ?"
				+ " WHERE id = ?", this.branchA, this.departmentA, this.jobTitleA, id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		this.jdbc.update("INSERT INTO salary_contracts (employee_id, basic_salary,"
				+ " effective_from) VALUES (?, 5000, '2026-01-01')", id);

		String html = body("/admin/employees");
		assertThat(html).contains("1001", "Aya", "Alpha HQ", "Alpha Ops", "Alpha Fitter",
				"Alpha Day");
	}

	@Test
	void onlyTheLatestShiftAndContractAreShown() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long later = createShift(this.companyA, "Alpha Night", true);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-06-01')", id, later);

		String html = body("/admin/employees");
		assertThat(html).contains("Alpha Night");
		assertThat(html).as("the superseded assignment is not shown").doesNotContain("Alpha Day");
	}

	@Test
	void aPendingJoinRequestIsNotAnEmployeeYet() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET join_request_status = 'pending' WHERE id = ?", id);

		assertThat(body("/admin/employees"))
				.as("legacy filters these out on every path")
				.doesNotContain("1001");
	}

	@Test
	void theSearchMatchesNamesCodePhoneAndTheIdAsText() {
		long id = seedEmployee(this.companyA, "7788", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET phone = '01012345678' WHERE id = ?", id);

		for (String term : List.of("Aya", "Alpha", "Aya Alpha", "7788", "0101234", String.valueOf(id))) {
			assertThat(body("/admin/employees?search=" + term))
					.as("search '%s' should find the employee", term)
					.contains("7788");
		}
		assertThat(body("/admin/employees?search=Zulu")).doesNotContain("7788");
	}

	@Test
	void everyFilterIsOptionalAndAnAbsentOneNeverBreaksThePage() {
		seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		for (String query : List.of(
				"", "?search=", "?filter=", "?filter_branch=", "?filter_department=",
				"?filter_job_title=", "?date_from=&date_to=", "?company_id=",
				"?search=&filter=&filter_branch=&filter_department=&filter_job_title="
						+ "&date_from=&date_to=&company_id=")) {
			assertThat(get("/admin/employees" + query, this.cookie).getStatusCode())
					.as("no filters should render, query '%s'", query)
					.isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void theStatusFilterSelectsActiveOrInactive() {
		long active = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long gone = seedEmployee(this.companyA, "1002", "Basim", "Alpha");
		this.jdbc.update("UPDATE employees SET is_active = 0 WHERE id = ?", gone);

		assertThat(body("/admin/employees?filter=active")).contains("1001").doesNotContain("1002");
		assertThat(body("/admin/employees?filter=inactive")).contains("1002").doesNotContain("1001");
		assertThat(body("/admin/employees?filter=all")).contains("1001", "1002");
		assertThat(active).isPositive();
	}

	@Test
	void theHireDateRangeFallsBackToTheCreatedDate() {
		long dated = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET hire_date = '2020-06-15' WHERE id = ?", dated);
		long undated = seedEmployee(this.companyA, "1002", "Basim", "Alpha");
		this.jdbc.update("UPDATE employees SET hire_date = NULL, created_at = '2024-03-01'"
				+ " WHERE id = ?", undated);

		assertThat(body("/admin/employees?date_from=2020-01-01&date_to=2020-12-31"))
				.contains("1001").doesNotContain("1002");
		assertThat(body("/admin/employees?date_from=2024-01-01&date_to=2024-12-31"))
				.as("an employee with no hire date is ranged on created_at, not dropped")
				.contains("1002").doesNotContain("1001");
	}

	@Test
	void addingAnEmployeeOpensALeaveBalanceAndAShiftAssignment() {
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "last_name", "Alpha",
				"employee_code", "2001", "hire_date", "2026-02-01");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT * FROM employees WHERE employee_code = '2001'");
		long id = ((Number) row.get("id")).longValue();
		assertThat(((Number) row.get("company_id")).longValue()).isEqualTo(this.companyA);
		assertThat(row.get("role")).isEqualTo("employee");

		assertThat(this.jdbc.queryForObject(
				"SELECT total_days FROM leave_balance WHERE employee_id = " + id, Integer.class))
				.as("legacy opens every new employee with 21 days")
				.isEqualTo(21);
		assertThat(this.jdbc.queryForObject(
				"SELECT shift_id FROM employee_shift_assignments WHERE employee_id = " + id,
				Long.class))
				.isEqualTo(this.shiftA);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM salary_contracts WHERE employee_id = " + id, Integer.class))
				.as("and no contract, because no salary was given")
				.isZero();
	}

	@Test
	void aSalaryContractIsWrittenOnlyWhenABasicSalaryIsGiven() {
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"hire_date", "2026-02-01", "basic_salary", "7500.50", "transport", "300");

		Map<String, Object> contract = this.jdbc.queryForMap(
				"SELECT sc.* FROM salary_contracts sc JOIN employees e ON e.id = sc.employee_id"
						+ " WHERE e.employee_code = '2001'");
		assertThat(((java.math.BigDecimal) contract.get("basic_salary")).doubleValue())
				.isEqualTo(7500.50);
		assertThat(((java.math.BigDecimal) contract.get("transport_allowance")).doubleValue())
				.isEqualTo(300.0);
		assertThat(contract.get("effective_from")).asString().startsWith("2026-02-01");
	}

	@Test
	void aFirstNameIsRequiredButALastNameIsNot() {
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Solo", "employee_code", "2001");
		assertThat(employeeCount()).as("no last name is fine").isEqualTo(1);

		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "", "last_name", "Only", "employee_code", "2002");
		assertThat(employeeCount()).as("but no first name is not").isEqualTo(1);
	}

	@Test
	void aBranchIsRequiredBecauseTheColumnCannotBeNull() {
		// R-055. Legacy validates the company, the shift, the first name and
		// the code, and not this -- then writes null into a NOT NULL column,
		// which is error 1048 even under production's non-strict sql_mode.
		ResponseEntity<String> response = postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001");

		assertThat(response.getHeaders().getLocation()).asString().contains("error_required");
		assertThat(employeeCount()).isZero();
	}

	@Test
	void aShiftIsRequiredOnCreate() {
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"shift_id", "0", "first_name", "Nadia", "employee_code", "2001");

		assertThat(employeeCount()).isZero();
	}

	@Test
	void theEmployeeCodeMustBeDigitsAndUniqueWithinItsCompanyOnly() {
		seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		ResponseEntity<String> lettered = postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "A2001");
		assertThat(lettered.getHeaders().getLocation()).asString()
				.contains("employee_code_invalid");

		ResponseEntity<String> duplicate = postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "1001");
		// R-054: legacy flashes a key its own catalogue does not define.
		assertThat(duplicate.getHeaders().getLocation()).asString().contains("already_exists");
		assertThat(employeeCount()).isEqualTo(1);

		// The same code in another company is fine -- uniqueness is per company.
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyB),
				"branch_id", String.valueOf(this.branchB),
				"shift_id", String.valueOf(this.shiftB),
				"first_name", "Basma", "employee_code", "1001");
		assertThat(employeeCount()).isEqualTo(2);
	}

	@Test
	void aPhoneNeedsItsCountryCodeAndMustBeValidForThatCountry() {
		ResponseEntity<String> noCode = postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"phone", "01012345678", "country_code", "");
		assertThat(noCode.getHeaders().getLocation()).asString().contains("error_required");

		ResponseEntity<String> nonsense = postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"phone", "123", "country_code", "+20");
		assertThat(nonsense.getHeaders().getLocation()).asString().contains("error_invalid_phone");
		assertThat(employeeCount()).isZero();

		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"phone", "01012345678", "country_code", "+20");
		assertThat(employeeCount()).isEqualTo(1);
	}

	@Test
	void theStoredPhoneIsTheTypedDigitsNotTheNormalisedNumber() {
		// Legacy validates with a normaliser that repairs a missing leading
		// zero, then stores phone_digits_only() of what was typed. So the
		// number it accepted and the number it saved are different strings.
		// Reproduced deliberately: silently storing the better value is how two
		// systems stop agreeing about who a phone number belongs to.
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"phone", "1012345678", "country_code", "+20");

		assertThat(this.jdbc.queryForObject(
				"SELECT phone FROM employees WHERE employee_code = '2001'", String.class))
				.isEqualTo("1012345678");
	}

	@Test
	void aPasswordWithNoPhoneIsDroppedOnCreateButKeptOnEdit() {
		// Legacy's asymmetry: on create the hash is only computed when a phone
		// is present, because there would be no way to sign in with it. On edit
		// there is no such condition.
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"password", "hunter2 hunter2");
		assertThat(this.jdbc.queryForObject(
				"SELECT password_hash FROM employees WHERE employee_code = '2001'", String.class))
				.as("no phone on create, so no credential")
				.isNull();

		long id = this.jdbc.queryForObject(
				"SELECT id FROM employees WHERE employee_code = '2001'", Long.class);
		postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Nadia", "employee_code", "2001",
				"branch_id", String.valueOf(this.branchA),
				"password", "hunter2 hunter2");
		assertThat(this.jdbc.queryForObject(
				"SELECT password_hash FROM employees WHERE id = " + id, String.class))
				.as("but an edit sets one regardless")
				.isNotNull();
	}

	@Test
	void anEditWithNoPasswordKeepsTheExistingCredential() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET password_hash = 'existing-hash' WHERE id = ?", id);

		postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "1001", "password", "",
				"branch_id", String.valueOf(this.branchA));

		assertThat(this.jdbc.queryForObject(
				"SELECT password_hash FROM employees WHERE id = " + id, String.class))
				.isEqualTo("existing-hash");
	}

	@Test
	void anEditCannotTouchAnotherCompanysEmployee() {
		// R-053, and the reason it is High: this exact request, unguarded, sets
		// a working mobile-app credential on someone else's employee.
		long victim = seedEmployee(this.companyB, "9001", "Basma", "Beta");
		this.jdbc.update("UPDATE employees SET password_hash = 'victim-hash' WHERE id = ?", victim);

		get("/admin/employees?company_id=" + this.companyA, this.cookie);
		ResponseEntity<String> response = postForm("action", "save_edit",
				"id", String.valueOf(victim),
				"first_name", "Taken", "employee_code", "9001",
				"branch_id", String.valueOf(this.branchB),
				"password", "attacker-chosen-password");

		assertThat(response.getHeaders().getLocation()).asString().contains("error_db");
		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT first_name, password_hash FROM employees WHERE id = " + victim);
		assertThat(row.get("password_hash")).isEqualTo("victim-hash");
		assertThat(row.get("first_name")).isEqualTo("Basma");
	}

	@Test
	void deletingAnotherCompanysEmployeeIsRefused() {
		long victim = seedEmployee(this.companyB, "9001", "Basma", "Beta");

		get("/admin/employees?company_id=" + this.companyA, this.cookie);
		postForm("action", "delete", "id", String.valueOf(victim));

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE id = " + victim, Integer.class))
				.as("fourteen tables cascade from this delete")
				.isEqualTo(1);
	}

	@Test
	void deactivatingAndReactivatingAnotherCompanysEmployeeIsRefused() {
		long victim = seedEmployee(this.companyB, "9001", "Basma", "Beta");

		get("/admin/employees?company_id=" + this.companyA, this.cookie);
		postForm("action", "deactivate", "id", String.valueOf(victim));

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM employees WHERE id = " + victim, Integer.class))
				.isEqualTo(1);
	}

	@Test
	void anEditCannotPointAnEmployeeAtAnotherCompanysOrgRows() {
		// D-176, indirect half. Legacy validates none of these four.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "1001",
				"branch_id", String.valueOf(this.branchB),
				"department_id", String.valueOf(this.departmentB),
				"job_title_id", String.valueOf(this.jobTitleB),
				"shift_id", String.valueOf(this.shiftB));

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT branch_id, department_id, job_title_id FROM employees WHERE id = " + id);
		assertThat(((Number) row.get("branch_id")).longValue())
				.as("the whole edit is refused, so the branch is still the seeded one")
				.isEqualTo(this.branchA);
		assertThat(row.get("department_id")).isNull();
		assertThat(row.get("job_title_id")).isNull();
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id,
				Integer.class))
				.as("and no assignment to another tenant's shift was written")
				.isZero();
	}

	@Test
	void anEditMayRePointAnEmployeeWithinItsOwnCompany() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "1001",
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentA),
				"job_title_id", String.valueOf(this.jobTitleA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"hire_date", "2026-03-01");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT branch_id, department_id, job_title_id, company_id FROM employees"
						+ " WHERE id = " + id);
		assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(this.branchA);
		assertThat(((Number) row.get("department_id")).longValue()).isEqualTo(this.departmentA);
		assertThat(((Number) row.get("job_title_id")).longValue()).isEqualTo(this.jobTitleA);
		assertThat(((Number) row.get("company_id")).longValue())
				.as("and it stays where it was")
				.isEqualTo(this.companyA);
	}

	@Test
	void reSavingTheSameShiftAndDateAddsNoSecondAssignment() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		for (int i = 0; i < 2; i++) {
			postForm("action", "save_edit", "id", String.valueOf(id),
					"first_name", "Aya", "employee_code", "1001",
					"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
					"shift_effective_from", "2026-03-01");
		}

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id,
				Integer.class))
				.as("employee_sync_shift_assignment() is a no-op when nothing changed")
				.isEqualTo(1);
	}

	@Test
	void aContractDurationInYearsIsStoredAsMonths() {
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"contract_duration", "2", "contract_duration_unit", "years");
		assertThat(this.jdbc.queryForObject(
				"SELECT contract_duration_months FROM employees WHERE employee_code = '2001'",
				Integer.class))
				.isEqualTo(24);
	}

	@Test
	void aBlankOrNonPositiveContractDurationIsStoredAsNull() {
		for (String[] pair : List.of(
				new String[] {"3001", ""}, new String[] {"3002", "0"},
				new String[] {"3003", "-5"}, new String[] {"3004", "not a number"})) {
			postForm("action", "add_employee",
					"company_id", String.valueOf(this.companyA),
					"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
					"first_name", "Nadia", "employee_code", pair[0],
					"contract_duration", pair[1], "contract_duration_unit", "months");
			assertThat(this.jdbc.queryForObject(
					"SELECT contract_duration_months FROM employees WHERE employee_code = ?",
					Integer.class, pair[0]))
					.as("duration '%s' should store as null", pair[1])
					.isNull();
		}
	}

	@Test
	void theFormOffersOnlyTheEditedEmployeesOwnCompanysOptions() {
		// R-051. This page is one of the seven call sites that ships every
		// company's org structure to the browser in legacy.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		String html = get("/admin/employees?company_id=0&action=edit&id=" + id, this.cookie)
				.getBody();

		assertThat(html).contains("Alpha HQ", "Alpha Ops", "Alpha Fitter", "Alpha Day");
		assertThat(html)
				.doesNotContain("Beta HQ")
				.doesNotContain("Beta Ops")
				.doesNotContain("Beta Fitter")
				.doesNotContain("Beta Day");
	}

	@Test
	void everyWriteIsAudited() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		postForm("action", "deactivate", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events WHERE target_type = 'employee'",
				Integer.class))
				.isEqualTo(1);
	}

	@Test
	void anUnknownActionIsRefused() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		ResponseEntity<String> response = postForm("action", "drop_everything",
				"id", String.valueOf(id));

		assertThat(response.getHeaders().getLocation()).asString().contains("error_db");
		assertThat(employeeCount()).isEqualTo(1);
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/employees", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()),
				String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private ResponseEntity<String> postForm(String... fields) {
		return post("/admin/employees", this.cookie,
				page("/admin/employees?action=add", this.cookie).csrf(), fields);
	}

	private int employeeCount() {
		return this.jdbc.queryForObject("SELECT COUNT(*) FROM employees", Integer.class);
	}

	private long seedEmployee(long companyId, String code, String first, String last) {
		long id = this.jdbc.queryForObject(
				"SELECT GREATEST(COALESCE(MAX(id), 0) + 1, 990001) FROM employees", Long.class);
		this.jdbc.update("INSERT INTO employees (id, company_id, branch_id, employee_code,"
				+ " first_name, last_name, role, is_active, is_mobile_attendance_enabled,"
				+ " can_check_in_any_branch, join_request_status, token_version, created_at,"
				+ " updated_at) VALUES (?, ?, ?, ?, ?, ?, 'employee', 1, 1, 0, 'accepted', 1,"
				+ " NOW(), NOW())",
				id, companyId, companyId == this.companyA ? this.branchA : this.branchB,
				code, first, last);
		return id;
	}

	private long createBranch(long companyId, String name, boolean active) {
		this.jdbc.update("INSERT INTO branches (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, ?, NOW())", companyId, name, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE name = ?", Long.class, name);
	}

	private long createDepartment(long companyId, String name, boolean active) {
		this.jdbc.update("INSERT INTO departments (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, ?, NOW())", companyId, name, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE name = ?", Long.class, name);
	}

	private long createJobTitle(long companyId, String name, boolean active) {
		this.jdbc.update("INSERT INTO job_titles (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, ?, NOW())", companyId, name, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM job_titles WHERE name = ?", Long.class, name);
	}

	private long createShift(long companyId, String name, boolean active) {
		this.jdbc.update("INSERT INTO shifts (company_id, name, start_time, end_time, is_active,"
				+ " created_at) VALUES (?, ?, '09:00:00', '17:00:00', ?, NOW())",
				companyId, name, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM shifts WHERE name = ?", Long.class, name);
	}
	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private long createCompany(String name) {
		String phone = "01" + System.nanoTime() % 1_000_000_000L;
		this.jdbc.update("INSERT INTO companies (company_name, phone, password_hash, status,"
				+ " otp_verified, profile_completed, created_at)"
				+ " VALUES (?, ?, ?, 'active', 1, 1, NOW())",
				name, phone, this.passwordEncoder.encode(PASSWORD));
		return this.jdbc.queryForObject("SELECT id FROM companies WHERE phone = ?", Long.class, phone);
	}

	private String body(String path) {
		return get(path, this.cookie).getBody();
	}

	private ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private Page page(String path, String sessionCookie) {
		ResponseEntity<String> response = get(path, sessionCookie);
		String resolved = sessionCookie != null ? sessionCookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	private ResponseEntity<String> post(String path, String sessionCookie, Csrf csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int index = 0; index < fields.length; index += 2) {
			form.add(fields[index], fields[index + 1]);
		}
		form.add(csrf.name(), csrf.value());
		return this.restTemplate.exchange(path, HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class);
	}

	private static Csrf csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new Csrf(matcher.group(1), matcher.group(2));
	}

	private static String code(String base32Seed) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(Instant.now()));
	}

	private static byte[] fromBase32(String seed) {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char character : seed.toCharArray()) {
			int value = alphabet.indexOf(character);
			if (value < 0) {
				continue;
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xFF);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}

	private static String cookieOf(ResponseEntity<String> response) {
		String value = tryCookieOf(response);
		assertThat(value).as("expected a session cookie").isNotNull();
		return value;
	}

	private static String tryCookieOf(ResponseEntity<String> response) {
		List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		if (cookies == null) {
			return null;
		}
		return cookies.stream()
				.filter(value -> value.startsWith("WORKIN_ADMIN_SESSION="))
				.map(header -> {
					int start = header.indexOf('=') + 1;
					int end = header.indexOf(';', start);
					return end < 0 ? header.substring(start) : header.substring(start, end);
				})
				.findFirst().orElse(null);
	}

	private static void applySchema(String resource) throws Exception {
		String sql = new String(AdminEmployeesEndToEndTest.class.getClassLoader()
				.getResourceAsStream(resource).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute("SET SESSION sql_mode = ''");
			for (String piece : sql.split(";\\R")) {
				if (!piece.isBlank()) {
					statement.execute(piece);
				}
			}
		}
	}

}
