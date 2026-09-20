package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.web.util.HtmlUtils;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.workin.backend.BackendApplication;
import com.workin.legacy.LegacyMariaDb;

/**
 * {@code /admin/advances} over real HTTP against a real MariaDB.
 *
 * <p>The most stateful HR page: an advance carries a {@code remaining} balance
 * that an edit <b>adjusts</b> rather than overwrites, and three of its six
 * actions are legal only from a particular status. The arithmetic itself is
 * unit-tested in {@link AdvanceTest}; what is proved here is that the page
 * applies it to the right row and stores the result.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminAdvancesEndToEndTest {

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



	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
		this.jdbc.update("DELETE FROM advances");
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


	}

	@Test
	void creatingAnAdvanceOwesItInFull() {
		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "add_advance", "employee_id", String.valueOf(this.employeeA),
				"amount", "1000", "reason", "school fees", "request_date", "2026-03-02");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT employee_id, amount, remaining, status FROM advances");
		assertThat(row.get("employee_id").toString()).isEqualTo(String.valueOf(this.employeeA));
		assertThat(((java.math.BigDecimal) row.get("amount"))
				.compareTo(new java.math.BigDecimal("1000"))).isZero();
		assertThat(((java.math.BigDecimal) row.get("remaining")))
				.as("nothing repaid yet, so the whole amount is outstanding")
				.isEqualByComparingTo("1000");
		assertThat(row.get("status"))
				.as("an advance created from the dashboard is money HR has already handed "
						+ "over, so it is approved on creation -- 'pending' belongs to the "
						+ "employee's own request from the mobile app")
				.isEqualTo("approved");
	}

	@Test
	void aNonPositiveAmountOrNoEmployeeIsRefused() {
		for (String amount : java.util.List.of("0", "-100", "abc", "")) {
			assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
					"action", "add_advance", "employee_id", String.valueOf(this.employeeA),
					"amount", amount)
					.getHeaders().getLocation()).asString()
					.as("amount '%s'", amount).contains("error=error_required");
		}
		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "add_advance", "employee_id", "0", "amount", "1000")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM advances", Integer.class)).isZero();
	}

	@Test
	void editingAPendingAdvanceMovesTheBalanceWithTheAmount() {
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "amount", "1500");

		assertThat(this.jdbc.queryForObject(
				"SELECT remaining FROM advances WHERE id = " + id, java.math.BigDecimal.class))
				.as("nothing repaid, so the balance is simply the new amount")
				.isEqualByComparingTo("1500");
	}

	@Test
	void editingAnApprovedAdvanceKeepsWhatWasAlreadyRepaid() {
		// 1000 advanced, 400 repaid, 600 outstanding. Raising to 1200 adds the
		// 200 difference; it does not reset the balance to 1200.
		long id = seedAdvance(this.employeeA, "1000", "600", "approved");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "amount", "1200");

		assertThat(this.jdbc.queryForObject(
				"SELECT remaining FROM advances WHERE id = " + id, java.math.BigDecimal.class))
				.isEqualByComparingTo("800");
	}

	@Test
	void reducingBelowWhatWasRepaidClearsTheDebtRatherThanGoingNegative() {
		long id = seedAdvance(this.employeeA, "1000", "600", "approved");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "amount", "300");

		assertThat(this.jdbc.queryForObject(
				"SELECT remaining FROM advances WHERE id = " + id, java.math.BigDecimal.class))
				.as("600 + (300 - 1000) is negative; the floor stops the employee becoming a creditor")
				.isEqualByComparingTo("0");
	}

	@Test
	void aSettledAdvanceCannotBeEdited() {
		long repaid = seedAdvance(this.employeeA, "1000", "0", "approved");
		long rejected = seedAdvance(this.employeeA, "500", "500", "rejected");

		for (long id : java.util.List.of(repaid, rejected)) {
			assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
					"action", "edit_advance", "id", String.valueOf(id),
					"employee_id", String.valueOf(this.employeeA), "amount", "9999")
					.getHeaders().getLocation()).asString()
					.as("advance %s", id).contains("error=error_required");
		}
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM advances WHERE amount = 9999", Integer.class)).isZero();
	}

	@Test
	void approvingIsOnlyLegalFromPending() {
		long pending = seedAdvance(this.employeeA, "1000", "1000", "pending");
		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(pending));
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM advances WHERE id = " + pending, String.class))
				.isEqualTo("approved");

		// A second approval is refused: an advance is decided once.
		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(pending))
				.getHeaders().getLocation()).asString().contains("error=error_required");
	}

	@Test
	void aRejectionMustSayWhy() {
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");

		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "rejection_reason", "   ")
				.getHeaders().getLocation()).asString().contains("error=rejection_reason_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM advances WHERE id = " + id, String.class)).isEqualTo("pending");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "reject", "id", String.valueOf(id), "rejection_reason", "not eligible");
		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT status, rejection_reason FROM advances WHERE id = " + id);
		assertThat(row.get("status")).isEqualTo("rejected");
		assertThat(row.get("rejection_reason")).isEqualTo("not eligible");
	}

	@Test
	void markPaidSettlesTheBalanceFromAnyStatus() {
		// Legacy checks no status here, so a pending advance becomes approved
		// and repaid in one statement -- an operator recording a repayment made
		// outside the system.
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "mark_paid", "id", String.valueOf(id));

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT status, remaining FROM advances WHERE id = " + id);
		assertThat(row.get("status")).isEqualTo("approved");
		assertThat((java.math.BigDecimal) row.get("remaining")).isEqualByComparingTo("0");
	}

	@Test
	void eachRowOffersTheActionsItsStatusAllows() {
		// hr_advances_row_actions(): Edit and Delete while there is something to
		// change, then Approve and Reject for a pending advance or Mark paid for
		// an approved one still owed; Delete alone once it is settled or rejected.
		// The service still accepts mark_paid from any status, as legacy's does.
		long pending = seedAdvance(this.employeeA, "1000", "1000", "pending");
		long owed = seedAdvance(this.employeeA, "1000", "600", "approved");
		long repaid = seedAdvance(this.employeeA, "1000", "0", "approved");
		long rejected = seedAdvance(this.employeeA, "500", "500", "rejected");

		String html = body("/admin/advances");

		assertThat(menuActions(html, pending)).containsExactly("edit", "delete_advance", "approve", "reject");
		assertThat(menuActions(html, owed)).containsExactly("edit", "delete_advance", "mark_paid");
		assertThat(menuActions(html, repaid)).as("nothing left to repay or change").containsExactly("delete_advance");
		assertThat(menuActions(html, rejected)).containsExactly("delete_advance");
		assertThat(menu(html, owed)).as("legacy's mark_paid label, in whichever language the page renders")
				.containsAnyOf(">Mark Paid<", ">سدّد<")
				.as("not the remaining balance it used to show").doesNotContain(": 0<");
	}

	/**
	 * hr_render_table_employee_cells() and hr_advances_row_actions() (hr_list_helper.php:69, 779)
	 * print the name through dashboard_employee_display_name(): a blank one is an em dash in the
	 * cell, in the edit's and the decision's subject, and in the label the edit's picker falls
	 * back to, which keeps the code.
	 */
	@Test
	void anEmployeeWithABlankNameReadsAsLegacysDash() {
		long blank = seedAdvance(createEmployee(this.companyA, "A200", "", ""), "1000", "1000", "pending");
		long named = seedAdvance(this.employeeA, "1000", "1000", "pending");

		String html = body("/admin/advances?company_id=" + this.companyA);
		assertThat(row(html, blank)).containsPattern("<td class=\"text-muted\">A200</td>\\s*<td class=\"bold\">—</td>");
		assertThat(menu(html, blank))
				.containsPattern("data-dialog=\"advance-edit\"[^>]*data-dialog-subject=\"—\"")
				.contains("data-dialog-employee_label=\"— (A200)\"")
				.containsPattern("data-dialog=\"advance-reject\"[^>]*data-dialog-subject=\"—\"");
		assertThat(row(html, named)).containsPattern("<td class=\"text-muted\">A100</td>\\s*<td class=\"bold\">Aya Alpha</td>");
		assertThat(menu(html, named))
				.containsPattern("data-dialog=\"advance-edit\"[^>]*data-dialog-subject=\"Aya Alpha\"")
				.contains("data-dialog-employee_label=\"Aya Alpha (A100)\"")
				.containsPattern("data-dialog=\"advance-reject\"[^>]*data-dialog-subject=\"Aya Alpha\"");
	}

	/** One advance's row of the list, from its opening tag to its end. */
	private static String row(String html, long advanceId) {
		int menu = html.indexOf("id=\"row-actions-menu-" + advanceId + "\"");
		assertThat(menu).as("the row for advance %s", advanceId).isPositive();
		return html.substring(html.lastIndexOf("<tr", menu), html.indexOf("</tr>", menu));
	}

	private static String menu(String html, long advanceId) {
		int start = html.indexOf("id=\"row-actions-menu-" + advanceId + "\"");
		assertThat(start).as("the row menu for advance %s", advanceId).isPositive();
		return html.substring(start, html.indexOf("</div>", start));
	}

	/** The menu's items in order: a dialog trigger by its dialog, a form by its action. */
	private static java.util.List<String> menuActions(String html, long advanceId) {
		java.util.List<String> actions = new java.util.ArrayList<>();
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("data-dialog=\"advance-(edit|reject)\"|name=\"action\" value=\"([a-z_]+)\"")
				.matcher(menu(html, advanceId));
		while (matcher.find()) {
			actions.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
		}
		return actions;
	}

	@Test
	void theStatusFilterNarrows() {
		seedAdvance(this.employeeA, "111", "111", "pending");
		seedAdvance(this.employeeA, "222", "0", "approved");

		assertThat(body("/admin/advances?status=pending")).contains("111").doesNotContain(">222<");
		assertThat(body("/admin/advances?status=approved")).contains("222");
		assertThat(body("/admin/advances")).contains("111").contains("222");
	}

	/**
	 * page.php:198: the export sits beside the add button and carries the filters the list is
	 * under, so the file is the list on the screen. Legacy offers it to anyone who can open the
	 * page, managing rights or not.
	 */
	@Test
	void theExportLinkCarriesTheListsOwnFilters() {
		assertThat(headActions(body("/admin/advances")))
				.contains("<a href=\"/admin/advances?export=csv&amp;status=all\""
						+ " class=\"btn btn-green btn-sm\">" + arabic("export_csv") + "</a>");

		assertThat(headActions(body("/admin/advances?company_id=" + this.companyA
				+ "&search=Aya+A&status=pending&date_from=2026-03-01&date_to=2026-03-31")))
				.as("every filter the pager carries, and the search encoded as a link encodes it")
				.contains("<a href=\"/admin/advances?export=csv&amp;search=Aya+A&amp;company_id="
						+ this.companyA
						+ "&amp;status=pending&amp;date_from=2026-03-01&amp;date_to=2026-03-31\"");
	}

	/**
	 * hr_export_advances_csv() (hr_list_helper.php:1110-1152) with csv_export_send()
	 * (query.php:375-405): every row the filter admits, not the page on the screen, ordered by
	 * {@code created_at} rather than the {@code request_date}-first order the table above it
	 * uses, as the spreadsheet legacy's button has always downloaded -- the helper rewrites its
	 * own .csv name to .xlsx (hr-legacy#23).
	 */
	@Test
	void theExportSendsEveryFilteredRowAsLegacysSpreadsheet() {
		seedAdvance(this.employeeA, "1000", "600", "approved");
		seedAdvance(this.employeeA, "500", "500", "pending");
		seedAdvance(this.employeeB, "999", "999", "pending");

		ResponseEntity<byte[]> response = getBytes(
				"/admin/advances?export=csv&company_id=" + this.companyA + "&per_page=1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).asString()
				.isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.asString().matches("attachment; filename=\"advances_\\d{4}-\\d{2}-\\d{2}\\.xlsx\"");
		assertThat(new String(response.getBody(), 0, 2, java.nio.charset.StandardCharsets.US_ASCII))
				.as("XLSX is a ZIP container").isEqualTo("PK");

		List<List<String>> rows = sheetRows(response.getBody());
		assertThat(rows.get(0)).as("legacy's eight headers, in the page's language").containsExactly(
				arabic("emp_code"), arabic("employee_name"), arabic("advance_amount"),
				arabic("remaining"), arabic("request_date"), arabic("advance_reason"),
				arabic("rejection_reason"), arabic("status"));
		assertThat(rows.subList(1, rows.size()))
				.as("both of Alpha's advances, newest created first, whatever the page size")
				.containsExactly(
						List.of("A100", "Aya Alpha", "500.00", "500.00", "2026-03-02", "", "", "pending"),
						List.of("A100", "Aya Alpha", "1000.00", "600.00", "2026-03-02", "", "", "approved"));
	}

	/** The filter is the boundary: another company's advances are not in this company's file. */
	@Test
	void theExportNeverReachesPastTheFilter() {
		seedAdvance(this.employeeA, "1000", "1000", "pending");
		seedAdvance(this.employeeB, "500", "500", "pending");

		List<List<String>> alpha = sheetRows(getBytes(
				"/admin/advances?export=csv&company_id=" + this.companyA).getBody());
		assertThat(alpha).as("a header and Alpha's one advance").hasSize(2);
		assertThat(alpha.get(1)).contains("Aya Alpha").doesNotContain("Basma Beta");

		assertThat(sheetRows(getBytes("/admin/advances?export=csv&company_id=").getBody()))
				.as("unfiltered, an administrator's file holds both companies")
				.hasSize(3);
	}

	/** An empty list exports the headers and nothing else, rather than failing. */
	@Test
	void anEmptyListStillExports() {
		List<List<String>> rows = sheetRows(getBytes("/admin/advances?export=csv").getBody());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).first().isEqualTo(arabic("emp_code"));
	}

	/**
	 * The task's second failure mode: a read must not disappear behind the write switch that
	 * gates the row actions. {@link AdminHrExportsWithActionsDisabledEndToEndTest} pins the
	 * control's presence and the endpoint's answer with the switch at its default (off); this
	 * pins that the export's own {@code WHERE} still narrows correctly with it on.
	 */
	@Test
	void theExportAppliesTheStatusFilterTheListApplies() {
		seedAdvance(this.employeeA, "111", "111", "pending");
		seedAdvance(this.employeeA, "222", "0", "approved");

		List<List<String>> rows = sheetRows(
				getBytes("/admin/advances?export=csv&status=pending").getBody());
		assertThat(rows.subList(1, rows.size())).as("only the pending advance")
				.singleElement().satisfies(row -> assertThat(row).contains("111.00"));
	}

	/** The actions beside the list's title. */
	private static String headActions(String html) {
		Matcher actions = Pattern.compile("(?s)<div class=\"data-table-head__actions\">.*?</div>")
				.matcher(html);
		assertThat(actions.find()).as("the list's head actions").isTrue();
		return actions.group();
	}

	private ResponseEntity<byte[]> getBytes(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, "WORKIN_ADMIN_SESSION=" + this.cookie);
		return this.restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
	}

	/** The workbook's one sheet, row by row, each cell's inline string. */
	private static List<List<String>> sheetRows(byte[] workbook) {
		String sheet = null;
		try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
				new java.io.ByteArrayInputStream(workbook))) {
			for (java.util.zip.ZipEntry entry = zip.getNextEntry(); entry != null;
					entry = zip.getNextEntry()) {
				if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
					sheet = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				}
			}
		} catch (java.io.IOException ex) {
			throw new AssertionError("the response is not a readable ZIP container", ex);
		}
		assertThat(sheet).as("the workbook's sheet").isNotNull();
		List<List<String>> rows = new ArrayList<>();
		Matcher row = Pattern.compile("(?s)<row\\b.*?</row>").matcher(sheet);
		while (row.find()) {
			List<String> cells = new ArrayList<>();
			Matcher cell = Pattern.compile("(?s)<is><t[^>]*>(.*?)</t></is>").matcher(row.group());
			while (cell.find()) {
				cells.add(HtmlUtils.htmlUnescape(cell.group(1)));
			}
			rows.add(cells);
		}
		return rows;
	}

	/** One label as the dashboard's default language renders it. */
	private static String arabic(String key) {
		java.util.Properties catalogue = new java.util.Properties();
		try (java.io.InputStream in = AdminAdvancesEndToEndTest.class
				.getResourceAsStream("/i18n/admin-messages_ar.properties")) {
			catalogue.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
		} catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		String value = catalogue.getProperty(key);
		assertThat(value).as("the catalogue's %s", key).isNotNull();
		return value;
	}

	@Test
	void anUnfilteredAdministratorCannotMoveAnAdvanceBetweenCompanies() {
		// D-176: an advance has no company_id, so reassigning the employee is
		// moving the debt.
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");
		body("/admin/advances?company_id=");

		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeB), "amount", "1000")
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT employee_id FROM advances WHERE id = " + id, Long.class))
				.isEqualTo(this.employeeA);
	}

	@Test
	void anEditMayStillReassignWithinTheSameCompany() {
		long other = createEmployee(this.companyA, "A400", "Other", "Alpha");
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");
		body("/admin/advances?company_id=");

		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(other), "amount", "1000");

		assertThat(this.jdbc.queryForObject(
				"SELECT employee_id FROM advances WHERE id = " + id, Long.class)).isEqualTo(other);
	}

	@Test
	void approvingOutsideTheCurrentFilterIsRefused() {
		long betaAdvance = seedAdvance(this.employeeB, "500", "500", "pending");
		seedAdvance(this.employeeA, "500", "500", "pending");
		body("/admin/advances?company_id=" + this.companyA);

		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "approve", "id", String.valueOf(betaAdvance))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM advances WHERE id = " + betaAdvance, String.class))
				.isEqualTo("pending");
	}

	@Test
	void deletingOutsideTheCurrentFilterIsRefused() {
		long betaAdvance = seedAdvance(this.employeeB, "500", "500", "pending");
		seedAdvance(this.employeeA, "500", "500", "pending");
		body("/admin/advances?company_id=" + this.companyA);

		assertThat(post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "delete_advance", "id", String.valueOf(betaAdvance))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM advances WHERE id = " + betaAdvance, Integer.class)).isEqualTo(1);
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "add_advance", "employee_id", String.valueOf(this.employeeA),
				"amount", "1000");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'advance'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/advances", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	@Test
	void anEditKeepsItsRowsEmployeeAfterThatEmployeeIsDeactivated() {
		long id = seedAdvance(this.employeeA, "1000", "1000", "pending");
		this.jdbc.update("UPDATE employees SET is_active = 0 WHERE id = ?", this.employeeA);

		String html = body("/admin/advances");
		Matcher trigger = Pattern.compile("<button[^>]*data-dialog=\"advance-edit\"[^>]*data-dialog-id=\""
				+ id + "\"[^>]*>").matcher(html);
		assertThat(trigger.find()).as("the row offers Edit").isTrue();
		assertThat(trigger.group())
				.as("Edit carries the row's employee and the label legacy shows for it")
				.contains("data-dialog-employee_id=\"" + this.employeeA + "\"")
				.contains("data-dialog-employee_label=\"Aya Alpha (A100)\"");

		String dialog = html.substring(html.indexOf("id=\"advance-edit\""));
		dialog = dialog.substring(0, dialog.indexOf("</form>"));
		assertThat(dialog)
				.as("the row fills a picker, not a select that can only hold listed employees")
				.doesNotContain("<select name=\"employee_id\"")
				.containsPattern("name=\"employee_id\" value=\"\" data-emp-id\\s+data-dialog-field=\"employee_id\"")
				.containsPattern("data-emp-fallback-label\\s+data-dialog-field=\"employee_label\"");
		assertThat(pickerLabels(html).keySet())
				.as("the list holds active employees; the row keeps its own")
				.contains(this.employeeB)
				.doesNotContain(this.employeeA);

		ResponseEntity<String> saved = post("/admin/advances", this.cookie, page("/admin/advances", this.cookie).csrf(),
				"action", "edit_advance", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA), "amount", "1200");
		assertThat(saved.getHeaders().getLocation()).asString().doesNotContain("error=");
		assertThat(this.jdbc.queryForObject("SELECT employee_id FROM advances WHERE id = " + id, Long.class))
				.as("saving keeps the deactivated employee, as legacy's edit does")
				.isEqualTo(this.employeeA);
	}

	/**
	 * page.php:209-210: number_format($amount, 0), and the remaining amount red while any is owed,
	 * green once it is repaid.
	 */
	@Test
	void theAmountsAreWholePoundsAndTheRemainingIsColouredByWhetherItIsOwed() {
		seedAdvance(this.employeeA, "12345.50", "600", "approved");
		seedAdvance(this.employeeA, "1000", "0", "approved");

		assertThat(body("/admin/advances"))
				.contains("<td class=\"bold\">12,346</td>")
				.contains("<td class=\"text-red\">600</td>")
				.contains("<td class=\"bold\">1,000</td>")
				.contains("<td class=\"text-green\">0</td>");
	}

	@Test
	void thePickerListsEveryActiveEmployeeUnderLegacysLabels() {
		insertActiveEmployees(this.companyB, 520);
		long active = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE is_active = 1", Long.class);

		Map<Long, String> everyone = pickerLabels(body("/admin/advances"));
		assertThat(everyone).as("no company chosen: every active employee, not the first 500")
				.hasSize((int) active);
		assertThat(everyone.get(this.employeeB)).as("a list across companies names the company")
				.isEqualTo("Basma Beta (B100) — Beta Co");
		assertThat(pickerLabels(body("/admin/advances?company_id=" + this.companyB)).get(this.employeeB))
				.as("one company chosen: the label leaves it out")
				.isEqualTo("Basma Beta (B100)");
	}

	private Map<Long, String> pickerLabels(String html) {
		Matcher list = Pattern.compile("id=\"employee-picker-list\" data-employees=\"([^\"]*)\"").matcher(html);
		assertThat(list.find()).as("the page renders the picker's list").isTrue();
		List<Map<String, Object>> entries = new ObjectMapper().readValue(
				HtmlUtils.htmlUnescape(list.group(1)), new TypeReference<List<Map<String, Object>>>() {
				});
		Map<Long, String> labels = new LinkedHashMap<>();
		entries.forEach(entry -> labels.put(((Number) entry.get("id")).longValue(), (String) entry.get("label")));
		return labels;
	}

	private void insertActiveEmployees(long companyId, int count) {
		long branchId = this.jdbc.queryForObject(
				"SELECT COALESCE(MAX(id), 0) + 1 FROM branches", Long.class);
		this.jdbc.update("INSERT INTO branches (id, company_id, name, is_active, created_at)"
				+ " VALUES (?, ?, ?, 1, NOW())", branchId, companyId, "Bulk branch");
		long first = this.jdbc.queryForObject(
				"SELECT GREATEST(COALESCE(MAX(id), 0) + 1, 990001) FROM employees", Long.class);
		List<Object[]> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			rows.add(new Object[] { first + i, companyId, branchId, "BULK" + i, "Bulk", "Employee " + i });
		}
		this.jdbc.batchUpdate("INSERT INTO employees (id, company_id, branch_id, employee_code,"
				+ " first_name, last_name, role, is_active, is_mobile_attendance_enabled,"
				+ " can_check_in_any_branch, join_request_status, token_version, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, 'employee', 1, 1, 0, 'accepted', 1, NOW(), NOW())", rows);
	}

	private long seedAdvance(long employeeId, String amount, String remaining, String status) {
		this.jdbc.update("INSERT INTO advances (employee_id, amount, remaining, status,"
				+ " request_date, created_at) VALUES (?, ?, ?, ?, '2026-03-02', NOW())",
				employeeId, new java.math.BigDecimal(amount), new java.math.BigDecimal(remaining),
				status);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM advances WHERE employee_id = ?", Long.class, employeeId);
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
