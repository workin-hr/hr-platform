package com.workin.backend.platformadmin.payroll;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.workin.backend.platformadmin.mfa.Totp;

/**
 * {@code /admin/salary_calculator} over real HTTP.
 *
 * <p>The figures themselves are pinned case by case against PHP in
 * {@link EgyptSalaryCalculatorTest}. What this covers is everything the
 * calculator is wrapped in: which of the query string and the body a field is
 * read from, what {@code reset} does, that malformed input renders the empty
 * panel instead of a 400, that the raw strings come back into the form
 * unchanged, and that the page carries its own stylesheets rather than the
 * shared HR ones.
 *
 * <p>Every request pins {@code lang=en}: this surface defaults to Arabic, and
 * an assertion on an English label would otherwise be testing the default
 * rather than the page.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class AdminSalaryCalculatorEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF =
			Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	private static final String PATH = "/admin/salary_calculator";

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

	private JdbcTemplate jdbc;

	private String cookie;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);

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
	}

	@Test
	void theEmptyPageOffersTheFormAndNoBreakdown() {
		String html = body(PATH);
		assertThat(html).contains("Important notes", "Calculate tax", "Reset");
		assertThat(html).contains("name=\"gross\"", "name=\"si_base\"",
				"name=\"other_non_taxable\"");
		assertThat(html).as("the placeholder, not a breakdown")
				.contains("salary-breakdown-empty");
		assertThat(html).doesNotContain("salary-net-box");
	}

	@Test
	void aPostedGrossRendersThePhpPagesOwnFigures() {
		String html = postForm("gross", "12,500");
		assertThat(html).contains("salary-net-box");
		assertThat(html).as("net, and the year built from the rounded month")
				.contains("10,083", "120,990");
		assertThat(html).as("insurance, tax, martyrs' levy and the employer's share")
				.contains("1,375", "1,036", "2,344");
		assertThat(html).as("the raw string comes back, separators and all")
				.contains("value=\"12,500\"");
	}

	@Test
	void theSameFiguresComeBackFromTheQueryString() {
		// The form posts, but every field is readable from the query string too,
		// which is what makes a computed page linkable.
		String html = body(PATH + "&gross=10000");
		assertThat(html).contains("8,304", "99,642", "1,100", "592", "1,875");
	}

	@Test
	void theQueryStringWinsOverThePostedBody() {
		// $_GET['gross'] ?? $_POST['gross']. Worth pinning because Spring would
		// happily merge the two and the order is then whatever the container
		// chose.
		String html = postTo(PATH + "&gross=10000", "gross", "12500");
		assertThat(html).as("the query string's 10,000, not the body's 12,500")
				.contains("8,304").doesNotContain("10,083");
	}

	@Test
	void anEmptyQueryValueStillWinsOverAPostedOne() {
		// ?? falls through on null, not on "". A blank gross in the query string
		// is a present-but-empty value, so the body's is never reached and the
		// page computes nothing.
		String html = postTo(PATH + "&gross=", "gross", "12500");
		assertThat(html).contains("salary-breakdown-empty").doesNotContain("salary-net-box");
	}

	@Test
	void resetBlanksTheFieldsWhateverElseTheRequestCarries() {
		String html = body(PATH + "&gross=12500&si_base=8000&other_non_taxable=500&reset=1");
		assertThat(html).contains("salary-breakdown-empty").doesNotContain("salary-net-box");
		assertThat(html).contains("name=\"gross\" value=\"\"");
	}

	@Test
	void resetIsThePresenceOfTheKeyAndNotItsValue() {
		// isset($_GET['reset']) -- ?reset= and a bare ?reset both reset, and so
		// does ?reset=0, which is the one a truthiness check would get wrong.
		for (String variant : List.of("&reset=1", "&reset=", "&reset", "&reset=0")) {
			assertThat(body(PATH + "&gross=12500" + variant))
					.as("gross with %s", variant)
					.contains("salary-breakdown-empty");
		}
		assertThat(body(PATH + "&gross=12500&resetting=1"))
				.as("a key that merely starts the same is not reset")
				.contains("salary-net-box");
	}

	@Test
	void resetOnlyLooksAtTheQueryStringNotTheBody() {
		// isset($_GET['reset']), not $_REQUEST.
		assertThat(postForm("gross", "12500", "reset", "1"))
				.contains("salary-net-box", "10,083");
	}

	@Test
	void malformedNumbersRenderTheEmptyPanelRatherThanFourHundred() {
		// (float)'abc' is 0 and the page shows its placeholder. Binding these as
		// doubles would answer 400 to a stale bookmark.
		for (String rubbish : List.of("abc", "--", "e5", ".", "%20", "null")) {
			ResponseEntity<String> response = get(PATH + "&gross=" + rubbish, this.cookie);
			assertThat(response.getStatusCode()).as("gross=%s", rubbish).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).as("gross=%s", rubbish)
					.contains("salary-breakdown-empty");
		}
	}

	@Test
	void aLeadingNumericPrefixIsReadAndTheRestIgnored() {
		assertThat(body(PATH + "&gross=10000abc")).contains("8,304");
		// "1e" is not an exponent without digits after it, so PHP keeps the "1"
		// -- a result, not the empty panel. The line either side of this is what
		// makes the prefix rule worth pinning rather than assuming.
		assertThat(body(PATH + "&gross=1e")).contains("salary-net-box");
		assertThat(body(PATH + "&gross=e5")).contains("salary-breakdown-empty");
	}

	@Test
	void aGrossBelowTheInsuranceFloorPrintsALoss() {
		// The base is clamped up to 2,700 whatever the salary, so the employee's
		// 297 is charged against a gross of one pound.
		String html = body(PATH + "&gross=1");
		assertThat(html).contains("salary-net-box").contains("-296");
	}

	@Test
	void aNegativeGrossRendersNothingAtAll() {
		assertThat(body(PATH + "&gross=-5000")).contains("salary-breakdown-empty");
	}

	@Test
	void aDeclaredInsuranceBaseIsUsedAndClamped() {
		// 25,000 declared against a 20,000 gross still bills the 16,700 ceiling.
		String html = body(PATH + "&gross=20000&si_base=25000");
		assertThat(html).contains("16,700", "1,837");
	}

	@Test
	void aNonTaxableAllowanceLowersTheTaxAndTheNet() {
		String withAllowance = body(PATH + "&gross=20000&other_non_taxable=1000");
		assertThat(withAllowance).contains("2,243", "15,910");
	}

	@Test
	void thePageCarriesItsOwnStylesheetsAndContentClass() {
		// The class names are the dashboard's, so the copied CSS applies; the
		// content class is the payroll one rather than the shared hr-page.
		String html = body(PATH);
		assertThat(html).contains("/admin/_assets/payroll-pages.css",
				"/admin/_assets/salary-calculator.css");
		assertThat(html).contains("class=\"content payroll-page org-page-salary-calculator\"");
	}

	@Test
	void theSidebarLinkIsLiveNowThatThePageAnswers() {
		assertThat(body("/admin")).contains("href=\"" + PATH + "\"");
	}

	@Test
	void thePageIsUnreachableWithoutASession() {
		ResponseEntity<String> response = get(PATH, null);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/admin/login");
	}

	@Test
	void aPostWithoutTheCsrfTokenIsRefused() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + this.cookie);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("gross", "12500");
		assertThat(this.restTemplate.exchange(PATH, HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void nothingIsWrittenAnywhere() {
		// The one ported page with no table behind it. If a later change gives
		// it one, this is what should start failing.
		int before = auditCount();
		postForm("gross", "12500");
		body(PATH + "&gross=20000");
		assertThat(auditCount()).isEqualTo(before);
	}

	private int auditCount() {
		return this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events", Integer.class);
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
		String sql = new String(AdminSalaryCalculatorEndToEndTest.class.getClassLoader()
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
