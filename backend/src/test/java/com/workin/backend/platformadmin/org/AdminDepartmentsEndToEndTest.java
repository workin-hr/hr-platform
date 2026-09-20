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
class AdminDepartmentsEndToEndTest {

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

		// One administrator, one password (ADR-0018): the bootstrap provisioned
		// the row from the configured password when the context started.
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));

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
	void anUnfilteredAdministratorCannotAttachAnotherCompanysBranches() {
		// The department's own company is authoritative on an edit. The form
		// carries company_id and an administrator may post any value, so
		// validating the branches against *that* would let this edit link a
		// company-A department to company-B branches -- tenant ownership
		// changed through an editable foreign key.
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Fixed",
				"branch_ids", String.valueOf(this.branchA1));
		long id = this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE name = 'Fixed'", Long.class);
		body("/admin/departments?company_id=");

		assertThat(post("/admin/departments", this.cookie,
				page("/admin/departments?action=edit&id=" + id, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyB), "name", "Fixed",
				"branch_ids", String.valueOf(this.branchB1), "is_active", "1")
				.getHeaders().getLocation()).asString()
				.contains("error=select_at_least_one_branch");

		assertThat(this.jdbc.queryForList(
				"SELECT branch_id FROM department_branches WHERE department_id = ?", Long.class, id))
				.as("still linked to its own company's branch")
				.containsExactly(this.branchA1);
		assertThat(this.jdbc.queryForObject(
				"SELECT company_id FROM departments WHERE id = " + id, Long.class))
				.isEqualTo(this.companyA);
	}

	@Test
	void anEditMayStillChangeBranchesWithinTheSameCompany() {
		// Immutable company, not immutable branch set.
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Movable2",
				"branch_ids", String.valueOf(this.branchA1));
		long id = this.jdbc.queryForObject(
				"SELECT id FROM departments WHERE name = 'Movable2'", Long.class);
		body("/admin/departments?company_id=");

		post("/admin/departments", this.cookie,
				page("/admin/departments?action=edit&id=" + id, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA), "name", "Movable2",
				"branch_ids", String.valueOf(this.branchA2), "is_active", "1");

		assertThat(this.jdbc.queryForList(
				"SELECT branch_id FROM department_branches WHERE department_id = ?", Long.class, id))
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
	void anUnchangedSaveAndARepeatDeleteStillFlashTheirSuccess() {
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Steady",
				"branch_ids", String.valueOf(this.branchA1));
		long id = this.jdbc.queryForObject("SELECT id FROM departments WHERE name = 'Steady'", Long.class);
		body("/admin/departments");
		for (int round = 1; round <= 2; round++) {
			assertThat(post("/admin/departments", this.cookie,
					page("/admin/departments?action=edit&id=" + id, this.cookie).csrf(),
					"action", "save_edit", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
					"name", "Steady", "branch_ids", String.valueOf(this.branchA1), "is_active", "1")
					.getHeaders().getLocation()).as("save %d", round).asString().doesNotContain("action=edit");
			assertThat(body("/admin/departments")).as("save %d", round)
					.contains("<div class=\"flash flash-success\">تم الحفظ بنجاح ✓</div>");
		}
		for (int round = 1; round <= 2; round++) {
			post("/admin/departments", this.cookie, page("/admin/departments", this.cookie).csrf(),
					"action", "delete", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA));
			assertThat(body("/admin/departments")).as("delete %d", round)
					.contains("<div class=\"flash flash-error\">تم الحذف</div>");
		}
	}

	@Test
	void theBranchFilterNarrowsToDepartmentsSpanningThatBranch() {
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "North Only",
				"branch_ids", String.valueOf(this.branchA1));
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "South Only",
				"branch_ids", String.valueOf(this.branchA2));

		String html = body("/admin/departments?filter_branch=" + this.branchA1);
		// The toolbar's filter cascade lists every department under its branches (D-260); the
		// list itself is what the filter narrows.
		Matcher toolbar = Pattern.compile(
				"<form method=\"GET\" class=\"toolbar-form toolbar-form--labeled\"((?:[^>\"]|\"[^\"]*\")*)>").matcher(html);
		assertThat(toolbar.find()).as("the toolbar's filter form").isTrue();
		assertThat(toolbar.group(1)).contains("South Only");
		assertThat(html.substring(0, toolbar.start()) + html.substring(toolbar.end()))
				.contains("North Only").doesNotContain("South Only");
	}

	/**
	 * The toolbar keeps the filtered branch for org-filter-cascade.js, which redraws the branch
	 * select from it (D-260). Kept as 0, the select would show "All" while the list stays
	 * filtered, and the next search would drop the branch.
	 */
	@Test
	void theToolbarKeepsTheFilteredBranchForItsCascade() {
		String html = body("/admin/departments?company_id=" + this.companyA + "&filter_branch=" + this.branchA1);
		Matcher toolbar = Pattern.compile(
				"<form method=\"GET\" class=\"toolbar-form toolbar-form--labeled\"((?:[^>\"]|\"[^\"]*\")*)>").matcher(html);
		assertThat(toolbar.find()).as("the toolbar's filter form").isTrue();
		assertThat(toolbar.group(1)).contains("data-selected-branch=\"" + this.branchA1 + "\"");
	}

	@Test
	void anAddWithNoCompanyChosenAsksForOneAndLeavesTheBranchesToIt() throws java.io.IOException {
		long suspended = createCompany("Zeta Suspended");
		this.jdbc.update("UPDATE companies SET status = 'suspended' WHERE id = ?", suspended);
		long retired = seedBranch(this.companyA, "Alpha Retired");
		this.jdbc.update("UPDATE branches SET is_active = 0 WHERE id = ?", retired);

		String form = formFor(body("/admin/departments?company_id=&action=add"), "add");

		Matcher company = Pattern.compile(
				"<select name=\"company_id\" id=\"dp_company_id\"([^>]*)>(.*?)</select>", Pattern.DOTALL).matcher(form);
		assertThat(company.find()).as("the add form asks for the company").isTrue();
		assertThat(company.group(1)).contains("data-dept-company=\"1\"").contains("required")
				.doesNotContain("data-jt-company");
		assertThat(company.group(2)).as("the active companies, not a suspended one")
				.contains("value=\"" + this.companyA + "\"").contains("value=\"" + this.companyB + "\"")
				.doesNotContain("value=\"" + suspended + "\"");
		assertThat(form.indexOf("data-org-dept-form")).as("the company select sits inside the script's wrapper")
				.isPositive().isLessThan(company.start());
		assertThat(form).as("no hidden company of 0").doesNotContain("type=\"hidden\" name=\"company_id\"");

		assertThat(form).as("the picker waits for the company, as legacy's does")
				.contains("dept-branches-panel is-disabled")
				.contains("data-dept-toolbar hidden")
				.contains("data-dept-branches-picker data-disabled=\"1\"")
				.contains(arabic("select_company_first_branches"))
				.doesNotContain("name=\"branch_ids[]\"");

		Matcher json = Pattern.compile("data-branches=\"([^\"]*)\"").matcher(form);
		assertThat(json.find()).as("the wrapper carries every company's active branches").isTrue();
		Map<?, ?> byCompany = new tools.jackson.databind.ObjectMapper().readValue(unescape(json.group(1)), Map.class);
		assertThat(names(byCompany, this.companyA)).containsExactly("Alpha North", "Alpha South");
		assertThat(names(byCompany, this.companyB)).containsExactly("Beta Central");
	}

	@Test
	void anAddUnderAChosenCompanyListsThatCompanysBranchesAsCards() {
		long retired = seedBranch(this.companyA, "Alpha Retired");
		this.jdbc.update("UPDATE branches SET is_active = 0 WHERE id = ?", retired);

		String form = formFor(body("/admin/departments?company_id=" + this.companyA + "&action=add"), "add");

		assertThat(form).as("the filtered company travels hidden")
				.contains("<input type=\"hidden\" name=\"company_id\" value=\"" + this.companyA + "\">")
				.doesNotContain("data-dept-company")
				.doesNotContain("data-disabled=\"1\"");
		assertThat(form).as("every hook department-form.js looks up")
				.contains("data-org-dept-form", "data-dept-branches-panel", "data-dept-toolbar", "data-dept-search",
						"data-dept-select-all", "data-dept-clear-all", "data-dept-count-badge",
						"data-dept-branches-picker", "data-dept-branches-grid", "dept-branch-card__name");
		assertThat(cards(form)).as("that company's active branches, in name order")
				.containsExactly(this.branchA1, this.branchA2);
		assertThat(checked(form)).isEmpty();
	}

	@Test
	void anEditListsItsOwnCompanysBranchesAndKeepsARetiredOneItIsLinkedTo() throws java.io.IOException {
		long west = seedBranch(this.companyA, "Alpha West");
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Kept",
				"branch_ids[]", String.valueOf(this.branchA1), "branch_ids[]", String.valueOf(this.branchA2));
		long id = this.jdbc.queryForObject("SELECT id FROM departments WHERE name = 'Kept'", Long.class);
		this.jdbc.update("UPDATE branches SET is_active = 0 WHERE id = ?", this.branchA2);

		Page page = page("/admin/departments?company_id=&action=edit&id=" + id, this.cookie);
		String form = formFor(page.response().getBody(), "save_edit");

		assertThat(cards(form)).as("the row's company's branches only, the retired linked one included")
				.containsExactly(this.branchA1, this.branchA2, west);
		assertThat(checked(form)).containsExactly(this.branchA1, this.branchA2);
		assertThat(form).as("the count legacy's badge shows")
				.contains("2 " + arabic("branches_selected_label_plural"))
				.doesNotContain("data-dept-company");
		assertThat(form).as("no data-fixed-company, so the script keeps these cards rather than re-rendering active ones")
				.doesNotContain("data-fixed-company");

		post("/admin/departments", this.cookie, page.csrf(), "action", "save_edit",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA), "name", "Kept",
				"branch_ids[]", String.valueOf(this.branchA1), "branch_ids[]", String.valueOf(this.branchA2),
				"is_active", "1");

		assertThat(this.jdbc.queryForList(
				"SELECT branch_id FROM department_branches WHERE department_id = ? ORDER BY branch_id", Long.class, id))
				.as("an unchanged save keeps the retired branch linked")
				.containsExactly(Math.min(this.branchA1, this.branchA2), Math.max(this.branchA1, this.branchA2));
	}

	@Test
	void theBranchesTheCardsPostAsBranchIdsWithBracketsAreLinked() {
		// The cards post branch_ids[]. The controller reads branch_ids, and Spring's
		// @RequestParam resolver falls back to the bracketed name; this holds it there.
		post("/admin/departments", this.cookie, page("/admin/departments?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Bracketed",
				"branch_ids[]", String.valueOf(this.branchA1), "branch_ids[]", String.valueOf(this.branchA2));

		assertThat(this.jdbc.queryForList("SELECT db.branch_id FROM department_branches db"
				+ " INNER JOIN departments d ON d.id = db.department_id WHERE d.name = 'Bracketed' ORDER BY db.branch_id", Long.class))
				.containsExactly(Math.min(this.branchA1, this.branchA2), Math.max(this.branchA1, this.branchA2));
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

	private static String formFor(String html, String action) {
		int field = html.indexOf("name=\"action\" value=\"" + action + "\"");
		assertThat(field).as("the page renders the %s form", action).isPositive();
		return html.substring(html.lastIndexOf("<form", field), html.indexOf("</form>", field));
	}

	private static final Pattern CARD = Pattern.compile("name=\"branch_ids\\[\\]\"\\s+value=\"(\\d+)\"( checked)?");

	private static List<Long> cards(String form) {
		return CARD.matcher(form).results().map(card -> Long.parseLong(card.group(1))).toList();
	}

	private static List<Long> checked(String form) {
		return CARD.matcher(form).results().filter(card -> card.group(2) != null)
				.map(card -> Long.parseLong(card.group(1))).toList();
	}

	private static List<String> names(Map<?, ?> byCompany, long companyId) {
		Object branches = byCompany.get(String.valueOf(companyId));
		return branches == null ? List.of()
				: ((List<?>) branches).stream().map(branch -> (String) ((Map<?, ?>) branch).get("name")).toList();
	}

	/** JTE escapes the JSON it puts in an attribute. */
	private static String unescape(String attribute) {
		return attribute.replace("&#34;", "\"").replace("&#39;", "'").replace("&lt;", "<")
				.replace("&gt;", ">").replace("&amp;", "&");
	}

	/** The page renders in Arabic by default. */
	private static String arabic(String key) throws java.io.IOException {
		java.util.Properties catalogue = new java.util.Properties();
		try (java.io.InputStream in = AdminDepartmentsEndToEndTest.class.getResourceAsStream("/i18n/admin-messages_ar.properties")) {
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
		assertThat(post("/admin/departments", this.cookie, page("/admin/departments", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(missing),
				"company_id", String.valueOf(this.companyA))
				.getHeaders().getLocation()).asString().contains("error=no_data");
		// The save was already refused before this change: it reads the row's own company
		// first, and a missing row has none (D-253's #256 row).
		assertThat(post("/admin/departments", this.cookie,
				page("/admin/departments?action=edit&id=" + missing, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(missing),
				"company_id", String.valueOf(this.companyA), "name", "Ghost",
				"branch_ids", String.valueOf(this.branchA1), "is_active", "1")
				.getHeaders().getLocation()).asString().contains("error=error_db");

		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_events"
				+ " WHERE target_type = 'department'", Integer.class))
				.as("no audit row for a department that is not there").isZero();
	}

}
