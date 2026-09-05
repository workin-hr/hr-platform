package com.workin.backend.platformadmin.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * {@code /admin/branches} over real HTTP against a real MariaDB.
 *
 * <p>The first org page, so this covers the machinery the other three inherit
 * as well as the page itself: the session company filter, the pagination
 * shape, the write gates, and the tenant check on a row id from a form post.
 *
 * <p>Weighted towards what a fresh implementation gets wrong. The list is easy
 * and is not what breaks; the filter surviving a request that does not mention
 * it, an out-of-range page reporting the last page with no rows, and a
 * generated code refusing an expiry in the past are.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminBranchesEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	private static final DateTimeFormatter LOCAL =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

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

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM branches");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2091" + System.nanoTime() % 100_000_000L;
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
		Page challenge = page("/admin/mfa", pending);
		this.cookie = cookieOf(post("/admin/mfa", pending, challenge.csrf(), "code", code(seed)));

		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");
	}

	@Test
	void theListShowsEveryCompanysBranchesUntilTheFilterIsSet() {
		seedBranch(this.companyA, "Alpha HQ");
		seedBranch(this.companyB, "Beta HQ");

		String all = body("/admin/branches");
		assertThat(all).contains("Alpha HQ").contains("Beta HQ");
		assertThat(all).as("the company column appears only when unfiltered").contains("Alpha Co");

		// Setting the filter narrows it, and -- the part a stateless reading
		// gets wrong -- a later request that does not mention company_id keeps
		// it rather than resetting to everything.
		assertThat(body("/admin/branches?company_id=" + this.companyA))
				.contains("Alpha HQ").doesNotContain("Beta HQ");
		assertThat(body("/admin/branches"))
				.as("the filter outlives the request that set it")
				.contains("Alpha HQ").doesNotContain("Beta HQ");

		// And an empty value clears it. isset() would treat this as absent and
		// make "show me everything again" unreachable.
		assertThat(body("/admin/branches?company_id="))
				.contains("Alpha HQ").contains("Beta HQ");
	}

	@Test
	void theSearchMatchesNameAndAddressAndTheStatusFilterNarrows() {
		seedBranch(this.companyA, "Cairo Office", "Nasr City", true);
		seedBranch(this.companyA, "Giza Office", "Dokki", false);

		assertThat(body("/admin/branches?search=Cairo"))
				.contains("Cairo Office").doesNotContain("Giza Office");
		// The address half of the OR, which a name-only search would miss.
		assertThat(body("/admin/branches?search=Dokki"))
				.contains("Giza Office").doesNotContain("Cairo Office");
		assertThat(body("/admin/branches?filter=inactive"))
				.contains("Giza Office").doesNotContain("Cairo Office");
		// An unrecognised filter value behaves as `all`, not as an error.
		assertThat(body("/admin/branches?filter=sideways"))
				.contains("Cairo Office").contains("Giza Office");
	}

	@Test
	void anOutOfRangePageReportsTheLastPageAndShowsNothing() {
		for (int index = 10; index < 22; index++) {
			seedBranch(this.companyA, "Branch " + index);
		}
		assertThat(body("/admin/branches?per_page=10")).contains("Branch 21");

		// dbPaginate() clamps the page number after taking the offset, so this
		// is the legacy behaviour: the pager says the last page, the table is
		// empty.
		assertThat(body("/admin/branches?per_page=10&page=99"))
				.contains("data-table-empty").doesNotContain("Branch 21");
	}

	@Test
	void theEmptyStateRendersRatherThanBreaking() {
		// The label resolves, so asserting on the key would pass only while the
		// translation was missing. The empty row's own class is the stable mark.
		assertThat(body("/admin/branches")).contains("data-table-empty");
	}

	@Test
	void everyPageRendersAUsableCsrfToken() {
		// Not a formality. The token comes from an advice rather than from each
		// controller because four pages had already forgotten to expose it, and
		// the symptom -- an empty value in the hidden field -- is a 403 on
		// submit that reads as a permissions problem.
		//
		// A row is seeded first: an empty list renders no form at all, so
		// checking it would prove nothing either way.
		seedBranch(this.companyA, "Has A Form");
		for (String path : List.of("/admin/branches", "/admin/branches?action=add",
				"/admin/faqs", "/admin/banners", "/admin/notifications", "/admin/phone_countries")) {
			Matcher matcher = CSRF.matcher(body(path));
			assertThat(matcher.find()).as("a token on %s", path).isTrue();
			assertThat(matcher.group(2)).as("a non-empty token on %s", path).isNotEmpty();
		}
	}

	@Test
	void addingABranchWritesItAndLeavesTheFilterOnItsCompany() {
		Page form = page("/admin/branches?action=add", this.cookie);
		assertThat(post("/admin/branches", this.cookie, form.csrf(),
				"action", "add", "company_id", String.valueOf(this.companyB),
				"name", "New Branch", "address", "Somewhere",
				"lat", "30.044", "lng", "31.235", "radius_meters", "150")
				.getStatusCode()).isEqualTo(HttpStatus.FOUND);

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, name, address, radius_meters, is_active"
						+ " FROM branches WHERE name = 'New Branch'");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyB));
		assertThat(row.get("address")).isEqualTo("Somewhere");
		assertThat(((Number) row.get("radius_meters")).intValue()).isEqualTo(150);
		// tinyint(1) comes back from MariaDB as a Boolean, not a Number.
		assertThat(row.get("is_active")).as("a new branch is active").isEqualTo(Boolean.TRUE);

		// org_redirect() sets the filter to the company just written, so the
		// operator lands looking at what they made rather than at everything.
		assertThat(body("/admin/branches")).contains("New Branch").doesNotContain("Alpha HQ");
	}

	@Test
	void anAddWithNoCompanyIsRefusedWithLegacysOwnMessage() {
		Page form = page("/admin/branches?action=add", this.cookie);
		ResponseEntity<String> refused = post("/admin/branches", this.cookie, form.csrf(),
				"action", "add", "company_id", "0", "name", "Nowhere");

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(refused.getHeaders().getLocation()).asString()
				.as("back to the form they were in, not to the list")
				.contains("action=add").contains("error=select_company_first");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM branches", Integer.class)).isZero();
	}

	@Test
	void theRadiusIsClampedNotRejected() {
		post("/admin/branches", this.cookie, page("/admin/branches?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Tiny",
				"radius_meters", "0");
		post("/admin/branches", this.cookie, page("/admin/branches?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Huge",
				"radius_meters", "999999");

		assertThat(this.jdbc.queryForObject(
				"SELECT radius_meters FROM branches WHERE name = 'Tiny'", Integer.class))
				.as("below 1 becomes the 200 m default").isEqualTo(200);
		assertThat(this.jdbc.queryForObject(
				"SELECT radius_meters FROM branches WHERE name = 'Huge'", Integer.class))
				.as("above 5 km is capped, not refused").isEqualTo(5000);
	}

	@Test
	void aNonNumericCoordinateIsStoredAsNullRatherThanZero() {
		// Zero would put the branch in the Gulf of Guinea and make
		// hasCoordinates() true, so the map link would appear and be wrong.
		post("/admin/branches", this.cookie, page("/admin/branches?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "No Fix",
				"lat", "not a number", "lng", "");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT latitude, longitude FROM branches WHERE name = 'No Fix'");
		assertThat(row.get("latitude")).isNull();
		assertThat(row.get("longitude")).isNull();
		assertThat(body("/admin/branches"))
				.as("no coordinates means no map link, rather than one pointing at 0,0")
				.doesNotContain("branch-map-link");
	}

	@Test
	void editingKeepsTheBranchInItsCompany() {
		long id = seedBranch(this.companyA, "Original");
		Page form = page("/admin/branches?action=edit&id=" + id, this.cookie);
		assertThat(form.response().getBody()).contains("Original");

		post("/admin/branches", this.cookie, form.csrf(), "action", "save_edit",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyB),
				"name", "Renamed", "radius_meters", "300", "is_active", "1");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, name FROM branches WHERE id = " + id);
		assertThat(row.get("name")).isEqualTo("Renamed");
		assertThat(row.get("company_id").toString())
				.as("company_id is not among the updated columns, so a posted one cannot move it")
				.isEqualTo(String.valueOf(this.companyA));
	}

	@Test
	void deleteDeactivatesRatherThanRemoving() {
		long id = seedBranch(this.companyA, "Closing");
		post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM branches WHERE id = " + id, Integer.class))
				.as("employees and attendance rows point at it; a hard delete would orphan them")
				.isEqualTo(1);
		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM branches WHERE id = " + id, Integer.class)).isZero();
	}

	@Test
	void generatingACodeStoresThirtyTwoHexCharactersAndItsExpiry() {
		long id = seedBranch(this.companyA, "Coded");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);
		assertThat(qr.response().getBody())
				.as("no code yet, so no image tag to a third party")
				.doesNotContain("api.qrserver.com");

		assertThat(post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", LocalDateTime.now().plusDays(1).format(LOCAL))
				.getHeaders().getLocation()).asString()
				.as("back to the QR panel, so the operator sees the code").contains("action=qr");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT qr_code, expires_at FROM branches WHERE id = " + id);
		assertThat((String) row.get("qr_code")).matches("[0-9a-f]{32}");
		assertThat(row.get("expires_at")).isNotNull();
		assertThat(body("/admin/branches?action=qr&id=" + id))
				.as("the panel now renders the code through legacy's own third-party renderer")
				.contains("api.qrserver.com");
	}

	@Test
	void anExpiryInThePastIsRefusedAndNothingIsWritten() {
		// A code that is already expired is indistinguishable from no code, so
		// generating one would look like it worked and do nothing.
		long id = seedBranch(this.companyA, "Stale");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);
		assertThat(post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", "2020-01-01T00:00").getHeaders().getLocation()).asString()
				.contains("error=branch_qr_invalid_expiry");
		assertThat(this.jdbc.queryForObject(
				"SELECT qr_code FROM branches WHERE id = " + id, String.class)).isNull();
	}

	@Test
	void anUnparseableExpiryIsRefusedRatherThanBecomingNow() {
		long id = seedBranch(this.companyA, "Garbled");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);
		assertThat(post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", "whenever").getHeaders().getLocation()).asString()
				.contains("error=branch_qr_invalid_expiry");
		assertThat(this.jdbc.queryForObject(
				"SELECT qr_code FROM branches WHERE id = " + id, String.class)).isNull();
	}

	@Test
	void aCodeForABranchOfAnotherCompanyIsRefused() {
		// generate_qr runs org_assert_company_row() itself, unconditionally --
		// unlike the write actions, which skip the check for an administrator.
		long id = seedBranch(this.companyB, "Beta Branch");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);

		assertThat(post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", LocalDateTime.now().plusDays(1).format(LOCAL))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT qr_code FROM branches WHERE id = " + id, String.class)).isNull();
	}

	@Test
	void theEditFormRefusesARowOutsideTheCurrentFilter() {
		long id = seedBranch(this.companyB, "Beta Only");
		body("/admin/branches?company_id=" + this.companyA);

		// Filtered to Alpha, an edit link for a Beta branch shows no form --
		// rather than silently editing a company the operator is not looking at.
		assertThat(body("/admin/branches?action=edit&id=" + id)).doesNotContain("Beta Only");
		// Unfiltered, it is reachable: that is what "all companies" means.
		body("/admin/branches?company_id=");
		assertThat(body("/admin/branches?action=edit&id=" + id)).contains("Beta Only");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/branches", this.cookie, page("/admin/branches?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Audited");
		long id = this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE name = 'Audited'", Long.class);
		post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		List<Map<String, Object>> events = this.jdbc.queryForList(
				"SELECT event_type, target_type, target_id FROM platform_admin_audit_events"
						+ " WHERE target_type = 'branch' ORDER BY id");
		assertThat(events).hasSize(2);
		assertThat(events.get(0).get("event_type")).isEqualTo("ORG_CREATED");
		assertThat(events.get(1).get("event_type")).isEqualTo("ORG_DELETED");
		assertThat(events.get(1).get("target_id")).isEqualTo(String.valueOf(id));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/branches", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
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
		return seedBranch(companyId, name, null, true);
	}

	private long seedBranch(long companyId, String name, String address, boolean active) {
		this.jdbc.update("INSERT INTO branches (company_id, name, address, is_active, created_at)"
				+ " VALUES (?, ?, ?, ?, NOW())", companyId, name, address, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE company_id = ? AND name = ?", Long.class,
				companyId, name);
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
		String sql = new String(AdminBranchesEndToEndTest.class.getClassLoader()
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
