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
 * {@code /admin/advances} over real HTTP against a real MariaDB.
 *
 * <p>The most stateful HR page: an advance carries a {@code remaining} balance
 * that an edit <b>adjusts</b> rather than overwrites, and three of its six
 * actions are legal only from a particular status. The arithmetic itself is
 * unit-tested in {@link AdvanceTest}; what is proved here is that the page
 * applies it to the right row and stores the result.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminAdvancesEndToEndTest {

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

	private long employeeA;

	private long employeeB;



	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM advances");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2099" + System.nanoTime() % 100_000_000L;
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

	@Test
	void creatingAnAdvanceOwesItInFull() {
		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "add_advance", "employee_id", String.valueOf(this.employeeA),
				"amount", "1000", "reason", "school fees", "request_date", "2026-03-02");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT employee_id, amount, remaining, status FROM advances");
		assertThat(row.get("employee_id").toString()).isEqualTo(String.valueOf(this.employeeA));
		assertThat(((java.math.BigDecimal) row.get("amount"))
				.compareTo(new java.math.BigDecimal("1000"))).isZero();
		assertThat(((java.math.BigDecimal) row.get("remaining")))
				.as("nothing repaid yet, so the whole amount is outstanding")
				.isEqualByComparingTo("1000");
		assertThat(row.get("status"))
				.as("an advance created from the dashboard is money HR has already handed "
						+ "over, so it is approved on creation -- 'pending' belongs to the "
						+ "employee's own request from the mobile app")
				.isEqualTo("approved");
	}

	@Test
	void aNonPositiveAmountOrNoEmployeeIsRefused() {
		for (String amount : java.util.List.of("0", "-100", "abc", "")) {
			assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
					"action", "add_advance", "employee_id", String.valueOf(this.employeeA),
					"amount", amount)
					.getHeaders().getLocation()).asString()
					.as("amount '%s'", amount).contains("error=error_required");
		}
		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "add_advance", "employee_id", "0", "amount", "1000")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM advances", Integer.class)).isZero();
	}

	@Test
	void editingAPendingAdvanceMovesTheBalanceWithTheAmount() {
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "amount", "1500");

		assertThat(this.jdbc.queryForObject(
				"SELECT remaining FROM advances WHERE id = " + id, java.math.BigDecimal.class))
				.as("nothing repaid, so the balance is simply the new amount")
				.isEqualByComparingTo("1500");
	}

	@Test
	void editingAnApprovedAdvanceKeepsWhatWasAlreadyRepaid() {
		// 1000 advanced, 400 repaid, 600 outstanding. Raising to 1200 adds the
		// 200 difference; it does not reset the balance to 1200.
		long id = seedAdvance(this.employeeA, "1000", "600", "approved");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "amount", "1200");

		assertThat(this.jdbc.queryForObject(
				"SELECT remaining FROM advances WHERE id = " + id, java.math.BigDecimal.class))
				.isEqualByComparingTo("800");
	}

	@Test
	void reducingBelowWhatWasRepaidClearsTheDebtRatherThanGoingNegative() {
		long id = seedAdvance(this.employeeA, "1000", "600", "approved");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "amount", "300");

		assertThat(this.jdbc.queryForObject(
				"SELECT remaining FROM advances WHERE id = " + id, java.math.BigDecimal.class))
				.as("600 + (300 - 1000) is negative; the floor stops the employee becoming a creditor")
				.isEqualByComparingTo("0");
	}

	@Test
	void aSettledAdvanceCannotBeEdited() {
		long repaid = seedAdvance(this.employeeA, "1000", "0", "approved");
		long rejected = seedAdvance(this.employeeA, "500", "500", "rejected");

		for (long id : java.util.List.of(repaid, rejected)) {
			assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
					"action", "edit_advance", "id", String.valueOf(id),
					"employee_id", String.valueOf(this.employeeA), "amount", "9999")
					.getHeaders().getLocation()).asString()
					.as("advance %s", id).contains("error=error_required");
		}
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM advances WHERE amount = 9999", Integer.class)).isZero();
	}

	@Test
	void approvingIsOnlyLegalFromPending() {
		long pending = seedAdvance(this.employeeA, "1000", "1000", "pending");
		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(pending));
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM advances WHERE id = " + pending, String.class))
				.isEqualTo("approved");

		// A second approval is refused: an advance is decided once.
		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(pending))
				.getHeaders().getLocation()).asString().contains("error=error_required");
	}

	@Test
	void aRejectionMustSayWhy() {
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");

		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "rejection_reason", "   ")
				.getHeaders().getLocation()).asString().contains("error=rejection_reason_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM advances WHERE id = " + id, String.class)).isEqualTo("pending");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "rejection_reason", "not eligible");
		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT status, rejection_reason FROM advances WHERE id = " + id);
		assertThat(row.get("status")).isEqualTo("rejected");
		assertThat(row.get("rejection_reason")).isEqualTo("not eligible");
	}

	@Test
	void markPaidSettlesTheBalanceFromAnyStatus() {
		// Legacy checks no status here, so a pending advance becomes approved
		// and repaid in one statement -- an operator recording a repayment made
		// outside the system.
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "mark_paid", "id", String.valueOf(id));

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT status, remaining FROM advances WHERE id = " + id);
		assertThat(row.get("status")).isEqualTo("approved");
		assertThat((java.math.BigDecimal) row.get("remaining")).isEqualByComparingTo("0");
	}

	@Test
	void theStatusFilterNarrows() {
		seedAdvance(this.employeeA, "111", "111", "pending");
		seedAdvance(this.employeeA, "222", "0", "approved");

		assertThat(body("/admin/advances?status=pending")).contains("111").doesNotContain(">222<");
		assertThat(body("/admin/advances?status=approved")).contains("222");
		assertThat(body("/admin/advances")).contains("111").contains("222");
	}

	@Test
	void anUnfilteredAdministratorCannotMoveAnAdvanceBetweenCompanies() {
		// D-176: an advance has no company_id, so reassigning the employee is
		// moving the debt.
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");
		body("/admin/advances?company_id=");

		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeB), "amount", "1000")
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT employee_id FROM advances WHERE id = " + id, Long.class))
				.isEqualTo(this.employeeA);
	}

	@Test
	void anEditMayStillReassignWithinTheSameCompany() {
		long other = createEmployee(this.companyA, "A400", "Other", "Alpha");
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");
		body("/admin/advances?company_id=");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(other), "amount", "1000");

		assertThat(this.jdbc.queryForObject(
				"SELECT employee_id FROM advances WHERE id = " + id, Long.class)).isEqualTo(other);
	}

	@Test
	void approvingOutsideTheCurrentFilterIsRefused() {
		long betaAdvance = seedAdvance(this.employeeB, "500", "500", "pending");
		seedAdvance(this.employeeA, "500", "500", "pending");
		body("/admin/advances?company_id=" + this.companyA);

		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(betaAdvance))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM advances WHERE id = " + betaAdvance, String.class))
				.isEqualTo("pending");
	}

	@Test
	void deletingOutsideTheCurrentFilterIsRefused() {
		long betaAdvance = seedAdvance(this.employeeB, "500", "500", "pending");
		seedAdvance(this.employeeA, "500", "500", "pending");
		body("/admin/advances?company_id=" + this.companyA);

		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "delete_advance", "id", String.valueOf(betaAdvance))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM advances WHERE id = " + betaAdvance, Integer.class)).isEqualTo(1);
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "add_advance", "employee_id", String.valueOf(this.employeeA),
				"amount", "1000");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'advance'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/advances", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private long seedAdvance(long employeeId, String amount, String remaining, String status) {
		this.jdbc.update("INSERT INTO advances (employee_id, amount, remaining, status,"
				+ " request_date, created_at) VALUES (?, ?, ?, ?, '2026-03-02', NOW())",
				employeeId, new java.math.BigDecimal(amount), new java.math.BigDecimal(remaining),
				status);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM advances WHERE employee_id = ?", Long.class, employeeId);
	}

	private long createEmployee(long companyId, String code, String first, String last) {
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
		String sql = new String(AdminAdvancesEndToEndTest.class.getClassLoader()
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
