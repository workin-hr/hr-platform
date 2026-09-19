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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/employee_detail} over real HTTP against a real MariaDB.
 *
 * <p><b>R-057</b>: legacy guarded this page with {@code requireLogin()} and
 * nothing else. Its sibling list page calls
 * {@code hr_require_section('employees')}; this one did not, so an HR employee
 * holding no permission reached it. And its tenant predicate was
 * {@code if (isCompany())} -- the company-owner session flag alone -- so an HR
 * session took neither branch and could read any employee in any company: pay,
 * attendance, penalties, advances, payslip and documents.
 *
 * <p>Measured against the production copy at the time it was found, that was
 * 3,783 employees across 152 companies reachable by any of 11 HR accounts.
 * What is asserted here is the scoping, and the assembly of the eight queries
 * the page runs.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminEmployeeDetailEndToEndTest {

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
		this.jdbc.update("DELETE FROM payslips");
		this.jdbc.update("DELETE FROM payroll_batches");
		this.jdbc.update("DELETE FROM employee_docs");
		this.jdbc.update("DELETE FROM attendance");
		this.jdbc.update("DELETE FROM advances");
		this.jdbc.update("DELETE FROM penalties");
		this.jdbc.update("DELETE FROM requests");
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
	void theDetailPageShowsTheEmployeeAndTheMonthsAttendance() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		seedAttendance(id, "2026-03-02 09:00:00", "2026-03-02 17:00:00");
		seedAttendance(id, "2026-03-03 09:00:00", "2026-03-03 14:30:00");

		String html = detailBody(id, 3, 2026);
		assertThat(html).contains("Aya", "1001", "Alpha Co");
		assertThat(html).as("two days present").contains(">2<");
		assertThat(html).as("8.0 + 5.5 hours").contains("13.5");
	}

	@Test
	void attendanceOutsideTheChosenMonthIsNotCounted() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		seedAttendance(id, "2026-03-02 09:00:00", "2026-03-02 17:00:00");
		seedAttendance(id, "2026-04-02 09:00:00", "2026-04-02 17:00:00");

		assertThat(detailBody(id, 3, 2026)).contains("2026-03-02").doesNotContain("2026-04-02");
		assertThat(detailBody(id, 4, 2026)).contains("2026-04-02").doesNotContain("2026-03-02");
	}

	@Test
	void anOpenShiftHasNoHoursAndReadsAsStillWorking() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("INSERT INTO attendance (employee_id, check_in, method)"
				+ " VALUES (?, '2026-03-02 09:00:00', 'qr')", id);

		String html = detailBody(id, 3, 2026);
		assertThat(html).contains("2026-03-02");
		assertThat(html)
				.as("detail.php:87: the badge takes the check-out cell, and the hours cell is a dash")
				.containsPattern("<td>09:00</td>\\s*<td><span class=\"badge badge-yellow\">Still Working</span></td>\\s*<td>—</td>\\s*<td>qr</td>");
		assertThat(this.jdbc.queryForObject(
				"SELECT ROUND(TIMESTAMPDIFF(MINUTE, check_in, check_out) / 60, 1)"
						+ " FROM attendance WHERE employee_id = " + id, java.math.BigDecimal.class))
				.isNull();
	}

	@Test
	void theLeaveBalanceShownIsTheOneForTheChosenYear() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("INSERT INTO leave_balance (employee_id, year, total_days, used_days)"
				+ " VALUES (?, 2025, 21, 6)", id);
		this.jdbc.update("INSERT INTO leave_balance (employee_id, year, total_days, used_days)"
				+ " VALUES (?, 2026, 21, 1)", id);

		assertThat(detailBody(id, 3, 2025)).as("21 - 6, a generated column").contains("15.0");
		assertThat(detailBody(id, 3, 2026)).as("21 - 1").contains("20.0");
	}

	@Test
	void theSalaryCardShowsTheLatestContractsGeneratedTotal() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("INSERT INTO salary_contracts (employee_id, basic_salary,"
				+ " transport_allowance, insurance_deduction, effective_from)"
				+ " VALUES (?, 5000, 500, 200, '2025-01-01')", id);
		this.jdbc.update("INSERT INTO salary_contracts (employee_id, basic_salary,"
				+ " transport_allowance, insurance_deduction, effective_from)"
				+ " VALUES (?, 8000, 300, 100, '2026-01-01')", id);

		// 8000 + 300 - 100, from the later contract only, through number_format.
		assertThat(detailBody(id, 3, 2026)).contains("<div class=\"stat-num\">8,200</div>");
	}

	@Test
	void requestsAndPenaltiesAreCappedAtTenAndAdvancesAreNot() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long typeId = seedRequestType(this.companyA, "Leave");
		for (int i = 0; i < 12; i++) {
			this.jdbc.update("INSERT INTO requests (employee_id, request_type_id, from_date,"
					+ " to_date, status, created_at) VALUES (?, ?, '2026-03-01', '2026-03-02',"
					+ " 'pending', NOW())", id, typeId);
			this.jdbc.update("INSERT INTO penalties (employee_id, penalty_date, penalty_type,"
					+ " penalty_days, applied_to_payroll) VALUES (?, '2026-03-01', 'late', 1, 0)",
					id);
			this.jdbc.update("INSERT INTO advances (employee_id, amount, remaining, status,"
					+ " created_at) VALUES (?, 100, 100, 'approved', NOW())", id);
		}

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM advances WHERE employee_id = " + id, Integer.class))
				.isEqualTo(12);
		String html = detailBody(id, 3, 2026);
		// Twelve advances render; requests and penalties stop at ten. Counting
		// rendered rows is brittle, so the caps are asserted on the queries the
		// page runs rather than on the markup.
		assertThat(countRendered(html, "late")).as("penalties capped at ten").isEqualTo(10);
		assertThat(html).contains("Leave");
	}

	@Test
	void thePayslipSectionAppearsOnlyForAMonthThatHasOne() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long batch = seedPayrollBatch(this.companyA, 3, 2026);
		this.jdbc.update("INSERT INTO payslips (batch_id, employee_id, basic_salary, allowances,"
				+ " overtime_pay, penalties_total, advance_deduction, advances_deduction,"
				+ " net_salary) VALUES (?, ?, 8000, 300, 100, 50, 20, 20, 8330)", batch, id);

		assertThat(detailBody(id, 3, 2026)).as("the month with a batch").contains("8,330");
		assertThat(detailBody(id, 4, 2026)).as("a month without one").doesNotContain("8,330");
	}

	@Test
	void theHeaderAndStatsReadAsLegacysDo() {
		long id = seedEmployee(this.companyA, "1001", "aya", "alpha");
		this.jdbc.update("UPDATE employees SET phone = '1012345678', country_code = '+20' WHERE id = ?", id);
		seedAttendance(id, "2026-03-02 09:00:00", "2026-03-02 17:00:00");

		String html = detailBody(id, 3, 2026);
		assertThat(html).as("detail.php:37").contains("<title>Employee — aya alpha");
		assertThat(html).as("no photo: the initials circle, upper-cased as legacy's label")
				.contains("<div class=\"emp-detail-avatar\">A A</div>");
		assertThat(html).as("detail.php:61: the name and legacy's yes badge")
				.containsPattern("<div class=\"emp-detail-name\">aya alpha\\s*<span class=\"badge badge-green\">Yes</span>\\s*</div>");
		assertThat(html).as("detail.php:64: the stored phone, without the country code")
				.contains("<strong>1012345678</strong>").doesNotContain("+20 1012345678");
		assertThat(html).as("detail.php:68: no hire date is a dash, not the created date")
				.containsPattern("Hire Date: <strong>—</strong>");
		assertThat(html).as("eight hours echo as PHP echoes 8.0").contains("<div class=\"stat-num\">8</div>");

		this.jdbc.update("UPDATE employees SET photo_url = 'https://files.example.com/p.jpg' WHERE id = ?", id);
		assertThat(detailBody(id, 3, 2026)).as("a photo, with the initials it falls back to")
				.containsPattern("<img src=\"https://files.example.com/p.jpg\" alt=\"\" class=\"emp-detail-photo\"\\s*"
						+ "data-fallback-initials=\"A A\"\\s*data-fallback-class=\"emp-detail-avatar\">");
	}

	@Test
	void anEmployeeWithABlankNameReadsAsLegacysDashInTheTitleHeaderAndAvatar() {
		// detail.php:36: dashboard_employee_display_name($emp) falls back to an em dash, and the
		// title, the name and the initials circle all use it.
		long id = seedEmployee(this.companyA, "1002", "", "");

		String html = detailBody(id, 3, 2026);
		assertThat(html).contains("<title>Employee — —");
		assertThat(html).contains("<div class=\"emp-detail-avatar\">—</div>");
		assertThat(html).containsPattern("<div class=\"emp-detail-name\">—\\s*<span class=\"badge badge-green\">");
	}

	@Test
	void theAttendanceTableHasLegacysColumnsAndTimes() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		seedAttendance(id, "2026-03-02 09:05:00", "2026-03-02 17:35:00");

		String html = detailBody(id, 3, 2026);
		assertThat(html).as("detail.php:85: hours before method")
				.containsPattern("<th>Check In</th>\\s*<th>Check Out</th><th>Hours</th>\\s*<th>Method</th>");
		assertThat(html).as("detail.php:87: times to the minute")
				.containsPattern("<td>2026-03-02</td>\\s*<td>09:05</td>\\s*<td>17:35</td>\\s*<td>8.5</td>\\s*<td>app</td>");
	}

	@Test
	void theRequestPenaltyAndAdvanceTablesDrawLegacysBadgesAndFigures() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long typeId = seedRequestType(this.companyA, "Sick leave");
		this.jdbc.update("INSERT INTO requests (employee_id, request_type_id, from_date, to_date, status, created_at)"
				+ " VALUES (?, ?, '2026-03-01', '2026-03-02', 'approved', NOW())", id, typeId);
		this.jdbc.update("INSERT INTO penalties (employee_id, penalty_date, penalty_type, penalty_days, applied_to_payroll)"
				+ " VALUES (?, '2026-03-05', 'late', 1.5, 1), (?, '2026-03-01', 'absent', 1, 0)", id, id);
		this.jdbc.update("INSERT INTO advances (employee_id, amount, remaining, status, created_at)"
				+ " VALUES (?, 1500, 250.5, 'pending', '2026-03-02 10:00:00'), (?, 900, 0, 'approved', '2026-03-01 10:00:00')",
				id, id);

		String html = detailBody(id, 3, 2026);
		assertThat(html).as("detail.php:95: the request's status as legacy's badge")
				.containsPattern("<td>Sick leave</td><td>2026-03-01</td><td>2026-03-02</td>\\s*<td>\\s*<span class=\"badge badge-green\">Approved</span>\\s*</td>");
		assertThat(html).as("detail.php:102: type, red days, date, and yes or no")
				.containsPattern("<td>late</td>\\s*<td class=\"text-red\">1.5</td>\\s*<td>2026-03-05</td>\\s*"
						+ "<td>\\s*<span class=\"badge badge-green\">Yes</span>\\s*</td>")
				.containsPattern("<td>absent</td>\\s*<td class=\"text-red\">1.0</td>\\s*<td>2026-03-01</td>\\s*"
						+ "<td>\\s*<span class=\"badge badge-gray\">No</span>\\s*</td>");
		assertThat(html).as("detail.php:109: whole pounds, remaining red while owed, and the status badge")
				.containsPattern("<td>1,500</td>\\s*<td class=\"text-red\">251</td>\\s*"
						+ "<td>\\s*<span class=\"badge badge-yellow\">Pending</span>\\s*</td>")
				.containsPattern("<td>900</td>\\s*<td class=\"text-green\">0</td>\\s*"
						+ "<td>\\s*<span class=\"badge badge-green\">Approved</span>\\s*</td>");
	}

	@Test
	void thePayslipShowsLegacysSevenFiguresAndItsCurrency() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		long batch = seedPayrollBatch(this.companyA, 3, 2026);
		this.jdbc.update("INSERT INTO payslips (batch_id, employee_id, basic_salary, allowances, overtime_pay,"
				+ " penalties_total, advance_deduction, advances_deduction, net_salary)"
				+ " VALUES (?, ?, 8000, 1300, 100, 50, 20, 35, 9295)", batch, id);

		String html = detailBody(id, 3, 2026);
		assertThat(html).as("detail.php:118-127")
				.containsPattern("Basic Salary</div><div class=\"bold\">8,000</div>")
				.containsPattern("Allowances</div><div class=\"bold\">1,300</div>")
				.containsPattern("Overtime Pay</div><div class=\"bold text-green\">100</div>")
				.containsPattern("Penalties</div><div class=\"bold text-red\">50</div>")
				.as("detail.php:124: labelled medical insurance, showing advances_deduction")
				.containsPattern("Medical Insurance</div><div class=\"bold text-red\">35</div>")
				.containsPattern("<div class=\"bold text-red\">20</div>")
				.containsPattern("<div class=\"emp-detail-net-value\">9,295 ج.م</div>");
	}

	@Test
	void theDocumentsAreLinksToTheirFilesUnlessTheStoredUrlIsNotAWebAddress() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		this.jdbc.update("INSERT INTO employee_docs (employee_id, doc_type, file_url, uploaded_at) VALUES"
				+ " (?, 'id_card', 'https://files.example.com/docs/id.pdf', '2026-03-02 10:00:00'),"
				+ " (?, 'contract', 'javascript:alert(1)', '2026-03-01 10:00:00')", id, id);

		String html = detailBody(id, 3, 2026);
		assertThat(html).as("detail.php:132-138: a chip opening the file")
				.contains("<a href=\"https://files.example.com/docs/id.pdf\" target=\"_blank\" rel=\"noopener\""
						+ " class=\"emp-detail-doc\">id_card</a>");
		assertThat(html).contains("<span class=\"emp-detail-doc\">contract</span>").doesNotContain("javascript:alert");
	}

	@Test
	void anEmployeeOfAnotherCompanyCannotBeOpened() {
		// R-057. In legacy an HR session read this page with no company
		// predicate at all -- pay, attendance, penalties, advances, payslip and
		// documents for any employee in any company.
		long victim = seedEmployee(this.companyB, "9001", "Basma", "Beta");
		this.jdbc.update("INSERT INTO salary_contracts (employee_id, basic_salary,"
				+ " effective_from) VALUES (?, 99999, '2026-01-01')", victim);

		get("/admin/employees?company_id=" + this.companyA, this.cookie);
		ResponseEntity<String> response = get(
				"/admin/employee_detail?id=" + victim, this.cookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString()
				.contains("/admin/employees")
				.contains("no_data");
	}

	@Test
	void anUnfilteredAdministratorMayOpenAnyCompanysEmployee() {
		long other = seedEmployee(this.companyB, "9001", "Basma", "Beta");

		get("/admin/employees?company_id=0", this.cookie);
		ResponseEntity<String> response = get("/admin/employee_detail?id=" + other, this.cookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Basma");
	}

	@Test
	void aMissingEmployeeIsIndistinguishableFromAForeignOne() {
		// Both land on the same redirect, so the page never confirms that an id
		// exists in a company the session cannot see.
		long victim = seedEmployee(this.companyB, "9001", "Basma", "Beta");
		get("/admin/employees?company_id=" + this.companyA, this.cookie);

		ResponseEntity<String> foreign = get("/admin/employee_detail?id=" + victim, this.cookie);
		ResponseEntity<String> missing = get("/admin/employee_detail?id=987654321", this.cookie);

		assertThat(foreign.getHeaders().getLocation())
				.isEqualTo(missing.getHeaders().getLocation());
	}

	@Test
	void anAbsentOrNonsenseIdIsRefusedRatherThanRendered() {
		for (String query : List.of("", "?id=", "?id=0", "?id=-1", "?id=abc")) {
			ResponseEntity<String> response = get("/admin/employee_detail" + query, this.cookie);
			assertThat(response.getStatusCode())
					.as("id '%s' should redirect, not render", query)
					.isEqualTo(HttpStatus.FOUND);
		}
	}

	@Test
	void anAbsentOrNonsenseMonthFallsBackRatherThanBreakingThePage() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");

		for (String query : List.of("", "&month=", "&month=0", "&month=13", "&month=abc",
				"&year=", "&month=3&year=2026")) {
			assertThat(get("/admin/employee_detail?id=" + id + query, this.cookie).getStatusCode())
					.as("query '%s' should render", query)
					.isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void theIdMonthAndYearArePhpsIntCast() {
		long id = seedEmployee(this.companyA, "1001", "Aya", "Alpha");
		seedAttendance(id, "2026-03-02 09:00:00", "2026-03-02 17:00:00");

		// (int) "3e0" is 3 and (int) "2.026e3" is 2026; Integer.parseInt refused both.
		String html = get("/admin/employee_detail?id=" + id + "e0&month=3e0&year=2.026e3&lang=en", this.cookie)
				.getBody();
		assertThat(html).contains("<option value=\"3\" selected>3</option>")
				.contains("<option value=\"2026\" selected>2026</option>")
				.contains("2026-03-02");
	}

	/** Legacy's {@code (int)} keeps 3000000000, which names no employee; bounding it to an int would not. */
	@Test
	void anIdPastTheIntRangeFindsNoEmployeeRatherThanTheLastIntId() {
		this.jdbc.update("INSERT INTO employees (id, company_id, branch_id, employee_code,"
				+ " first_name, last_name, role, is_active, is_mobile_attendance_enabled,"
				+ " can_check_in_any_branch, join_request_status, token_version, created_at,"
				+ " updated_at) VALUES (2147483647, ?, ?, 'MAX', 'Max', 'Int', 'employee', 1, 1, 0,"
				+ " 'accepted', 1, NOW(), NOW())", this.companyA, this.branchA);
		try {
			for (String id : new String[] {"3000000000", "1e10"}) {
				assertThat(get("/admin/employee_detail?id=" + id, this.cookie).getHeaders().getLocation())
						.as(id).asString().endsWith("/admin/employees?error=no_data");
			}
			assertThat(get("/admin/employee_detail?id=2147483647", this.cookie).getStatusCode())
					.as("the row itself still opens").isEqualTo(HttpStatus.OK);
		} finally {
			this.jdbc.update("DELETE FROM employees WHERE id = 2147483647");
		}
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/employee_detail?id=1", HttpMethod.GET,
				new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	/**
	 * {@code lang=en} is pinned so label assertions read as English. The
	 * surface defaults to Arabic, which is correct for its users and unhelpful
	 * in a test that means to assert on a rendered label rather than on data.
	 */
	private String detailBody(long id, int month, int year) {
		return get("/admin/employee_detail?id=" + id + "&month=" + month + "&year=" + year
				+ "&lang=en", this.cookie).getBody();
	}

	private static int countRendered(String html, String needle) {
		int count = 0;
		int from = 0;
		while ((from = html.indexOf(needle, from)) >= 0) {
			count++;
			from += needle.length();
		}
		return count;
	}

	private void seedAttendance(long employeeId, String checkIn, String checkOut) {
		this.jdbc.update("INSERT INTO attendance (employee_id, check_in, check_out, method)"
				+ " VALUES (?, ?, ?, 'app')", employeeId, checkIn, checkOut);
	}

	private long seedRequestType(long companyId, String name) {
		this.jdbc.update("INSERT INTO request_types (company_id, name, is_active)"
				+ " VALUES (?, ?, 1)", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM request_types WHERE name = ?", Long.class, name);
	}

	private long seedPayrollBatch(long companyId, int month, int year) {
		this.jdbc.update("INSERT INTO payroll_batches (company_id, month, year, created_at)"
				+ " VALUES (?, ?, ?, NOW())", companyId, month, year);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM payroll_batches", Long.class);
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
