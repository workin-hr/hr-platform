package com.workin.backend.platformadmin.hr;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/activities} over real HTTP against a real MariaDB.
 *
 * <p>A read-only feed, so what these cover is the three permission decisions
 * and the query shape rather than any write. The one that would fail loudly if
 * it were wrong is the collation: {@code employees}, {@code attendance} and
 * {@code requests} need not share one, and MariaDB refuses a {@code UNION}
 * whose columns disagree -- an error, not a degraded result. Every test that
 * returns a row from both halves at once is exercising that cast.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminActivitiesEndToEndTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF =
			Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	private static final String PATH = "/admin/activities";

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
		registry.add("app.platform-admin.password", () -> PASSWORD);
		registry.add("app.platform-admin.actions.enabled", () -> "true");
	}

	@Autowired
	private TestRestTemplate restTemplate;


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
		this.jdbc.update("DELETE FROM requests");
		this.jdbc.update("DELETE FROM employees");
		this.jdbc.update("DELETE FROM request_types");
		this.jdbc.update("DELETE FROM branches");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		String phone = "+2101" + System.nanoTime() % 100_000_000L;
		// One administrator, one password (ADR-0018): the bootstrap provisioned
		// the row from the configured password when the context started.
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));

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

	private long createEmployee(long companyId, String name, String phone) {
		long branchId = companyId == this.companyA ? this.branchA : this.branchB;
		this.jdbc.update("INSERT INTO employees (company_id, branch_id, first_name, last_name,"
				+ " phone, password_hash, role, join_request_status, is_active, created_at)"
				+ " VALUES (?, ?, ?, '', ?, ?, 'employee', 'accepted', 1, NOW())",
				companyId, branchId, name, phone, this.passwordEncoder.encode(PASSWORD));
		return this.jdbc.queryForObject(
				"SELECT id FROM employees WHERE phone = ?", Long.class, phone);
	}

	private void punch(long employeeId, String checkIn, String checkOut) {
		this.jdbc.update("INSERT INTO attendance (employee_id, check_in, check_out, method)"
				+ " VALUES (?, ?, ?, 'app')", employeeId, checkIn, checkOut);
	}

	private long createRequestType(long companyId, String name) {
		this.jdbc.update("INSERT INTO request_types (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM request_types WHERE company_id = ? AND name = ?",
				Long.class, companyId, name);
	}

	/**
	 * {@code requests.request_type_id} is NOT NULL behind a RESTRICT foreign
	 * key, so every request has a type and the page's LEFT JOIN can never miss.
	 * A type is created on demand rather than passed as null.
	 */
	private void request(long employeeId, String createdAt, String status) {
		request(employeeId, createdAt, status, defaultType());
	}

	private Long cachedType;

	private long defaultType() {
		if (this.cachedType == null) {
			this.cachedType = createRequestType(this.companyA, "Leave");
		}
		return this.cachedType;
	}

	private void request(long employeeId, String createdAt, String status, Long typeId) {
		this.jdbc.update("INSERT INTO requests (employee_id, request_type_id, status, created_at)"
				+ " VALUES (?, ?, ?, ?)", employeeId, typeId, status, createdAt);
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
	void bothHalvesOfTheFeedAppearTogether() {
		// The collation test in disguise: attendance and requests come from
		// different tables through a UNION, and a mismatched collation makes
		// this query fail outright rather than return the wrong rows.
		long employee = createEmployee(this.companyA, "Aya", "01000000001");
		punch(employee, "2026-09-03 09:00:00", "2026-09-03 17:00:00");
		request(employee, "2026-09-03 10:00:00", "pending");

		String html = body(PATH + "&date_from=2026-09-01&date_to=2026-09-30");
		assertThat(html).contains("Aya");
		// The punch is closed, so it is a departure; the request is a request.
		// Both badge classes present means the UNION returned both halves.
		assertThat(html).as("an attendance row and a request row in one table")
				.contains("hr-activity-badge departure")
				.contains("hr-activity-badge request");
	}

	@Test
	void aPunchWithACheckOutReadsAsADeparture() {
		long employee = createEmployee(this.companyA, "Bassem", "01000000002");
		punch(employee, "2026-09-04 09:00:00", "2026-09-04 17:30:00");
		String html = body(PATH + "&kind=attendance&date_from=2026-09-01&date_to=2026-09-30");
		assertThat(html).contains("departure");
		// home_format_time is a 12-hour clock with no leading zero, not 17:30.
		assertThat(html).contains("5:30 PM").doesNotContain("17:30 PM");
	}

	@Test
	void aPunchWithoutACheckOutReadsAsAnArrival() {
		long employee = createEmployee(this.companyA, "Carine", "01000000003");
		punch(employee, "2026-09-04 08:05:00", null);
		String html = body(PATH + "&kind=attendance&date_from=2026-09-01&date_to=2026-09-30");
		assertThat(html).contains("checkin").doesNotContain("departure");
		assertThat(html).contains("8:05 AM");
	}

	@Test
	void middayAndMidnightBothRenderAsTwelve() {
		// $h12 === 0 -> 12, the branch a naive modulo gets wrong.
		long employee = createEmployee(this.companyA, "Dalia", "01000000004");
		punch(employee, "2026-09-05 00:15:00", null);
		assertThat(body(PATH + "&kind=attendance&date_from=2026-09-01&date_to=2026-09-30"))
				.contains("12:15 AM");

		long other = createEmployee(this.companyA, "Diaa", "01000000014");
		punch(other, "2026-09-06 12:20:00", null);
		assertThat(body(PATH + "&kind=attendance&date_from=2026-09-01&date_to=2026-09-30"))
				.contains("12:20 PM");
	}

	@Test
	void aRequestIsLabelledByItsType() {
		long employee = createEmployee(this.companyA, "Emad", "01000000005");
		long type = createRequestType(this.companyA, "Annual leave");
		request(employee, "2026-09-04 11:00:00", "pending", type);
		assertThat(body(PATH + "&kind=request&date_from=2026-09-01&date_to=2026-09-30"))
				.contains("Annual leave");
	}

	@Test
	void aRequestWhoseTypeHasNoNameFallsBackToThePlainLabel() {
		// The only way legacy's COALESCE(t.name, '') fallback can fire.
		// request_type_id is NOT NULL behind a RESTRICT foreign key, so the
		// LEFT JOIN never misses -- an empty *name* is the reachable case, and
		// a missing type is not reachable at all.
		long employee = createEmployee(this.companyA, "Farida", "01000000006");
		long blankType = createRequestType(this.companyA, "");
		request(employee, "2026-09-04 12:00:00", "pending", blankType);
		String html = body(PATH + "&kind=request&date_from=2026-09-01&date_to=2026-09-30");
		assertThat(html).contains("Farida");
		assertThat(html).as("the plain label, with no colon and no type after it")
				.contains(">Request<");
	}

	@Test
	void theKindFilterSelectsOneHalfAndFallsBackToAll() {
		long employee = createEmployee(this.companyA, "Ghada", "01000000007");
		punch(employee, "2026-09-04 09:00:00", "2026-09-04 17:00:00");
		request(employee, "2026-09-04 10:00:00", "pending");
		String range = "&date_from=2026-09-01&date_to=2026-09-30";

		assertThat(body(PATH + "&kind=attendance" + range)).contains("departure").doesNotContain(">request<");
		assertThat(body(PATH + "&kind=request" + range)).contains("request").doesNotContain("departure");
		assertThat(body(PATH + "&kind=nonsense" + range)).as("an unknown kind is all")
				.contains("departure");
	}

	@Test
	void theDateRangeExcludesWhatFallsOutsideIt() {
		long employee = createEmployee(this.companyA, "Hana", "01000000008");
		punch(employee, "2026-08-15 09:00:00", "2026-08-15 17:00:00");
		punch(employee, "2026-09-15 09:00:00", "2026-09-15 17:00:00");

		assertThat(body(PATH + "&date_from=2026-09-01&date_to=2026-09-30"))
				.containsOnlyOnce("departure");
		assertThat(body(PATH + "&date_from=2026-08-01&date_to=2026-09-30"))
				.as("both months").contains("departure");
	}

	@Test
	void anAttendanceRowIsDatedByItsCheckOutWhenItHasOne() {
		// COALESCE(check_out, check_in) is what the feed sorts and filters by,
		// so a punch that opens in one month and closes in the next belongs to
		// the month it closed in.
		long employee = createEmployee(this.companyA, "Islam", "01000000009");
		punch(employee, "2026-08-31 22:00:00", "2026-09-01 06:00:00");
		assertThat(body(PATH + "&date_from=2026-09-01&date_to=2026-09-30"))
				.as("dated by the check-out").contains("Islam");
		assertThat(body(PATH + "&date_from=2026-08-01&date_to=2026-08-31"))
				.doesNotContain("Islam");
	}

	@Test
	void theCompanyFilterScopesBothHalves() {
		long a = createEmployee(this.companyA, "Kamal", "01000000010");
		long b = createEmployee(this.companyB, "Laila", "01000000011");
		punch(a, "2026-09-04 09:00:00", "2026-09-04 17:00:00");
		request(b, "2026-09-04 10:00:00", "pending");
		String range = "&date_from=2026-09-01&date_to=2026-09-30";

		assertThat(body(PATH + range)).as("unfiltered sees both").contains("Kamal", "Laila");
		assertThat(body(PATH + "&company_id=" + this.companyA + range))
				.contains("Kamal").doesNotContain("Laila");
		assertThat(body(PATH + "&company_id=" + this.companyB + range))
				.contains("Laila").doesNotContain("Kamal");
	}

	@Test
	void theFeedIsOrderedNewestFirstAcrossBothTables() {
		long employee = createEmployee(this.companyA, "Mona", "01000000012");
		punch(employee, "2026-09-04 08:00:00", null);
		request(employee, "2026-09-04 09:00:00", "pending");
		String html = body(PATH + "&date_from=2026-09-01&date_to=2026-09-30");
		// The request is the later event, so it comes first.
		assertThat(html.indexOf("request")).isLessThan(html.indexOf("checkin"));
	}

	@Test
	void anEmptyRangeRendersTheEmptyTableRatherThanFailing() {
		String html = body(PATH + "&date_from=2020-01-01&date_to=2020-01-02");
		assertThat(html).contains("Activity log (0)");
		assertThat(html).doesNotContain("hr-activity-badge");
	}

	@Test
	void thePageSizeFloorsAtTenAndCapsAtOneHundred() {
		long employee = createEmployee(this.companyA, "Nabil", "01000000013");
		for (int i = 1; i <= 12; i++) {
			punch(employee, String.format("2026-09-%02d 09:00:00", i), null);
		}
		// per_page=1 is floored to 10, so a second page exists for 12 rows.
		String first = body(PATH + "&per_page=1&date_from=2026-09-01&date_to=2026-09-30");
		assertThat(first).as("floored to ten, so twelve rows paginate").contains("page=2");
		// per_page=5000 is capped at 100, which still holds all twelve.
		assertThat(body(PATH + "&per_page=5000&date_from=2026-09-01&date_to=2026-09-30"))
				.doesNotContain("page=2");
	}

	@Test
	void aPagePastTheEndReportsTheLastPageAboveAnEmptyTable() {
		// Legacy takes the offset from the requested page and clamps the page
		// number afterwards, so the pager says 1 and the table is empty.
		long employee = createEmployee(this.companyA, "Omar", "01000000015");
		punch(employee, "2026-09-04 09:00:00", null);
		String html = body(PATH + "&page=99&date_from=2026-09-01&date_to=2026-09-30");
		assertThat(html).doesNotContain("Omar");
	}

	@Test
	void malformedPagingParametersDoNotAnswerFourHundred() {
		for (String bad : List.of("abc", "-5", "", "1e", "99999999999999999999")) {
			ResponseEntity<String> response =
					get(PATH + "&page=" + bad + "&per_page=" + bad, this.cookie);
			assertThat(response.getStatusCode()).as("page=%s", bad).isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void thePageIsUnreachableWithoutASession() {
		ResponseEntity<String> response = get(PATH, null);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/admin/login");
	}

	@Test
	void nothingIsWritten() {
		long employee = createEmployee(this.companyA, "Rania", "01000000016");
		punch(employee, "2026-09-04 09:00:00", null);
		int audits = auditCount();
		int rows = this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class);
		body(PATH + "&date_from=2026-09-01&date_to=2026-09-30");
		assertThat(auditCount()).isEqualTo(audits);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class))
				.isEqualTo(rows);
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

}
