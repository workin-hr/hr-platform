package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.util.List;
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
import com.workin.backend.platformadmin.web.DashboardSession;
import com.workin.backend.platformadmin.mfa.Totp;

/**
 * {@code /admin/join_requests} over real HTTP against a real MariaDB.
 *
 * <p>A join request is an {@code employees} row whose
 * {@code join_request_status} is still {@code pending}, so both decisions
 * write to that table and one of them <b>deletes from it</b>. The tests that
 * matter here are the ones guarding that delete: another company's request,
 * and a request that is no longer pending.
 *
 * <p>Legacy gets the tenant rule right on this page --
 * {@code home_can_manage_join_employee()} compares the session's company
 * against the row's own -- so unlike its siblings there is no legacy patch
 * beside this port. These tests pin that it stays right.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminJoinRequestsEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF =
			Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	private static final String PATH = "/admin/join_requests";

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
	private TestRestTemplate restTemplate;

	@Autowired
	private PlatformAdminMfaService mfaService;

	@Autowired
	private JoinRequestAdminService service;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	private JdbcTemplate jdbc;

	private String cookie;

	private long companyA;

	private long companyB;

	private long branchA;

	private long branchB;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		// Children first: employees cascade from several tables, and a leftover
		// row blocks the company delete rather than failing loudly.
		this.jdbc.update("DELETE FROM attendance");
		this.jdbc.update("DELETE FROM employees");
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
		this.branchA = createBranch(this.companyA, "Alpha HQ");
		this.branchB = createBranch(this.companyB, "Beta HQ");
	}

	/**
	 * {@code employees.branch_id} is NOT NULL with a foreign key, so an employee
	 * cannot exist without a real branch -- the constraint R-055 records.
	 */
	private long createBranch(long companyId, String name) {
		this.jdbc.update("INSERT INTO branches (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE company_id = ? AND name = ?",
				Long.class, companyId, name);
	}

	private long createCompany(String name) {
		String phone = "01" + System.nanoTime() % 1_000_000_000L;
		this.jdbc.update("INSERT INTO companies (company_name, phone, password_hash, status,"
				+ " otp_verified, profile_completed, created_at)"
				+ " VALUES (?, ?, ?, 'active', 1, 1, NOW())",
				name, phone, this.passwordEncoder.encode(PASSWORD));
		return this.jdbc.queryForObject(
				"SELECT id FROM companies WHERE phone = ?", Long.class, phone);
	}

	/** An employees row in the state a join request is: role employee, a status. */
	private long createJoinRequest(long companyId, String name, String phone, String status) {
		long branchId = companyId == this.companyA ? this.branchA : this.branchB;
		this.jdbc.update("INSERT INTO employees (company_id, branch_id, first_name, last_name,"
				+ " phone, password_hash, role, join_request_status, is_active, created_at)"
				+ " VALUES (?, ?, ?, '', ?, ?, 'employee', ?, 1, NOW())",
				companyId, branchId, name, phone,
				this.passwordEncoder.encode(PASSWORD), status);
		return this.jdbc.queryForObject(
				"SELECT id FROM employees WHERE phone = ?", Long.class, phone);
	}

	private String statusOf(long id) {
		return this.jdbc.queryForObject(
				"SELECT join_request_status FROM employees WHERE id = ?", String.class, id);
	}

	private boolean exists(long id) {
		return this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE id = ?", Integer.class, id) > 0;
	}

	private int auditCount() {
		return this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events", Integer.class);
	}

	@Test
	void thePendingListIsTheDefaultAndShowsBothDecisions() {
		long id = createJoinRequest(this.companyA, "Aya", "01000000001", "pending");
		String html = body(PATH);
		assertThat(html).contains("Aya", "01000000001");
		assertThat(html).as("both decisions offered on a pending row")
				.contains("accept_join", "reject_join");
		assertThat(html).contains("value=\"" + id + "\"");
	}

	@Test
	void acceptingFlipsTheStatusAndActivatesTheEmployee() {
		long id = createJoinRequest(this.companyA, "Bassem", "01000000002", "pending");
		this.jdbc.update("UPDATE employees SET is_active = 0 WHERE id = ?", id);

		postForm("action", "accept_join", "id", String.valueOf(id));

		assertThat(statusOf(id)).isEqualTo("accepted");
		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM employees WHERE id = ?", Integer.class, id)).isEqualTo(1);
	}

	@Test
	void rejectingDeletesTheEmployeeRow() {
		// Not a status change: legacy deletes, on this surface and in the API.
		long id = createJoinRequest(this.companyA, "Carine", "01000000003", "pending");
		postForm("action", "reject_join", "id", String.valueOf(id));
		assertThat(exists(id)).as("the employee row is gone").isFalse();
	}

	@Test
	void anAdministratorActsAcrossCompaniesEvenWithAFilterSet() {
		// This page's tenant rule is home_can_manage_join_employee(), and its
		// first branch is `if (isAdmin()) return true` -- the company filter is
		// never consulted for an administrator. Every R-046-patched page uses
		// hr_verify_post_row() instead, which *does* confine a filtered
		// administrator. Two legacy rules, reproduced as they are, and the
		// divergence is R-061.
		long foreign = createJoinRequest(this.companyB, "Dalia", "01000000004", "pending");
		get(PATH + "&company_id=" + this.companyA, this.cookie);

		postForm("action", "accept_join", "id", String.valueOf(foreign));

		assertThat(statusOf(foreign))
				.as("the filter is a view convenience here, not a boundary")
				.isEqualTo("accepted");
	}

	@Test
	void theScopedSessionBranchRefusesAnotherCompanysRow() {
		// The branch that is dormant while every session here is an
		// administrator's, and the one that will matter when the company-owner
		// and HR logins arrive (ADR-0016, R-044). Asserted against the service
		// rather than over HTTP, because no HTTP session can currently produce
		// a scoped audience.
		long foreign = createJoinRequest(this.companyB, "Diaa", "01000000019", "pending");
		DashboardSession scoped = DashboardSession.company(this.companyA);

		assertThatThrownBy(() -> this.service.accept(scoped, 1L, true, foreign))
				.isInstanceOf(JoinRequestAdminService.RefusedException.class);
		assertThat(statusOf(foreign)).isEqualTo("pending");
		assertThat(exists(foreign)).isTrue();
	}

	@Test
	void anAlreadyAcceptedRequestCannotBeRejected() {
		// This is what stops the reject button deleting a working employee.
		long id = createJoinRequest(this.companyA, "Emad", "01000000005", "accepted");
		postForm("action", "reject_join", "id", String.valueOf(id));
		assertThat(exists(id)).as("an accepted employee is not deletable here").isTrue();
	}

	@Test
	void anAlreadyAcceptedRequestCannotBeAcceptedAgain() {
		long id = createJoinRequest(this.companyA, "Farida", "01000000006", "accepted");
		int before = auditCount();
		postForm("action", "accept_join", "id", String.valueOf(id));
		assertThat(auditCount()).as("refused before it wrote anything").isEqualTo(before);
	}

	@Test
	void aRowThatIsNotAnEmployeeIsNotAJoinRequest() {
		long id = createJoinRequest(this.companyA, "Ghada", "01000000007", "pending");
		this.jdbc.update("UPDATE employees SET role = 'hr' WHERE id = ?", id);
		postForm("action", "reject_join", "id", String.valueOf(id));
		assertThat(exists(id)).as("role gates the page, as legacy's ROLE_EMPLOYEE check does")
				.isTrue();
	}

	@Test
	void theStatusFilterSelectsAndFallsBackToPending() {
		createJoinRequest(this.companyA, "Hana", "01000000008", "pending");
		createJoinRequest(this.companyA, "Islam", "01000000009", "accepted");

		assertThat(body(PATH + "&status=pending")).contains("Hana").doesNotContain("Islam");
		assertThat(body(PATH + "&status=accepted")).contains("Islam").doesNotContain("Hana");
		assertThat(body(PATH + "&status=all")).contains("Hana", "Islam");
		assertThat(body(PATH + "&status=nonsense")).as("an unknown status falls back to pending")
				.contains("Hana").doesNotContain("Islam");
	}

	@Test
	void theRejectedFilterIsOfferedAndIsAlwaysEmpty() {
		// A valid enum value that nothing ever writes, because both reject paths
		// delete instead. Pinned so the dead option is a decision, not a bug.
		createJoinRequest(this.companyA, "Kamal", "01000000010", "pending");
		String html = body(PATH + "&status=rejected");
		assertThat(html).as("the option is still offered").contains("value=\"rejected\"");
		assertThat(html).doesNotContain("Kamal");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE join_request_status = 'rejected'",
				Integer.class)).isZero();
	}

	@Test
	void anAcceptedRowOffersEditRatherThanADecision() {
		long id = createJoinRequest(this.companyA, "Laila", "01000000011", "accepted");
		String html = body(PATH + "&status=accepted");
		// JTE does not escape & in an attribute value, and legacy emits a bare
		// & here too.
		assertThat(html).contains("/admin/employees?action=edit&id=" + id);
		assertThat(html).doesNotContain("accept_join");
	}

	@Test
	void anUnfilteredAdministratorSeesEveryCompany() {
		createJoinRequest(this.companyA, "Mona", "01000000012", "pending");
		createJoinRequest(this.companyB, "Nabil", "01000000013", "pending");
		assertThat(body(PATH)).contains("Mona", "Nabil");
		assertThat(body(PATH + "&company_id=" + this.companyA))
				.contains("Mona").doesNotContain("Nabil");
	}

	@Test
	void aPostWithoutTheCsrfTokenIsRefused() {
		long id = createJoinRequest(this.companyA, "Omar", "01000000014", "pending");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + this.cookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("action", "reject_join");
		form.add("id", String.valueOf(id));
		assertThat(this.restTemplate.exchange(PATH, HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(exists(id)).isTrue();
	}

	@Test
	void thePageIsUnreachableWithoutASession() {
		ResponseEntity<String> response = get(PATH, null);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/admin/login");
	}

	@Test
	void anUnknownActionWritesNothing() {
		long id = createJoinRequest(this.companyA, "Rania", "01000000015", "pending");
		int before = auditCount();
		postForm("action", "approve_everything", "id", String.valueOf(id));
		assertThat(exists(id)).isTrue();
		assertThat(statusOf(id)).isEqualTo("pending");
		assertThat(auditCount()).isEqualTo(before);
	}

	@Test
	void bothDecisionsAreAudited() {
		long accept = createJoinRequest(this.companyA, "Sara", "01000000016", "pending");
		long reject = createJoinRequest(this.companyA, "Tarek", "01000000017", "pending");
		int before = auditCount();
		postForm("action", "accept_join", "id", String.valueOf(accept));
		postForm("action", "reject_join", "id", String.valueOf(reject));
		assertThat(auditCount()).isEqualTo(before + 2);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events WHERE target_type = 'employees'",
				Integer.class)).isGreaterThanOrEqualTo(2);
	}

	@Test
	void theDecisionReturnsToTheFilterItWasMadeFrom() {
		long id = createJoinRequest(this.companyA, "Wael", "01000000018", "pending");
		ResponseEntity<String> response = post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "accept_join", "id", String.valueOf(id),
				"redirect_status", "all");
		assertThat(response.getHeaders().getLocation().toString()).contains("status=all");
	}

	private String postForm(String... fields) {
		return postTo(PATH, fields);
	}

	private String postTo(String path, String... fields) {
		return post(path, this.cookie, page(PATH, this.cookie).csrf(), fields).getBody();
	}

	private String body(String path) {
		return get(path, this.cookie).getBody();
	}

	/**
	 * Every request carries {@code lang=en}. Callers append their own
	 * parameters with a leading {@code &}, and this puts the {@code ?} in front
	 * of the lot.
	 */
	private ResponseEntity<String> get(String path, String sessionCookie) {
		HttpHeaders headers = new HttpHeaders();
		if (sessionCookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		}
		return this.restTemplate.exchange(withLang(path), HttpMethod.GET,
				new HttpEntity<>(headers), String.class);
	}

	private Page page(String path, String sessionCookie) {
		ResponseEntity<String> response = get(path, sessionCookie);
		String resolved = sessionCookie != null ? sessionCookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	private ResponseEntity<String> post(
			String path, String sessionCookie, Csrf csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + sessionCookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int index = 0; index < fields.length; index += 2) {
			form.add(fields[index], fields[index + 1]);
		}
		form.add(csrf.name(), csrf.value());
		return this.restTemplate.exchange(withLang(path), HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class);
	}

	private static String withLang(String path) {
		int firstParameter = path.indexOf('&');
		return firstParameter < 0
				? path + "?lang=en"
				: path.substring(0, firstParameter) + "?lang=en" + path.substring(firstParameter);
	}

	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private static Csrf csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new Csrf(matcher.group(1), matcher.group(2));
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

	private static String code(String base32Seed) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(java.time.Instant.now()));
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

	private static void applySchema(String resource) throws Exception {
		String sql = new String(AdminJoinRequestsEndToEndTest.class.getClassLoader()
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
