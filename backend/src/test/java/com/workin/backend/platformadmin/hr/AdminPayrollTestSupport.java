package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * The container, sign-in and fixtures the three {@code /admin/payroll} suites
 * share.
 *
 * <p>They are three classes on purpose. A failure should say which of the
 * page's two independent contracts broke: {@link AdminPayrollTenantIsolationTest}
 * covers access control (R-064's divergence), {@link AdminPayrollCalculationParityTest}
 * covers the business numbers, and {@link AdminPayrollEndToEndTest} covers what
 * the page does for the operator who is allowed to use it. Splitting them means
 * a red build names the broken contract instead of leaving it to be worked out
 * from the assertion.
 *
 * <p>They share this base rather than a copied harness so all three run against
 * one MariaDB and one Spring context.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
abstract class AdminPayrollTestSupport {

	protected static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	protected static final String PASSWORD = "correct horse battery staple";

	protected static final String PATH = "/admin/payroll";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	/**
	 * The twenty-five columns of {@code payslips}, in schema order.
	 * {@code edit_detail} writes twelve of them; the assertions that matter
	 * most on this page are about the other thirteen.
	 */
	protected static final List<String> PAYSLIP_COLUMNS = List.of(
			"id", "batch_id", "employee_id",
			"days_present", "days_absent", "days_leave", "overtime_hours",
			"basic_salary", "allowances", "overtime_pay", "penalties_total",
			"advance_deduction", "other_deductions", "net_salary",
			"food_allowance", "risk_allowance", "transport_allowance", "incentives",
			"insurance_deduction", "tax_deduction", "advances_deduction", "fund_deduction",
			"gross_salary", "total_entitlements", "total_deductions");

	/** The twelve {@code edit_detail} posts. */
	protected static final List<String> WRITABLE_COLUMNS = List.of(
			"days_present", "days_absent", "days_leave", "overtime_hours",
			"basic_salary", "allowances", "overtime_pay", "penalties_total",
			"advance_deduction", "advances_deduction", "other_deductions", "net_salary");

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
		}
		catch (Exception ex) {
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
	protected TestRestTemplate restTemplate;

	@Autowired
	private PlatformAdminMfaService mfaService;

	@Autowired
	protected PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	protected JdbcTemplate jdbc;

	protected String cookie;

	protected long companyA;

	protected long companyB;

	protected long employeeA;

	protected long employeeB;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM payslips");
		this.jdbc.update("DELETE FROM payroll_batches");
		this.jdbc.update("DELETE FROM attendance");
		this.jdbc.update("DELETE FROM salary_contracts");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2097" + System.nanoTime() % 100_000_000L;
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
		this.employeeA = createEmployee(this.companyA, "A100", "Aya", "Alpha");
		this.employeeB = createEmployee(this.companyB, "B100", "Basma", "Beta");
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	protected long createCompany(String name) {
		String phone = "01" + System.nanoTime() % 1_000_000_000L;
		this.jdbc.update("INSERT INTO companies (company_name, phone, password_hash, status,"
				+ " otp_verified, profile_completed, created_at)"
				+ " VALUES (?, ?, ?, 'active', 1, 1, NOW())",
				name, phone, this.passwordEncoder.encode(PASSWORD));
		return this.jdbc.queryForObject("SELECT id FROM companies WHERE phone = ?", Long.class, phone);
	}

	protected long createEmployee(long companyId, String code, String first, String last) {
		long branchId = this.jdbc.queryForObject(
				"SELECT COALESCE(MAX(id), 0) + 1 FROM branches", Long.class);
		this.jdbc.update("INSERT INTO branches (id, company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, ?, 1, NOW())", branchId, companyId, "Branch " + code);
		long id = this.jdbc.queryForObject(
				"SELECT GREATEST(COALESCE(MAX(id), 0) + 1, 990001) FROM employees", Long.class);
		this.jdbc.update("INSERT INTO employees (id, company_id, branch_id, employee_code,"
				+ " first_name, last_name, role, is_active, is_mobile_attendance_enabled,"
				+ " can_check_in_any_branch, join_request_status, token_version, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, 'employee', 1, 1, 0, 'accepted', 1, NOW(), NOW())",
				id, companyId, branchId, code, first, last);
		return id;
	}

	protected void salaryContract(long employeeId, String basic, String effectiveFrom) {
		this.jdbc.update("INSERT INTO salary_contracts (employee_id, basic_salary, salary_mode,"
				+ " effective_from, created_at) VALUES (?, ?, 'monthly', ?, NOW())",
				employeeId, new BigDecimal(basic), effectiveFrom);
	}

	protected long batch(long companyId, int month, int year, String from, String to, String status) {
		this.jdbc.update("INSERT INTO payroll_batches (company_id, month, year, period_from,"
				+ " period_to, status, created_at) VALUES (?, ?, ?, ?, ?, ?, NOW())",
				companyId, month, year, from, to, status);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM payroll_batches", Long.class);
	}

	/**
	 * A payslip with a distinct value in every one of the twenty-five columns,
	 * so an assertion that one of them changed cannot pass by coincidence.
	 */
	protected long payslip(long batchId, long employeeId) {
		this.jdbc.update("INSERT INTO payslips (batch_id, employee_id, days_present, days_absent,"
				+ " days_leave, overtime_hours, basic_salary, allowances, overtime_pay,"
				+ " penalties_total, advance_deduction, other_deductions, net_salary,"
				+ " food_allowance, risk_allowance, transport_allowance, incentives,"
				+ " insurance_deduction, tax_deduction, advances_deduction, fund_deduction,"
				+ " gross_salary, total_entitlements, total_deductions)"
				+ " VALUES (?, ?, 21, 2, 1, 5, 5000, 300, 150, 40, 60, 25, 5180,"
				+ " 110, 120, 130, 140, 210, 220, 70, 230, 5680, 5950, 770)",
				batchId, employeeId);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM payslips", Long.class);
	}

	/** Every column of one payslip, for a before/after comparison. */
	protected Map<String, Object> payslipRow(long payslipId) {
		return this.jdbc.queryForMap("SELECT * FROM payslips WHERE id = " + payslipId);
	}

	/** Every column of one batch, likewise. */
	protected Map<String, Object> batchRow(long batchId) {
		return this.jdbc.queryForMap("SELECT * FROM payroll_batches WHERE id = " + batchId);
	}

	/**
	 * The worked-minutes calendar is request-scoped, so a test that drives the
	 * calculation service directly rather than through the page has to stand up
	 * a request first.
	 */
	protected <T> T inRequestScope(java.util.function.Supplier<T> work) {
		org.springframework.mock.web.MockHttpServletRequest request =
				new org.springframework.mock.web.MockHttpServletRequest();
		org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
				new org.springframework.web.context.request.ServletRequestAttributes(request));
		try {
			return work.get();
		}
		finally {
			org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
		}
	}

	// ------------------------------------------------------------------
	// HTTP
	// ------------------------------------------------------------------

	protected record Csrf(String name, String value) {
	}

	protected record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	protected String body(String path) {
		return get(path, this.cookie).getBody();
	}

	protected ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	protected Page page(String path, String sessionCookie) {
		ResponseEntity<String> response = get(path, sessionCookie);
		String resolved = sessionCookie != null ? sessionCookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	protected ResponseEntity<String> post(String path, String sessionCookie, Csrf csrf, String... fields) {
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

	/** A POST to the payroll page with a token fetched from the page itself. */
	protected ResponseEntity<String> submit(String... fields) {
		return post(PATH, this.cookie, page(PATH, this.cookie).csrf(), fields);
	}

	/** The same, from a session already filtered to one company. */
	protected ResponseEntity<String> submitFilteredTo(long companyId, String... fields) {
		String filtered = PATH + "?company_id=" + companyId;
		get(filtered, this.cookie);
		return post(PATH, this.cookie, page(filtered, this.cookie).csrf(), fields);
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
		String sql = new String(AdminPayrollTestSupport.class.getClassLoader()
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
