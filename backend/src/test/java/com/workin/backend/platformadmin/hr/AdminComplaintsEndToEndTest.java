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
 * {@code /admin/complaints} over real HTTP against a real MariaDB.
 *
 * <p>D-176 does not bite here -- no write touches an ownership column and
 * there is no editable foreign key. What this page has instead is a rule about
 * <b>source</b>, a {@code company_id} that is <b>nullable</b> on this table
 * alone, and a status column whose enum legacy writes into unvalidated
 * (<b>R-048</b>).
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminComplaintsEndToEndTest {

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
		this.jdbc.update("DELETE FROM complaints");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
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
		this.employeeA = createEmployee(this.companyA, "A100", "Aya", "Alpha");
		this.employeeB = createEmployee(this.companyB, "B100", "Basma", "Beta");


	}

	@Test
	void everyFilterIsOptionalAndAnAbsentOneNeverBreaksThePage() {
		// This is a regression test with a specific shape in mind.
		// `List.of(...).contains(null)` throws rather than answering false, so
		// an absent filter reaching a whitelist check 500s the page for
		// everyone -- which is exactly what happened, and is invisible to a
		// store-level unit test because the null arrives from the request.
		//
		// Named rather than left implicit: most tests below happen to omit
		// these parameters, so they would all fail together and none of them
		// would say why.
		seedComplaint(this.companyA, "employee", "Aya", "Too cold", "pending");

		for (String query : java.util.List.of(
				"",
				"?status=",
				"?source=",
				"?search=",
				"?date_from=&date_to=",
				"?company_id=",
				"?status=&source=&search=&date_from=&date_to=&company_id=")) {
			assertThat(get("/admin/complaints" + query, this.cookie).getStatusCode())
					.as("no filters should render, query '%s'", query)
					.isEqualTo(HttpStatus.OK);
		}
		// And a value outside the whitelist is ignored as a filter rather than
		// throwing -- the other way this check can go wrong.
		assertThat(get("/admin/complaints?status=archived&source=nonsense", this.cookie)
				.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(body("/admin/complaints?status=archived"))
				.as("an unrecognised status narrows nothing rather than erroring")
				.contains("Too cold");
	}

	@Test
	void replyingStoresTheAnswerAndTheStatus() {
		long id = seedComplaint(this.companyA, "employee", "Aya", "Too cold", "pending");

		post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "reply", "id", String.valueOf(id),
				"reply", "Heating fixed.", "status", "done");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT reply, status FROM complaints WHERE id = " + id);
		assertThat(row.get("reply")).isEqualTo("Heating fixed.");
		assertThat(row.get("status")).isEqualTo("done");
	}

	@Test
	void anEmptyReplyIsStoredAsNull() {
		long id = seedComplaint(this.companyA, "employee", "Aya", "Too cold", "pending");
		post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "reply", "id", String.valueOf(id), "reply", "   ", "status", "pending");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM complaints WHERE id = " + id, String.class)).isNull();
	}

	@Test
	void aStatusOutsideTheEnumIsRefusedRatherThanStoredEmpty() {
		// R-048. Legacy writes the reply's status unvalidated, and production
		// runs a non-strict sql_mode, so an unrecognised value lands as '' --
		// measured against this database, not assumed. A complaint with no
		// status renders as nothing and matches no filter.
		long id = seedComplaint(this.companyA, "employee", "Aya", "Too cold", "pending");

		assertThat(post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "reply", "id", String.valueOf(id), "reply", "ok", "status", "archived")
				.getHeaders().getLocation()).asString().contains("error=error_required");

		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM complaints WHERE id = " + id, String.class))
				.as("untouched, and certainly not empty").isEqualTo("pending");
	}

	@Test
	void anAbsentStatusOnAReplyMeansPending() {
		long id = seedComplaint(this.companyA, "employee", "Aya", "Too cold", "done");
		post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "reply", "id", String.valueOf(id), "reply", "noted");
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM complaints WHERE id = " + id, String.class))
				.as("`$_POST['status'] ?? 'pending'`").isEqualTo("pending");
	}

	@Test
	void setStatusAcceptsOnlyTheThreeValues() {
		long id = seedComplaint(this.companyA, "employee", "Aya", "Too cold", "pending");

		for (String good : java.util.List.of("pending", "done", "closed")) {
			post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
					"action", "set_status", "id", String.valueOf(id), "status", good);
			assertThat(this.jdbc.queryForObject(
					"SELECT status FROM complaints WHERE id = " + id, String.class))
					.as("status %s", good).isEqualTo(good);
		}
		assertThat(post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "set_status", "id", String.valueOf(id), "status", "archived")
				.getHeaders().getLocation()).asString().contains("error=error_required");
	}

	@Test
	void deletingRemovesTheComplaint() {
		long id = seedComplaint(this.companyA, "employee", "Aya", "Too cold", "pending");
		post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id));
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM complaints WHERE id = " + id, Integer.class)).isZero();
	}

	@Test
	void anAdministratorSeesBothKindsAndCanFilterBySource() {
		seedComplaint(this.companyA, "employee", "Aya", "Too cold", "pending");
		seedComplaint(this.companyA, "company_support", "Alpha Co", "Billing question", "pending");

		assertThat(body("/admin/complaints")).contains("Too cold").contains("Billing question");
		assertThat(body("/admin/complaints?source=employee"))
				.contains("Too cold").doesNotContain("Billing question");
		assertThat(body("/admin/complaints?source=company_support"))
				.contains("Billing question").doesNotContain("Too cold");
	}

	@Test
	void theSearchReachesTheMessageAndTheCompanyName() {
		seedComplaint(this.companyA, "employee", "Aya", "Radiator broken", "pending");
		seedComplaint(this.companyB, "employee", "Basma", "Lift broken", "pending");

		assertThat(body("/admin/complaints?search=Radiator"))
				.contains("Radiator broken").doesNotContain("Lift broken");
		assertThat(body("/admin/complaints?search=Beta Co"))
				.as("the search joins to the company name")
				.contains("Lift broken").doesNotContain("Radiator broken");
	}

	@Test
	void theStatusFilterNarrows() {
		seedComplaint(this.companyA, "employee", "Aya", "Still open", "pending");
		seedComplaint(this.companyA, "employee", "Aya", "All sorted", "done");

		assertThat(body("/admin/complaints?status=pending"))
				.contains("Still open").doesNotContain("All sorted");
		assertThat(body("/admin/complaints?status=done"))
				.contains("All sorted").doesNotContain("Still open");
	}

	@Test
	void aComplaintWithNoCompanyIsReachableOnlyWhileUnfiltered() {
		// company_id is nullable on this table alone -- a support complaint from
		// someone not yet attached to a company. It belongs to no tenant, so a
		// filtered view must not show it and must not let it be touched.
		long orphan = seedComplaint(null, "company_support", "Someone", "No company yet", "pending");
		// Company A needs a row of its own: the filtered page renders forms only
		// for the rows it lists, so without one there is no CSRF token to post
		// with and the request could not be made at all.
		seedComplaint(this.companyA, "employee", "Aya", "Alpha issue", "pending");

		assertThat(body("/admin/complaints?company_id=")).contains("No company yet");
		assertThat(body("/admin/complaints?company_id=" + this.companyA))
				.doesNotContain("No company yet");
		assertThat(post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(orphan))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM complaints WHERE id = " + orphan, Integer.class)).isEqualTo(1);
	}

	@Test
	void replyingOutsideTheCurrentFilterIsRefused() {
		long betaComplaint = seedComplaint(this.companyB, "employee", "Basma", "Beta issue", "pending");
		seedComplaint(this.companyA, "employee", "Aya", "Alpha issue", "pending");
		body("/admin/complaints?company_id=" + this.companyA);

		assertThat(post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "reply", "id", String.valueOf(betaComplaint),
				"reply", "meddling", "status", "closed")
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM complaints WHERE id = " + betaComplaint, String.class)).isNull();
	}

	@Test
	void deletingOutsideTheCurrentFilterIsRefused() {
		long betaComplaint = seedComplaint(this.companyB, "employee", "Basma", "Beta issue", "pending");
		seedComplaint(this.companyA, "employee", "Aya", "Alpha issue", "pending");
		body("/admin/complaints?company_id=" + this.companyA);

		assertThat(post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(betaComplaint))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM complaints WHERE id = " + betaComplaint, Integer.class))
				.isEqualTo(1);
	}

	@Test
	void anUnfilteredAdministratorMayRestatusACompanySupportComplaint() {
		// The source rule binds a company-scoped session, not the platform --
		// these are precisely the complaints the platform exists to answer.
		long id = seedComplaint(this.companyA, "company_support", "Alpha Co", "Billing", "pending");
		body("/admin/complaints?company_id=");

		post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "set_status", "id", String.valueOf(id), "status", "done");
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM complaints WHERE id = " + id, String.class)).isEqualTo("done");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		long id = seedComplaint(this.companyA, "employee", "Aya", "Too cold", "pending");
		post("/admin/complaints", this.cookie, page("/admin/complaints", this.cookie).csrf(),
				"action", "set_status", "id", String.valueOf(id), "status", "done");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'complaint'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_UPDATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/complaints", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private long seedComplaint(
			Long companyId, String source, String name, String message, String status) {
		this.jdbc.update("INSERT INTO complaints (company_id, source, name, phone, message,"
				+ " status, created_at) VALUES (?, ?, ?, '0100000000', ?, ?, NOW())",
				companyId, source, name, message, status);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM complaints WHERE message = ?", Long.class, message);
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
		String sql = new String(AdminComplaintsEndToEndTest.class.getClassLoader()
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
