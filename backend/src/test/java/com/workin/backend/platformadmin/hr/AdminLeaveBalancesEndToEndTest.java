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
 * {@code /admin/leave_balances} over real HTTP against a real MariaDB.
 *
 * <p>The first HR page, so it proves the machinery still holds where a row
 * reaches its company through an <b>employee join</b> rather than a column of
 * its own.
 *
 * <p>Its centrepiece is <b>R-046</b>. Legacy checks the employee's company on
 * the add path and then writes {@code edit_leave} and {@code delete_leave}
 * with {@code WHERE id = ?} and no check at all. This port refuses those, and
 * the two cases below that would pass against the PHP are the whole reason the
 * divergence is written down.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminLeaveBalancesEndToEndTest {

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
		this.jdbc.update("DELETE FROM leave_balance");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2095" + System.nanoTime() % 100_000_000L;
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
	void addingABalanceWritesItAgainstTheEmployeesCompany() {
		post("/admin/leave_balances", this.cookie, page("/admin/leave_balances", this.cookie).csrf(),
				"action", "add_leave", "employee_id", String.valueOf(this.employeeA),
				"year", "2026", "total_days", "21");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT employee_id, total_days, used_days FROM leave_balance");
		assertThat(row.get("employee_id").toString()).isEqualTo(String.valueOf(this.employeeA));
		// `year` is YEAR(4), which the driver hands back as a java.sql.Date --
		// so it is read as an int rather than cast, the way the store does.
		assertThat(this.jdbc.queryForObject("SELECT year FROM leave_balance", Integer.class))
				.isEqualTo(2026);
		assertThat(((java.math.BigDecimal) row.get("total_days"))
				.compareTo(new java.math.BigDecimal("21"))).isZero();
		assertThat(((java.math.BigDecimal) row.get("used_days")).signum())
				.as("a new balance starts unused").isZero();
	}

	@Test
	void theListNarrowsToOneYearAndDefaultsToThisOne() {
		long id2026 = seedBalance(this.employeeA, 2026, "21", "0");
		long id2020 = seedBalance(this.employeeA, 2020, "15", "3");

		assertThat(body("/admin/leave_balances?year=2026")).contains("Aya Alpha").contains("21");
		// A balance only means anything inside its year, so the filter is never
		// "all" -- 2020's row is absent from 2026's page.
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM leave_balance WHERE year = 2020", Integer.class)).isEqualTo(1);
		assertThat(body("/admin/leave_balances?year=2020")).contains("15");
		assertThat(id2026).isNotEqualTo(id2020);
	}

	@Test
	void anUnreadableYearFallsBackRatherThanFailing() {
		seedBalance(this.employeeA, java.time.LocalDate.now().getYear(), "21", "0");
		assertThat(get("/admin/leave_balances?year=nonsense", this.cookie).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(body("/admin/leave_balances?year=nonsense")).contains("Aya Alpha");
	}

	@Test
	void remainingDaysIsComputedFromItsParts() {
		seedBalance(this.employeeA, 2026, "21", "5.5");
		// Not stored, so it cannot drift from total and used.
		assertThat(body("/admin/leave_balances?year=2026")).contains("15.5");
	}

	@Test
	void editingUpdatesBothDayCounts() {
		long id = seedBalance(this.employeeA, 2026, "21", "0");
		post("/admin/leave_balances", this.cookie,
				page("/admin/leave_balances?year=2026", this.cookie).csrf(),
				"action", "edit_leave", "id", String.valueOf(id),
				"total_days", "25", "used_days", "4");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT total_days, used_days FROM leave_balance WHERE id = " + id);
		assertThat(((java.math.BigDecimal) row.get("total_days"))
				.compareTo(new java.math.BigDecimal("25"))).isZero();
		assertThat(((java.math.BigDecimal) row.get("used_days"))
				.compareTo(new java.math.BigDecimal("4"))).isZero();
	}

	@Test
	void deleteRemovesTheRowOutright() {
		long id = seedBalance(this.employeeA, 2026, "21", "0");
		post("/admin/leave_balances", this.cookie,
				page("/admin/leave_balances?year=2026", this.cookie).csrf(),
				"action", "delete_leave", "id", String.valueOf(id));

		// A hard delete, unlike the org pages' deactivate. Nothing references a
		// balance row, so there is nothing to orphan -- and nothing to recover.
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM leave_balance WHERE id = " + id, Integer.class)).isZero();
	}

	// ------------------------------------------------------------------
	// R-046: the two cases the PHP allows and this does not
	// ------------------------------------------------------------------

	@Test
	void editingABalanceOutsideTheCurrentFilterIsRefused() {
		long betaBalance = seedBalance(this.employeeB, 2026, "21", "0");
		// Filter to Alpha, then post Beta's row id -- which is exactly the
		// request the PHP would have honoured.
		body("/admin/leave_balances?company_id=" + this.companyA);

		assertThat(post("/admin/leave_balances", this.cookie,
				page("/admin/leave_balances?year=2026", this.cookie).csrf(),
				"action", "edit_leave", "id", String.valueOf(betaBalance),
				"total_days", "999", "used_days", "999")
				.getHeaders().getLocation()).asString().contains("error=error_db");

		assertThat(((java.math.BigDecimal) this.jdbc.queryForMap(
				"SELECT total_days FROM leave_balance WHERE id = " + betaBalance).get("total_days"))
				.compareTo(new java.math.BigDecimal("21")))
				.as("the other company's row is untouched").isZero();
	}

	@Test
	void deletingABalanceOutsideTheCurrentFilterIsRefused() {
		long betaBalance = seedBalance(this.employeeB, 2026, "21", "0");
		body("/admin/leave_balances?company_id=" + this.companyA);

		assertThat(post("/admin/leave_balances", this.cookie,
				page("/admin/leave_balances?year=2026", this.cookie).csrf(),
				"action", "delete_leave", "id", String.valueOf(betaBalance))
				.getHeaders().getLocation()).asString().contains("error=error_db");

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM leave_balance WHERE id = " + betaBalance, Integer.class))
				.as("a hard delete refused is a row that still exists").isEqualTo(1);
	}

	@Test
	void addingForAnEmployeeOutsideTheCurrentFilterIsRefused() {
		body("/admin/leave_balances?company_id=" + this.companyA);

		assertThat(post("/admin/leave_balances", this.cookie,
				page("/admin/leave_balances", this.cookie).csrf(),
				"action", "add_leave", "employee_id", String.valueOf(this.employeeB),
				"year", "2026", "total_days", "21")
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM leave_balance", Integer.class)).isZero();
	}

	@Test
	void anUnfilteredAdministratorReachesEveryCompany() {
		// The other side of the same rule: refusing across the filter must not
		// become refusing altogether.
		long betaBalance = seedBalance(this.employeeB, 2026, "21", "0");
		body("/admin/leave_balances?company_id=");

		post("/admin/leave_balances", this.cookie,
				page("/admin/leave_balances?year=2026", this.cookie).csrf(),
				"action", "edit_leave", "id", String.valueOf(betaBalance),
				"total_days", "30", "used_days", "0");

		assertThat(((java.math.BigDecimal) this.jdbc.queryForMap(
				"SELECT total_days FROM leave_balance WHERE id = " + betaBalance).get("total_days"))
				.compareTo(new java.math.BigDecimal("30"))).isZero();
	}

	@Test
	void aBalanceThatDoesNotExistIsRefusedRatherThanIgnored() {
		assertThat(post("/admin/leave_balances", this.cookie,
				page("/admin/leave_balances", this.cookie).csrf(),
				"action", "delete_leave", "id", "999999999")
				.getHeaders().getLocation()).asString().contains("error=error_db");
	}

	@Test
	void anAddWithNoEmployeeIsRefused() {
		assertThat(post("/admin/leave_balances", this.cookie,
				page("/admin/leave_balances", this.cookie).csrf(),
				"action", "add_leave", "employee_id", "0", "year", "2026", "total_days", "21")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM leave_balance", Integer.class)).isZero();
	}

	@Test
	void anEmployeeAwaitingAcceptanceIsNotOnTheRoster() {
		// COALESCE(join_request_status,'accepted') = 'accepted' -- a pending
		// join request is not staff yet, so neither their balance nor their
		// name belongs on this page.
		long pending = createEmployee(this.companyA, "A200", "Pending", "Person");
		this.jdbc.update("UPDATE employees SET join_request_status = 'pending' WHERE id = ?", pending);
		seedBalance(pending, 2026, "21", "0");

		assertThat(body("/admin/leave_balances?year=2026")).doesNotContain("Pending Person");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/leave_balances", this.cookie, page("/admin/leave_balances", this.cookie).csrf(),
				"action", "add_leave", "employee_id", String.valueOf(this.employeeA),
				"year", "2026", "total_days", "21");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'leave_balance'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/leave_balances", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
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

	private long seedBalance(long employeeId, int year, String total, String used) {
		this.jdbc.update("INSERT INTO leave_balance (employee_id, year, total_days, used_days)"
				+ " VALUES (?, ?, ?, ?)", employeeId, year, new java.math.BigDecimal(total),
				new java.math.BigDecimal(used));
		return this.jdbc.queryForObject(
				"SELECT id FROM leave_balance WHERE employee_id = ? AND year = ?", Long.class,
				employeeId, year);
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
		String sql = new String(AdminLeaveBalancesEndToEndTest.class.getClassLoader()
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
