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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/shifts} over real HTTP against a real MariaDB.
 *
 * <p>The simplest of the four org pages, so this is mostly about proving that
 * the shared machinery still holds on a page with almost no rules of its own:
 * a non-empty name, and two times that are deliberately <b>not</b> validated.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminShiftsEndToEndTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

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


	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM employee_shift_assignments");
		this.jdbc.update("DELETE FROM shifts");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		// One administrator, one password (ADR-0018): the bootstrap provisioned
		// the row from the configured password when the context started.
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));

		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");

	}

	@Test
	void addingAShiftStoresItsTimesAndDefaultsToActive() {
		post("/admin/shifts", this.cookie, page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Morning",
				"start_time", "09:00", "end_time", "17:00");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, start_time, end_time, is_active FROM shifts WHERE name = 'Morning'");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("start_time").toString()).startsWith("09:00");
		assertThat(row.get("end_time").toString()).startsWith("17:00");
		assertThat(row.get("is_active")).isEqualTo(Boolean.TRUE);
		assertThat(body("/admin/shifts")).contains("Morning").contains("09:00");
	}

	@Test
	void absentTimesTakeTheDefaultsAndEmptyOnesDoNot() {
		// `$_POST['start_time'] ?? '08:00'` is a null coalesce, so it fires
		// only when the field is missing. An empty box stores '' and
		// non-strict MariaDB coerces it to midnight -- wrong, and what the
		// live system holds.
		post("/admin/shifts", this.cookie, page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Defaulted");
		Map<String, Object> defaulted = this.jdbc.queryForMap(
				"SELECT start_time, end_time FROM shifts WHERE name = 'Defaulted'");
		assertThat(defaulted.get("start_time").toString()).startsWith("08:00");
		assertThat(defaulted.get("end_time").toString()).startsWith("16:00");

		post("/admin/shifts", this.cookie, page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Emptied",
				"start_time", "", "end_time", "");
		assertThat(this.jdbc.queryForMap(
				"SELECT start_time FROM shifts WHERE name = 'Emptied'").get("start_time").toString())
				.as("empty is not absent")
				.startsWith("00:00");
	}

	@Test
	void anEmptyNameIsRefused() {
		assertThat(post("/admin/shifts", this.cookie,
				page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "   ",
				"start_time", "09:00").getHeaders().getLocation()).asString()
				.contains("action=add").contains("error=error_required");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM shifts", Integer.class)).isZero();
	}

	@Test
	void anAddWithNoCompanyIsRefused() {
		assertThat(post("/admin/shifts", this.cookie,
				page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", "0", "name", "Nowhere")
				.getHeaders().getLocation()).asString().contains("error=select_company_first");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM shifts", Integer.class)).isZero();
	}

	@Test
	void editingKeepsTheShiftInItsCompany() {
		long id = seedShift(this.companyA, "Original");
		post("/admin/shifts", this.cookie, page("/admin/shifts?action=edit&id=" + id, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyB), "name", "Renamed",
				"start_time", "10:00", "end_time", "18:00", "is_active", "1");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, name FROM shifts WHERE id = " + id);
		assertThat(row.get("name")).isEqualTo("Renamed");
		assertThat(row.get("company_id").toString())
				.as("company_id is not among the updated columns")
				.isEqualTo(String.valueOf(this.companyA));
	}

	/** As on branches: an unchanged save and a repeat delete flash their success (D-253). */
	@Test
	void anUnchangedSaveAndARepeatDeleteStillFlashTheirSuccess() {
		long id = seedShift(this.companyA, "Steady");
		for (int round = 1; round <= 2; round++) {
			assertThat(post("/admin/shifts", this.cookie, page("/admin/shifts?action=edit&id=" + id, this.cookie).csrf(),
					"action", "save_edit", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
					"name", "Steady", "start_time", "10:00", "end_time", "18:00", "is_active", "1")
					.getHeaders().getLocation()).as("save %d", round).asString().doesNotContain("action=edit");
			assertThat(body("/admin/shifts")).as("save %d", round).contains("<div class=\"flash flash-success\">تم الحفظ بنجاح ✓</div>");
		}
		for (int round = 1; round <= 2; round++) {
			post("/admin/shifts", this.cookie, page("/admin/shifts", this.cookie).csrf(),
					"action", "delete", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA));
			assertThat(body("/admin/shifts")).as("delete %d", round).contains("<div class=\"flash flash-error\">تم الحذف</div>");
		}
	}

	@Test
	void deleteDeactivatesAndLeavesAssignmentsAlone() {
		long id = seedShift(this.companyA, "Closing");
		post("/admin/shifts", this.cookie, page("/admin/shifts", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM shifts WHERE id = " + id, Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM shifts WHERE id = " + id, Integer.class)).isEqualTo(1);
	}

	/**
	 * {@code shifts/page.php:91-92, 125-126}: the start and end headers carry {@code col-center},
	 * and their cells are {@code col-center dir="ltr"} plain text -- not the badges the port drew.
	 */
	@Test
	void theStartAndEndColumnsAreColCenterLtrPlainTextNotBadges() {
		seedShift(this.companyA, "Timed");
		String html = body("/admin/shifts?lang=en");
		assertThat(html)
				.contains("<th class=\"col-center\">Start Time</th>")
				.contains("<th class=\"col-center\">End Time</th>")
				.contains("<td class=\"col-center\" dir=\"ltr\">08:00</td>")
				.contains("<td class=\"col-center\" dir=\"ltr\">16:00</td>")
				.doesNotContain("badge-gray\">08:00").doesNotContain("badge-gray\">16:00");
	}

	/** {@code shifts/page.php:79}: {@code $colCount = $showCompanyCol ? 9 : 8}. The port had 8 and 7. */
	@Test
	void theEmptyStateSpansLegacysColumnCount() {
		assertThat(body("/admin/shifts")).contains("colspan=\"9\"");
		assertThat(body("/admin/shifts?company_id=" + this.companyA)).contains("colspan=\"8\"");
	}

	/** {@code shifts/page.php:121}: the row number carries no class, unlike the company cell beside it. */
	@Test
	void theRowNumberCarriesNoClassAsLegacyDoesNot() {
		seedShift(this.companyA, "Numbered");
		assertThat(row(body("/admin/shifts"), "Numbered")).contains("<td>1</td>");
	}

	private static String row(String html, String name) {
		return java.util.regex.Pattern.compile("(?s)<tr\\b[^>]*>(.*?)</tr>").matcher(html).results()
				.map(match -> match.group(1)).filter(cells -> cells.contains(">" + name + "<"))
				.findFirst().orElseThrow(() -> new AssertionError("no row for " + name));
	}

	/** {@code _shift_form.php:17}: the name label is {@code shift_name}, not {@code shift}. */
	@Test
	void theAddFormLabelsTheNameFieldAsShiftName() {
		assertThat(body("/admin/shifts?action=add&lang=en"))
				.contains("<label for=\"name\">Shift Name</label>");
	}

	@Test
	void theListFiltersAndTheCompanyFilterOutlivesItsRequest() {
		seedShift(this.companyA, "Alpha Morning");
		seedShift(this.companyB, "Beta Morning");

		assertThat(body("/admin/shifts")).contains("Alpha Morning").contains("Beta Morning");
		assertThat(body("/admin/shifts?company_id=" + this.companyA))
				.contains("Alpha Morning").doesNotContain("Beta Morning");
		assertThat(body("/admin/shifts"))
				.as("the filter is session state")
				.contains("Alpha Morning").doesNotContain("Beta Morning");
		assertThat(body("/admin/shifts?search=Alpha")).contains("Alpha Morning");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/shifts", this.cookie, page("/admin/shifts?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Audited");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'shift'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/shifts", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private long seedShift(long companyId, String name) {
		this.jdbc.update("INSERT INTO shifts (company_id, name, start_time, end_time, is_active,"
				+ " created_at) VALUES (?, ?, '08:00:00', '16:00:00', 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM shifts WHERE company_id = ? AND name = ?", Long.class, companyId, name);
	}

	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	@Test
	void anAddWithNoCompanyChosenAsksWhichActiveCompany() {
		long suspended = createCompany("Zeta Suspended");
		this.jdbc.update("UPDATE companies SET status = 'suspended' WHERE id = ?", suspended);

		String form = body("/admin/shifts?action=add&company_id=");
		Matcher select = Pattern.compile(
				"<select name=\"company_id\" id=\"sh_add_company\" required>(.*?)</select>", Pattern.DOTALL).matcher(form);
		assertThat(select.find()).as("with no company chosen, the add form asks for one").isTrue();
		assertThat(select.group(1))
				.as("every active company, and no other")
				.contains("<option value=\"" + this.companyA + "\">Alpha Co</option>")
				.contains("<option value=\"" + this.companyB + "\">Beta Co</option>")
				.doesNotContain("value=\"" + suspended + "\"");
		assertThat(form).as("instead of posting a company of 0").doesNotContain("name=\"company_id\" value=\"0\"");

		assertThat(addForm(body("/admin/shifts?action=add&company_id=" + this.companyA), "add"))
				.as("the add form on a page already filtered to a company keeps that company, hidden")
				.doesNotContain("<select name=\"company_id\"")
				.contains("<input type=\"hidden\" name=\"company_id\" value=\"" + this.companyA + "\">");
	}

	/** The add form alone, so a hidden input elsewhere on the page (the pager's) cannot answer for it. */
	private static String addForm(String html, String action) {
		int field = html.indexOf("value=\"" + action + "\"");
		assertThat(field).as("the page renders the add form").isPositive();
		return html.substring(html.lastIndexOf("<form", field), html.indexOf("</form>", field));
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

	/**
	 * A write for an id that matches no row is refused, and writes no audit row (#286). An
	 * administrator's row ownership is checked on neither side (R-044), so the update's count is
	 * the only thing left to catch a stale tab or a crafted id. Legacy flashes
	 * {@code error_required} there, which says a required field is missing when none is; this
	 * answers {@code no_data}, which legacy uses for a row that is not there.
	 */
	@Test
	void aSaveOrDeleteForAnIdThatMatchesNoRowIsRefusedAndAuditsNothing() {
		long missing = 987654L;
		assertThat(post("/admin/shifts", this.cookie,
				page("/admin/shifts?action=edit&id=" + missing, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(missing),
				"company_id", String.valueOf(this.companyA), "name", "Ghost",
				"start_time", "08:00", "end_time", "16:00", "is_active", "1")
				.getHeaders().getLocation()).asString().contains("error=no_data");
		assertThat(post("/admin/shifts", this.cookie, page("/admin/shifts", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(missing),
				"company_id", String.valueOf(this.companyA))
				.getHeaders().getLocation()).asString().contains("error=no_data");

		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_events"
				+ " WHERE target_type = 'shift'", Integer.class))
				.as("no audit row for a shift that is not there").isZero();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM shifts WHERE name = 'Ghost'",
				Integer.class)).isZero();
	}

}
