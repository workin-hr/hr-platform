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
 * {@code /admin/requests} over real HTTP against a real MariaDB.
 *
 * <p>Approving is not a status change: it deducts leave and can write one
 * attendance row per day of the request's span. Those side effects are most of
 * what is worth testing here, together with the two things that decide whether
 * they happen at all -- the request type's flags and the employee's balance.
 *
 * <p>And <b>R-046</b>: legacy scopes {@code approve} properly through
 * {@code dashboard_request_fetch_for_approval()} and leaves {@code reject} and
 * {@code delete} writing by id alone. Both are guarded here.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminRequestsEndToEndTest {

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

	private long plainTypeA;

	private long deductingTypeA;

	private long typeB;


	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM attendance");
		this.jdbc.update("DELETE FROM requests");
		this.jdbc.update("DELETE FROM request_types");
		this.jdbc.update("DELETE FROM exception_types");
		this.jdbc.update("DELETE FROM leave_balance");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2096" + System.nanoTime() % 100_000_000L;
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
		this.plainTypeA = createType(this.companyA, "Unpaid", false, false);
		this.deductingTypeA = createType(this.companyA, "Annual", true, false);
		this.typeB = createType(this.companyB, "Annual", true, false);

	}

	@Test
	void approvingAPlainRequestChangesOnlyItsStatus() {
		long id = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id), "comment", "fine");

		assertThat(statusOf(id)).isEqualTo("approved");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM requests WHERE id = ?", String.class, id)).isEqualTo("fine");
		assertThat(this.jdbc.queryForObject(
				"SELECT decided_at FROM requests WHERE id = ?", String.class, id)).isNotNull();
		// The type neither deducts nor writes attendance, so nothing else moved.
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM leave_balance", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
	}

	@Test
	void approvingADeductingRequestSpendsTheBalanceInclusively() {
		seedBalance(this.employeeA, 2026, "21", "0");
		// 2nd to 4th is three days, both ends counted.
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA)
				.compareTo(new java.math.BigDecimal("3"))).isZero();
	}

	@Test
	void aDeductionLandsInTheYearTheLeaveStartsIn() {
		// Not this year, and not the year it ends in either.
		seedBalance(this.employeeA, 2025, "21", "0");
		seedBalance(this.employeeA, 2026, "21", "0");
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2025-12-30", "2026-01-02");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2025",
				java.math.BigDecimal.class, this.employeeA)
				.compareTo(new java.math.BigDecimal("4"))).as("four days, in 2025").isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA).signum()).isZero();
	}

	@Test
	void anEmployeeWithNoBalanceForTheYearGetsOneCreated() {
        // dashboard_request_insufficient_leave_balance() returns false when the
        // year has no row -- a missing year was never granted, and refusing
        // would make the first request of a new year impossible to approve.
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-03");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(statusOf(id)).isEqualTo("approved");
		Map<String, Object> created = this.jdbc.queryForMap(
				"SELECT total_days, used_days FROM leave_balance WHERE employee_id = " + this.employeeA);
		assertThat(((java.math.BigDecimal) created.get("total_days"))
				.compareTo(new java.math.BigDecimal("15"))).as("the 15-day default").isZero();
		assertThat(((java.math.BigDecimal) created.get("used_days"))
				.compareTo(new java.math.BigDecimal("2"))).isZero();
	}

	@Test
	void anInsufficientBalanceRefusesWithNothingWritten() {
		seedBalance(this.employeeA, 2026, "5", "4");
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-06");

		assertThat(post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id))
				.getHeaders().getLocation()).asString().contains("error=insufficient_leave_balance");

		assertThat(statusOf(id)).as("checked before deciding, so not approved").isEqualTo("pending");
		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA)
				.compareTo(new java.math.BigDecimal("4"))).as("and nothing spent").isZero();
	}

	@Test
	void approvingAnExceptionTypeWritesOneAttendanceRowPerDay() {
		long exceptionType = createExceptionType(this.companyA, "Leave");
		long type = createType(this.companyA, "Sick", false, true);
		this.jdbc.update("UPDATE request_types SET exception_type_id = ? WHERE id = ?",
				exceptionType, type);
		long id = seedRequest(this.employeeA, type, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		List<Map<String, Object>> rows = this.jdbc.queryForList(
				"SELECT DATE(check_in) AS d, method, exception_type_id FROM attendance"
						+ " WHERE employee_id = ? ORDER BY check_in", this.employeeA);
		assertThat(rows).hasSize(3);
		assertThat(rows.get(0).get("method")).as("the literal 'app'").isEqualTo("app");
		assertThat(rows.get(0).get("exception_type_id").toString())
				.isEqualTo(String.valueOf(exceptionType));
	}

	@Test
	void aDayTheEmployeeAlreadyAttendedIsSkipped() {
		long exceptionType = createExceptionType(this.companyA, "Leave");
		long type = createType(this.companyA, "Sick", false, true);
		this.jdbc.update("UPDATE request_types SET exception_type_id = ? WHERE id = ?",
				exceptionType, type);
		this.jdbc.update("INSERT INTO attendance (employee_id, check_in, method)"
				+ " VALUES (?, '2026-03-03 09:00:00', 'excel')", this.employeeA);
		long id = seedRequest(this.employeeA, type, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		// Three days, one already attended, so two new rows -- and the existing
		// one is untouched rather than overwritten.
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM attendance WHERE employee_id = ?", Integer.class, this.employeeA))
				.isEqualTo(3);
		assertThat(this.jdbc.queryForObject(
				"SELECT method FROM attendance WHERE employee_id = ? AND DATE(check_in) = '2026-03-03'",
				String.class, this.employeeA)).isEqualTo("excel");
	}

	@Test
	void aCompanyWithNoExceptionTypeWritesNothingRatherThanFailing() {
		long type = createType(this.companyA, "Sick", false, true);
		long id = seedRequest(this.employeeA, type, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(statusOf(id)).as("the approval still succeeds").isEqualTo("approved");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
	}

	@Test
	void anAlreadyDecidedRequestCannotBeApprovedAgain() {
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-04");
		this.jdbc.update("UPDATE requests SET status = 'approved' WHERE id = ?", id);
		seedBalance(this.employeeA, 2026, "21", "0");

		assertThat(post("/admin/requests", this.cookie,
				page("/admin/requests?status=all", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id))
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA).signum())
				.as("a second approval would have deducted twice").isZero();
	}

	@Test
	void rejectingChangesTheStatusAndNothingElse() {
		seedBalance(this.employeeA, 2026, "21", "0");
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "comment", "no");

		assertThat(statusOf(id)).isEqualTo("rejected");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM requests WHERE id = ?", String.class, id)).isEqualTo("no");
		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA).signum())
				.as("a rejection deducts nothing").isZero();
	}

	@Test
	void anEmptyCommentIsStoredAsNullNotAnEmptyString() {
		long id = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "comment", "   ");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM requests WHERE id = ?", String.class, id)).isNull();
	}

	// ------------------------------------------------------------------
	// R-046
	// ------------------------------------------------------------------

	@Test
	void rejectingARequestOutsideTheCurrentFilterIsRefused() {
		long betaRequest = seedRequest(this.employeeB, this.typeB, "2026-03-02", "2026-03-04");
		// Alpha needs a row of its own: the page renders action forms only for
		// the rows it lists, so a filtered-to-empty page carries no CSRF token
		// and the request could not be made at all.
		seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		body("/admin/requests?company_id=" + this.companyA);

		assertThat(post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(betaRequest))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(statusOf(betaRequest)).isEqualTo("pending");
	}

	@Test
	void deletingARequestOutsideTheCurrentFilterIsRefused() {
		long betaRequest = seedRequest(this.employeeB, this.typeB, "2026-03-02", "2026-03-04");
		// Alpha needs a row of its own: the page renders action forms only for
		// the rows it lists, so a filtered-to-empty page carries no CSRF token
		// and the request could not be made at all.
		seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		body("/admin/requests?company_id=" + this.companyA);

		assertThat(post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(betaRequest))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM requests WHERE id = " + betaRequest, Integer.class)).isEqualTo(1);
	}

	@Test
	void approvingARequestOutsideTheCurrentFilterIsRefused() {
		long betaRequest = seedRequest(this.employeeB, this.typeB, "2026-03-02", "2026-03-04");
		// Alpha needs a row of its own: the page renders action forms only for
		// the rows it lists, so a filtered-to-empty page carries no CSRF token
		// and the request could not be made at all.
		seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		body("/admin/requests?company_id=" + this.companyA);

		assertThat(post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(betaRequest))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(statusOf(betaRequest)).isEqualTo("pending");
	}

	@Test
	void anUnfilteredAdministratorReachesEveryCompany() {
		long betaRequest = seedRequest(this.employeeB, this.typeB, "2026-03-02", "2026-03-04");
		body("/admin/requests?company_id=");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(betaRequest));
		assertThat(statusOf(betaRequest)).isEqualTo("rejected");
	}

	// ------------------------------------------------------------------

	@Test
	void theListDefaultsToPendingRatherThanEverything() {
		long pending = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		long decided = seedRequest(this.employeeA, this.plainTypeA, "2026-04-02", "2026-04-04");
		this.jdbc.update("UPDATE requests SET status = 'approved' WHERE id = ?", decided);

		assertThat(body("/admin/requests")).contains("2026-03-02").doesNotContain("2026-04-02");
		assertThat(body("/admin/requests?status=all"))
				.contains("2026-03-02").contains("2026-04-02");
		assertThat(pending).isNotEqualTo(decided);
	}

	@Test
	void theDateFiltersBothCompareAgainstTheStartDate() {
		seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-20");
		seedRequest(this.employeeA, this.plainTypeA, "2026-05-02", "2026-05-04");

		// A request that starts inside the window and ends outside it is in;
		// legacy compares both bounds against from_date.
		assertThat(body("/admin/requests?date_from=2026-03-01&date_to=2026-03-31"))
				.contains("2026-03-02").doesNotContain("2026-05-02");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		long id = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'request'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_UPDATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/requests", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private long seedBalance(long employeeId, int year, String total, String used) {
		this.jdbc.update("INSERT INTO leave_balance (employee_id, year, total_days, used_days)"
				+ " VALUES (?, ?, ?, ?)", employeeId, year, new java.math.BigDecimal(total),
				new java.math.BigDecimal(used));
		return this.jdbc.queryForObject(
				"SELECT id FROM leave_balance WHERE employee_id = ? AND year = ?", Long.class,
				employeeId, year);
	}

	private long createType(long companyId, String name, boolean deduct, boolean exception) {
		this.jdbc.update("INSERT INTO request_types (company_id, name, deduct_balance,"
				+ " counts_as_paid_leave, add_attendance_exception, is_active, created_at)"
				+ " VALUES (?, ?, ?, 0, ?, 1, NOW())",
				companyId, name, deduct ? 1 : 0, exception ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM request_types WHERE company_id = ? AND name = ?", Long.class,
				companyId, name);
	}

	private long seedRequest(long employeeId, long typeId, String from, String to) {
		this.jdbc.update("INSERT INTO requests (employee_id, request_type_id, status, from_date,"
				+ " to_date, notes, created_at) VALUES (?, ?, 'pending', ?, ?, 'because', NOW())",
				employeeId, typeId, from, to);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM requests WHERE employee_id = ?", Long.class, employeeId);
	}

	private String statusOf(long id) {
		return this.jdbc.queryForObject("SELECT status FROM requests WHERE id = ?", String.class, id);
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

	private long createExceptionType(long companyId, String name) {
		this.jdbc.update("INSERT INTO exception_types (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM exception_types WHERE company_id = ? AND name = ?", Long.class,
				companyId, name);
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
		String sql = new String(AdminRequestsEndToEndTest.class.getClassLoader()
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
