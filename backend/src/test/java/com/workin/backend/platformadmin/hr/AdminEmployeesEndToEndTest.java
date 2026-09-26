package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

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
class AdminEmployeesEndToEndTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.password", () -> PASSWORD);
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;


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
		this.jdbc.update("DELETE FROM department_branches");
		this.jdbc.update("DELETE FROM departments");
		this.jdbc.update("DELETE FROM branches");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");
		// seedCountries()' rows. This database has no other country, so a leftover one would
		// become the dial code every other code resolves to, and +20 would stop being Egypt.
		this.jdbc.update("DELETE FROM phone_countries WHERE country_code IN ('+881', '+882')");

		String phone = "+2101" + System.nanoTime() % 100_000_000L;
		// One administrator, one password (ADR-0018): the bootstrap provisioned
		// the row from the configured password when the context started.
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));

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

	/**
	 * org_filter_cascade_form_attrs() (org_helper.php:450-466): the toolbar form carries, for
	 * org-filter-cascade.js, every company's active branches, each department under the branches
	 * it is linked to and under its company, each job title under its department, and the
	 * filtered branch, department and job title to keep -- every company's while the list is
	 * filtered to one, because the company select changes without a request.
	 */
	@Test
	void theToolbarCarriesEveryCompanysOrgRowsForTheFilterCascade() {
		long quoted = createBranch(this.companyA, "Alpha \"North\" <2>", true);
		createBranch(this.companyA, "Alpha Closed", false);
		this.jdbc.update("INSERT INTO department_branches (department_id, branch_id) VALUES (?, ?)",
				this.departmentA, quoted);
		this.jdbc.update("UPDATE job_titles SET department_id = ? WHERE id = ?", this.departmentA, this.jobTitleA);

		String html = body("/admin/employees?company_id=" + this.companyA + "&filter_branch=" + quoted
				+ "&filter_department=" + this.departmentA + "&filter_job_title=" + this.jobTitleA);
		Matcher toolbar = TOOLBAR_FORM.matcher(html);
		assertThat(toolbar.find()).as("the toolbar's filter form").isTrue();
		String form = toolbar.group(1);

		assertThat(hasAttribute(form, "data-org-filters")).isTrue();
		assertThat(attribute(form, "data-branches-by-company")).isEqualTo("{\"" + this.companyA + "\":["
				+ "{\"id\":" + quoted + ",\"name\":\"Alpha \\\"North\\\" <2>\"},"
				+ "{\"id\":" + this.branchA + ",\"name\":\"Alpha HQ\"}],"
				+ "\"" + this.companyB + "\":[{\"id\":" + this.branchB + ",\"name\":\"Beta HQ\"}]}");
		assertThat(attribute(form, "data-departments-by-company")).isEqualTo("{\"" + this.companyA + "\":["
				+ "{\"id\":" + this.departmentA + ",\"name\":\"Alpha Ops\"}],"
				+ "\"" + this.companyB + "\":[{\"id\":" + this.departmentB + ",\"name\":\"Beta Ops\"}]}");
		assertThat(attribute(form, "data-departments-by-branch")).isEqualTo("{\"" + quoted + "\":["
				+ "{\"id\":" + this.departmentA + ",\"name\":\"Alpha Ops\"}]}");
		assertThat(attribute(form, "data-job-titles-by-dept")).isEqualTo("{\"" + this.departmentA + "\":["
				+ "{\"id\":" + this.jobTitleA + ",\"name\":\"Alpha Fitter\"}]}");
		assertThat(attribute(form, "data-selected-branch")).isEqualTo(String.valueOf(quoted));
		assertThat(attribute(form, "data-selected-department")).isEqualTo(String.valueOf(this.departmentA));
		assertThat(attribute(form, "data-selected-job-title")).isEqualTo(String.valueOf(this.jobTitleA));
		assertThat(attribute(form, "data-filter-all")).isEqualTo("الكل");

		assertThat(html)
				.contains("<select id=\"emp_branch_f\" name=\"filter_branch\" data-filter-branch>")
				.contains("<select id=\"emp_dept_f\" name=\"filter_department\" data-filter-department>")
				.contains("<select id=\"emp_job_f\" name=\"filter_job_title\" data-filter-job-title>")
				.contains("<script src=\"/admin/_assets/org-filter-cascade.js\"></script>");
	}

	@Test
	void theToolbarsFilterIdsArePhpsIntCast() {
		// (int) $_GET['filter_job_title']: "<id>e0" is the id.
		String html = body("/admin/employees?company_id=" + this.companyA + "&filter_job_title=" + this.jobTitleA + "e0");
		Matcher toolbar = TOOLBAR_FORM.matcher(html);
		assertThat(toolbar.find()).as("the toolbar's filter form").isTrue();
		assertThat(attribute(toolbar.group(1), "data-selected-job-title")).isEqualTo(String.valueOf(this.jobTitleA));
	}

	@Test
	void eachEmployeeRowLinksToItsDetailPageBeforeEdit() {
		// employee_helper.php:666-667: the row menu opens with Details, then Edit.
		// The detail page existed with no way to reach it from the list.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		String html = body("/admin/employees?company_id=" + this.companyA);
		int start = html.indexOf("id=\"row-actions-menu-" + id + "\"");
		assertThat(start).as("the row menu for employee %s", id).isPositive();
		String menu = html.substring(start, html.indexOf("</div>", start));

		int details = menu.indexOf("href=\"/admin/employee_detail?id=" + id + "\"");
		int edit = menu.indexOf("href=\"/admin/employees?action=edit&amp;id=" + id + "\"");
		if (edit < 0) {
			edit = menu.indexOf("href=\"/admin/employees?action=edit&id=" + id + "\"");
		}
		assertThat(details).as("the menu links to this employee's detail page").isPositive();
		assertThat(edit).as("the menu still offers Edit").isPositive();
		assertThat(details).as("Details comes before Edit, as in legacy").isLessThan(edit);
	}

	@Test
	void anEmployeeWithABlankNameDrawsLegacysEInItsAvatar() {
		// employee_table_avatar_html() (employee_helper.php:506): the initials of
		// dashboard_employee_display_name($row, 'E').
		long id = seedEmployee(this.companyA, "1002", "", "");

		String html = body("/admin/employees?company_id=" + this.companyA);
		int menu = html.indexOf("id=\"row-actions-menu-" + id + "\"");
		assertThat(menu).as("the row for employee %s", id).isPositive();
		assertThat(html.substring(html.lastIndexOf("<tr", menu), menu))
				.contains("<span class=\"emp-tbl-avatar\" aria-hidden=\"true\">E</span>");
	}

	@Test
	void anEmployeeWithABlankNameReadsAsLegacysDashInItsNameCell() {
		// page.php:343: the name through dashboard_employee_display_name(), whose own fallback
		// is an em dash; only the avatar beside it passes 'E'.
		long blank = seedEmployee(this.companyA, "1002", "", "");
		long named = seedEmployee(this.companyA, "1003", "Aya", "Alpha");

		String html = body("/admin/employees?company_id=" + this.companyA);
		assertThat(row(html, blank)).contains("<div class=\"bold\">—</div>");
		assertThat(row(html, named)).contains("<div class=\"bold\">Aya Alpha</div>");
	}

	@Test
	void theEditWindowIsTitledEditEmployeeAndTheName() {
		// page.php:399: __('edit_employee') . ': ' . dashboard_employee_display_name($editEmp).
		// The row is SELECT e.*, so the helper trims each stored name, joins and trims the
		// pair, and gives an em dash when both are blank.
		long blank = seedEmployee(this.companyA, "1002", "", "");
		long firstOnly = seedEmployee(this.companyA, "1003", "Aya", "");
		long padded = seedEmployee(this.companyA, "1004", " Aya ", "Alpha");

		assertThat(body("/admin/employees?action=edit&id=" + blank)).contains("<h2>تعديل موظف: —</h2>");
		assertThat(body("/admin/employees?action=edit&id=" + firstOnly)).contains("<h2>تعديل موظف: Aya</h2>");
		assertThat(body("/admin/employees?action=edit&id=" + padded)).contains("<h2>تعديل موظف: Aya Alpha</h2>");
		assertThat(body("/admin/employees?action=add")).as("the add window keeps its own title")
				.contains("<h2>إضافة موظف</h2>");
	}

	@Test
	void theListRendersAnEmployeeWhoHasAContractDuration() {
		// Every other fixture here leaves contract_duration_months NULL, and
		// that is why the whole page answered 500 against real data without
		// one test failing: the column is int(10) unsigned, whose range does
		// not fit a signed int, so MariaDB Connector/J boxes it as a Long and
		// the row mapper's cast to Integer threw. 1,448 of the development
		// seed's 3,783 employees carry a value.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update(
				"UPDATE employees SET contract_duration_months = 12 WHERE id = ?", id);

		assertThat(body("/admin/employees")).contains("1001", "Aya");
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

		// hr-legacy 505004f deleted the dbInsert that opened a 21-day balance
		// here, so a new employee starts with none.
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM leave_balance WHERE employee_id = " + id, Integer.class))
				.as("creation no longer opens a leave balance")
				.isZero();
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
	void theStoredPhoneIsTheNumbersNationalFormAndItsOwnDialCode() {
		// Legacy validated with a normaliser that repairs a missing leading
		// zero, then stored phone_digits_only() of what was typed -- the number
		// it accepted and the number it saved were different strings. Every
		// Java write now stores the number's national form beside its own dial
		// code (D-291, ADR-0020), which is the spelling PHP's own clients show.
		postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"phone", "1012345678", "country_code", "+20");

		assertThat(this.jdbc.queryForObject(
				"SELECT CONCAT(phone, '|', country_code) FROM employees WHERE employee_code = '2001'", String.class))
				.isEqualTo("01012345678|+20");
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
	void anotherEmployeesNumberInAnotherSpellingIsRefusedOnCreateAndEdit() {
		// Every uniqueness check compares the canonical number (D-291): global,
		// with a rejected join request not reserving the number.
		long holder = seedEmployee(this.companyB, "9101", "Hala", "Holder");
		this.jdbc.update("UPDATE employees SET phone = '1012345678', country_code = '+20' WHERE id = ?", holder);

		ResponseEntity<String> created = postForm("action", "add_employee",
				"company_id", String.valueOf(this.companyA),
				"branch_id", String.valueOf(this.branchA),
				"shift_id", String.valueOf(this.shiftA),
				"first_name", "Nadia", "employee_code", "2001",
				"phone", "+20 10 1234 5678", "country_code", "+20");
		assertThat(created.getHeaders().getLocation()).asString().contains("phone_exists");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE employee_code = '2001'", Integer.class)).isZero();

		long editing = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		ResponseEntity<String> edited = postForm("action", "save_edit", "id", String.valueOf(editing),
				"first_name", "Aya", "employee_code", "1001", "branch_id", String.valueOf(this.branchA),
				"phone", "01012345678", "country_code", "+20");
		assertThat(edited.getHeaders().getLocation()).asString().contains("phone_exists");
		assertThat(this.jdbc.queryForObject(
				"SELECT phone FROM employees WHERE id = " + editing, String.class)).isNotEqualTo("01012345678");

		// A rejected applicant does not hold the number.
		this.jdbc.update("UPDATE employees SET join_request_status = 'rejected' WHERE id = ?", holder);
		this.jdbc.update("UPDATE employees SET phone = '201012345678' WHERE id = ?", holder);
		postForm("action", "save_edit", "id", String.valueOf(editing),
				"first_name", "Aya", "employee_code", "1001", "branch_id", String.valueOf(this.branchA),
				"phone", "01012345678", "country_code", "+20");
		assertThat(this.jdbc.queryForObject(
				"SELECT phone FROM employees WHERE id = " + editing, String.class)).isEqualTo("01012345678");
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
	void deactivatingAnEmployeeEndsTheSessionTheirAppHolds() {
		// D-289: the app's guard compares token_version, so a deactivation that
		// leaves it alone leaves the employee's token working.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		get("/admin/employees?company_id=" + this.companyA, this.cookie);
		postForm("action", "deactivate", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForObject(
				"SELECT CONCAT(is_active, '/', token_version) FROM employees WHERE id = " + id, String.class))
				.isEqualTo("0/2");

		postForm("action", "reactivate", "id", String.valueOf(id));
		assertThat(this.jdbc.queryForObject(
				"SELECT CONCAT(is_active, '/', token_version) FROM employees WHERE id = " + id, String.class))
				.as("reactivating ends nothing: the old token is already dead")
				.isEqualTo("1/2");
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
	void anEditHoldingAnotherCompanysDepartmentIsRefusedWhileItStillPostsIt() {
		// D-250. Legacy's unguarded save_edit (R-053) can leave an employee pointing at
		// another company's department, and the copied script keeps it selected and posts
		// it. With everything else the employee's own, only the department check can
		// refuse it: the kept id passes as the employee's current row, but is still held to
		// the company.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET department_id = ? WHERE id = ?", this.departmentB, id);

		ResponseEntity<String> response = postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "1001",
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(this.departmentB),
				"shift_id", String.valueOf(this.shiftA));

		assertThat(response.getHeaders().getLocation()).asString().contains("error_required");
		assertThat(this.jdbc.queryForObject("SELECT department_id FROM employees WHERE id = " + id, Long.class))
				.as("refused, so the stored department is left as it was").isEqualTo(this.departmentB);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id, Integer.class))
				.as("and nothing else of the edit was written").isZero();
	}

	@Test
	void anEditHoldingAnotherCompanysJobTitleIsRefusedWhileItStillPostsIt() {
		// The same for a job title: everything else the employee's own, so only the job
		// title check can refuse it.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET job_title_id = ? WHERE id = ?", this.jobTitleB, id);

		ResponseEntity<String> response = postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "1001",
				"branch_id", String.valueOf(this.branchA),
				"job_title_id", String.valueOf(this.jobTitleB),
				"shift_id", String.valueOf(this.shiftA));

		assertThat(response.getHeaders().getLocation()).asString().contains("error_required");
		assertThat(this.jdbc.queryForObject("SELECT job_title_id FROM employees WHERE id = " + id, Long.class))
				.as("refused, so the stored job title is left as it was").isEqualTo(this.jobTitleB);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id, Integer.class))
				.as("and nothing else of the edit was written").isZero();
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
		// Except the toolbar's filter cascade, which carries every company's rows for an
		// administrator, whose reach they are, because its company select changes them without
		// a request (D-260). Nothing else on the page may name another company's.
		Matcher toolbar = TOOLBAR_FORM.matcher(html);
		assertThat(toolbar.find()).as("the toolbar's filter form").isTrue();
		assertThat(toolbar.group(1)).contains("Beta HQ");
		String page = html.substring(0, toolbar.start()) + html.substring(toolbar.end());

		assertThat(page).contains("Alpha HQ", "Alpha Ops", "Alpha Fitter", "Alpha Day");
		assertThat(page)
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

	/**
	 * A row action returns to the page and filters the administrator was on
	 * (#347). Legacy's bare redirect was harmless while the list was one page;
	 * paged, it landed a refusal on page 3 back on page 1, away from the row.
	 */
	@Test
	void aRowActionReturnsToThePageAndFiltersItCameFrom() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		String list = "http://localhost/admin/employees?company_id=" + this.companyA
				+ "&page=3&per_page=50&search=Aya";

		assertThat(postFrom(list, "action", "deactivate", "id", String.valueOf(id))
				.getHeaders().getLocation()).asString()
				.as("the same list, page and filters -- the row's own company")
				.endsWith("/admin/employees?company_id=" + this.companyA + "&page=3&per_page=50&search=Aya");

		assertThat(postFrom(list, "action", "drop_everything", "id", String.valueOf(id))
				.getHeaders().getLocation()).asString()
				.as("a refusal keeps them too, with its own message")
				.endsWith("?company_id=" + this.companyA + "&page=3&per_page=50&search=Aya&error=error_db");

		String otherCompany = "http://localhost/admin/employees?company_id=" + this.companyB + "&page=3&search=Aya";
		assertThat(postFrom(otherCompany, "action", "reactivate", "id", String.valueOf(id))
				.getHeaders().getLocation()).asString()
				.as("a filter the write left is dropped with its page, so the row just written is on "
						+ "screen under rememberAfterWrite's filter")
				.endsWith("/admin/employees?search=Aya");

		assertThat(postFrom("http://localhost/admin/branches?page=4", "action", "deactivate",
				"id", String.valueOf(id)).getHeaders().getLocation()).asString()
				.as("another page's query is never carried")
				.endsWith("/admin/employees");
	}

	@Test
	void aWriteFromTheUnfilteredListStartsAtPageOneOfTheRowsCompany() {
		// The administrator's default view carries no company_id, so the URL
		// alone cannot say the write narrowed the list: the session filter
		// rememberAfterWrite replaced (0, all companies) is what does.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		assertThat(get("/admin/employees?company_id=0", this.cookie).getStatusCode()).isEqualTo(HttpStatus.OK);

		String unfiltered = "http://localhost/admin/employees?page=3&per_page=50&filter_branch=31&search=Aya";
		assertThat(postFrom(unfiltered, "action", "deactivate", "id", String.valueOf(id))
				.getHeaders().getLocation()).asString()
				.as("page 3 of every company is not page 3 of company A's; the branch was nobody's in "
						+ "particular; the size and the search are the administrator's own")
				.endsWith("/admin/employees?per_page=50&search=Aya");
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

	@Test
	void savingTheEditFormUnchangedLeavesTheEmployeeUnchanged() {
		// #212. save_edit writes every identity, contract and attendance column
		// from the post, so the form has to carry each one's stored value. The
		// form is read out of the rendered page and posted back as it is, which
		// is what a browser sends when nobody touches a field.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET phone = '01012345678', country_code = '+20',"
				+ " national_id = '29001011234567', birth_date = '1990-01-01', gender = 'female',"
				+ " address = '12 Nile Street', hire_date = '2020-06-15', department_id = ?,"
				+ " job_title_id = ?, contract_duration_months = 24,"
				+ " is_mobile_attendance_enabled = 1 WHERE id = ?",
				this.departmentA, this.jobTitleA, id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id))
				.as("saving the form unchanged changes nothing")
				.isEqualTo(before);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id,
				Integer.class))
				.as("the same shift from the same date adds no assignment")
				.isEqualTo(1);
	}

	@Test
	void anUnchangedSaveKeepsWhatIsNotStoredUnstored() {
		// The other half: a form must not invent a value either. PHP's gender
		// select has no "other", and its hire date falls back to today, so an
		// unchanged save there rewrites both; this one keeps them. Eighteen
		// months is not whole years, and attendance off must stay off.
		long id = seedEmployee(this.companyA, "1001", "Aya", "");
		this.jdbc.update("UPDATE employees SET gender = 'other', contract_duration_months = 18,"
				+ " is_mobile_attendance_enabled = 0 WHERE id = ?", id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
	}

	@Test
	void anUnchangedSaveKeepsOrgRowsDeactivatedAfterTheyWereSet() {
		// Legacy's lists hold active rows only, so its selects fall back to
		// "none": the save clears the department and job title, and refuses
		// outright for the branch, whose column cannot be null.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET department_id = ?, job_title_id = ? WHERE id = ?",
				this.departmentA, this.jobTitleA, id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		this.jdbc.update("UPDATE branches SET is_active = 0 WHERE id = ?", this.branchA);
		this.jdbc.update("UPDATE departments SET is_active = 0 WHERE id = ?", this.departmentA);
		this.jdbc.update("UPDATE job_titles SET is_active = 0 WHERE id = ?", this.jobTitleA);
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
	}

	@Test
	void aDeactivatedOrgRowIsKeptButNeverNewlyChosen() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long retired = createDepartment(this.companyA, "Alpha Retired", false);

		assertThat(body("/admin/employees?action=edit&id=" + id))
				.as("offered only to the employee who already has it")
				.doesNotContain("Alpha Retired");

		ResponseEntity<String> response = postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "1001",
				"branch_id", String.valueOf(this.branchA),
				"department_id", String.valueOf(retired));

		assertThat(response.getHeaders().getLocation()).asString().contains("error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT department_id FROM employees WHERE id = " + id, Long.class))
				.isNull();
	}

	@Test
	void anEmployeeWithNoCodeCanStillBeEditedAndKeepsHavingNone() {
		// The list shows the id for a blank code (CODE_SQL), and the form used
		// to post that id back as the code. Legacy's form requires a code, so
		// there such an employee cannot be saved without inventing one.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET employee_code = NULL WHERE id = ?", id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
	}

	@Test
	void anEditCannotBlankACodeTheEmployeeHas() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		ResponseEntity<String> response = postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "",
				"branch_id", String.valueOf(this.branchA));

		assertThat(response.getHeaders().getLocation()).asString().contains("error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT employee_code FROM employees WHERE id = " + id, String.class))
				.isEqualTo("1001");
	}

	@Test
	void anUnchangedSaveKeepsZeroDatesNoDateInputCanHold() {
		// Legacy data carries MariaDB's 0000-00-00, which a date input cannot
		// show: a browser submits it as empty. The development seed holds 68.
		// With a real hire date beside it, an empty shift date would also fall
		// back to that hire date and add an assignment.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		nonStrict("UPDATE employees SET birth_date = '0000-00-00', hire_date = '2020-06-15'"
				+ " WHERE id = " + id);
		nonStrict("INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from)"
				+ " VALUES (" + id + ", " + this.shiftA + ", '0000-00-00')");
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id,
				Integer.class))
				.as("and no assignment is added")
				.isEqualTo(1);
	}

	@Test
	void anUnchangedSaveKeepsAZeroHireDate() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		nonStrict("UPDATE employees SET hire_date = '0000-00-00' WHERE id = " + id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
	}

	@Test
	void anUnchangedSaveKeepsACodeTheDigitsRuleWouldRefuse() {
		// Every employee in the development seed has a code such as E000002.
		// The digits-only rule is for a code someone types, not one already stored.
		long id = seedEmployee(this.companyA, "E000002", "Aya", "Alpha");
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
	}

	@Test
	void aReplacedCodeIsStillHeldToTheDigitsRule() {
		long id = seedEmployee(this.companyA, "E000002", "Aya", "Alpha");

		ResponseEntity<String> response = postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "E000003",
				"branch_id", String.valueOf(this.branchA));

		assertThat(response.getHeaders().getLocation()).asString().contains("employee_code_invalid");
		assertThat(this.jdbc.queryForObject(
				"SELECT employee_code FROM employees WHERE id = " + id, String.class))
				.isEqualTo("E000002");
	}

	@Test
	void anUnchangedSaveKeepsAPhoneStoredWithNoCountryCode() {
		// R-019: join_company.php stores the phone and discards its dial code,
		// a pair the phone rule refuses.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET phone = '01012345678', country_code = NULL WHERE id = ?", id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
	}

	/**
	 * _employee_form.php's country select (D-261): an optional first choice, then the active
	 * countries labelled flag, name and code; the form carries legacy's invalid-phone message,
	 * and the page loads the rules and the two phone scripts. The list alone renders no form,
	 * so it loads none of them.
	 */
	@Test
	void theFormSelectsACountryFromTheActiveOnesAndLoadsLegacysPhoneChecks() {
		seedCountries();
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET phone = '0712345678', country_code = '+881' WHERE id = ?", id);

		String add = body("/admin/employees?company_id=" + this.companyA + "&action=add");
		String select = countrySelect(add);
		assertThat(select).as("optional, as legacy's is, and nothing chosen on an add")
				.containsPattern("^<select id=\"country_code\" name=\"country_code\">\\s*<option value=\"\">اختياري</option>")
				.doesNotContain("selected");
		assertThat(select).as("an active country, labelled as phone_country_option_label() labels it")
				.contains("<option value=\"+881\"").contains(">🏳 بلد تجريبي (+881)</option>");
		assertThat(select).as("a retired country").doesNotContain("+882");
		assertThat(formOf(add, "add_employee"))
				.contains("data-invalid-phone-msg=\"رقم الهاتف غير صالح لهذه الدولة\"")
				.as("an add has no stored pair to keep").doesNotContain("data-phone-keep-untouched");
		assertThat(add).containsSubsequence(
				"<script src=\"/admin/_assets/phone-countries-rules.js\" data-rules=\"",
				"&#34;+881&#34;:{&#34;phone_length&#34;:10,&#34;phone_prefixes&#34;:[&#34;07&#34;]}",
				"<script src=\"/admin/_assets/phone-validator.js\"></script>",
				"<script src=\"/admin/_assets/phone-form-bind.js\"></script>");

		String edit = body("/admin/employees?action=edit&id=" + id);
		assertThat(countrySelect(edit)).as("the stored country chosen")
				.containsPattern("<option value=\"\\+881\"\\s+selected>");
		assertThat(formOf(edit, "save_edit")).contains("data-phone-keep-untouched");

		assertThat(body("/admin/employees?company_id=" + this.companyA))
				.as("a list without its form loads no phone script").doesNotContain("phone-form-bind.js");
	}

	/**
	 * A code no active country has -- a retired country's -- is listed and chosen, so an
	 * unchanged save posts it and keeps the pair. Legacy's select drops it, and the save then
	 * asks for a country.
	 */
	@Test
	void anUnchangedSaveKeepsACountryCodeNoActiveCountryHas() {
		seedCountries();
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET phone = '0712345678', country_code = '+882' WHERE id = ?", id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		Map<String, Object> before = editableColumns(id);

		String edit = body("/admin/employees?action=edit&id=" + id);
		assertThat(countrySelect(edit)).containsPattern("<option value=\"\\+882\" selected>\\+882</option>");
		assertSaved(postFields(formFields(edit, "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
	}

	@Test
	void aReplacedPhoneIsStillCheckedForItsCountryCode() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("UPDATE employees SET phone = '01012345678', country_code = NULL WHERE id = ?", id);

		ResponseEntity<String> response = postForm("action", "save_edit", "id", String.valueOf(id),
				"first_name", "Aya", "employee_code", "1001",
				"branch_id", String.valueOf(this.branchA),
				"phone", "01099999999", "country_code", "");

		assertThat(response.getHeaders().getLocation()).asString().contains("error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT phone FROM employees WHERE id = " + id, String.class))
				.isEqualTo("01012345678");
	}

	@Test
	void anUnchangedSaveKeepsBlankAndPaddedTextAsStored() {
		// The save trims and turns blanks into NULL, so it would rewrite every
		// one of these while changing nothing anyone could see.
		long id = seedEmployee(this.companyA, "1001", " Aya ", "Alpha");
		nonStrict("UPDATE employees SET national_id = '', gender = '', address = '  12 Nile Street  ',"
				+ " phone = NULL, country_code = '+20' WHERE id = " + id);
		this.jdbc.update("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (?, ?, '2026-01-01')", id, this.shiftA);
		Map<String, Object> before = editableColumns(id);

		assertSaved(postFields(
				formFields(body("/admin/employees?action=edit&id=" + id), "save_edit")));

		assertThat(editableColumns(id)).isEqualTo(before);
	}

	@Test
	void aZeroDateIsKeptOnlyWhileItsInputIsLeftEmpty() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		nonStrict("UPDATE employees SET birth_date = '0000-00-00' WHERE id = " + id);
		BrowserForm form = formFields(body("/admin/employees?action=edit&id=" + id), "save_edit");
		form.fields().put("birth_date", "1990-01-01");

		assertSaved(postFields(form));

		assertThat(this.jdbc.queryForObject(
				"SELECT CAST(birth_date AS CHAR) FROM employees WHERE id = " + id, String.class))
				.isEqualTo("1990-01-01");
	}

	@Test
	void theAddFormHasMobileAttendanceOnByDefault() {
		// PHP's form ticks the box by default. A form without the box posts
		// nothing for it, and nothing reads as off.
		BrowserForm form = formFields(
				body("/admin/employees?company_id=" + this.companyA + "&action=add"), "add_employee");
		form.fields().put("first_name", "Nadia");
		form.fields().put("employee_code", "2001");
		form.fields().put("branch_id", String.valueOf(this.branchA));
		form.fields().put("shift_id", String.valueOf(this.shiftA));

		assertSaved(postFields(form));

		assertThat(this.jdbc.queryForObject(
				"SELECT is_mobile_attendance_enabled FROM employees WHERE employee_code = '2001'",
				Integer.class))
				.isEqualTo(1);
	}

	@Test
	void anUnfilteredAddAsksForTheCompanyInsteadOfPostingNone() {
		// #213: with no company filter the add form posted a hidden company_id of 0,
		// which the service refuses. _employee_form.php asks for the company instead.
		BrowserForm form = formFields(body("/admin/employees?company_id=0&action=add"), "add_employee");

		assertThat(form.fields()).as("no company is chosen for the administrator").containsEntry("company_id", "");
		assertThat(form.required()).as("and a browser will not submit the form without one").contains("company_id");

		form.fields().put("company_id", String.valueOf(this.companyB));
		form.fields().put("first_name", "Nadia");
		form.fields().put("employee_code", "2001");
		form.fields().put("branch_id", String.valueOf(this.branchB));
		form.fields().put("shift_id", String.valueOf(this.shiftB));
		assertSaved(postFields(form));
		assertThat(this.jdbc.queryForObject(
				"SELECT company_id FROM employees WHERE employee_code = '2001'", Long.class))
				.isEqualTo(this.companyB);
	}

	@Test
	void aBrowserWillNotSubmitAnAddWithoutAShift() {
		// Legacy's empty choice is value="", which required refuses. The port's was 0,
		// which required accepts: the post reached the service, was refused, and lost
		// everything typed into the form.
		for (String path : List.of("/admin/employees?company_id=0&action=add",
				"/admin/employees?company_id=" + this.companyA + "&action=add")) {
			BrowserForm form = formFields(body(path), "add_employee");
			assertThat(form.fields()).as(path).containsEntry("shift_id", "");
			assertThat(form.required()).as(path).contains("shift_id");
		}
	}

	@Test
	void anEditOfAnEmployeeWithNoShiftCanStillBeSubmittedUnchanged() {
		// The other side of the add's empty choice: an edit's is 0, so an employee who
		// has no shift assignment is not held back by the required shift select.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		BrowserForm form = formFields(body("/admin/employees?action=edit&id=" + id), "save_edit");

		assertThat(form.fields()).containsEntry("shift_id", "0");
		assertSaved(postFields(form));
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + id, Integer.class))
				.as("and no assignment is invented").isZero();
	}

	@Test
	void anUnfilteredAddCarriesEveryCompanysMapsAndShifts() {
		// With no filter the administrator's reach is every company (R-051), and the
		// shifts travel with the maps: legacy's form has none for a company chosen in it.
		String html = body("/admin/employees?company_id=0&action=add");
		String form = formOf(html, "add_employee");

		assertThat(form).contains("data-employee-form", "id=\"emp_company\"");
		assertThat(html.split("id=\"emp_company\"", -1))
				.as("one element on the page owns the id employee-form.js looks up").hasSize(2);
		assertThat(form).as("the hooks the two scripts find the selects by")
				.contains("data-emp-branch", "data-emp-department", "data-emp-job", "data-emp-shift");
		assertThat(html).contains("/admin/_assets/employee-form.js", "/admin/_assets/employee-shift.js");
		assertThat(names(map(form, "data-branches-by-company"), this.companyA)).containsExactly("Alpha HQ");
		assertThat(names(map(form, "data-branches-by-company"), this.companyB)).containsExactly("Beta HQ");
		assertThat(names(map(form, "data-departments-by-company"), this.companyB)).containsExactly("Beta Ops");
		assertThat(names(map(form, "data-job-titles-by-company"), this.companyB)).containsExactly("Beta Fitter");
		assertThat(names(map(form, "data-shifts-by-company"), this.companyA)).containsExactly("Alpha Day");
		assertThat(names(map(form, "data-shifts-by-company"), this.companyB)).containsExactly("Beta Day");
	}

	@Test
	void aFilteredAddCarriesOnlyThatCompanysMapsAndItsCompanyHidden() {
		String form = formOf(body("/admin/employees?company_id=" + this.companyA + "&action=add"), "add_employee");

		assertThat(form).doesNotContain("id=\"emp_company\"")
				.contains("<input type=\"hidden\" name=\"company_id\" value=\"" + this.companyA + "\">");
		for (String attribute : List.of("data-branches-by-company", "data-departments-by-company",
				"data-job-titles-by-company")) {
			assertThat(map(form, attribute).keySet()).as(attribute).containsExactly(String.valueOf(this.companyA));
		}
		assertThat(map(form, "data-shifts-by-company")).as("its shifts are the server-rendered options").isEmpty();
		assertThat(form).doesNotContain("Beta HQ", "Beta Ops", "Beta Fitter", "Beta Day");
	}

	@Test
	void anEditsMapsKeepTheEmployeesOwnRetiredRowsAndListItsDepartmentUnderItsBranch() {
		// D-250. employee-form.js lists only a branch's own departments and disables the
		// select when there are none, and a disabled select posts nothing. This
		// employee's department is linked to no branch, so with legacy's maps an
		// unchanged save would clear it; and a retired row missing from the maps would
		// show as its id.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long linked = createDepartment(this.companyA, "Alpha Sales", true);
		linkDepartment(linked, this.branchA);
		this.jdbc.update("UPDATE employees SET department_id = ?, job_title_id = ? WHERE id = ?",
				this.departmentA, this.jobTitleA, id);
		this.jdbc.update("UPDATE departments SET is_active = 0 WHERE id = ?", this.departmentA);
		this.jdbc.update("UPDATE job_titles SET is_active = 0 WHERE id = ?", this.jobTitleA);

		String form = formOf(body("/admin/employees?company_id=0&action=edit&id=" + id), "save_edit");

		assertThat(names(map(form, "data-departments-by-branch"), this.branchA))
				.containsExactly("Alpha Ops", "Alpha Sales");
		assertThat(names(map(form, "data-departments-by-company"), this.companyA))
				.containsExactly("Alpha Ops", "Alpha Sales");
		assertThat(names(map(form, "data-job-titles-by-company"), this.companyA)).containsExactly("Alpha Fitter");
		assertThat(form).contains("data-selected-company=\"" + this.companyA + "\"",
				"data-selected-branch=\"" + this.branchA + "\"",
				"data-selected-department=\"" + this.departmentA + "\"",
				"data-selected-job=\"" + this.jobTitleA + "\"",
				"data-selected-job-label=\"Alpha Fitter\"");

		linkDepartment(this.departmentA, this.branchA);
		assertThat(names(map(formOf(body("/admin/employees?company_id=0&action=edit&id=" + id), "save_edit"),
				"data-departments-by-branch"), this.branchA))
				.as("listed once when it is linked as well")
				.containsExactly("Alpha Ops", "Alpha Sales");
	}

	/**
	 * {@code _employee_form.php:54-287}: four titled sections, each a grid of
	 * rows, in legacy's own classes. {@code employee-form.css} and
	 * {@code org-form.css} are legacy's byte for byte and were styling nothing
	 * here, because the port rendered one flat column of {@code form-row}s with
	 * no sections, no titles and no grid.
	 */
	@Test
	void theWindowDrawsLegacysTitledSectionsAndItsCloseControl() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		String add = body("/admin/employees?company_id=" + this.companyA + "&action=add");
		String edit = body("/admin/employees?action=edit&id=" + id);

		for (String html : List.of(add, edit)) {
			assertThat(html).as("page.php:383,398: the window's own close")
					.contains("class=\"modal-close\"", "aria-label=\"إغلاق\"", "&#215;");
		}
		assertThat(sectionTitles(add)).containsExactly(
				"البيانات الشخصية", "بيانات الوظيفة", "حضور وانصراف الموبايل", "بيانات المرتب");
		assertThat(sectionTitles(edit)).as("legacy's edit never shows the salary section")
				.containsExactly("البيانات الشخصية", "بيانات الوظيفة", "حضور وانصراف الموبايل");

		String form = formOf(add, "add_employee");
		assertThat(form).contains("<form method=\"POST\" class=\"org-form-grid-wrap\"")
				.contains("<section class=\"emp-form-section org-form-span-2\">")
				.contains("<div class=\"org-form-grid\">")
				.as("the login block legacy groups the phone, country and password into")
				.contains("<div class=\"form-row org-form-span-2 emp-login-credentials\">",
						"<p class=\"form-hint emp-login-credentials__hint\">",
						"<div class=\"emp-login-credentials__grid\">",
						"<div class=\"emp-login-credentials__country\">",
						"<div class=\"emp-login-credentials__phone\">",
						"<div class=\"emp-login-credentials__password\">");
		assertThat(form).as("and legacy's span-2 rows, which the grid runs full width")
				.contains("<div class=\"form-row org-form-span-2\">")
				.contains("<div class=\"form-footer org-form-span-2\">");
		assertThat(formOf(edit, "save_edit"))
				.as("password_optional, which legacy adds on the edit path only")
				.contains("كلمة المرور (اتركها فارغة لعدم التغيير)");
		assertThat(form).doesNotContain("كلمة المرور (اتركها فارغة لعدم التغيير)");
	}

	/** Each {@code emp-form-section__title} on the page, in the order it renders. */
	private static List<String> sectionTitles(String html) {
		return Pattern.compile("<h3 class=\"emp-form-section__title\">([^<]*)</h3>")
				.matcher(html).results().map(match -> match.group(1)).toList();
	}

	/**
	 * {@code _employee_form.php:231-283}: five entitlements and five deductions,
	 * each labelled through {@code employee_field_requirement_label()} -- the
	 * basic salary mandatory and {@code required}, the nine others optional --
	 * and every one a number input stepping by a piastre from zero, opening at
	 * zero. The port rendered the basic salary alone, optional and empty, while
	 * its controller and store already read and wrote all ten.
	 */
	@Test
	void theAddWindowsSalarySectionIsLegacysTenComponents() {
		String add = body("/admin/employees?company_id=" + this.companyA + "&action=add");
		String form = formOf(add, "add_employee");

		assertThat(form).contains("<div class=\"org-form-grid emp-salary-grid\">",
				"<h4 class=\"emp-form-subtitle\">الاستحقاقات</h4>",
				"<h4 class=\"emp-form-subtitle\">الخصومات</h4>");
		assertThat(form).contains("<label for=\"basic_salary\">الراتب الأساسي (إجباري)</label>")
				.contains("<input type=\"number\" id=\"basic_salary\" name=\"basic_salary\""
						+ " step=\"0.01\" min=\"0\" value=\"0\" required>");

		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("transport", "بدل انتقال");
		labels.put("food", "بدل طعام");
		labels.put("risk", "بدل مخاطر");
		labels.put("incentives", "حوافز");
		labels.put("insurance", "التأمينات");
		labels.put("tax", "الضرائب");
		labels.put("advances", "تأمين طبي");
		labels.put("fund", "الصناديق");
		labels.put("penalty", "خصومات ثابتة أخرى");
		labels.forEach((name, label) -> assertThat(form).as(name)
				.contains("<label for=\"" + name + "\">" + label + " (اختياري)</label>")
				.contains("<input type=\"number\" id=\"" + name + "\" name=\"" + name
						+ "\" step=\"0.01\" min=\"0\" value=\"0\">"));

		BrowserForm browser = formFields(add, "add_employee");
		assertThat(browser.required()).as("legacy's basic salary is required").contains("basic_salary");
		assertThat(browser.fields()).containsEntry("basic_salary", "0")
				.containsEntry("transport", "0").containsEntry("penalty", "0");

		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		assertThat(formOf(body("/admin/employees?action=edit&id=" + id), "save_edit"))
				.as("legacy's edit never touches the salary contract")
				.doesNotContain("name=\"basic_salary\"", "name=\"transport\"", "name=\"penalty\"");
	}

	@Test
	void aCreatedEmployeesSalaryRowCarriesEveryComponentAsPosted() {
		// page.php:93-106 writes all ten from the post. The controller and the
		// store already read and wrote all ten; the window offered a field for
		// one, so nine of them could only ever be stored as zero. Filled and
		// submitted as a browser would, not posted past the form.
		Map<String, String> typed = new LinkedHashMap<>();
		typed.put("basic_salary", "7500.50");
		typed.put("transport", "300.25");
		typed.put("food", "150.10");
		typed.put("risk", "75.05");
		typed.put("incentives", "220.40");
		typed.put("insurance", "60.15");
		typed.put("tax", "310.20");
		typed.put("advances", "45.35");
		typed.put("fund", "90.45");
		typed.put("penalty", "25.55");

		BrowserForm form = formFields(
				body("/admin/employees?company_id=" + this.companyA + "&action=add"), "add_employee");
		assertThat(form.fields().keySet()).as("the window offers all ten").containsAll(typed.keySet());
		form.fields().putAll(typed);
		form.fields().put("first_name", "Nadia");
		form.fields().put("employee_code", "2001");
		form.fields().put("branch_id", String.valueOf(this.branchA));
		form.fields().put("shift_id", String.valueOf(this.shiftA));
		form.fields().put("hire_date", "2026-02-01");
		assertSaved(postFields(form));

		Map<String, Object> contract = this.jdbc.queryForMap(
				"SELECT sc.* FROM salary_contracts sc JOIN employees e ON e.id = sc.employee_id"
						+ " WHERE e.employee_code = '2001'");
		Map<String, String> stored = new LinkedHashMap<>();
		stored.put("basic_salary", "7500.50");
		stored.put("transport_allowance", "300.25");
		stored.put("food_allowance", "150.10");
		stored.put("risk_allowance", "75.05");
		stored.put("incentives", "220.40");
		stored.put("insurance_deduction", "60.15");
		stored.put("tax_deduction", "310.20");
		stored.put("advances_deduction", "45.35");
		stored.put("fund_deduction", "90.45");
		stored.put("penalty_deduction", "25.55");
		stored.forEach((column, amount) -> assertThat((java.math.BigDecimal) contract.get(column))
				.as(column).isEqualByComparingTo(amount));
		assertThat((java.math.BigDecimal) contract.get("housing_allowance"))
				.as("legacy writes a zero housing allowance; neither form has a field for it")
				.isEqualByComparingTo("0");
		assertThat(contract.get("effective_from")).asString().startsWith("2026-02-01");
	}

	@Test
	void theWindowsButtonSaysAddOnAnAddAndSaveOnAnEdit() {
		// _employee_form.php:286: the add window's button names what it adds.
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		assertThat(formOf(body("/admin/employees?company_id=" + this.companyA + "&action=add"),
				"add_employee"))
				.contains("<button type=\"submit\" class=\"btn btn-blue\">إضافة موظف</button>");
		assertThat(formOf(body("/admin/employees?action=edit&id=" + id), "save_edit"))
				.contains("<button type=\"submit\" class=\"btn btn-blue\">حفظ</button>");
	}

	@Test
	void deactivatingAsksLegacysConfirmationInRedAndReactivatingDoesNot() {
		// employee_helper.php:671-686: deactivate is a danger item behind
		// confirm_delete, legacy's own prompt for it, and reactivate a success
		// item that asks nothing. The port offered both as a plain item, and
		// deactivating an employee took one click with no prompt at all.
		long active = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long suspended = seedEmployee(this.companyA, "1002", "Bassem", "Beta");
		this.jdbc.update("UPDATE employees SET is_active = 0 WHERE id = ?", suspended);

		String html = body("/admin/employees?company_id=" + this.companyA);

		String deactivate = formOf(row(html, active), "deactivate");
		assertThat(deactivate).contains("data-confirm=\"هل تريد الحذف؟ لا يمكن التراجع!\" data-confirm-tone=\"danger\"")
				.contains("class=\"row-actions__item row-actions__item--danger\">إيقاف</button>");
		String reactivate = formOf(row(html, suspended), "reactivate");
		assertThat(reactivate).as("legacy asks nothing to switch an employee back on")
				.doesNotContain("data-confirm");
		assertThat(reactivate)
				.contains("class=\"row-actions__item row-actions__item--success\">تفعيل</button>");
		assertThat(formOf(row(html, active), "delete")).as("the delete item keeps its own prompt")
				.contains("data-confirm=\"هل تريد الحذف؟ لا يمكن التراجع!\" data-confirm-tone=\"danger\"")
				.contains("class=\"row-actions__item row-actions__item--danger\">حذف</button>");
	}

	/**
	 * A refused post also redirects, and leaves every column as it was, so an
	 * unchanged-row assertion alone would pass on a save that never ran.
	 */
	private void assertSaved(ResponseEntity<String> response) {
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString()
				.as("accepted, not refused with an error")
				.endsWith("/admin/employees");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events WHERE target_type = 'employee'",
				Integer.class))
				.as("and audited, which only a completed write is")
				.isEqualTo(1);
	}

	/** Writes a zero date, which a strict session refuses; production runs non-strict. */
	private void nonStrict(String sql) {
		this.jdbc.execute("SET STATEMENT sql_mode = '' FOR " + sql);
	}

	/**
	 * Every column save_edit writes, and the assignment it may add, as one map.
	 * Dates are read as text: the driver can hand a zero date back as null,
	 * which would make a zero date saved as NULL look unchanged.
	 */
	private Map<String, Object> editableColumns(long id) {
		Map<String, Object> columns = new LinkedHashMap<>(this.jdbc.queryForMap(
				"SELECT company_id, first_name, last_name, employee_code, phone, country_code,"
						+ " national_id, gender, CAST(birth_date AS CHAR) AS birth_date,"
						+ " CAST(hire_date AS CHAR) AS hire_date, address, branch_id,"
						+ " department_id, job_title_id, contract_duration_months,"
						+ " is_mobile_attendance_enabled, password_hash FROM employees WHERE id = ?",
				id));
		columns.putAll(this.jdbc.queryForMap(
				"SELECT shift_id, CAST(effective_from AS CHAR) AS effective_from"
						+ " FROM employee_shift_assignments"
						+ " WHERE employee_id = ? ORDER BY effective_from DESC, id DESC LIMIT 1",
				id));
		return columns;
	}

	/**
	 * A rendered form as a browser holds it: what it would submit, and what
	 * stops it submitting -- a required field left empty, a value its
	 * {@code pattern} does not match, a number outside {@code min} and
	 * {@code max}.
	 */
	private record BrowserForm(Map<String, String> fields, java.util.Set<String> required,
			Map<String, String> patterns, Map<String, String[]> ranges) {
	}

	/**
	 * The form whose hidden {@code action} is {@code action}, read the way a
	 * browser submits it: text-like inputs with their values, a date input
	 * whose value is not a valid date as empty, a checkbox only when ticked,
	 * and each select's selected option (or its first, as a browser falls back
	 * to). The CSRF field is left to the post.
	 */
	private static BrowserForm formFields(String html, String action) {
		String form = formOf(html, action);

		Map<String, String> fields = new LinkedHashMap<>();
		java.util.Set<String> required = new java.util.LinkedHashSet<>();
		Matcher inputs = Pattern.compile("<input\\b([^>]*)>").matcher(form);
		while (inputs.find()) {
			String attributes = inputs.group(1);
			String name = attribute(attributes, "name");
			if (name == null || name.contains("_csrf")) {
				continue;
			}
			String type = attribute(attributes, "type");
			String value = attribute(attributes, "value");
			if ("checkbox".equals(type)) {
				if (hasAttribute(attributes, "checked")) {
					fields.put(name, value == null ? "on" : value);
				}
				continue;
			}
			value = value == null ? "" : value;
			fields.put(name, "date".equals(type) ? sanitizedDate(value) : value);
			if (hasAttribute(attributes, "required")) {
				required.add(name);
			}
		}
		Matcher selects = Pattern.compile("<select\\b([^>]*)>(.*?)</select>", Pattern.DOTALL).matcher(form);
		while (selects.find()) {
			String first = null;
			String chosen = null;
			Matcher options = Pattern.compile("<option\\b([^>]*)>").matcher(selects.group(2));
			while (options.find()) {
				String value = attribute(options.group(1), "value");
				value = value == null ? "" : value;
				if (first == null) {
					first = value;
				}
				if (hasAttribute(options.group(1), "selected")) {
					chosen = value;
				}
			}
			String name = attribute(selects.group(1), "name");
			fields.put(name, chosen != null ? chosen : first == null ? "" : first);
			if (hasAttribute(selects.group(1), "required")) {
				required.add(name);
			}
		}
		return new BrowserForm(fields, required, inputAttribute(form, "pattern"), numberRanges(form));
	}

	/** The form whose hidden {@code action} is {@code action}, as markup. */
	private static String formOf(String html, String action) {
		int marker = html.indexOf("name=\"action\" value=\"" + action + "\"");
		assertThat(marker).as("the %s form renders", action).isPositive();
		return html.substring(html.lastIndexOf("<form", marker), html.indexOf("</form>", marker));
	}

	/** A map the form carries as JSON in {@code attribute}, unescaped as a browser reads it. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(String form, String attribute) {
		Matcher json = Pattern.compile(attribute + "=\"([^\"]*)\"").matcher(form);
		assertThat(json.find()).as("the form carries %s", attribute).isTrue();
		String unescaped = json.group(1).replace("&#34;", "\"").replace("&#39;", "'").replace("&lt;", "<")
				.replace("&gt;", ">").replace("&amp;", "&");
		return (Map<String, Object>) new tools.jackson.databind.ObjectMapper().readValue(unescaped, Map.class);
	}

	private static List<String> names(Map<?, ?> map, long key) {
		Object entries = map.get(String.valueOf(key));
		return entries == null ? List.of()
				: ((List<?>) entries).stream().map(entry -> (String) ((Map<?, ?>) entry).get("name")).toList();
	}

	/** Each named input's value for the attribute {@code key}, where it has one. */
	private static Map<String, String> inputAttribute(String form, String key) {
		Map<String, String> found = new LinkedHashMap<>();
		Matcher inputs = Pattern.compile("<input\\b([^>]*)>").matcher(form);
		while (inputs.find()) {
			String name = attribute(inputs.group(1), "name");
			String value = attribute(inputs.group(1), key);
			if (name != null && value != null) {
				found.put(name, value);
			}
		}
		return found;
	}

	/** Each number input's {@code min} and {@code max}; either may be absent. */
	private static Map<String, String[]> numberRanges(String form) {
		Map<String, String[]> found = new LinkedHashMap<>();
		Matcher inputs = Pattern.compile("<input\\b([^>]*)>").matcher(form);
		while (inputs.find()) {
			String attributes = inputs.group(1);
			String name = attribute(attributes, "name");
			if (name != null && "number".equals(attribute(attributes, "type"))) {
				found.put(name, new String[] {attribute(attributes, "min"), attribute(attributes, "max")});
			}
		}
		return found;
	}

	/** A browser's value sanitisation: a date input holding no valid date submits as empty. */
	private static String sanitizedDate(String value) {
		try {
			return value.length() == 10 && java.time.LocalDate.parse(value).getYear() >= 1 ? value : "";
		}
		catch (java.time.format.DateTimeParseException ex) {
			return "";
		}
	}

	/** The toolbar form's start tag; a quoted attribute value may hold a {@code >}. */
	private static final Pattern TOOLBAR_FORM = Pattern.compile(
			"<form method=\"GET\" class=\"toolbar-form toolbar-form--labeled\"((?:[^>\"]|\"[^\"]*\")*)>");

	private static String attribute(String attributes, String name) {
		Matcher matcher = Pattern.compile("(?:^|\\s)" + name + "=\"([^\"]*)\"").matcher(attributes);
		return matcher.find()
				? matcher.group(1).replace("&quot;", "\"").replace("&#34;", "\"").replace("&#39;", "'")
						.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
				: null;
	}

	private static boolean hasAttribute(String attributes, String name) {
		return Pattern.compile("(?:^|\\s)" + name + "(?:\\s|=|$)").matcher(attributes).find();
	}

	private ResponseEntity<String> postFields(BrowserForm form) {
		for (String name : form.required()) {
			assertThat(form.fields().get(name))
					.as("a browser will not submit the form while required %s is empty", name)
					.isNotEmpty();
		}
		// A browser checks pattern, min and max only on a value that is not empty.
		form.patterns().forEach((name, pattern) -> {
			String value = form.fields().get(name);
			assertThat(value == null || value.isEmpty() || value.matches(pattern))
					.as("a browser will not submit %s '%s', which pattern %s does not match", name, value, pattern)
					.isTrue();
		});
		form.ranges().forEach((name, range) -> {
			String value = form.fields().get(name);
			if (value != null && !value.isEmpty()) {
				double number = Double.parseDouble(value);
				assertThat((range[0] == null || number >= Double.parseDouble(range[0]))
						&& (range[1] == null || number <= Double.parseDouble(range[1])))
						.as("a browser will not submit %s %s outside min %s and max %s",
								name, value, range[0], range[1])
						.isTrue();
			}
		});
		List<String> pairs = new ArrayList<>();
		form.fields().forEach((name, value) -> {
			pairs.add(name);
			pairs.add(value);
		});
		return postForm(pairs.toArray(String[]::new));
	}

	private ResponseEntity<String> postFrom(String referer, String... fields) {
		Csrf csrf = page("/admin/employees?action=add", this.cookie).csrf();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + this.cookie);
		headers.add(HttpHeaders.REFERER, referer);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int index = 0; index < fields.length; index += 2) {
			form.add(fields[index], fields[index + 1]);
		}
		form.add(csrf.name(), csrf.value());
		return this.restTemplate.exchange("/admin/employees", HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class);
	}

	private ResponseEntity<String> postForm(String... fields) {
		return post("/admin/employees", this.cookie,
				page("/admin/employees?action=add", this.cookie).csrf(), fields);
	}

	private int employeeCount() {
		return this.jdbc.queryForObject("SELECT COUNT(*) FROM employees", Integer.class);
	}

	/** An active country and a retired one, with codes no other test uses; signIn() removes them. */
	private void seedCountries() {
		this.jdbc.update("INSERT INTO phone_countries (country_code, name_ar, name_en, flag_emoji, phone_length,"
				+ " phone_prefixes, is_active, sort_order) VALUES ('+881', 'بلد تجريبي', 'Test land', '🏳', 10,"
				+ " '[\"07\"]', 1, 90), ('+882', 'بلد متقاعد', 'Retired land', '', 10, '[\"07\"]', 0, 91)");
	}

	private static String countrySelect(String html) {
		int start = html.indexOf("<select id=\"country_code\"");
		assertThat(start).as("the country select renders").isPositive();
		return html.substring(start, html.indexOf("</select>", start));
	}

	/** One employee's row of the list, from its opening tag to its end. */
	private static String row(String html, long employeeId) {
		int menu = html.indexOf("id=\"row-actions-menu-" + employeeId + "\"");
		assertThat(menu).as("the row for employee %s", employeeId).isPositive();
		return html.substring(html.lastIndexOf("<tr", menu), html.indexOf("</tr>", menu));
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
	private void linkDepartment(long departmentId, long branchId) {
		this.jdbc.update("INSERT INTO department_branches (department_id, branch_id) VALUES (?, ?)",
				departmentId, branchId);
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

}
