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
 * {@code /admin/job_titles} over real HTTP against a real MariaDB.
 *
 * <p>The list and filter machinery is proved on the branches page. This
 * concentrates on the two rules only this page has: a department that is
 * <b>optional</b> but must belong to the same company when given, and
 * {@code work_hours} that is required and must be strictly positive.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminJobTitlesEndToEndTest {

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

	private long departmentA;

	private long departmentB;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM job_titles");
		this.jdbc.update("DELETE FROM department_branches");
		this.jdbc.update("DELETE FROM departments");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		// One administrator, one password (ADR-0018): the bootstrap provisioned
		// the row from the configured password when the context started.
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));

		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");
		this.departmentA = seedDepartment(this.companyA, "Alpha Dept");
		this.departmentB = seedDepartment(this.companyB, "Beta Dept");
	}

	@Test
	void addingAJobTitleWithADepartmentWritesBoth() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Engineer",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, department_id, work_hours, is_active FROM job_titles"
						+ " WHERE name = 'Engineer'");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("department_id").toString()).isEqualTo(String.valueOf(this.departmentA));
		assertThat(((java.math.BigDecimal) row.get("work_hours")).compareTo(
				new java.math.BigDecimal("8"))).isZero();
		assertThat(row.get("is_active")).isEqualTo(Boolean.TRUE);
		assertThat(body("/admin/job_titles")).contains("Engineer").contains("Alpha Dept");
	}

	@Test
	void theDepartmentIsOptionalAndZeroMeansNone() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Floater",
				"department_id", "0", "work_hours", "6");

		assertThat(this.jdbc.queryForObject(
				"SELECT department_id FROM job_titles WHERE name = 'Floater'", Long.class))
				.as("`?: null` makes 0 mean none, and none is not checked for ownership")
				.isNull();
		// And it renders as an em dash rather than as a broken join.
		assertThat(body("/admin/job_titles")).contains("Floater");
	}

	@Test
	void aDepartmentFromAnotherCompanyIsRefusedForTheAdministratorToo() {
		// Same rule as the department page's branch check, and the same reason:
		// pointing at another company's row is a broken link, not cross-company
		// editing.
		assertThat(post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Crossed",
				"department_id", String.valueOf(this.departmentB), "work_hours", "8")
				.getHeaders().getLocation()).asString()
				.contains("error=select_company_first_department");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM job_titles", Integer.class)).isZero();
	}

	@Test
	void workHoursMustBePresentAndPositive() {
		for (String hours : List.of("", "0", "-3", "eight")) {
			assertThat(post("/admin/job_titles", this.cookie,
					page("/admin/job_titles?action=add", this.cookie).csrf(),
					"action", "add", "company_id", String.valueOf(this.companyA),
					"name", "Hours " + hours, "work_hours", hours)
					.getHeaders().getLocation()).asString()
					.as("work_hours '%s'", hours)
					.contains("action=add").contains("error=error_required");
		}
		// "eight" is worth naming: PHP's float cast yields 0.0 rather than
		// raising, so it fails the positivity test, not a parse.
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM job_titles", Integer.class)).isZero();
	}

	@Test
	void aFractionalWorkHoursValueIsKept() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Part Time",
				"work_hours", "7.5");

		assertThat(this.jdbc.queryForObject(
				"SELECT work_hours FROM job_titles WHERE name = 'Part Time'", java.math.BigDecimal.class)
				.compareTo(new java.math.BigDecimal("7.5"))).isZero();
	}

	@Test
	void anEmptyNameIsRefused() {
		assertThat(post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "  ",
				"work_hours", "8").getHeaders().getLocation()).asString()
				.contains("error=error_required");
	}

	@Test
	void anAddWithNoCompanyChosenAsksForOneAndLeavesTheDepartmentsToIt() throws Exception {
		// _job_title_form.php: with no company, the company is a required select of
		// active ones, and the department select waits for it with every company's
		// active departments on the wrapper for job-title-form.js to narrow.
		long suspended = createCompany("Zeta Suspended");
		this.jdbc.update("UPDATE companies SET status = 'suspended' WHERE id = ?", suspended);
		long retired = seedDepartment(this.companyA, "Alpha Retired");
		this.jdbc.update("UPDATE departments SET is_active = 0 WHERE id = ?", retired);

		String form = formFor(body("/admin/job_titles?action=add&company_id="), "add");

		Matcher company = Pattern.compile(
				"<select name=\"company_id\" id=\"jt_add_company\"([^>]*)>(.*?)</select>", Pattern.DOTALL).matcher(form);
		assertThat(company.find()).as("with no company chosen, the add form asks for one").isTrue();
		assertThat(company.group(1)).as("required, and marked for job-title-form.js")
				.contains("required").contains("data-jt-company");
		assertThat(company.group(2)).as("every active company, and no other")
				.contains("value=\"" + this.companyA + "\"")
				.contains("value=\"" + this.companyB + "\"")
				.doesNotContain("value=\"" + suspended + "\"");
		assertThat(form).as("instead of posting a company of 0").doesNotContain("name=\"company_id\" value=\"0\"");
		assertThat(form.indexOf("data-org-jt-form"))
				.as("the company select sits inside the wrapper the script searches")
				.isPositive()
				.isLessThan(form.indexOf("id=\"jt_add_company\""));

		Matcher department = departmentSelect(form);
		assertThat(department.group(1)).as("waiting for a company, as legacy's select does")
				.contains("data-jt-department").contains("disabled");
		assertThat(department.group(2)).as("with no company's departments until one is chosen")
				.contains(arabic("select_company_first_department"))
				.doesNotContain("value=\"" + this.departmentA + "\"")
				.doesNotContain("value=\"" + this.departmentB + "\"");

		Matcher json = Pattern.compile("data-departments=\"([^\"]*)\"").matcher(form);
		assertThat(json.find()).as("the departments travel on the wrapper").isTrue();
		Map<?, ?> byCompany = new tools.jackson.databind.ObjectMapper().readValue(unescape(json.group(1)), Map.class);
		assertThat(names(byCompany, this.companyA)).as("each company's active departments").containsExactly("Alpha Dept");
		assertThat(names(byCompany, this.companyB)).containsExactly("Beta Dept");
	}

	@Test
	void anAddUnderAChosenCompanyListsThatCompanysDepartmentsOnly() {
		String form = formFor(body("/admin/job_titles?action=add&company_id=" + this.companyA), "add");

		assertThat(form).as("the chosen company travels hidden")
				.contains("<input type=\"hidden\" name=\"company_id\" value=\"" + this.companyA + "\">")
				.doesNotContain("<select name=\"company_id\"");
		Matcher department = departmentSelect(form);
		assertThat(department.group(1)).doesNotContain("disabled");
		assertThat(department.group(2))
				.contains("value=\"" + this.departmentA + "\"")
				.doesNotContain("value=\"" + this.departmentB + "\"");
	}

	@Test
	void anEditListsItsOwnCompanysDepartmentsAndKeepsARetiredOneItHas() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Keeper",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");
		long id = this.jdbc.queryForObject("SELECT id FROM job_titles WHERE name = 'Keeper'", Long.class);
		this.jdbc.update("UPDATE departments SET is_active = 0 WHERE id = ?", this.departmentA);

		String form = formFor(body("/admin/job_titles?company_id=&action=edit&id=" + id), "save_edit");

		Matcher department = departmentSelect(form);
		assertThat(department.group(2))
				.as("the retired department the title has is still its choice")
				.containsPattern("value=\"" + this.departmentA + "\"\\s+selected")
				.as("and no other company's department is offered")
				.doesNotContain("value=\"" + this.departmentB + "\"")
				.doesNotContain("Beta Co");

		post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=edit&id=" + id, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA), "name", "Keeper",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8", "is_active", "1");
		assertThat(this.jdbc.queryForObject("SELECT department_id FROM job_titles WHERE id = " + id, Long.class))
				.as("an unchanged save keeps it").isEqualTo(this.departmentA);
	}

	@Test
	void editingCanClearTheDepartment() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Movable",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");
		long id = this.jdbc.queryForObject(
				"SELECT id FROM job_titles WHERE name = 'Movable'", Long.class);

		post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=edit&id=" + id, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA), "name", "Movable",
				"department_id", "0", "work_hours", "8", "is_active", "1");

		assertThat(this.jdbc.queryForObject(
				"SELECT department_id FROM job_titles WHERE id = " + id, Long.class)).isNull();
	}

	@Test
	void anUnfilteredAdministratorCannotAttachAnotherCompanysDepartment() {
		// Same invariant as the departments page: the job title's own company
		// decides which departments it may point at, not the posted company_id.
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Fixed",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");
		long id = this.jdbc.queryForObject(
				"SELECT id FROM job_titles WHERE name = 'Fixed'", Long.class);
		body("/admin/job_titles?company_id=");

		assertThat(post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=edit&id=" + id, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyB), "name", "Fixed",
				"department_id", String.valueOf(this.departmentB), "work_hours", "8",
				"is_active", "1")
				.getHeaders().getLocation()).asString()
				.contains("error=select_company_first_department");

		assertThat(this.jdbc.queryForObject(
				"SELECT department_id FROM job_titles WHERE id = " + id, Long.class))
				.as("still its own company's department").isEqualTo(this.departmentA);
	}

	/**
	 * {@code org_helper.php:485-492} ({@code org_option_label}): unfiltered, an option reads
	 * {@code department — company}. The port had the order backwards.
	 */
	@Test
	void theFilterDepartmentOptionsAreLegacysDepartmentThenCompanyOrder() {
		assertThat(body("/admin/job_titles")).contains("Alpha Dept — Alpha Co");
	}

	/** {@code job_titles/page.php:149}: the department cell carries no class. */
	@Test
	void theDepartmentCellCarriesNoClassAsLegacyDoesNot() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Muted",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");
		assertThat(body("/admin/job_titles")).contains("<td>Alpha Dept</td>");
	}

	@Test
	void theDepartmentFilterNarrows() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "In Dept",
				"department_id", String.valueOf(this.departmentA), "work_hours", "8");
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "No Dept",
				"work_hours", "8");

		assertThat(body("/admin/job_titles?filter_department=" + this.departmentA))
				.contains("In Dept").doesNotContain("No Dept");
	}

	/**
	 * The toolbar keeps the filtered department for org-filter-cascade.js, which redraws the
	 * department select from it (D-260). Kept as 0, the select would show "All" while the list
	 * stays filtered, and the next search would drop the department.
	 */
	@Test
	void theToolbarKeepsTheFilteredDepartmentForItsCascade() {
		String html = body("/admin/job_titles?company_id=" + this.companyA + "&filter_department=" + this.departmentA);
		Matcher toolbar = Pattern.compile(
				"<form method=\"GET\" class=\"toolbar-form toolbar-form--labeled\"((?:[^>\"]|\"[^\"]*\")*)>").matcher(html);
		assertThat(toolbar.find()).as("the toolbar's filter form").isTrue();
		assertThat(toolbar.group(1)).contains("data-selected-department=\"" + this.departmentA + "\"");
	}

	/** As on branches: an unchanged save and a repeat delete flash their success (D-253). */
	@Test
	void anUnchangedSaveAndARepeatDeleteStillFlashTheirSuccess() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Steady", "work_hours", "8");
		long id = this.jdbc.queryForObject("SELECT id FROM job_titles WHERE name = 'Steady'", Long.class);
		body("/admin/job_titles");
		for (int round = 1; round <= 2; round++) {
			assertThat(post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=edit&id=" + id, this.cookie).csrf(),
					"action", "save_edit", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
					"name", "Steady", "department_id", "0", "work_hours", "8", "is_active", "1")
					.getHeaders().getLocation()).as("save %d", round).asString().doesNotContain("action=edit");
			assertThat(body("/admin/job_titles")).as("save %d", round).contains("<div class=\"flash flash-success\">تم الحفظ بنجاح ✓</div>");
		}
		for (int round = 1; round <= 2; round++) {
			post("/admin/job_titles", this.cookie, page("/admin/job_titles", this.cookie).csrf(),
					"action", "delete", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA));
			assertThat(body("/admin/job_titles")).as("delete %d", round).contains("<div class=\"flash flash-error\">تم الحذف</div>");
		}
	}

	@Test
	void deleteDeactivatesRatherThanRemoving() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Closing",
				"work_hours", "8");
		long id = this.jdbc.queryForObject(
				"SELECT id FROM job_titles WHERE name = 'Closing'", Long.class);

		post("/admin/job_titles", this.cookie, page("/admin/job_titles", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM job_titles WHERE id = " + id, Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM job_titles WHERE id = " + id, Integer.class)).isEqualTo(1);
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/job_titles", this.cookie, page("/admin/job_titles?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Audited",
				"work_hours", "8");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'job_title'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/job_titles", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	/** One form alone, so an input elsewhere on the page (the toolbar's, the pager's) cannot answer for it. */
	private static String formFor(String html, String action) {
		int field = html.indexOf("name=\"action\" value=\"" + action + "\"");
		assertThat(field).as("the page renders the %s form", action).isPositive();
		return html.substring(html.lastIndexOf("<form", field), html.indexOf("</form>", field));
	}

	private static Matcher departmentSelect(String form) {
		Matcher select = Pattern.compile(
				"<select id=\"department_id\" name=\"department_id\"([^>]*)>(.*?)</select>", Pattern.DOTALL).matcher(form);
		assertThat(select.find()).as("the form's department select").isTrue();
		return select;
	}

	private static List<String> names(Map<?, ?> byCompany, long companyId) {
		Object departments = byCompany.get(String.valueOf(companyId));
		return departments == null ? List.of()
				: ((List<?>) departments).stream().map(department -> (String) ((Map<?, ?>) department).get("name")).toList();
	}

	/** An attribute value as the browser reads it: JTE escapes the JSON's quotes. */
	private static String unescape(String attribute) {
		return attribute.replace("&#34;", "\"").replace("&#39;", "'").replace("&lt;", "<")
				.replace("&gt;", ">").replace("&amp;", "&");
	}

	/** The page renders in Arabic by default, so text is compared with the catalogue. */
	private static String arabic(String key) throws java.io.IOException {
		java.util.Properties catalogue = new java.util.Properties();
		try (java.io.InputStream in = AdminJobTitlesEndToEndTest.class.getResourceAsStream("/i18n/admin-messages_ar.properties")) {
			catalogue.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
		}
		return catalogue.getProperty(key);
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

	private long seedDepartment(long companyId, String name) {
		this.jdbc.update("INSERT INTO departments (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE company_id = ? AND name = ?", Long.class,
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
	void aDeleteForAnIdThatMatchesNoRowIsRefusedAndAuditsNothing() {
		long missing = 987654L;
		assertThat(post("/admin/job_titles", this.cookie, page("/admin/job_titles", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(missing),
				"company_id", String.valueOf(this.companyA))
				.getHeaders().getLocation()).asString().contains("error=no_data");
		// The save was already refused before this change, as departments' is (D-253's #256 row).
		assertThat(post("/admin/job_titles", this.cookie,
				page("/admin/job_titles?action=edit&id=" + missing, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(missing),
				"company_id", String.valueOf(this.companyA), "name", "Ghost",
				"work_hours", "8", "is_active", "1")
				.getHeaders().getLocation()).asString().contains("error=error_db");

		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_events"
				+ " WHERE target_type = 'job_title'", Integer.class))
				.as("no audit row for a job title that is not there").isZero();
	}

}
