package com.workin.legacy;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
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
import com.workin.backend.platformadmin.mfa.Totp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform-admin surface running on **MySQL**, under the same profile that
 * serves the Flutter clients.
 *
 * <p>Legacy has a platform admin web of its own -- `dashboard/pages/companies/`
 * -- so a deployment that stays on MySQL needs one too. What is deliberately
 * not carried over is how legacy authenticates it: `doAdminLogin()` verifies a
 * single shared password held in a config constant (`hr-legacy#11`), and there
 * is no admin table in the legacy schema at all. The identity model here is the
 * one F-26 requires, over MariaDB instead of PostgreSQL.
 *
 * <p>The point of this test is that the *same code* works on the other
 * database. The PostgreSQL suite proves the behaviour; this proves the
 * portability, which is a different claim and the one that breaks on a dialect
 * difference.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyPlatformAdminOnMySqlTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	private static final Pattern SEED = Pattern.compile("<code>([A-Z2-7]+)</code>");

	private static final Pattern APPROVAL = Pattern.compile("name=\"approvalId\" value=\"([0-9a-f]+)\"");

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
	private PasswordEncoder passwordEncoder;

	@Autowired
	private javax.sql.DataSource legacyDataSource;

	@BeforeEach
	void doNotFollowRedirects() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
	}

	@Test
	void theLegacyApiAndTheAdminSurfaceAreServedByTheSameApplication() {
		// The clients' surface is still there...
		assertThat(get("/apis/api/phone_countries/list", null).getStatusCode())
			.isNotEqualTo(HttpStatus.NOT_FOUND);
		// ...and so is the admin one, which under this profile used to be a 404.
		assertThat(get("/admin", null).getStatusCode()).isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void theWholeAdminJourneyWorksOnMySql() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		String phone = "+2091" + System.nanoTime() % 100_000_000L;
		long adminId = createPlatformAdmin(jdbc, phone);
		long companyId = createCompany(jdbc);

		// Enrol: password plus an operator-issued bootstrap token.
		String bootstrapToken = this.mfaService.issueBootstrapToken(adminId, adminId);
		Page enrol = get2("/admin/enrol", null);
		ResponseEntity<String> seedPage = post("/admin/enrol", enrol.cookie(), enrol.csrf(),
				"phone", phone, "password", PASSWORD, "bootstrapToken", bootstrapToken);
		Matcher seedMatch = SEED.matcher(seedPage.getBody());
		assertThat(seedMatch.find()).as("the seed is shown once").isTrue();
		String seed = seedMatch.group(1);

		assertThat(post("/admin/enrol/confirm", enrol.cookie(), csrfOf(seedPage),
				"code", code(seed, 0)).getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(this.mfaService.isBound(adminId))
			.as("the encrypted seed round-tripped through a MySQL VARBINARY column")
			.isTrue();

		// Password alone reaches only the challenge.
		Page login = get2("/admin/login", null);
		ResponseEntity<String> afterPassword = post("/admin/login", login.cookie(), login.csrf(),
				"phone", phone, "password", PASSWORD);
		assertThat(afterPassword.getHeaders().getLocation()).asString().endsWith("/admin/mfa");
		String pending = cookieOf(afterPassword);
		assertThat(get("/admin", pending).getStatusCode()).isEqualTo(HttpStatus.FOUND);

		// Second factor completes it.
		jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL "
				+ "WHERE platform_admin_id = ?", adminId);
		Page challenge = get2("/admin/mfa", pending);
		ResponseEntity<String> signedIn = post("/admin/mfa", pending, challenge.csrf(),
				"code", code(seed, 0));
		assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		String cookie = cookieOf(signedIn);
		assertThat(get("/admin", cookie).getStatusCode())
			.as("the session round-tripped through MySQL's SPRING_SESSION tables")
			.isEqualTo(HttpStatus.OK);

		// Step-up, then the action.
		jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL "
				+ "WHERE platform_admin_id = ?", adminId);
		Page companies = get2("/admin/companies", cookie);
		ResponseEntity<String> confirm = post("/admin/companies/confirm", cookie, companies.csrf(),
				"action", "COMPANY_SUSPEND", "companyId", String.valueOf(companyId),
				"reason", "non-payment", "code", code(seed, 0));
		Matcher approvalMatch = APPROVAL.matcher(confirm.getBody());
		assertThat(approvalMatch.find()).as("a verified code mints an approval").isTrue();
		String approvalId = approvalMatch.group(1);

		assertThat(post("/admin/companies/apply", cookie, csrfOf(confirm),
				"action", "COMPANY_SUSPEND", "companyId", String.valueOf(companyId),
				"reason", "non-payment", "approvalId", approvalId).getStatusCode())
			.isEqualTo(HttpStatus.FOUND);

		assertThat(jdbc.queryForObject("SELECT status FROM companies WHERE id = ?",
				String.class, companyId))
			.as("the action changed the same companies table the PHP dashboard writes")
			.isEqualTo("suspended");

		assertThat(jdbc.queryForList("SELECT event_type, target_type, target_id, step_up_approval_id "
				+ "FROM platform_admin_audit_events WHERE platform_admin_id = ? "
				+ "AND event_type = 'COMPANY_SUSPENDED'", adminId))
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("target_id")).isEqualTo(String.valueOf(companyId));
				assertThat(row.get("step_up_approval_id")).isEqualTo(approvalId);
			});

		// Logout clears the shared session row.
		assertThat(post("/admin/logout", cookie, get2("/admin", cookie).csrf()).getStatusCode())
			.isEqualTo(HttpStatus.FOUND);
		assertThat(get("/admin", cookie).getStatusCode()).isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void theBearerApiStillRequiresTheSecondFactorOnMySql() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		String phone = "+2092" + System.nanoTime() % 100_000_000L;
		long adminId = createPlatformAdmin(jdbc, phone);
		String seed = enrol(adminId);

		HttpHeaders json = new HttpHeaders();
		json.setContentType(MediaType.APPLICATION_JSON);
		assertThat(this.restTemplate.exchange("/api/platform-admin/login", HttpMethod.POST,
				new HttpEntity<>("{\"phone\":\"" + phone + "\",\"password\":\"" + PASSWORD + "\"}", json),
				String.class).getStatusCode())
			.isEqualTo(HttpStatus.UNAUTHORIZED);

		jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL "
				+ "WHERE platform_admin_id = ?", adminId);
		assertThat(this.restTemplate.exchange("/api/platform-admin/login", HttpMethod.POST,
				new HttpEntity<>("{\"phone\":\"" + phone + "\",\"password\":\"" + PASSWORD
						+ "\",\"code\":\"" + code(seed, 0) + "\"}", json),
				String.class).getStatusCode())
			.isEqualTo(HttpStatus.OK);
	}

	@Test
	void approveAndRejectWorkOnMySqlAndRejectRecordsWhy() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		String phone = "+2093" + System.nanoTime() % 100_000_000L;
		long adminId = createPlatformAdmin(jdbc, phone);
		String seed = enrol(adminId);
		long pending = createCompany(jdbc);
		jdbc.update("UPDATE companies SET status = 'pending' WHERE id = ?", pending);

		String cookie = signIn(jdbc, phone, seed, adminId);

		// The list offers Approve and Reject for a pending company -- the
		// workflow the PHP dashboard exists for, and the one the first cut of
		// this page did not have.
		Page companies = get2("/admin/companies", cookie);
		assertThat(companies.response().getBody()).contains("COMPANY_APPROVE", "COMPANY_REJECT");

		jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL "
				+ "WHERE platform_admin_id = ?", adminId);
		ResponseEntity<String> confirm = post("/admin/companies/confirm", cookie, companies.csrf(),
				"action", "COMPANY_REJECT", "companyId", String.valueOf(pending),
				"reason", "no commercial registration", "code", code(seed, 0));
		Matcher approvalMatch = APPROVAL.matcher(confirm.getBody());
		assertThat(approvalMatch.find()).isTrue();

		assertThat(post("/admin/companies/apply", cookie, csrfOf(confirm),
				"action", "COMPANY_REJECT", "companyId", String.valueOf(pending),
				"reason", "no commercial registration", "approvalId", approvalMatch.group(1))
				.getStatusCode()).isEqualTo(HttpStatus.FOUND);

		assertThat(jdbc.queryForObject("SELECT status FROM companies WHERE id = ?",
				String.class, pending)).isEqualTo("rejected");
		assertThat(jdbc.queryForObject("SELECT rejection_reason FROM companies WHERE id = ?",
				String.class, pending))
			.as("the same column the PHP dashboard's reject writes")
			.isEqualTo("no commercial registration");
	}

	@Test
	void theCompanyDetailPageCountsOutstandingWork() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		String phone = "+2094" + System.nanoTime() % 100_000_000L;
		long adminId = createPlatformAdmin(jdbc, phone);
		String seed = enrol(adminId);
		long company = createCompany(jdbc);
		String cookie = signIn(jdbc, phone, seed, adminId);

		ResponseEntity<String> detail = get("/admin/companies/" + company, cookie);

		assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(detail.getBody())
			.as("the counts legacy's detail.php shows, over the same join through employees")
			.contains("Pending requests", "Pending advances");
	}

	// --- helpers ------------------------------------------------------------

	/** Password step, second factor, and back with a usable session cookie. */
	private String signIn(JdbcTemplate jdbc, String phone, String seed, long adminId) {
		Page login = get2("/admin/login", null);
		ResponseEntity<String> afterPassword = post("/admin/login", login.cookie(), login.csrf(),
				"phone", phone, "password", PASSWORD);
		String pending = cookieOf(afterPassword);
		jdbc.update("UPDATE platform_admin_mfa SET last_accepted_time_step = NULL "
				+ "WHERE platform_admin_id = ?", adminId);
		Page challenge = get2("/admin/mfa", pending);
		return cookieOf(post("/admin/mfa", pending, challenge.csrf(), "code", code(seed, 0)));
	}


	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private record Csrf(String name, String value) {
	}

	private String enrol(long adminId) {
		String token = this.mfaService.issueBootstrapToken(adminId, adminId);
		String seed = this.mfaService.beginEnrolment(adminId, token).orElseThrow();
		assertThat(this.mfaService.confirmEnrolment(adminId, code(seed, 0))).isTrue();
		return seed;
	}

	private ResponseEntity<String> get(String path, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		if (cookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + cookie);
		}
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private Page get2(String path, String cookie) {
		ResponseEntity<String> response = get(path, cookie);
		String resolved = cookie != null ? cookie : tryCookieOf(response);
		return new Page(response, resolved, csrfOf(response));
	}

	private ResponseEntity<String> post(String path, String cookie, Csrf csrf, String... fields) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + cookie);
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		for (int i = 0; i < fields.length; i += 2) {
			body.add(fields[i], fields[i + 1]);
		}
		body.add(csrf.name(), csrf.value());
		return this.restTemplate.exchange(path, HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
	}

	private static Csrf csrfOf(ResponseEntity<String> response) {
		Matcher matcher = CSRF.matcher(response.getBody());
		assertThat(matcher.find()).as("expected a CSRF token").isTrue();
		return new Csrf(matcher.group(1), matcher.group(2));
	}

	private static String code(String base32Seed, long offset) {
		return Totp.codeAt(fromBase32(base32Seed), Totp.timeStepAt(Instant.now()) + offset);
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

	private long createPlatformAdmin(JdbcTemplate jdbc, String phone) {
		jdbc.update("INSERT INTO platform_admins (phone, password_hash, active) VALUES (?, ?, 1)",
				phone, this.passwordEncoder.encode(PASSWORD));
		return jdbc.queryForObject("SELECT id FROM platform_admins WHERE phone = ?", Long.class, phone);
	}

	private long createCompany(JdbcTemplate jdbc) {
		String companyPhone = "01" + System.nanoTime() % 1_000_000_000L;
		// company_name, not name -- the legacy column. password_hash is NOT NULL
		// with no default, so a fixture has to supply it.
		jdbc.update("INSERT INTO companies (company_name, phone, password_hash, status) "
				+ "VALUES (?, ?, ?, 'active')",
				"MySQL Admin Fixture", companyPhone, "unused-hash");
		return jdbc.queryForObject("SELECT id FROM companies WHERE phone = ?", Long.class, companyPhone);
	}

	private static byte[] fromBase32(String encoded) {
		final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0;
		int bitsLeft = 0;
		for (char c : encoded.toCharArray()) {
			buffer = (buffer << 5) | alphabet.indexOf(c);
			bitsLeft += 5;
			if (bitsLeft >= 8) {
				out.write((buffer >> (bitsLeft - 8)) & 0xFF);
				bitsLeft -= 8;
			}
		}
		return out.toByteArray();
	}

	private static void applySchema(String resource) throws Exception {
		try (InputStream stream = LegacyPlatformAdminOnMySqlTest.class.getClassLoader()
				.getResourceAsStream(resource)) {
			String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			try (Connection connection = DriverManager.getConnection(
					MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
					Statement statement = connection.createStatement()) {
				// One statement per `;` at end of line, comments included --
				// the same split AbstractLegacyMySqlTest uses. Skipping chunks
				// that *start* with a comment would skip the statement the
				// comment documents, which is most of them in these files.
				for (String piece : sql.split(";\\s*\\R")) {
					if (!piece.isBlank()) {
						statement.execute(piece);
					}
				}
			}
		}
	}

}
