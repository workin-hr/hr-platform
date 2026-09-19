package com.workin.backend.platformadmin.web;

import java.net.http.HttpClient;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.legacy.profile.LegacyCompanyDelete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole administrative journey over real HTTP: the password, the pages,
 * one company action with its audit row, the logout that ends the shared
 * session, and a deactivation that ends a live one.
 *
 * <p>End to end because the pieces have been green in isolation while the
 * journey was broken between them -- a route omitted from the public list
 * lands on the entry point and redirects to the login page, which is also
 * where success goes, so a test that trusts the destination passes either way.
 */
@TestPropertySource(properties = "app.platform-admin.actions.enabled=true")
class PlatformAdminFullFlowTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");



	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private LegacyCompanyDelete companyDelete;

	@Autowired
	@Qualifier("legacyTransactionManager")
	private PlatformTransactionManager legacyTransactionManager;


	@BeforeEach
	void doNotFollowRedirects() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
	}

	@Test
	void theCompleteAdministrativeJourney() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long adminId = adminId();
		long companyId = createCompany();

		String cookie = signIn();
		Page home = get("/admin", cookie);
		assertThat(home.response().getStatusCode()).isEqualTo(HttpStatus.OK);
		// home_format_money(): the three salary totals carry the currency in the page's language.
		assertThat(java.util.regex.Pattern.compile("<div class=\"num\">\\d{1,3}(,\\d{3})* \u062c\\.\u0645</div>")
				.matcher(home.response().getBody()).results().count())
				.as("gross, basic and net salaries").isEqualTo(3);
		Page sessions = get("/admin/sessions", cookie);
		// The current session is the row carrying the badge. Asserted as markup
		// rather than as its label: this surface renders in Arabic by default
		// and the wording is a translation now (D-208), which is not what this
		// journey is about -- AdminTemplateMessageKeyTest owns the key.
		assertThat(sessions.response().getBody()).contains("<span class=\"badge\">");

		// One POST, like every other page's actions (ADR-0018).
		Page companies = get("/admin/companies", cookie);
		ResponseEntity<String> applied = post("/admin/companies/action", cookie, companies.csrf(),
				"action", "COMPANY_SUSPEND", "companyId", String.valueOf(companyId),
				"reason", "non-payment");
		assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(statusOf(companyId)).isEqualTo("suspended");
		assertThat(jdbc.queryForList(
				"SELECT event_type, target_type, target_id "
						+ "FROM platform_admin_audit_events WHERE platform_admin_id = ? "
						+ "AND event_type = 'COMPANY_SUSPENDED'", adminId))
			.as("a committed change cannot exist without its audit row")
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("target_type")).isEqualTo("COMPANY");
				assertThat(row.get("target_id")).isEqualTo(String.valueOf(companyId));
			});

		ResponseEntity<String> loggedOut = post("/admin/logout", cookie, get("/admin", cookie).csrf());
		assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(get("/admin", cookie).response().getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?",
				Integer.class, sessionIdOf(cookie)))
			.as("the shared row must be gone, or another worker still honours the cookie")
			.isZero();
	}

	@Test
	void eachCompanyRowOffersEditRightAfterDetails() {
		// company_helper.php:190-197: Details, then Edit. The controller already
		// opens the prefilled form at ?edit=<id>; only the way in was missing.
		String cookie = signIn();
		long companyId = createCompany();

		Page companies = get("/admin/companies", cookie);
		String html = companies.response().getBody();
		int start = html.indexOf("id=\"row-actions-menu-" + companyId + "\"");
		assertThat(start).as("the row menu for company %s", companyId).isPositive();
		String menu = html.substring(start, html.indexOf("</div>", start));

		int details = menu.indexOf("href=\"/admin/companies/" + companyId + "\"");
		int edit = menu.indexOf("href=\"/admin/companies?edit=" + companyId + "\"");
		assertThat(details).as("the menu still links to the detail page").isPositive();
		assertThat(edit).as("the menu links to this company's edit form").isPositive();
		assertThat(edit).as("Edit comes right after Details, as in legacy").isGreaterThan(details);

		String form = get("/admin/companies?edit=" + companyId, cookie).response().getBody();
		assertThat(form).as("the link opens the company form").contains("class=\"modal-bg open\" id=\"companyModal\"");
		// ?action=add opens the same modal, so the markup must also be this company's edit form.
		String name = new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT company_name FROM companies WHERE id = ?", String.class, companyId);
		assertThat(form).as("the form saves an edit, not an add")
				.containsPattern("name=\"action\"\\s+value=\"save_edit\"");
		assertThat(form).as("for this company").contains("name=\"id\" value=\"" + companyId + "\"");
		assertThat(form).as("prefilled with its name")
				.containsPattern("id=\"co_name\"[^>]*value=\"" + Pattern.quote(name) + "\"");
	}

	@Test
	void aCompanyWithoutLookupsOpensItsFormWithNothingChosen() {
		// _company_form.php:74-93 starts each required lookup select with an empty
		// "choose" option. Without it, a company whose lookups are NULL (a
		// registration stopped after step one) shows, and would save, the first
		// activity, title and size as if they were stored.
		String cookie = signIn();
		long companyId = createCompany();
		// Real options, so a select without its empty choice would show the first of them.
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		jdbc.update("INSERT INTO company_activities (id, name) VALUES (24301, 'Flow activity')");
		jdbc.update("INSERT INTO company_titles (id, name) VALUES (24311, 'Flow title')");
		jdbc.update("INSERT INTO company_sizes (id, name, min_employees, max_employees) VALUES (24321, 'Flow size', 1, 10)");

		String form = get("/admin/companies?edit=" + companyId, cookie).response().getBody();
		java.util.Map<String, Long> seeded = java.util.Map.of("co_act", 24301L, "co_title_id", 24311L, "co_size_id", 24321L);
		for (String select : List.of("co_act", "co_title_id", "co_size_id")) {
			int start = form.indexOf("<select id=\"" + select + "\"");
			assertThat(start).as("the %s select renders", select).isPositive();
			String markup = form.substring(start, form.indexOf("</select>", start));
			assertThat(markup).as("%s lists the stored options", select)
					.contains("value=\"" + seeded.get(select) + "\"");
			assertThat(markup).as("%s starts with an empty choice", select)
					.containsPattern("^<select[^>]*>\\s*<option value=\"\">");
			assertThat(markup).as("%s preselects nothing for a company without one", select)
					.doesNotContain("selected");
		}
	}

	/**
	 * _company_form.php's country select (D-261): the active countries, labelled flag, name and
	 * code, +20 chosen on an add and the stored code on an edit; the form carries legacy's
	 * invalid-phone message, and the page loads the rules and the two phone scripts.
	 */
	@Test
	void theCompanyFormSelectsItsCountryFromTheActiveOnesAndLoadsLegacysPhoneChecks() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		// Removed afterwards: in a database with no other country, a leftover one becomes the
		// dial code every other code resolves to.
		int egyptAdded = jdbc.update("INSERT IGNORE INTO phone_countries (country_code, name_ar, name_en, flag_emoji,"
				+ " phone_length, phone_prefixes, is_active, sort_order) VALUES ('+20', 'مصر', 'Egypt', '', 11,"
				+ " '[\"010\"]', 1, 1)");
		jdbc.update("INSERT INTO phone_countries (country_code, name_ar, name_en, flag_emoji, phone_length,"
				+ " phone_prefixes, is_active, sort_order) VALUES ('+881', 'بلد تجريبي', 'Test land', '🏳', 10,"
				+ " '[\"07\"]', 1, 90), ('+882', 'بلد متقاعد', 'Retired land', '', 10, '[\"07\"]', 0, 91)");
		try {
			companyFormSelectsItsCountry(jdbc);
		}
		finally {
			jdbc.update("DELETE FROM phone_countries WHERE country_code IN ('+881', '+882')");
			if (egyptAdded == 1) {
				jdbc.update("DELETE FROM phone_countries WHERE country_code = '+20'");
			}
		}
	}

	private void companyFormSelectsItsCountry(JdbcTemplate jdbc) {
		String cookie = signIn();
		long companyId = createCompany();
		jdbc.update("UPDATE companies SET country_code = '+881' WHERE id = ?", companyId);

		String add = get("/admin/companies", cookie).response().getBody();
		assertThat(countrySelect(add)).as("required, as legacy's is")
				.startsWith("<select id=\"co_code\" name=\"country_code\" required>")
				.containsPattern("<option value=\"\\+20\"\\s+selected>")
				.contains("<option value=\"+881\"").contains(">🏳 بلد تجريبي (+881)</option>")
				.as("a retired country").doesNotContain("+882");
		assertThat(countrySelect(add).split("selected", -1)).as("one choice").hasSize(2);
		assertThat(add).contains("data-invalid-phone-msg=\"رقم الهاتف غير صالح لهذه الدولة\"")
				.containsSubsequence(
						"<script src=\"/admin/_assets/phone-countries-rules.js\" data-rules=\"",
						"&#34;+881&#34;:{&#34;phone_length&#34;:10,&#34;phone_prefixes&#34;:[&#34;07&#34;]}",
						"<script src=\"/admin/_assets/phone-validator.js\"></script>",
						"<script src=\"/admin/_assets/phone-form-bind.js\"></script>");

		String edit = get("/admin/companies?edit=" + companyId + "&lang=en", cookie).response().getBody();
		assertThat(countrySelect(edit)).as("the stored country, in the page's language")
				.containsPattern("<option value=\"\\+881\"\\s+selected>🏳 Test land \\(\\+881\\)</option>")
				.doesNotContainPattern("<option value=\"\\+20\"\\s+selected>");

		assertThat(get("/admin/sessions", cookie).response().getBody())
				.as("a page with no phone form loads no phone script").doesNotContain("phone-form-bind.js");
	}

	private static String countrySelect(String html) {
		int start = html.indexOf("<select id=\"co_code\"");
		assertThat(start).as("the country select renders").isPositive();
		return html.substring(start, html.indexOf("</select>", start));
	}

	@Test
	void eachCompanyRowOffersDeleteAfterEdit() {
		// company_helper.php:191-197: Details, Edit, then Delete as a danger item,
		// ahead of the status actions.
		String cookie = signIn();
		long companyId = createCompany();

		String html = get("/admin/companies", cookie).response().getBody();
		int start = html.indexOf("id=\"row-actions-menu-" + companyId + "\"");
		assertThat(start).as("the row menu for company %s", companyId).isPositive();
		String menu = html.substring(start, html.indexOf("</div>", start));

		Matcher delete = Pattern.compile("<a href=\"/admin/companies/" + companyId
				+ "/delete\"\\s+class=\"([^\"]*)\"").matcher(menu);
		assertThat(delete.find()).as("the menu links to this company's delete page").isTrue();
		assertThat(delete.group(1).split("\\s+")).as("styled as a destructive item")
				.contains("row-actions__item--danger");
		assertThat(delete.start()).as("after Edit")
				.isGreaterThan(menu.indexOf("href=\"/admin/companies?edit=" + companyId + "\""));
		assertThat(delete.start()).as("before the status actions, as in legacy")
				.isLessThan(menu.indexOf("COMPANY_SUSPEND"));
	}

	@Test
	void theDeletePageCountsWhatGoesWithTheCompanyAndDeletesNothing() {
		String cookie = signIn();
		long companyId = createCompany();
		long employeeId = seedBranchAndEmployee(companyId);
		seedDevice(companyId);

		ResponseEntity<String> page = get("/admin/companies/" + companyId + "/delete", cookie).response();

		assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
		// Every table the cascade deletes from, device tables included, not only the
		// client preview's fifteen; labelled in the page's default Arabic.
		assertThat(page.getBody())
			.containsPattern(statCard("1", arabic("admin-own", "company_delete_table_employees")))
			.containsPattern(statCard("1", arabic("admin-own", "company_delete_table_attendance_devices")))
			.containsPattern(statCard("1", arabic("admin-own", "company_delete_table_branches")))
			.containsPattern(statCard("3", arabic("admin-own", "company_delete_total")))
			.contains("<code dir=\"auto\">" + nameOf(companyId) + "</code>")
			.contains("<form method=\"post\" action=\"/admin/companies/" + companyId + "/delete\">");
		assertThat(companyExists(companyId)).as("showing the page deletes nothing").isTrue();
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = ?", employeeId)).isOne();
	}

	@Test
	void aConfirmationThatDoesNotMatchDeletesNothingAndRecordsNothing() {
		String cookie = signIn();
		long companyId = createCompany();
		long employeeId = seedBranchAndEmployee(companyId);
		String name = nameOf(companyId);
		String path = "/admin/companies/" + companyId + "/delete";

		for (String typed : List.of("", "Flow", name + "1", name.toUpperCase(java.util.Locale.ROOT))) {
			ResponseEntity<String> refused = post(path, cookie, get(path, cookie).csrf(), "confirmation", typed);
			assertThat(refused.getStatusCode()).as("typed %s", typed).isEqualTo(HttpStatus.OK);
			assertThat(refused.getBody()).as("typed %s", typed).contains(
					"<div class=\"flash flash-error\">" + arabic("admin-own", "company_delete_mismatch") + "</div>");
		}
		assertThat(companyExists(companyId)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = ?", employeeId)).isOne();
		assertThat(deletionAudits(companyId)).isEmpty();
	}

	@Test
	void typingTheNameDeletesTheCompanyWithEverythingUnderItAndAuditsIt() {
		String cookie = signIn();
		long adminId = adminId();
		long companyId = createCompany();
		long employeeId = seedBranchAndEmployee(companyId);
		String name = nameOf(companyId);
		String path = "/admin/companies/" + companyId + "/delete";

		// Surrounding spaces, as a paste can bring them, still match.
		ResponseEntity<String> deleted = post(path, cookie, get(path, cookie).csrf(),
				"confirmation", " " + name + " ");

		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(deleted.getHeaders().getLocation()).hasPath("/admin/companies");
		assertThat(companyExists(companyId)).isFalse();
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = ?", employeeId)).as("the cascade ran").isZero();
		assertThat(count("SELECT COUNT(*) FROM branches WHERE company_id = ?", companyId)).isZero();
		assertThat(deletionAudits(companyId)).singleElement().satisfies(row -> {
			assertThat(((Number) row.get("platform_admin_id")).longValue()).isEqualTo(adminId);
			assertThat(row.get("target_type")).isEqualTo("COMPANY");
			assertThat(row.get("detail")).as("what was confirmed, and what went with it")
					.isEqualTo(name + " (employees=1, branches=1)");
		});
	}

	/**
	 * Approving a company already active, or rejecting one already rejected with the same reason,
	 * still flashes the success and writes its audit row (D-253). Legacy flashes nothing, because its
	 * flash sits behind {@code dbUpdate()}'s changed-row count; this connection counts matched rows
	 * ({@code LegacyRowCountStartupCheck}), and the company is in the state asked for.
	 */
	@Test
	void reapprovingOrRerejectingACompanyStillFlashesItsSuccessAndIsAudited() {
		String cookie = signIn();
		long adminId = adminId();
		long approved = createCompany();
		long rejected = createCompany();
		new JdbcTemplate(this.legacyDataSource).update(
				"UPDATE companies SET status = 'rejected', rejection_reason = 'no registration' WHERE id = ?", rejected);

		post("/admin/companies/action", cookie, get("/admin/companies", cookie).csrf(),
				"action", "COMPANY_APPROVE", "companyId", String.valueOf(approved));
		assertThat(get("/admin/companies", cookie).response().getBody())
				.contains("<div class=\"flash flash-success\">تم القبول ✓</div>");
		post("/admin/companies/action", cookie, get("/admin/companies", cookie).csrf(),
				"action", "COMPANY_REJECT", "companyId", String.valueOf(rejected), "reason", "no registration");
		assertThat(get("/admin/companies", cookie).response().getBody())
				.contains("<div class=\"flash flash-warning\">تم الرفض</div>");

		assertThat(statusOf(approved)).isEqualTo("active");
		assertThat(statusOf(rejected)).isEqualTo("rejected");
		assertThat(new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT COUNT(*) FROM platform_admin_audit_events WHERE platform_admin_id = ?"
						+ " AND event_type IN ('COMPANY_APPROVED', 'COMPANY_REJECTED') AND target_id IN (?, ?)",
				Integer.class, adminId, String.valueOf(approved), String.valueOf(rejected)))
				.isEqualTo(2);
	}

	@Test
	void aCompanyWithoutANameDrawsLegacysCInItsAvatar() {
		// company_logo_src() (company_helper.php:173): `trim($name) ?: 'C'`.
		String cookie = signIn();
		long companyId = new JdbcTemplate(this.legacyDataSource).queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status)"
						+ " VALUES (NULL, ?, 'unused-hash', 'active') RETURNING id", Long.class,
				"+91" + (System.nanoTime() % 100_000_000_000L));

		String html = get("/admin/companies", cookie).response().getBody();
		int menu = html.indexOf("id=\"row-actions-menu-" + companyId + "\"");
		assertThat(menu).as("the row for company %s", companyId).isPositive();
		assertThat(html.substring(html.lastIndexOf("<tr", menu), menu))
				.contains("<span class=\"emp-tbl-avatar\" aria-hidden=\"true\">C</span>");
	}

	@Test
	void aCompanyWithoutANameIsConfirmedByItsPhone() {
		String cookie = signIn();
		String phone = "+91" + (System.nanoTime() % 100_000_000_000L);
		long companyId = new JdbcTemplate(this.legacyDataSource).queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status)"
						+ " VALUES (NULL, ?, 'unused-hash', 'pending') RETURNING id", Long.class, phone);
		String path = "/admin/companies/" + companyId + "/delete";

		Page page = get(path, cookie);
		assertThat(page.response().getBody()).contains("<code dir=\"auto\">" + phone + "</code>");
		assertThat(post(path, cookie, page.csrf(), "confirmation", "").getStatusCode())
			.as("an empty field never confirms, even with no name to type")
			.isEqualTo(HttpStatus.OK);
		assertThat(companyExists(companyId)).isTrue();

		assertThat(post(path, cookie, get(path, cookie).csrf(), "confirmation", phone).getStatusCode())
			.isEqualTo(HttpStatus.FOUND);
		assertThat(companyExists(companyId)).isFalse();
	}

	@Test
	void aCascadeThatFailsRollsBackTheDeleteAndItsAuditRow() {
		String cookie = signIn();
		long companyId = createCompany();
		long employeeId = seedBranchAndEmployee(companyId);
		String name = nameOf(companyId);
		String path = "/admin/companies/" + companyId + "/delete";
		Page page = get(path, cookie);
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		// The company row goes last, so this fails the cascade after its employee
		// and branch deletes have run in the same transaction.
		jdbc.execute("CREATE TRIGGER flow_company_delete_refused BEFORE DELETE ON companies"
				+ " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced failure for the rollback test'");
		ResponseEntity<String> failed;
		try {
			failed = post(path, cookie, page.csrf(), "confirmation", name);
		} finally {
			jdbc.execute("DROP TRIGGER IF EXISTS flow_company_delete_refused");
		}

		// pages/companies/page.php:24-27 tells the operator "database error".
		assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(failed.getBody())
			.contains("<div class=\"flash flash-error\">" + arabic("admin-messages", "error_db") + "</div>");
		assertThat(companyExists(companyId)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = ?", employeeId))
			.as("the deletes that ran before the failure roll back with it")
			.isOne();
		assertThat(deletionAudits(companyId)).as("no audit row claims a delete that did not happen").isEmpty();
	}

	@Test
	void anAuditRowThatCannotBeWrittenDeletesNothing() {
		String cookie = signIn();
		long companyId = createCompany();
		long employeeId = seedBranchAndEmployee(companyId);
		String name = nameOf(companyId);
		String path = "/admin/companies/" + companyId + "/delete";
		Page page = get(path, cookie);
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		jdbc.execute("CREATE TRIGGER flow_audit_insert_refused BEFORE INSERT ON platform_admin_audit_events"
				+ " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced failure for the audit test'");
		ResponseEntity<String> failed;
		try {
			failed = post(path, cookie, page.csrf(), "confirmation", name);
		} finally {
			jdbc.execute("DROP TRIGGER IF EXISTS flow_audit_insert_refused");
		}

		assertThat(failed.getBody())
			.as("the delete path reached the database and failed there")
			.contains("<div class=\"flash flash-error\">" + arabic("admin-messages", "error_db") + "</div>");
		assertThat(companyExists(companyId)).as("a delete that cannot be recorded does not happen").isTrue();
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = ?", employeeId)).isOne();
	}

	@Test
	void aLockFailureInAToleratedDeleteRollsBackEverything() {
		// Deleting branches is a failure legacy tolerates. A deadlock there has already
		// rolled the transaction back on the server; swallowed, the rest of the cascade
		// would commit the delete, through the foreign keys, without its audit row.
		// SQLSTATE 40001 with error 1213 is what a deadlock reports.
		String cookie = signIn();
		long companyId = createCompany();
		long employeeId = seedBranchAndEmployee(companyId);
		String name = nameOf(companyId);
		String path = "/admin/companies/" + companyId + "/delete";
		Page page = get(path, cookie);
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		jdbc.execute("CREATE TRIGGER flow_branch_delete_deadlock BEFORE DELETE ON branches FOR EACH ROW"
				+ " SIGNAL SQLSTATE '40001' SET MESSAGE_TEXT = 'forced deadlock', MYSQL_ERRNO = 1213");
		ResponseEntity<String> failed;
		try {
			failed = post(path, cookie, page.csrf(), "confirmation", name);
		} finally {
			jdbc.execute("DROP TRIGGER IF EXISTS flow_branch_delete_deadlock");
		}

		assertThat(failed.getStatusCode()).as("the delete failed rather than reporting success")
			.isEqualTo(HttpStatus.OK);
		assertThat(failed.getBody())
			.contains("<div class=\"flash flash-error\">" + arabic("admin-messages", "error_db") + "</div>");
		assertThat(companyExists(companyId)).isTrue();
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = ?", employeeId)).isOne();
		assertThat(deletionAudits(companyId)).isEmpty();
	}

	@Test
	void aNameIsConfirmedAsThePageShowsIt() {
		// A browser collapses runs of whitespace and hides zero-width and bidi marks,
		// so the operator can only see, and type, the name without them.
		String cookie = signIn();
		String shown = "Flow " + System.nanoTime() + " Trading";
		String stored = shown.replace(" Trading", "  Trading\u200F");
		long companyId = new JdbcTemplate(this.legacyDataSource).queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status)"
						+ " VALUES (?, ?, 'unused-hash', 'active') RETURNING id",
				Long.class, stored, "+92" + (System.nanoTime() % 100_000_000_000L));
		String path = "/admin/companies/" + companyId + "/delete";

		Page page = get(path, cookie);
		assertThat(page.response().getBody()).contains("<code dir=\"auto\">" + shown + "</code>");
		// Typed with the stored double space, as a copy from the database would be.
		assertThat(post(path, cookie, page.csrf(), "confirmation", shown.replace(" Trading", "  Trading"))
				.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(companyExists(companyId)).isFalse();
	}

	@Test
	void theAdministratorsCascadeRollsBackWithTheCallersTransaction() {
		long companyId = createCompany();
		long employeeId = seedBranchAndEmployee(companyId);

		new TransactionTemplate(this.legacyTransactionManager).executeWithoutResult(status -> {
			this.companyDelete.cascadeDeleteInCurrentTransaction(companyId);
			status.setRollbackOnly();
		});

		assertThat(companyExists(companyId)).as("the caller's rollback undoes the cascade").isTrue();
		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = ?", employeeId)).isOne();
		assertThatThrownBy(() -> this.companyDelete.cascadeDeleteInCurrentTransaction(companyId))
			.as("with no transaction to join it refuses, rather than committing statement by statement")
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(companyExists(companyId)).isTrue();
	}

	/**
	 * companies/detail.php (D-264): the quick links, the header card, six counts and three tables,
	 * each read from this company's rows alone, in legacy's order. A second company with rows of
	 * every kind shows none of them.
	 */
	@Test
	void theCompanyDetailPageShowsLegacysCardCountsAndTablesForThatCompanyAlone() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		String cookie = signIn();
		long nano = System.nanoTime();
		String name = "Flow Detail " + nano;
		String phone = "+93" + (nano % 100_000_000_000L);
		long companyId = jdbc.queryForObject("INSERT INTO companies (company_name, phone, password_hash, email,"
				+ " otp_verified, logo_url, commercial_reg_url, status, created_at) VALUES (?, ?, 'unused-hash', ?,"
				+ " 1, '/uploads/logos/flow.png', '/uploads/docs/flow.pdf', 'active', '2026-01-02 10:11:12')"
				+ " RETURNING id", Long.class, name, phone, "flow" + nano + "@example.test");
		long zeta = seedBranch(companyId, "Zeta " + nano, true);
		long alpha = seedBranch(companyId, "Alpha " + nano, true);
		seedBranch(companyId, "Mid " + nano, false);
		String ayaPhone = uniquePhone();
		String basemPhone = uniquePhone();
		String carlPhone = uniquePhone();
		String dinaPhone = uniquePhone();
		long aya = seedEmployee(companyId, zeta, "Aya", "Alpha", "A-1", "manager", true, "2024-02-03", ayaPhone);
		long basem = seedEmployee(companyId, zeta, "Basem", "Beta", "  ", "hr", true, null, basemPhone);
		long carl = seedEmployee(companyId, alpha, "Carl", "Gamma", "C-3", "company_admin", false, "2023-01-01", carlPhone);
		long dina = seedEmployee(companyId, alpha, "Dina", "Delta", "D-4", "employee", true, "2025-05-06", dinaPhone);
		checkIn(aya, 0, "09:00:00");
		checkIn(aya, 0, "13:00:00");
		checkIn(dina, 0, "09:30:00");
		checkIn(basem, 1, "09:00:00");
		long leave = jdbc.queryForObject("INSERT INTO request_types (company_id, name) VALUES (?, 'Flow leave')"
				+ " RETURNING id", Long.class, companyId);
		request(aya, leave, "pending");
		request(basem, leave, "approved");
		advance(aya, "pending");
		advance(dina, "pending");
		advance(carl, "rejected");

		long other = createCompany();
		long otherBranch = seedBranch(other, "Other " + nano, true);
		String omarPhone = uniquePhone();
		long omar = seedEmployee(other, otherBranch, "Omar", "Other" + nano, "O-1", "hr", true, "2022-02-02", omarPhone);
		checkIn(omar, 0, "09:00:00");
		long otherLeave = jdbc.queryForObject("INSERT INTO request_types (company_id, name) VALUES (?, 'Other leave')"
				+ " RETURNING id", Long.class, other);
		request(omar, otherLeave, "pending");
		advance(omar, "pending");

		ResponseEntity<String> page = get("/admin/companies/" + companyId, cookie).response();
		assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
		String html = page.getBody();

		assertThat(html).as("detail.php:29: the title names the company")
				.contains("<h1 class=\"page-title\">" + arabic("admin-messages", "company") + " — " + name + "</h1>");
		assertThat(html).as("detail.php:33-38: back, then this company's branches, employees and payroll")
				.containsSubsequence("<div class=\"company-detail-bar\">",
						"<a href=\"/admin/companies\" class=\"btn btn-outline btn-sm\">← " + arabic("admin-messages", "back") + "</a>",
						"<a href=\"/admin/branches?company_id=" + companyId + "\" class=\"btn btn-blue btn-sm\">"
								+ arabic("admin-messages", "nav_branches") + "</a>",
						"<a href=\"/admin/employees?company_id=" + companyId + "\" class=\"btn btn-green btn-sm\">"
								+ arabic("admin-messages", "employees") + "</a>",
						"<a href=\"/admin/payroll?company_id=" + companyId + "\" class=\"btn btn-yellow btn-sm\">"
								+ arabic("admin-messages", "nav_payroll") + "</a>");

		assertThat(html).as("detail.php:40-52: the logo, the name and status, four fields and the registration")
				.containsPattern("<img src=\"/uploads/logos/flow\\.png\" alt=\"\" class=\"company-detail-logo\"\\s+"
						+ "data-fallback-initials=\"F D\"\\s+data-fallback-class=\"company-detail-avatar\">")
				.containsPattern("<div class=\"company-detail-name\">" + Pattern.quote(name)
						+ "\\s*<span class=\"badge badge-green\">" + arabic("admin-messages", "status_active") + "</span>\\s*</div>")
				.contains("<span>&#9632; " + arabic("admin-messages", "company_phone") + ": <strong>" + phone + "</strong></span>")
				.contains("<span>&#9632; " + arabic("admin-messages", "company_email") + ": <strong>flow" + nano
						+ "@example.test</strong></span>")
				.containsPattern("<span>&#9632; OTP: <strong>\\s*<span class=\"badge badge-green\">"
						+ arabic("admin-messages", "yes") + "</span>\\s*</strong></span>")
				.contains("<span>&#9632; " + arabic("admin-messages", "reg_date") + ": <strong>2026-01-02</strong></span>")
				.containsPattern("<a href=\"/uploads/docs/flow\\.pdf\" target=\"_blank\" rel=\"noopener\"\\s+"
						+ "class=\"btn btn-outline btn-sm company-detail-reg\">" + arabic("admin-messages", "commercial_reg") + "</a>");

		assertThat(html).as("detail.php:54-61: six counts, in legacy's order and colours")
				.containsPattern("<div class=\"stats-grid company-detail-stats\">\\s*"
						+ stat("green", "3", arabic("admin-messages", "total_employees"))
						+ stat("blue", "4", arabic("admin-messages", "employees"))
						+ stat("blue", "3", arabic("admin-messages", "branches"))
						+ stat("green", "2", arabic("admin-messages", "checked_in_today"))
						+ stat("yellow", "1", arabic("admin-messages", "pending_requests"))
						+ stat("yellow", "2", arabic("admin-messages", "pending_advances")) + "</div>");

		assertThat(html).containsPattern("<h2>" + arabic("admin-messages", "branches") + " \\(3\\)</h2>\\s*<a href=\"/admin/branches\\?company_id="
				+ companyId + "\" class=\"btn btn-outline btn-sm\">" + arabic("admin-messages", "edit") + "</a>");
		assertThat(bodyRows(html, "<h2>" + arabic("admin-messages", "branches") + " (3)</h2>"))
				.as("every branch, active or not, by name, each with its active employees")
				.containsExactly("<td>Alpha " + nano + "</td><td>1</td>", "<td>Mid " + nano + "</td><td>0</td>",
						"<td>Zeta " + nano + "</td><td>2</td>");

		assertThat(bodyRows(html, "<h2>HR / " + arabic("admin-messages", "manager") + "</h2>"))
				.as("the company's admins, HR and managers, in the role enum's order")
				.containsExactly(
						"<td>" + carlPhone + "</td><td><span class=\"badge badge-blue\">" + arabic("admin-messages", "role_admin") + "</span></td>",
						"<td>" + basemPhone + "</td><td><span class=\"badge badge-green\">" + arabic("admin-messages", "role_hr") + "</span></td>",
						"<td>" + ayaPhone + "</td><td><span class=\"badge badge-yellow\">" + arabic("admin-messages", "role_manager") + "</span></td>");

		String employees = "<h2>" + arabic("admin-messages", "employees") + " (4)</h2>";
		assertThat(html).containsPattern(Pattern.quote(employees) + "\\s*<a href=\"/admin/employees\\?company_id="
				+ companyId + "\" class=\"btn btn-outline btn-sm\">" + arabic("admin-messages", "all") + "</a>");
		assertThat(bodyRows(html, employees))
				.as("active first, then by name: the code or the id, the name, phone, branch, hire date or a dash")
				.containsExactly(
						employeeRow(1, "A-1", "Aya Alpha", ayaPhone, "Zeta " + nano, "2024-02-03", true, aya),
						employeeRow(2, String.valueOf(basem), "Basem Beta", basemPhone, "Zeta " + nano, "—", true, basem),
						employeeRow(3, "D-4", "Dina Delta", dinaPhone, "Alpha " + nano, "2025-05-06", true, dina),
						employeeRow(4, "C-3", "Carl Gamma", carlPhone, "Alpha " + nano, "2023-01-01", false, carl));

		assertThat(html).as("the other company's rows").doesNotContain("Other " + nano, omarPhone,
				"employee_detail?id=" + omar, "company_id=" + other);

		assertThat(get("/admin/companies/" + companyId + "?lang=en", cookie).response().getBody())
				.as("the title in English").contains("<h1 class=\"page-title\">Company — " + name + "</h1>");
	}

	/** detail.php:79-82: fifteen rows, then "and N more" with a link to them all. */
	@Test
	void theEmployeesTableListsFifteenAndCountsTheRestFromTheSixteenth() {
		String cookie = signIn();
		long companyId = createCompany();
		long branch = seedBranch(companyId, "Flow many", true);
		List<Long> listed = new java.util.ArrayList<>();
		for (int at = 1; at <= 15; at++) {
			listed.add(seedEmployee(companyId, branch, String.format("E%02d", at), "Flow", null, "employee", true,
					null, uniquePhone()));
		}
		String employees = "<h2>" + arabic("admin-messages", "employees") + " (%d)</h2>";

		String fifteen = get("/admin/companies/" + companyId, cookie).response().getBody();
		assertThat(bodyRows(fifteen, employees.formatted(15))).hasSize(15);
		assertThat(fifteen).as("no more to count").doesNotContain("company-detail-more");

		// First by name, but inactive, so it sorts after the fifteen and is the one left out.
		long sixteenth = seedEmployee(companyId, branch, "A00", "Flow", null, "employee", false, null, uniquePhone());
		String sixteen = get("/admin/companies/" + companyId, cookie).response().getBody();
		List<String> rows = bodyRows(sixteen, employees.formatted(16));
		assertThat(rows).hasSize(16);
		for (int at = 0; at < 15; at++) {
			assertThat(rows.get(at)).startsWith("<td>" + (at + 1) + "</td>")
					.contains("employee_detail?id=" + listed.get(at) + "\"");
		}
		assertThat(rows.get(15)).isEqualTo("<td colspan=\"8\" class=\"company-detail-more\">و 1 "
				+ arabic("admin-messages", "employee") + " — <a href=\"/admin/employees?company_id=" + companyId + "\">"
				+ arabic("admin-messages", "all") + "</a></td>");
		assertThat(sixteen).doesNotContain("employee_detail?id=" + sixteenth + "\"");
	}

	/**
	 * A signup with no name, no email and nothing under it: legacy's blanks, zero counts and three
	 * empty tables. The port's approve and reject stay.
	 */
	@Test
	void aCompanyWithNothingUnderItShowsLegacysBlanksAndEmptyTables() {
		String cookie = signIn();
		long companyId = new JdbcTemplate(this.legacyDataSource).queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status)"
						+ " VALUES (NULL, ?, 'unused-hash', 'pending') RETURNING id", Long.class,
				"+94" + (System.nanoTime() % 100_000_000_000L));

		String html = get("/admin/companies/" + companyId, cookie).response().getBody();

		assertThat(html).contains("<h1 class=\"page-title\">" + arabic("admin-messages", "company") + " — </h1>")
				.contains("<span class=\"company-detail-avatar\" aria-hidden=\"true\">C</span>")
				.containsPattern("<div class=\"company-detail-name\">\\s*<span class=\"badge badge-yellow\">"
						+ arabic("admin-messages", "status_pending") + "</span>\\s*</div>")
				.contains(": <strong>—</strong></span>")
				.containsPattern("<span>&#9632; OTP: <strong>\\s*<span class=\"badge badge-gray\">"
						+ arabic("admin-messages", "no") + "</span>\\s*</strong></span>")
				.doesNotContain("company-detail-logo", "company-detail-reg", "company-detail-more")
				.containsPattern("<div class=\"stats-grid company-detail-stats\">\\s*"
						+ stat("green", "0", arabic("admin-messages", "total_employees"))
						+ stat("blue", "0", arabic("admin-messages", "employees"))
						+ stat("blue", "0", arabic("admin-messages", "branches"))
						+ stat("green", "0", arabic("admin-messages", "checked_in_today"))
						+ stat("yellow", "0", arabic("admin-messages", "pending_requests"))
						+ stat("yellow", "0", arabic("admin-messages", "pending_advances")) + "</div>");
		assertThat(bodyRows(html, "<h2>" + arabic("admin-messages", "branches") + " (0)</h2>")).isEmpty();
		assertThat(bodyRows(html, "<h2>HR / " + arabic("admin-messages", "manager") + "</h2>")).isEmpty();
		assertThat(bodyRows(html, "<h2>" + arabic("admin-messages", "employees") + " (0)</h2>")).isEmpty();
		assertThat(html).as("the port's lifecycle actions")
				.contains("value=\"COMPANY_APPROVE\"", "data-dialog=\"company-reject\"");
	}

	/**
	 * D-262's rule for a stored file URL, on the detail page's button and the list's link: a script
	 * or data URL, however a browser would still read it as one, is not linked; a web address is.
	 */
	@Test
	void aCommercialRegistrationIsLinkedOnlyWhenItIsAWebAddress() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		String cookie = signIn();
		List<String> refused = List.of("javascript:alert(1)", " JavaScript:alert(1)", "\u0001javascript:alert(1)",
				"java\tscript:alert(1)", "java\nscript:alert(1)", "data:text/html,<script>alert(1)</script>");
		java.util.Map<Long, String> scripts = new java.util.LinkedHashMap<>();
		for (String url : refused) {
			long companyId = createCompany();
			jdbc.update("UPDATE companies SET commercial_reg_url = ? WHERE id = ?", url, companyId);
			scripts.put(companyId, url);
		}
		long web = createCompany();
		jdbc.update("UPDATE companies SET commercial_reg_url = 'https://files.example.test/reg.pdf' WHERE id = ?", web);

		String list = get("/admin/companies", cookie).response().getBody();
		scripts.forEach((companyId, url) -> {
			assertThat(get("/admin/companies/" + companyId, cookie).response().getBody()).as("detail, stored %s", url)
					.doesNotContain("company-detail-reg", "alert(1)");
			assertThat(listRow(list, companyId)).as("list, stored %s", url).doesNotContain("tbl-sub", "alert(1)");
		});
		assertThat(get("/admin/companies/" + web, cookie).response().getBody()).containsPattern(
				"<a href=\"https://files\\.example\\.test/reg\\.pdf\" target=\"_blank\" rel=\"noopener\"\\s+"
						+ "class=\"btn btn-outline btn-sm company-detail-reg\">");
		assertThat(listRow(list, web)).containsPattern(
				"<a href=\"https://files\\.example\\.test/reg\\.pdf\" target=\"_blank\" rel=\"noopener\"\\s+class=\"tbl-sub\">");
	}

	@Test
	void aWrongPasswordIsRefusedAndOpensNothing() {
		Page loginForm = get("/admin/login", null);
		ResponseEntity<String> refused = post("/admin/login", loginForm.cookie(), loginForm.csrf(),
				"password", "not the password");

		// PHP re-renders the form with error_auth; so does this, on the same URL.
		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(refused.getBody()).contains("login-alert--error");
		assertThat(get("/admin", loginForm.cookie()).response().getStatusCode())
			.as("the pre-login session opens no page")
			.isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void deactivationEndsALiveSessionOnTheNextRequest() {
		String cookie = signIn();
		assertThat(get("/admin", cookie).response().getStatusCode()).isEqualTo(HttpStatus.OK);

		new JdbcTemplate(this.legacyDataSource)
			.update("UPDATE platform_admins SET active = false WHERE phone = 'admin'");

		assertThat(get("/admin", cookie).response().getStatusCode())
			.as("D-145: revocation must take effect on the next request, not at expiry")
			.isEqualTo(HttpStatus.FOUND);
		new JdbcTemplate(this.legacyDataSource)
			.update("UPDATE platform_admins SET active = true WHERE phone = 'admin'");
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	private record Csrf(String name, String value) {
	}

	private String signIn() {
		Page loginForm = get("/admin/login", null);
		ResponseEntity<String> signedIn = post("/admin/login", loginForm.cookie(), loginForm.csrf(),
				"password", PASSWORD);
		assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		String cookie = cookieOf(signedIn);
		assertThat(cookie).as("the session id rotates on login").isNotEqualTo(loginForm.cookie());
		return cookie;
	}

	private Page get(String path, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		if (cookie != null) {
			headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + cookie);
		}
		ResponseEntity<String> response = this.restTemplate.exchange(
				path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
		String resolved = cookie != null ? cookie : tryCookieOf(response);
		return new Page(response, resolved, response.getBody() == null ? null : csrfOf(response));
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
		assertThat(matcher.find()).as("expected a CSRF token in the response").isTrue();
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

	private static String sessionIdOf(String cookie) {
		return new String(java.util.Base64.getDecoder().decode(cookie),
				java.nio.charset.StandardCharsets.UTF_8);
	}

	private String statusOf(long companyId) {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT status FROM companies WHERE id = ?", String.class, companyId);
	}

	private long adminId() {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
	}

	private long createCompany() {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status) VALUES (?, ?, 'unused-hash', 'active') RETURNING id",
				Long.class, "Flow " + System.nanoTime(), "+90" + System.nanoTime());
	}

	private String nameOf(long companyId) {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"SELECT company_name FROM companies WHERE id = ?", String.class, companyId);
	}

	private boolean companyExists(long companyId) {
		return count("SELECT COUNT(*) FROM companies WHERE id = ?", companyId) == 1;
	}

	private int count(String sql, Object... args) {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(sql, Integer.class, args);
	}

	/** One branch with one employee in it, so the cascade has something under the company to remove. */
	private long seedBranchAndEmployee(long companyId) {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long branchId = jdbc.queryForObject(
				"INSERT INTO branches (company_id, name) VALUES (?, 'Flow branch') RETURNING id",
				Long.class, companyId);
		return jdbc.queryForObject("INSERT INTO employees (company_id, branch_id, first_name, last_name,"
				+ " phone, password_hash, role, join_request_status, is_active, created_at)"
				+ " VALUES (?, ?, 'Flow', 'Employee', ?, 'unused-hash', 'employee', 'accepted', 1, NOW())"
				+ " RETURNING id", Long.class, companyId, branchId, "+80" + System.nanoTime());
	}

	/** One attendance device in the company's first branch: a table the client preview does not count. */
	private void seedDevice(long companyId) {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long branchId = jdbc.queryForObject("SELECT MIN(id) FROM branches WHERE company_id = ?", Long.class, companyId);
		jdbc.update("INSERT INTO attendance_devices (company_id, branch_id, vendor, serial_number, name,"
				+ " device_time_zone, is_active, created_at, updated_at)"
				+ " VALUES (?, ?, 'zkteco', ?, 'Flow device', 'Africa/Cairo', 1, NOW(), NOW())",
				companyId, branchId, "FLOW-" + System.nanoTime());
	}

	private List<java.util.Map<String, Object>> deletionAudits(long companyId) {
		return new JdbcTemplate(this.legacyDataSource).queryForList(
				"SELECT platform_admin_id, target_type, target_id, detail FROM platform_admin_audit_events"
						+ " WHERE event_type = 'COMPANY_DELETED' AND target_id = ?", String.valueOf(companyId));
	}

	/** A value from one of this surface's Arabic catalogues, the language the page renders by default. */
	private static String arabic(String basename, String key) {
		java.util.Properties catalogue = new java.util.Properties();
		try (java.io.InputStream in = PlatformAdminFullFlowTest.class
				.getResourceAsStream("/i18n/" + basename + "_ar.properties")) {
			catalogue.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return catalogue.getProperty(key);
	}

	/** One stat card's number and label, as company-delete.jte draws them. */
	private static Pattern statCard(String number, String label) {
		return Pattern.compile("<div class=\"stat-num\">" + Pattern.quote(number) + "</div>\\s*"
				+ "<div class=\"stat-label\">" + Pattern.quote(label) + "</div>");
	}

	/** One whole stat card, colour included, and the space after it: companies/detail.php:55-60. */
	private static String stat(String colour, String number, String label) {
		return "<div class=\"stat-card " + colour + "\"><div class=\"stat-num\">" + number + "</div>\\s*"
				+ "<div class=\"stat-label\">" + Pattern.quote(label) + "</div></div>\\s*";
	}

	/** The rows of the table under a heading, each with the space between its tags removed. */
	private static List<String> bodyRows(String html, String heading) {
		int start = html.indexOf(heading);
		assertThat(start).as("the section headed %s", heading).isPositive();
		int body = html.indexOf("<tbody>", start);
		String rows = html.substring(body, html.indexOf("</tbody>", body));
		return Pattern.compile("(?s)<tr>(.*?)</tr>").matcher(rows).results()
				.map(row -> row.group(1).replaceAll(">\\s+<", "><").strip()).toList();
	}

	/** One row of the detail page's employees table, as companies/detail.php:80 draws it. */
	private static String employeeRow(int number, String code, String name, String phone, String branch,
			String hired, boolean active, long employeeId) {
		return "<td>" + number + "</td><td class=\"text-muted\">" + code + "</td><td class=\"bold\">" + name
				+ "</td><td>" + phone + "</td><td>" + branch + "</td><td>" + hired + "</td><td><span class=\"badge "
				+ (active ? "badge-green\">" + arabic("admin-messages", "yes") : "badge-gray\">" + arabic("admin-messages", "no"))
				+ "</span></td><td><a href=\"/admin/employee_detail?id=" + employeeId + "\" class=\"btn btn-blue btn-sm\">"
				+ arabic("admin-messages", "details") + "</a></td>";
	}

	/** The companies list's row for one company, up to its actions menu. */
	private static String listRow(String html, long companyId) {
		int menu = html.indexOf("id=\"row-actions-menu-" + companyId + "\"");
		assertThat(menu).as("the row for company %s", companyId).isPositive();
		return html.substring(html.lastIndexOf("<tr", menu), menu);
	}

	private long seedBranch(long companyId, String name, boolean active) {
		return new JdbcTemplate(this.legacyDataSource).queryForObject(
				"INSERT INTO branches (company_id, name, is_active) VALUES (?, ?, ?) RETURNING id",
				Long.class, companyId, name, active ? 1 : 0);
	}

	private long seedEmployee(long companyId, long branchId, String first, String last, String code,
			String role, boolean active, String hired, String phone) {
		return new JdbcTemplate(this.legacyDataSource).queryForObject("INSERT INTO employees (company_id, branch_id,"
				+ " first_name, last_name, employee_code, phone, password_hash, role, join_request_status, is_active,"
				+ " hire_date, created_at) VALUES (?, ?, ?, ?, ?, ?, 'unused-hash', ?, 'accepted', ?, ?, NOW())"
				+ " RETURNING id", Long.class, companyId, branchId, first, last, code, phone, role, active ? 1 : 0, hired);
	}

	private static String uniquePhone() {
		return "+81" + (System.nanoTime() % 100_000_000_000L);
	}

	/** A check-in some days before today, in the zone CURDATE() answers in. */
	private void checkIn(long employeeId, int daysAgo, String time) {
		new JdbcTemplate(this.legacyDataSource).update("INSERT INTO attendance (employee_id, check_in, method)"
				+ " VALUES (?, CONCAT(CURDATE() - INTERVAL ? DAY, ' ', ?), 'app')", employeeId, daysAgo, time);
	}

	private void request(long employeeId, long typeId, String status) {
		new JdbcTemplate(this.legacyDataSource).update("INSERT INTO requests (employee_id, request_type_id,"
				+ " from_date, to_date, status) VALUES (?, ?, '2026-03-01', '2026-03-01', ?)", employeeId, typeId, status);
	}

	private void advance(long employeeId, String status) {
		new JdbcTemplate(this.legacyDataSource).update("INSERT INTO advances (employee_id, amount, remaining, status,"
				+ " request_date) VALUES (?, 100, 100, ?, '2026-03-02')", employeeId, status);
	}




}
