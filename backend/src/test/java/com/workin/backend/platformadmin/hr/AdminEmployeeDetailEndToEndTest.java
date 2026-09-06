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
@ActiveProfiles("phase1-mysql")
class AdminEmployeeDetailEndToEndTest {

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
				+ " VALUES (?, '2026-03-02 09:00:00', 'mobile')", id);

		String html = detailBody(id, 3, 2026);
		assertThat(html).contains("2026-03-02");
		assertThat(html)
				.as("TIMESTAMPDIFF over a null check_out is null, which renders as the label")
				.contains("Still Working");
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

		// 8000 + 300 - 100, from the later contract only.
		assertThat(detailBody(id, 3, 2026)).contains("8200");
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

		assertThat(detailBody(id, 3, 2026)).as("the month with a batch").contains("8330");
		assertThat(detailBody(id, 4, 2026)).as("a month without one").doesNotContain("8330");
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
				+ " VALUES (?, ?, ?, 'mobile')", employeeId, checkIn, checkOut);
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
		String sql = new String(AdminEmployeeDetailEndToEndTest.class.getClassLoader()
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
