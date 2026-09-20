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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/requests} over real HTTP against a real MariaDB.
 *
 * <p>Approving is not a status change: it deducts leave and can write one
 * attendance row per day of the request's span. Those side effects are most of
 * what is worth testing here, together with the two things that decide whether
 * they happen at all -- the request type's flags and the employee's balance.
 *
 * <p>And <b>R-046</b>: legacy scopes {@code approve} properly through
 * {@code dashboard_request_fetch_for_approval()} and leaves {@code reject} and
 * {@code delete} writing by id alone. Both are guarded here.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminRequestsEndToEndTest {

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

	private long employeeA;

	private long employeeB;

	private long plainTypeA;

	private long deductingTypeA;

	private long typeB;


	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM attendance");
		this.jdbc.update("DELETE FROM requests");
		this.jdbc.update("DELETE FROM request_types");
		this.jdbc.update("DELETE FROM exception_types");
		this.jdbc.update("DELETE FROM leave_balance");
		this.jdbc.update("DELETE FROM employees WHERE id > 990000");
		this.jdbc.update("DELETE FROM platform_admin_audit_events");

		// One administrator, one password (ADR-0018): the bootstrap provisioned
		// the row from the configured password when the context started.
		long adminId = this.jdbc.queryForObject(
				"SELECT id FROM platform_admins WHERE phone = 'admin'", Long.class);
		Page login = page("/admin/login", null);
		this.cookie = cookieOf(post("/admin/login", login.cookie(), login.csrf(), "password", PASSWORD));

		this.companyA = createCompany("Alpha Co");
		this.companyB = createCompany("Beta Co");
		this.employeeA = createEmployee(this.companyA, "A100", "Aya", "Alpha");
		this.employeeB = createEmployee(this.companyB, "B100", "Basma", "Beta");
		this.plainTypeA = createType(this.companyA, "Unpaid", false, false);
		this.deductingTypeA = createType(this.companyA, "Annual", true, false);
		this.typeB = createType(this.companyB, "Annual", true, false);

	}

	/**
	 * page.php:65-78: twelve columns, in this order, and no more. The port carried a thirteenth,
	 * the request's registration date, which legacy builds no header and prints no cell for
	 * (:119-132) -- so the table read one column wider than the dashboard it replaces, and the
	 * empty row spanned one column too many.
	 *
	 * <p>The company column is the port's own, for the administrator's unfiltered view. It is a
	 * thirteenth column, but not the last one: it renders fourth, straight after the employee's
	 * name (`requests.jte:124`), and only when no company is chosen.
	 */
	@Test
	void theTableCarriesLegacysTwelveColumnsAndNotTheRegistrationDate() {
		long id = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");

		String filtered = body("/admin/requests?company_id=" + this.companyA);
		assertThat(headers(filtered)).containsExactly(
				"#", arabic("emp_code"), arabic("employee_name"), arabic("request_type"),
				arabic("from_date"), arabic("to_date"), arabic("from_time"), arabic("to_time"),
				arabic("request_notes"), arabic("decision_reply"), arabic("status"),
				arabic("actions"));
		assertThat(filtered).as("legacy never displays a request's registration date here")
				.doesNotContain(arabic("reg_date"));
		assertThat(cellCount(row(filtered, id))).as("one cell per header").isEqualTo(12);

		// The empty row spans the table, so it counts the same columns.
		assertThat(body("/admin/requests?company_id=" + this.companyA + "&search=nobody"))
				.contains("<td colspan=\"12\" class=\"data-table-empty\">");
		assertThat(headers(body("/admin/requests?company_id=")))
				.as("unfiltered, the port's own company column joins them fourth")
				.containsExactly(
						"#", arabic("emp_code"), arabic("employee_name"), arabic("company"),
						arabic("request_type"), arabic("from_date"), arabic("to_date"),
						arabic("from_time"), arabic("to_time"), arabic("request_notes"),
						arabic("decision_reply"), arabic("status"), arabic("actions"));
	}

	/**
	 * page.php:101-107: "all" leads the status options, before the three states. The port put it
	 * last, so the one option that widens the queue was the one below the fold of a short select.
	 * The default stays pending either way.
	 */
	@Test
	void theStatusFilterOffersAllFirstAndStillDefaultsToPending() {
		assertThat(statusOptions(body("/admin/requests"))).containsExactly(
				"all", "pending selected", "approved", "rejected");
		assertThat(statusOptions(body("/admin/requests?status=all"))).containsExactly(
				"all selected", "pending", "approved", "rejected");
		assertThat(statusOptions(body("/admin/requests?status=rejected"))).containsExactly(
				"all", "pending", "approved", "rejected selected");
	}

	/** page.php:89: the box says what it searches, as every other HR list's does. */
	@Test
	void theSearchBoxCarriesLegacysPlaceholder() {
		assertThat(body("/admin/requests"))
				.contains("<input type=\"search\" id=\"rq_search\" name=\"search\"")
				.contains("placeholder=\"" + arabic("requests_search_placeholder") + "\"");
	}

	/**
	 * page.php:152-156: the decision window's box is labelled with the reply's own key and marked
	 * optional, and it carries legacy's placeholder. The port labelled it {@code reply} -- a
	 * different entry, which reads "رد" rather than "الرد" -- and left the box unmarked, so
	 * nothing on either window said an empty reply was accepted.
	 */
	@Test
	void bothDecisionWindowsLabelTheirReplyAsLegacyDoes() {
		String html = body("/admin/requests");
		String label = arabic("decision_reply") + " (" + arabic("optional") + ")";
		for (String field : List.of("req_approve_comment", "req_reject_comment")) {
			assertThat(html).contains("<label for=\"" + field + "\">" + label + "</label>");
			assertThat(dialogTextarea(html, field))
					.contains("placeholder=\"" + arabic("decision_reply") + "...\"")
					.doesNotContain("required");
		}
	}

	/** The header cells of the list, in order, with the actions column's own label. */
	private static List<String> headers(String html) {
		Matcher head = Pattern.compile("(?s)<thead>.*?</thead>").matcher(html);
		assertThat(head.find()).as("the list's header row").isTrue();
		Matcher cells = Pattern.compile("(?s)<th\\b[^>]*>(.*?)</th>").matcher(head.group());
		List<String> labels = new java.util.ArrayList<>();
		while (cells.find()) {
			labels.add(cells.group(1).replaceAll("<[^>]*>", "").trim());
		}
		return labels;
	}

	private static int cellCount(String rowHtml) {
		return (int) Pattern.compile("<td\\b").matcher(rowHtml).results().count();
	}

	/** Each status option's value, in order, with " selected" on the chosen one. */
	private static List<String> statusOptions(String html) {
		Matcher select = Pattern.compile("(?s)<select id=\"rq_status\".*?</select>").matcher(html);
		assertThat(select.find()).as("the status filter").isTrue();
		Matcher options = Pattern.compile("<option value=\"([a-z]+)\"( selected)?>").matcher(select.group());
		List<String> values = new java.util.ArrayList<>();
		while (options.find()) {
			values.add(options.group(1) + (options.group(2) == null ? "" : " selected"));
		}
		return values;
	}

	private static String dialogTextarea(String html, String fieldId) {
		Matcher textarea = Pattern.compile("<textarea[^>]*id=\"" + fieldId + "\"[^>]*>").matcher(html);
		assertThat(textarea.find()).as("the %s box", fieldId).isTrue();
		return textarea.group();
	}

	/** One label as the dashboard's default language renders it. */
	private static String arabic(String key) {
		java.util.Properties catalogue = new java.util.Properties();
		try (java.io.InputStream in = AdminRequestsEndToEndTest.class
				.getResourceAsStream("/i18n/admin-messages_ar.properties")) {
			catalogue.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
		} catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		String value = catalogue.getProperty(key);
		assertThat(value).as("the catalogue's %s", key).isNotNull();
		return value;
	}

	/**
	 * hr_request_filter_form_attrs() and hr_render_request_type_filter_field()
	 * (hr_list_helper.php:970-999): the toolbar carries every company's active request types for
	 * request-filter-cascade.js, and the type select lists one company's types. With no company
	 * chosen it asks for one, because a type is one company's row.
	 */
	@Test
	void theRequestTypeFilterListsOneCompanysTypesAndAsksForACompanyWithoutOne() {
		this.jdbc.update("UPDATE request_types SET is_active = 0 WHERE id = ?",
				createType(this.companyB, "Retired", false, false));

		String unfiltered = body("/admin/requests");
		Matcher toolbar = Pattern.compile("<form method=\"GET\" class=\"toolbar-form toolbar-form--labeled\"([^>]*)>")
				.matcher(unfiltered);
		assertThat(toolbar.find()).as("the toolbar's filter form").isTrue();
		assertThat(toolbar.group(1))
				.contains(" data-request-filters=\"1\"")
				.contains(" data-request-types-by-company=\"" + ("{\"" + this.companyA + "\":["
						+ "{\"id\":" + this.deductingTypeA + ",\"name\":\"Annual\"},"
						+ "{\"id\":" + this.plainTypeA + ",\"name\":\"Unpaid\"}],"
						+ "\"" + this.companyB + "\":[{\"id\":" + this.typeB + ",\"name\":\"Annual\"}]}")
						.replace("\"", "&#34;") + "\"")
				.contains(" data-selected-request-type=\"0\"");
		assertThat(typeSelect(unfiltered))
				.as("no company: the select is disabled and asks for one")
				.startsWith("<select id=\"rq_type\" name=\"type_id\" data-filter-request-type disabled>")
				.contains("<option value=\"0\" disabled>اختر الشركة أولاً</option>")
				.doesNotContain("Annual").doesNotContain("Unpaid");
		assertThat(unfiltered).contains("<script src=\"/admin/_assets/request-filter-cascade.js\"></script>");

		String filtered = body("/admin/requests?company_id=" + this.companyA + "&type_id=" + this.plainTypeA);
		assertThat(typeSelect(filtered))
				.startsWith("<select id=\"rq_type\" name=\"type_id\" data-filter-request-type>")
				.contains("<option value=\"" + this.deductingTypeA + "\">Annual</option>")
				.contains("<option value=\"" + this.plainTypeA + "\" selected>Unpaid</option>")
				.doesNotContain("value=\"" + this.typeB + "\"")
				.doesNotContain("disabled");
		assertThat(filtered).contains(" data-selected-request-type=\"" + this.plainTypeA + "\"");
	}

	@Test
	void theTypeFilterIsPhpsIntCast() {
		// (int) ($_GET['type_id'] ?? 0): "<id>e0" is the id.
		String filtered = body("/admin/requests?company_id=" + this.companyA + "&type_id=" + this.plainTypeA + "e0");
		assertThat(filtered).contains(" data-selected-request-type=\"" + this.plainTypeA + "\"");
	}

	private static String typeSelect(String html) {
		Matcher select = Pattern.compile("<select id=\"rq_type\".*?</select>", Pattern.DOTALL).matcher(html);
		assertThat(select.find()).as("the request type filter").isTrue();
		return select.group();
	}

	@Test
	void approvingAPlainRequestChangesOnlyItsStatus() {
		long id = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id), "comment", "fine");

		assertThat(statusOf(id)).isEqualTo("approved");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM requests WHERE id = ?", String.class, id)).isEqualTo("fine");
		assertThat(this.jdbc.queryForObject(
				"SELECT decided_at FROM requests WHERE id = ?", String.class, id)).isNotNull();
		// The type neither deducts nor writes attendance, so nothing else moved.
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM leave_balance", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
	}

	@Test
	void approvingADeductingRequestSpendsTheBalanceInclusively() {
		seedBalance(this.employeeA, 2026, "21", "0");
		// 2nd to 4th is three days, both ends counted.
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA)
				.compareTo(new java.math.BigDecimal("3"))).isZero();
	}

	@Test
	void aDeductionLandsInTheYearTheLeaveStartsIn() {
		// Not this year, and not the year it ends in either.
		seedBalance(this.employeeA, 2025, "21", "0");
		seedBalance(this.employeeA, 2026, "21", "0");
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2025-12-30", "2026-01-02");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2025",
				java.math.BigDecimal.class, this.employeeA)
				.compareTo(new java.math.BigDecimal("4"))).as("four days, in 2025").isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA).signum()).isZero();
	}

	@Test
	void anEmployeeWithNoBalanceForTheYearGetsOneCreated() {
        // dashboard_request_insufficient_leave_balance() returns false when the
        // year has no row -- a missing year was never granted, and refusing
        // would make the first request of a new year impossible to approve.
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-03");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(statusOf(id)).isEqualTo("approved");
		Map<String, Object> created = this.jdbc.queryForMap(
				"SELECT total_days, used_days FROM leave_balance WHERE employee_id = " + this.employeeA);
		assertThat(((java.math.BigDecimal) created.get("total_days"))
				.compareTo(new java.math.BigDecimal("15"))).as("the 15-day default").isZero();
		assertThat(((java.math.BigDecimal) created.get("used_days"))
				.compareTo(new java.math.BigDecimal("2"))).isZero();
	}

	@Test
	void anInsufficientBalanceRefusesWithNothingWritten() {
		seedBalance(this.employeeA, 2026, "5", "4");
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-06");

		assertThat(post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id))
				.getHeaders().getLocation()).asString().contains("error=insufficient_leave_balance");

		assertThat(statusOf(id)).as("checked before deciding, so not approved").isEqualTo("pending");
		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA)
				.compareTo(new java.math.BigDecimal("4"))).as("and nothing spent").isZero();
	}

	@Test
	void approvingAnExceptionTypeWritesOneAttendanceRowPerDay() {
		long exceptionType = createExceptionType(this.companyA, "Leave");
		long type = createType(this.companyA, "Sick", false, true);
		this.jdbc.update("UPDATE request_types SET exception_type_id = ? WHERE id = ?",
				exceptionType, type);
		long id = seedRequest(this.employeeA, type, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		List<Map<String, Object>> rows = this.jdbc.queryForList(
				"SELECT DATE(check_in) AS d, method, exception_type_id FROM attendance"
						+ " WHERE employee_id = ? ORDER BY check_in", this.employeeA);
		assertThat(rows).hasSize(3);
		assertThat(rows.get(0).get("method")).as("the literal 'app'").isEqualTo("app");
		assertThat(rows.get(0).get("exception_type_id").toString())
				.isEqualTo(String.valueOf(exceptionType));
	}

	@Test
	void aDayTheEmployeeAlreadyAttendedIsSkipped() {
		long exceptionType = createExceptionType(this.companyA, "Leave");
		long type = createType(this.companyA, "Sick", false, true);
		this.jdbc.update("UPDATE request_types SET exception_type_id = ? WHERE id = ?",
				exceptionType, type);
		this.jdbc.update("INSERT INTO attendance (employee_id, check_in, method)"
				+ " VALUES (?, '2026-03-03 09:00:00', 'excel')", this.employeeA);
		long id = seedRequest(this.employeeA, type, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		// Three days, one already attended, so two new rows -- and the existing
		// one is untouched rather than overwritten.
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM attendance WHERE employee_id = ?", Integer.class, this.employeeA))
				.isEqualTo(3);
		assertThat(this.jdbc.queryForObject(
				"SELECT method FROM attendance WHERE employee_id = ? AND DATE(check_in) = '2026-03-03'",
				String.class, this.employeeA)).isEqualTo("excel");
	}

	@Test
	void aCompanyWithNoExceptionTypeWritesNothingRatherThanFailing() {
		long type = createType(this.companyA, "Sick", false, true);
		long id = seedRequest(this.employeeA, type, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(statusOf(id)).as("the approval still succeeds").isEqualTo("approved");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
	}

	@Test
	void anAlreadyDecidedRequestCannotBeApprovedAgain() {
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-04");
		this.jdbc.update("UPDATE requests SET status = 'approved' WHERE id = ?", id);
		seedBalance(this.employeeA, 2026, "21", "0");

		assertThat(post("/admin/requests", this.cookie,
				page("/admin/requests?status=all", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id))
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA).signum())
				.as("a second approval would have deducted twice").isZero();
	}

	@Test
	void anAlreadyDecidedRequestCannotBeRejected() {
		// Legacy's reject updates by id whatever the status (requests/page.php:43-48).
		// A reject dialog left open while someone else approves would turn the
		// approval into a rejection, and keep the leave the approval deducted.
		long id = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		this.jdbc.update("UPDATE requests SET status = 'approved', reply = 'ok' WHERE id = ?", id);

		assertThat(post("/admin/requests", this.cookie,
				page("/admin/requests?status=all", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "comment", "no")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(statusOf(id)).as("the approval stands").isEqualTo("approved");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM requests WHERE id = ?", String.class, id))
				.as("and so does its reply").isEqualTo("ok");
	}

	@Test
	void rejectingChangesTheStatusAndNothingElse() {
		seedBalance(this.employeeA, 2026, "21", "0");
		long id = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-04");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "comment", "no");

		assertThat(statusOf(id)).isEqualTo("rejected");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM requests WHERE id = ?", String.class, id)).isEqualTo("no");
		assertThat(this.jdbc.queryForObject(
				"SELECT used_days FROM leave_balance WHERE employee_id = ? AND year = 2026",
				java.math.BigDecimal.class, this.employeeA).signum())
				.as("a rejection deducts nothing").isZero();
	}

	/** {@code flash(__('rejected_ok'), 'warning')}, then {@code approved_ok} (D-253). */
	@Test
	void aDecisionFlashesLegacysMessageInLegacysColour() {
		seedBalance(this.employeeA, 2026, "21", "0");
		long rejected = seedRequest(this.employeeA, this.deductingTypeA, "2026-03-02", "2026-03-04");
		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(rejected), "comment", "no");
		assertThat(body("/admin/requests")).contains("<div class=\"flash flash-warning\">تم الرفض</div>");

		long approved = seedRequest(this.employeeA, this.deductingTypeA, "2026-04-06", "2026-04-06");
		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(approved), "comment", "");
		assertThat(body("/admin/requests")).contains("<div class=\"flash flash-success\">تم القبول ✓</div>");
	}

	@Test
	void rejectingOpensADialogWithAnOptionalReplyRatherThanPostingAtOnce() {
		// Legacy's reject opens the decision modal with an optional reply
		// (requests/page.php:143-157, requests-actions.js); it never posts on
		// the first click. The reply stays optional: an empty one is stored as
		// NULL (anEmptyCommentIsStoredAsNullNotAnEmptyString).
		long typeId = createType(this.companyA, "Errand", false, false);
		long id = seedRequest(this.employeeA, typeId, "2026-03-02", "2026-03-02");

		String html = body("/admin/requests?status=pending");
		String menu = rowMenu(html, id);

		assertThat(menu).as("reject opens its dialog for this row")
				.containsPattern("data-dialog=\"request-reject\"\\s+data-dialog-id=\"" + id + "\"");
		assertThat(menu).as("and does not post from the menu").doesNotContain("value=\"reject\"");

		int dialog = html.indexOf("<div class=\"modal-bg\" id=\"request-reject\"");
		assertThat(dialog).as("the reject dialog renders").isPositive();
		String markup = html.substring(dialog, html.indexOf("</form>", dialog));
		assertThat(markup).contains("name=\"action\" value=\"reject\"");
		java.util.regex.Matcher comment = java.util.regex.Pattern.compile("<textarea\\b[^>]*name=\"comment\"[^>]*>").matcher(markup);
		assertThat(comment.find()).as("a reply box").isTrue();
		assertThat(comment.group()).as("the reply is optional, as in legacy").doesNotContain("required");
	}

	/**
	 * page.php:122 and hr_requests_row_actions() (hr_list_helper.php:524) print the name through
	 * dashboard_employee_display_name(): a blank one is an em dash in the cell and in the approve
	 * and reject windows, whose titles the port's subject line stands for. PHP trims what SQL's
	 * TRIM leaves, and SQL's strips only spaces, so a name that is only a tab is blank too.
	 */
	@Test
	void anEmployeeWithABlankNameReadsAsLegacysDash() {
		long blank = seedRequest(createEmployee(this.companyA, "A200", "", ""), this.plainTypeA,
				"2026-03-02", "2026-03-04");
		long tab = seedRequest(createEmployee(this.companyA, "A300", "\t", ""), this.plainTypeA,
				"2026-03-02", "2026-03-04");
		long named = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");

		String html = body("/admin/requests?company_id=" + this.companyA);
		assertThat(row(html, blank)).containsPattern("<td class=\"text-muted\">A200</td>\\s*<td class=\"bold\">—</td>");
		assertThat(row(html, tab)).containsPattern("<td class=\"text-muted\">A300</td>\\s*<td class=\"bold\">—</td>");
		for (long id : new long[] {blank, tab}) {
			assertThat(java.util.regex.Pattern.compile("data-dialog-subject=\"—\"").matcher(rowMenu(html, id))
					.results().count()).as("approve and reject name the dash for request %s", id).isEqualTo(2);
		}
		assertThat(row(html, named)).containsPattern("<td class=\"text-muted\">A100</td>\\s*<td class=\"bold\">Aya Alpha</td>");
		assertThat(rowMenu(html, named)).contains("data-dialog-subject=\"Aya Alpha\"");
	}

	/** One row's action menu. */
	private static String rowMenu(String html, long rowId) {
		int start = html.indexOf("id=\"row-actions-menu-" + rowId + "\"");
		assertThat(start).as("the row menu for request %s", rowId).isPositive();
		return html.substring(start, html.indexOf("</div>", start));
	}

	/** One request's row of the list, from its opening tag to its end. */
	private static String row(String html, long rowId) {
		int menu = html.indexOf("id=\"row-actions-menu-" + rowId + "\"");
		assertThat(menu).as("the row for request %s", rowId).isPositive();
		return html.substring(html.lastIndexOf("<tr", menu), html.indexOf("</tr>", menu));
	}

	@Test
	void anEmptyCommentIsStoredAsNullNotAnEmptyString() {
		long id = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "comment", "   ");
		assertThat(this.jdbc.queryForObject(
				"SELECT reply FROM requests WHERE id = ?", String.class, id)).isNull();
	}

	// ------------------------------------------------------------------
	// R-046
	// ------------------------------------------------------------------

	@Test
	void rejectingARequestOutsideTheCurrentFilterIsRefused() {
		long betaRequest = seedRequest(this.employeeB, this.typeB, "2026-03-02", "2026-03-04");
		// Alpha needs a row of its own: the page renders action forms only for
		// the rows it lists, so a filtered-to-empty page carries no CSRF token
		// and the request could not be made at all.
		seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		body("/admin/requests?company_id=" + this.companyA);

		assertThat(post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(betaRequest))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(statusOf(betaRequest)).isEqualTo("pending");
	}

	@Test
	void deletingARequestOutsideTheCurrentFilterIsRefused() {
		long betaRequest = seedRequest(this.employeeB, this.typeB, "2026-03-02", "2026-03-04");
		// Alpha needs a row of its own: the page renders action forms only for
		// the rows it lists, so a filtered-to-empty page carries no CSRF token
		// and the request could not be made at all.
		seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		body("/admin/requests?company_id=" + this.companyA);

		assertThat(post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(betaRequest))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM requests WHERE id = " + betaRequest, Integer.class)).isEqualTo(1);
	}

	@Test
	void approvingARequestOutsideTheCurrentFilterIsRefused() {
		long betaRequest = seedRequest(this.employeeB, this.typeB, "2026-03-02", "2026-03-04");
		// Alpha needs a row of its own: the page renders action forms only for
		// the rows it lists, so a filtered-to-empty page carries no CSRF token
		// and the request could not be made at all.
		seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		body("/admin/requests?company_id=" + this.companyA);

		assertThat(post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(betaRequest))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(statusOf(betaRequest)).isEqualTo("pending");
	}

	@Test
	void anUnfilteredAdministratorReachesEveryCompany() {
		long betaRequest = seedRequest(this.employeeB, this.typeB, "2026-03-02", "2026-03-04");
		body("/admin/requests?company_id=");

		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(betaRequest));
		assertThat(statusOf(betaRequest)).isEqualTo("rejected");
	}

	// ------------------------------------------------------------------

	@Test
	void theListDefaultsToPendingRatherThanEverything() {
		long pending = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		long decided = seedRequest(this.employeeA, this.plainTypeA, "2026-04-02", "2026-04-04");
		this.jdbc.update("UPDATE requests SET status = 'approved' WHERE id = ?", decided);

		assertThat(body("/admin/requests")).contains("2026-03-02").doesNotContain("2026-04-02");
		assertThat(body("/admin/requests?status=all"))
				.contains("2026-03-02").contains("2026-04-02");
		assertThat(pending).isNotEqualTo(decided);
	}

	@Test
	void theDateFiltersBothCompareAgainstTheStartDate() {
		seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-20");
		seedRequest(this.employeeA, this.plainTypeA, "2026-05-02", "2026-05-04");

		// A request that starts inside the window and ends outside it is in;
		// legacy compares both bounds against from_date.
		assertThat(body("/admin/requests?date_from=2026-03-01&date_to=2026-03-31"))
				.contains("2026-03-02").doesNotContain("2026-05-02");
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		long id = seedRequest(this.employeeA, this.plainTypeA, "2026-03-02", "2026-03-04");
		post("/admin/requests", this.cookie, page("/admin/requests", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(id));

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'request'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_UPDATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/requests", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private long seedBalance(long employeeId, int year, String total, String used) {
		this.jdbc.update("INSERT INTO leave_balance (employee_id, year, total_days, used_days)"
				+ " VALUES (?, ?, ?, ?)", employeeId, year, new java.math.BigDecimal(total),
				new java.math.BigDecimal(used));
		return this.jdbc.queryForObject(
				"SELECT id FROM leave_balance WHERE employee_id = ? AND year = ?", Long.class,
				employeeId, year);
	}

	private long createType(long companyId, String name, boolean deduct, boolean exception) {
		this.jdbc.update("INSERT INTO request_types (company_id, name, deduct_balance,"
				+ " counts_as_paid_leave, add_attendance_exception, is_active, created_at)"
				+ " VALUES (?, ?, ?, 0, ?, 1, NOW())",
				companyId, name, deduct ? 1 : 0, exception ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM request_types WHERE company_id = ? AND name = ?", Long.class,
				companyId, name);
	}

	private long seedRequest(long employeeId, long typeId, String from, String to) {
		this.jdbc.update("INSERT INTO requests (employee_id, request_type_id, status, from_date,"
				+ " to_date, notes, created_at) VALUES (?, ?, 'pending', ?, ?, 'because', NOW())",
				employeeId, typeId, from, to);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM requests WHERE employee_id = ?", Long.class, employeeId);
	}

	private String statusOf(long id) {
		return this.jdbc.queryForObject("SELECT status FROM requests WHERE id = ?", String.class, id);
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

	private long createExceptionType(long companyId, String name) {
		this.jdbc.update("INSERT INTO exception_types (company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, 1, NOW())", companyId, name);
		return this.jdbc.queryForObject(
				"SELECT id FROM exception_types WHERE company_id = ? AND name = ?", Long.class,
				companyId, name);
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

}
