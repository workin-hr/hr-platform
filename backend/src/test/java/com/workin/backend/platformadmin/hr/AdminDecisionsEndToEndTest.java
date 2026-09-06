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
 * {@code /admin/administrative_decisions} over real HTTP against a real
 * MariaDB.
 *
 * <p>The simplest table on the surface -- a company, a title, a body and a
 * flag -- and the strongest example of <b>D-176</b>. Legacy's visibility guard
 * on this page is correct; its update then writes {@code company_id} from the
 * posted value, so an unfiltered administrator can transfer a decision from
 * one company to another (<b>R-047</b>). Here the column is not in the update
 * at all, and not on the edit form either.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminDecisionsEndToEndTest {

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
		this.jdbc.update("DELETE FROM administrative_decisions");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2100" + System.nanoTime() % 100_000_000L;
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
	void creatingADecisionStoresItAgainstTheChosenCompany() {
		post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions?action=add&company_id=" + this.companyA,
						this.cookie).csrf(),
				"action", "add_decision", "company_id", String.valueOf(this.companyA),
				"title", "Dress code", "body", "Smart casual from March.", "is_active", "1");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, title, body, is_active FROM administrative_decisions");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("title")).isEqualTo("Dress code");
		assertThat(row.get("body")).isEqualTo("Smart casual from March.");
		assertThat(row.get("is_active")).isEqualTo(Boolean.TRUE);
	}

	@Test
	void anAbsentActiveFlagMeansInactiveOnThisPage() {
		// `!empty($_POST['is_active'])` -- the opposite default from the org
		// pages, where an absent flag means active.
		post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions?action=add&company_id=" + this.companyA,
						this.cookie).csrf(),
				"action", "add_decision", "company_id", String.valueOf(this.companyA),
				"title", "Quiet", "body", "Not published yet.");

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM administrative_decisions", Boolean.class)).isFalse();
	}

	@Test
	void anEmptyTitleOrBodyOrCompanyIsRefused() {
		Csrf csrf = page("/admin/administrative_decisions?action=add&company_id=" + this.companyA,
				this.cookie).csrf();
		assertThat(post("/admin/administrative_decisions", this.cookie, csrf,
				"action", "add_decision", "company_id", String.valueOf(this.companyA),
				"title", "   ", "body", "Body")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(post("/admin/administrative_decisions", this.cookie, csrf,
				"action", "add_decision", "company_id", String.valueOf(this.companyA),
				"title", "Title", "body", "  ")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM administrative_decisions", Integer.class)).isZero();
	}

	@Test
	void anAddWithNoCompanyAnywhereIsRefused() {
		// `org_post_company_id()` falls back to the resolved filter, so posting
		// company_id=0 while filtered to a company creates it there. Only with
		// the filter cleared as well is there genuinely no company.
		body("/admin/administrative_decisions?company_id=");

		assertThat(post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions?action=add", this.cookie).csrf(),
				"action", "add_decision", "company_id", "0", "title", "Title", "body", "Body")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM administrative_decisions", Integer.class)).isZero();
	}

	@Test
	void aPostedCompanyFallsBackToTheCurrentFilter() {
		// The other half of the same rule, so the test above cannot pass by the
		// fallback being broken.
		body("/admin/administrative_decisions?company_id=" + this.companyA);
		post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions?action=add", this.cookie).csrf(),
				"action", "add_decision", "company_id", "0", "title", "Title", "body", "Body",
				"is_active", "1");

		assertThat(this.jdbc.queryForObject(
				"SELECT company_id FROM administrative_decisions", Long.class))
				.isEqualTo(this.companyA);
	}

	@Test
	void editingChangesTheTextAndTheFlag() {
		long id = seedDecision(this.companyA, "Old title", "Old body", false);

		post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions?action=edit&id=" + id, this.cookie).csrf(),
				"action", "edit_decision", "id", String.valueOf(id),
				"title", "New title", "body", "New body", "is_active", "1");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT title, body, is_active FROM administrative_decisions WHERE id = " + id);
		assertThat(row.get("title")).isEqualTo("New title");
		assertThat(row.get("body")).isEqualTo("New body");
		assertThat(row.get("is_active")).isEqualTo(Boolean.TRUE);
	}

	// ------------------------------------------------------------------
	// D-176 / R-047: the case this page is the strongest example of
	// ------------------------------------------------------------------

	@Test
	void anUnfilteredAdministratorCannotTransferADecisionBetweenCompanies() {
		// Legacy writes company_id from the posted value on an edit, and its
		// visibility guard passes any row for an unfiltered administrator -- so
		// this exact request moves the decision out of company A and into
		// company B. Here company_id is not in the update at all.
		long id = seedDecision(this.companyA, "Dress code", "Smart casual.", true);
		body("/admin/administrative_decisions?company_id=");

		post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions?action=edit&id=" + id, this.cookie).csrf(),
				"action", "edit_decision", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyB),
				"title", "Dress code", "body", "Smart casual.", "is_active", "1");

		assertThat(this.jdbc.queryForObject(
				"SELECT company_id FROM administrative_decisions WHERE id = " + id, Long.class))
				.as("the decision stays with the company that owns it")
				.isEqualTo(this.companyA);
	}

	@Test
	void theEditFormCarriesNoCompanyFieldAtAll() {
		// The invariant held in the markup as well as the service: there is
		// nothing on an edit form for a crafted request to imitate.
		long id = seedDecision(this.companyA, "Dress code", "Smart casual.", true);
		String page = body("/admin/administrative_decisions?action=edit&id=" + id);

		// The page as a whole has a company_id input -- the administrator's
		// filter at the top. What matters is the edit form, so look only
		// between its action marker and its closing tag.
		int formStart = page.indexOf("value=\"edit_decision\"");
		assertThat(formStart).as("the edit form is rendered").isGreaterThan(0);
		String editForm = page.substring(formStart, page.indexOf("</form>", formStart));

		assertThat(editForm).as("no company_id input inside the edit form")
				.doesNotContain("name=\"company_id\"");
		assertThat(editForm).as("it does carry the row id").contains("name=\"id\"");
	}

	@Test
	void editingARowOutsideTheCurrentFilterIsRefused() {
		long betaDecision = seedDecision(this.companyB, "Beta rule", "Body.", true);
		body("/admin/administrative_decisions?company_id=" + this.companyA);

		assertThat(post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions?action=add&company_id=" + this.companyA,
						this.cookie).csrf(),
				"action", "edit_decision", "id", String.valueOf(betaDecision),
				"title", "Changed", "body", "Changed")
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT title FROM administrative_decisions WHERE id = " + betaDecision,
				String.class)).isEqualTo("Beta rule");
	}

	@Test
	void deletingOutsideTheCurrentFilterIsRefused() {
		long betaDecision = seedDecision(this.companyB, "Beta rule", "Body.", true);
		seedDecision(this.companyA, "Alpha rule", "Body.", true);
		body("/admin/administrative_decisions?company_id=" + this.companyA);

		assertThat(post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions", this.cookie).csrf(),
				"action", "delete_decision", "id", String.valueOf(betaDecision))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM administrative_decisions WHERE id = " + betaDecision,
				Integer.class)).isEqualTo(1);
	}

	@Test
	void anUnfilteredAdministratorReachesEveryCompany() {
		long betaDecision = seedDecision(this.companyB, "Beta rule", "Body.", true);
		body("/admin/administrative_decisions?company_id=");

		post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions", this.cookie).csrf(),
				"action", "delete_decision", "id", String.valueOf(betaDecision));
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM administrative_decisions WHERE id = " + betaDecision,
				Integer.class)).isZero();
	}

	// ------------------------------------------------------------------

	@Test
	void theListFiltersByCompanyAndSearchesTitleAndBody() {
		seedDecision(this.companyA, "Dress code", "Smart casual.", true);
		seedDecision(this.companyB, "Parking", "Use the north lot.", true);

		assertThat(body("/admin/administrative_decisions"))
				.contains("Dress code").contains("Parking");
		assertThat(body("/admin/administrative_decisions?company_id=" + this.companyA))
				.contains("Dress code").doesNotContain("Parking");
		body("/admin/administrative_decisions?company_id=");
		assertThat(body("/admin/administrative_decisions?search=north"))
				.as("the search covers the body, not only the title")
				.contains("Parking").doesNotContain("Dress code");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/administrative_decisions", this.cookie,
				page("/admin/administrative_decisions?action=add&company_id=" + this.companyA,
						this.cookie).csrf(),
				"action", "add_decision", "company_id", String.valueOf(this.companyA),
				"title", "Audited", "body", "Body.", "is_active", "1");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events"
						+ " WHERE target_type = 'administrative_decision'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/administrative_decisions", HttpMethod.GET,
				new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private long seedDecision(long companyId, String title, String body, boolean active) {
		this.jdbc.update("INSERT INTO administrative_decisions (company_id, title, body,"
				+ " is_active, created_at) VALUES (?, ?, ?, ?, NOW())",
				companyId, title, body, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM administrative_decisions WHERE company_id = ?", Long.class,
				companyId);
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
		String sql = new String(AdminDecisionsEndToEndTest.class.getClassLoader()
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
