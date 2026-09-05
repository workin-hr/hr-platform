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
 * {@code /admin/assets} over real HTTP against a real MariaDB.
 *
 * <p>The penalties page's shape, one rule lighter: an asset marked returned is
 * frozen against editing, and there is no value whitelist. What it adds is a
 * {@code company_id} of its own that the edit <b>writes</b> from the chosen
 * employee -- so an edit that changed the employee could carry the row into
 * another company, and the test for that is the one worth having.
 *
 * <p>And <b>R-046</b>: {@code mark_returned} and {@code delete_asset} wrote by
 * id alone.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminAssetsEndToEndTest {

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
		this.jdbc.update("DELETE FROM assets");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2098" + System.nanoTime() % 100_000_000L;
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
	void assigningAnAssetWritesItUnreturnedAgainstTheEmployeesCompany() {
		post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "add_asset", "employee_id", String.valueOf(this.employeeA),
				"asset_text", "Laptop", "asset_date", "2026-03-02", "asset_end_date", "2026-09-02");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, employee_id, asset_text, asset_end_date, is_returned FROM assets");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("employee_id").toString()).isEqualTo(String.valueOf(this.employeeA));
		assertThat(row.get("asset_text")).isEqualTo("Laptop");
		assertThat(row.get("is_returned")).as("still out on loan").isEqualTo(Boolean.FALSE);
	}

	@Test
	void anEmptyEndDateIsStoredAsNullNotAnEmptyString() {
		post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "add_asset", "employee_id", String.valueOf(this.employeeA),
				"asset_text", "Phone", "asset_end_date", "");

		assertThat(this.jdbc.queryForObject("SELECT asset_end_date FROM assets", String.class))
				.as("an open-ended loan has no end date, not a blank one").isNull();
	}

	@Test
	void anAbsentDateBecomesToday() {
		post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "add_asset", "employee_id", String.valueOf(this.employeeA),
				"asset_text", "Phone");

		assertThat(this.jdbc.queryForObject("SELECT asset_date FROM assets", String.class))
				.isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.ofHours(2)).toString());
	}

	@Test
	void anEmptyTextOrNoEmployeeIsRefused() {
		assertThat(post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "add_asset", "employee_id", String.valueOf(this.employeeA),
				"asset_text", "   ")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "add_asset", "employee_id", "0", "asset_text", "Laptop")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM assets", Integer.class)).isZero();
	}

	@Test
	void markingReturnedStampsTheDateAndFreezesTheRow() {
		long id = seedAsset(this.employeeA, this.companyA, "Laptop", false);

		post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "mark_returned", "id", String.valueOf(id));

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT is_returned, returned_at FROM assets WHERE id = " + id);
		assertThat(row.get("is_returned")).isEqualTo(Boolean.TRUE);
		assertThat(row.get("returned_at")).isNotNull();

		// Nothing left to correct about a loan that has ended.
		assertThat(post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "edit_asset", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "asset_text", "Changed")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT asset_text FROM assets WHERE id = " + id, String.class)).isEqualTo("Laptop");
	}

	@Test
	void anAssetStillOutCanBeEdited() {
		long id = seedAsset(this.employeeA, this.companyA, "Laptop", false);

		post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "edit_asset", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "asset_text", "Laptop and dock",
				"asset_date", "2026-04-01");

		assertThat(this.jdbc.queryForObject(
				"SELECT asset_text FROM assets WHERE id = " + id, String.class))
				.isEqualTo("Laptop and dock");
	}

	@Test
	void anEditCannotCarryTheAssetIntoAnotherCompany() {
		// The edit writes company_id from the chosen employee, so a foreign one
		// would move the row. This is what makes the employee check on every
		// write load-bearing rather than defensive.
		long id = seedAsset(this.employeeA, this.companyA, "Laptop", false);
		body("/admin/assets?company_id=" + this.companyA);

		assertThat(post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "edit_asset", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeB), "asset_text", "Laptop")
				.getHeaders().getLocation()).asString().contains("error=error_db");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, employee_id FROM assets WHERE id = " + id);
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("employee_id").toString()).isEqualTo(String.valueOf(this.employeeA));
	}

	@Test
	void anUnfilteredAdministratorStillCannotMoveTheAssetBetweenCompanies() {
		// The case the filtered test above does not reach, and the one that was
		// actually open: with no company filter an administrator satisfies every
		// session-based check, so the employee has to be checked against the
		// *row's* company instead.
		long id = seedAsset(this.employeeA, this.companyA, "Laptop", false);
		body("/admin/assets?company_id=");

		assertThat(post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "edit_asset", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeB), "asset_text", "Laptop")
				.getHeaders().getLocation()).asString().contains("error=error_db");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, employee_id FROM assets WHERE id = " + id);
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("employee_id").toString()).isEqualTo(String.valueOf(this.employeeA));
	}

	@Test
	void anEditMayStillReassignWithinTheSameCompany() {
		// Immutable company, not immutable employee: the page's own purpose is
		// to move an asset from one person to another.
		long other = createEmployee(this.companyA, "A300", "Other", "Alpha");
		long id = seedAsset(this.employeeA, this.companyA, "Laptop", false);
		body("/admin/assets?company_id=");

		post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "edit_asset", "id", String.valueOf(id),
				"employee_id", String.valueOf(other), "asset_text", "Laptop");

		assertThat(this.jdbc.queryForObject(
				"SELECT employee_id FROM assets WHERE id = " + id, Long.class)).isEqualTo(other);
		assertThat(this.jdbc.queryForObject(
				"SELECT company_id FROM assets WHERE id = " + id, Long.class))
				.isEqualTo(this.companyA);
	}

	@Test
	void theReturnedFilterNarrowsBothWays() {
		seedAsset(this.employeeA, this.companyA, "OutOnLoan", false);
		seedAsset(this.employeeA, this.companyA, "GivenBack", true);

		assertThat(body("/admin/assets?returned=0")).contains("OutOnLoan").doesNotContain("GivenBack");
		assertThat(body("/admin/assets?returned=1")).contains("GivenBack").doesNotContain("OutOnLoan");
		assertThat(body("/admin/assets")).contains("OutOnLoan").contains("GivenBack");
	}

	@Test
	void theSearchMatchesTheAssetTextAsWellAsTheEmployee() {
		seedAsset(this.employeeA, this.companyA, "Dell Latitude", false);
		seedAsset(this.employeeA, this.companyA, "Company car", false);

		assertThat(body("/admin/assets?search=Latitude"))
				.contains("Dell Latitude").doesNotContain("Company car");
		assertThat(body("/admin/assets?search=Aya")).contains("Dell Latitude");
	}

	@Test
	void markingReturnedOutsideTheCurrentFilterIsRefused() {
		long betaAsset = seedAsset(this.employeeB, this.companyB, "Beta laptop", false);
		seedAsset(this.employeeA, this.companyA, "Alpha laptop", false);
		body("/admin/assets?company_id=" + this.companyA);

		assertThat(post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "mark_returned", "id", String.valueOf(betaAsset))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT is_returned FROM assets WHERE id = " + betaAsset, Boolean.class)).isFalse();
	}

	@Test
	void deletingOutsideTheCurrentFilterIsRefused() {
		long betaAsset = seedAsset(this.employeeB, this.companyB, "Beta laptop", false);
		seedAsset(this.employeeA, this.companyA, "Alpha laptop", false);
		body("/admin/assets?company_id=" + this.companyA);

		assertThat(post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "delete_asset", "id", String.valueOf(betaAsset))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM assets WHERE id = " + betaAsset, Integer.class)).isEqualTo(1);
	}

	@Test
	void anUnfilteredAdministratorReachesEveryCompany() {
		long betaAsset = seedAsset(this.employeeB, this.companyB, "Beta laptop", false);
		body("/admin/assets?company_id=");

		post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "mark_returned", "id", String.valueOf(betaAsset));
		assertThat(this.jdbc.queryForObject(
				"SELECT is_returned FROM assets WHERE id = " + betaAsset, Boolean.class)).isTrue();
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/assets", this.cookie, page("/admin/assets", this.cookie).csrf(),
				"action", "add_asset", "employee_id", String.valueOf(this.employeeA),
				"asset_text", "Laptop");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'asset'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/assets", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private long seedAsset(long employeeId, long companyId, String text, boolean returned) {
		this.jdbc.update("INSERT INTO assets (company_id, employee_id, asset_date, asset_text,"
				+ " is_returned, created_at) VALUES (?, ?, '2026-03-02', ?, ?, NOW())",
				companyId, employeeId, text, returned ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM assets WHERE employee_id = ? AND asset_text = ?", Long.class,
				employeeId, text);
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
		String sql = new String(AdminAssetsEndToEndTest.class.getClassLoader()
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
