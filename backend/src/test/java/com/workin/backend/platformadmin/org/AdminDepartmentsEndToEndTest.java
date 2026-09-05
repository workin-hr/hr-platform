package com.workin.backend.platformadmin.org;

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
 * {@code /admin/departments} over real HTTP against a real MariaDB.
 *
 * <p>The list, filter and pagination machinery is the branches page's and is
 * proved there. This concentrates on what departments add: the
 * {@code department_branches} link table, the rule that a department must span
 * at least one branch, and the check that every branch it names belongs to the
 * same company -- which is a cross-tenant write dressed as a checkbox.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminDepartmentsEndToEndTest {

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

	private long branchA1;

	private long branchA2;

	private long branchB1;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM department_branches");
		this.jdbc.update("DELETE FROM departments");
		this.jdbc.update("DELETE FROM branches");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2092" + System.nanoTime() % 100_000_000L;
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
		this.branchA1 = seedBranch(this.companyA, "Alpha North");
		this.branchA2 = seedBranch(this.companyA, "Alpha South");
		this.branchB1 = seedBranch(this.companyB, "Beta Central");
	}

	@Test
	void addingADepartmentLinksEveryBranchItNames() {
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Engineering",
				"branch_ids", String.valueOf(this.branchA1), "branch_ids", String.valueOf(this.branchA2));

		long id = this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE name = 'Engineering'", Long.class);
		assertThat(this.jdbc.queryForList(
				"SELECT branch_id FROM department_branches WHERE department_id = ? ORDER BY branch_id",
				Long.class, id))
				.containsExactly(Math.min(this.branchA1, this.branchA2), Math.max(this.branchA1, this.branchA2));

		// The list shows them joined, in name order, from the GROUP_CONCAT.
		assertThat(body("/admin/departments")).contains("Alpha North, Alpha South");
	}

	@Test
	void aDepartmentWithNoBranchIsRefused() {
		// The only validation rule this page has, and the reason a department
		// is not simply a branch with a different table.
		ResponseEntity<String> refused = post("/admin/departments", this.cookie,
				page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Orphan");

		assertThat(refused.getHeaders().getLocation()).asString()
				.contains("action=add").contains("error=select_at_least_one_branch");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM departments", Integer.class)).isZero();
	}

	@Test
	void aBranchFromAnotherCompanyIsRefusedForTheAdministratorToo() {
		// Unlike the row check, which the administrator skips by design, this
		// one runs for everyone: linking two companies' data is not the
		// cross-company mode, it is a broken row.
		ResponseEntity<String> refused = post("/admin/departments", this.cookie,
				page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Mixed",
				"branch_ids", String.valueOf(this.branchA1),
				"branch_ids", String.valueOf(this.branchB1));

		assertThat(refused.getHeaders().getLocation()).asString()
				.contains("error=select_at_least_one_branch");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM departments", Integer.class))
				.as("and nothing partial was written -- the insert and the links share a transaction")
				.isZero();
	}

	@Test
	void anEmptyNameIsRefusedBeforeTheBranchesAreLookedAt() {
		// Legacy checks the name first, so this is the message an operator sees
		// when both are wrong.
		assertThat(post("/admin/departments", this.cookie,
				page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "   ")
				.getHeaders().getLocation()).asString().contains("error=error_required");
	}

	@Test
	void aRepeatedBranchIsDeduplicatedRatherThanFailingTheInsert() {
		// department_branches has a unique pair, so posting the same id twice
		// would fail the second insert without the dedupe.
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Doubled",
				"branch_ids", String.valueOf(this.branchA1),
				"branch_ids", String.valueOf(this.branchA1));

		long id = this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE name = 'Doubled'", Long.class);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM department_branches WHERE department_id = ?", Integer.class, id))
				.isEqualTo(1);
	}

	@Test
	void editingReplacesTheBranchSetRatherThanAddingToIt() {
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Movable",
				"branch_ids", String.valueOf(this.branchA1), "branch_ids", String.valueOf(this.branchA2));
		long id = this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE name = 'Movable'", Long.class);

		Page form = page("/admin/departments?action=edit&id=" + id, this.cookie);
		// JTE emits a bare `checked` for a true boolean attribute, and the
		// template puts it on its own line -- so count the boxes rather than
		// matching value and checked as adjacent text.
		assertThat(form.response().getBody().split("\\bchecked\\b", -1).length - 1)
				.as("the picker pre-checks exactly what the department already spans")
				.isEqualTo(2);

		post("/admin/departments", this.cookie, form.csrf(), "action", "save_edit",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"name", "Movable", "branch_ids", String.valueOf(this.branchA2), "is_active", "1");

		assertThat(this.jdbc.queryForList(
				"SELECT branch_id FROM department_branches WHERE department_id = ?", Long.class, id))
				.as("delete-then-insert, so the old link is gone")
				.containsExactly(this.branchA2);
	}

	@Test
	void deleteDeactivatesAndLeavesTheBranchLinksAlone() {
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Closing",
				"branch_ids", String.valueOf(this.branchA1));
		long id = this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE name = 'Closing'", Long.class);

		post("/admin/departments", this.cookie, page("/admin/departments", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM departments WHERE id = " + id, Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM department_branches WHERE department_id = ?", Integer.class, id))
				.as("deactivating is not detaching: reactivating must bring the branches back")
				.isEqualTo(1);
	}

	@Test
	void theBranchFilterNarrowsToDepartmentsSpanningThatBranch() {
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "North Only",
				"branch_ids", String.valueOf(this.branchA1));
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "South Only",
				"branch_ids", String.valueOf(this.branchA2));

		assertThat(body("/admin/departments?filter_branch=" + this.branchA1))
				.contains("North Only").doesNotContain("South Only");
	}

	@Test
	void thePickerOffersOnlyTheFilteredCompanysBranches() {
		String unfiltered = body("/admin/departments?company_id=&action=add");
		assertThat(unfiltered).as("every company's, labelled by company")
				.contains("Alpha North").contains("Beta Central").contains("Beta Co");

		String filtered = body("/admin/departments?company_id=" + this.companyA + "&action=add");
		assertThat(filtered).contains("Alpha North").doesNotContain("Beta Central");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Audited",
				"branch_ids", String.valueOf(this.branchA1));

		List<Map<String, Object>> events = this.jdbc.queryForList(
				"SELECT event_type, target_type FROM platform_admin_audit_events"
						+ " WHERE target_type = 'department'");
		assertThat(events).singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/departments", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
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

	private long seedBranch(long companyId, String name) {
		this.jdbc.update("INSERT INTO branches (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE company_id = ? AND name = ?", Long.class, companyId, name);
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
		String sql = new String(AdminDepartmentsEndToEndTest.class.getClassLoader()
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
