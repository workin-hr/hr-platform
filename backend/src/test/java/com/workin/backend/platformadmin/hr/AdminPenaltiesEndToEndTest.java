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
 * {@code /admin/penalties} over real HTTP against a real MariaDB.
 *
 * <p>Two rules carry this page. Penalty days are a <b>seven-value whitelist</b>
 * shared with payroll's own, so anything else is refused rather than rounded --
 * these days become money. And a penalty {@code applied_to_payroll} is
 * <b>frozen</b>: a payslip has already been computed from it, so editing it
 * would leave the two disagreeing.
 *
 * <p>And <b>R-046</b>: {@code mark_applied} and {@code delete_penalty} wrote by
 * id alone.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminPenaltiesEndToEndTest {

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
		this.jdbc.update("DELETE FROM penalties");
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
	void addingAPenaltyWritesItUnappliedAgainstTheEmployeesCompany() {
		post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "add_penalty", "employee_id", String.valueOf(this.employeeA),
				"penalty_type", "Lateness", "penalty_days", "0.5",
				"reason", "third time", "penalty_date", "2026-03-02");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT employee_id, penalty_type, penalty_days, reason, penalty_date,"
						+ " applied_to_payroll FROM penalties");
		assertThat(row.get("employee_id").toString()).isEqualTo(String.valueOf(this.employeeA));
		assertThat(row.get("penalty_type")).isEqualTo("Lateness");
		assertThat(((java.math.BigDecimal) row.get("penalty_days"))
				.compareTo(new java.math.BigDecimal("0.5"))).isZero();
		assertThat(row.get("reason")).isEqualTo("third time");
		assertThat(row.get("applied_to_payroll"))
				.as("a new penalty has not reached payroll").isEqualTo(Boolean.FALSE);
	}

	@Test
	void everyAllowedDayValueIsAcceptedAndNothingElseIs() {
		// Shared with payroll's own whitelist, because these days become money.
		for (String allowed : List.of("0.25", "0.5", "1", "2", "3", "4", "5")) {
			this.jdbc.update("DELETE FROM penalties");
			post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
					"action", "add_penalty", "employee_id", String.valueOf(this.employeeA),
					"penalty_type", "T", "penalty_days", allowed);
			assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM penalties", Integer.class))
					.as("allowed value %s", allowed).isEqualTo(1);
		}
		this.jdbc.update("DELETE FROM penalties");
		for (String rejected : List.of("0", "0.75", "1.5", "6", "-1", "abc", "")) {
			assertThat(post("/admin/penalties", this.cookie,
					page("/admin/penalties", this.cookie).csrf(),
					"action", "add_penalty", "employee_id", String.valueOf(this.employeeA),
					"penalty_type", "T", "penalty_days", rejected)
					.getHeaders().getLocation()).asString()
					.as("rejected value '%s'", rejected).contains("error=penalty_days_invalid");
		}
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM penalties", Integer.class)).isZero();
	}

	@Test
	void anEmptyTypeOrNoEmployeeIsRefused() {
		assertThat(post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "add_penalty", "employee_id", String.valueOf(this.employeeA),
				"penalty_type", "   ", "penalty_days", "1")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "add_penalty", "employee_id", "0",
				"penalty_type", "Lateness", "penalty_days", "1")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM penalties", Integer.class)).isZero();
	}

	@Test
	void anAbsentDateBecomesToday() {
		post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "add_penalty", "employee_id", String.valueOf(this.employeeA),
				"penalty_type", "Lateness", "penalty_days", "1");

		assertThat(this.jdbc.queryForObject(
				"SELECT penalty_date FROM penalties", String.class))
				.isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.ofHours(2)).toString());
	}

	@Test
	void markingAppliedFreezesTheRowAgainstEditing() {
		long id = seedPenalty(this.employeeA, "Lateness", "1", false);

		post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "mark_applied", "id", String.valueOf(id));
		assertThat(this.jdbc.queryForObject(
				"SELECT applied_to_payroll FROM penalties WHERE id = " + id, Boolean.class)).isTrue();

		// A payslip has been computed from these days; changing them would
		// leave the two disagreeing, so legacy refuses and so does this.
		assertThat(post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "edit_penalty", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA),
				"penalty_type", "Changed", "penalty_days", "5")
				.getHeaders().getLocation()).asString().contains("error=error_required");
		assertThat(this.jdbc.queryForObject(
				"SELECT penalty_type FROM penalties WHERE id = " + id, String.class))
				.isEqualTo("Lateness");
	}

	@Test
	void anUnappliedPenaltyCanStillBeEdited() {
		long id = seedPenalty(this.employeeA, "Lateness", "1", false);

		post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "edit_penalty", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA),
				"penalty_type", "Absence", "penalty_days", "2", "penalty_date", "2026-04-01");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT penalty_type, penalty_days FROM penalties WHERE id = " + id);
		assertThat(row.get("penalty_type")).isEqualTo("Absence");
		assertThat(((java.math.BigDecimal) row.get("penalty_days"))
				.compareTo(new java.math.BigDecimal("2"))).isZero();
	}

	@Test
	void anEditCannotMoveAPenaltyToAnotherCompany() {
		long id = seedPenalty(this.employeeA, "Lateness", "1", false);
		body("/admin/penalties?company_id=" + this.companyA);

		assertThat(post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "edit_penalty", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeB),
				"penalty_type", "Lateness", "penalty_days", "1")
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT employee_id FROM penalties WHERE id = " + id, Long.class))
				.isEqualTo(this.employeeA);
	}

	@Test
	void anUnfilteredAdministratorStillCannotMoveThePenaltyBetweenCompanies() {
		// A penalty has no company column -- it is whichever company its
		// employee is in -- so reassigning the employee *is* moving the row.
		long id = seedPenalty(this.employeeA, "Lateness", "1", false);
		body("/admin/penalties?company_id=");

		assertThat(post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "edit_penalty", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeB),
				"penalty_type", "Lateness", "penalty_days", "1")
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT employee_id FROM penalties WHERE id = " + id, Long.class))
				.isEqualTo(this.employeeA);
	}

	@Test
	void anAppliedPenaltyCanStillBeDeleted() {
		// Legacy allows it, and it is not obviously right -- but tightening it
		// here would refuse an operation operators may rely on, and this port
		// is not the place to decide that.
		long id = seedPenalty(this.employeeA, "Lateness", "1", true);
		post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "delete_penalty", "id", String.valueOf(id));
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM penalties WHERE id = " + id, Integer.class)).isZero();
	}

	/**
	 * page.php:181 and :231 run the days through dashboard_penalty_days_option_label(): a list cell in
	 * red bold, and the words in both day pickers. What a form posts back stays the number.
	 */
	@Test
	void theDaysReadAsLegacysWords() {
		long half = seedPenalty(this.employeeA, "Half", "0.5", false);
		seedPenalty(this.employeeA, "Two", "2", false);

		String html = body("/admin/penalties");
		assertThat(html)
				.contains("<td class=\"text-red bold\">\u0646\u0635 \u064a\u0648\u0645</td>")
				.contains("<td class=\"text-red bold\">2 \u0623\u064a\u0627\u0645</td>")
				.doesNotContain("badge badge-gray\">0.5<");
		// Each picker read on its own: the two render the same options, so a check
		// against the whole page would pass with either one left as bare numbers.
		assertThat(picker(html, "data-dialog-field=\"penalty_days\"")).as("the edit window's picker")
				.contains("<option value=\"0.25\">\u0631\u0628\u0639 \u064a\u0648\u0645</option>")
				.contains("<option value=\"1\">\u064a\u0648\u0645</option>")
				.contains("<option value=\"5\">5 \u0623\u064a\u0627\u0645</option>");
		assertThat(picker(html, "id=\"penalty_days\"")).as("the add form's picker, choosing one day as legacy does")
				.contains("<option value=\"0.25\">\u0631\u0628\u0639 \u064a\u0648\u0645</option>")
				.contains("<option value=\"1\" selected=\"true\">\u064a\u0648\u0645</option>")
				.contains("<option value=\"5\">5 \u0623\u064a\u0627\u0645</option>");
		assertThat(html).as("the edit dialog still posts the number")
				.containsPattern("data-dialog-id=\"" + half + "\"[^>]*data-dialog-penalty_days=\"0.5\"");
	}

	@Test
	void theAppliedFilterNarrowsBothWays() {
		seedPenalty(this.employeeA, "Unapplied", "1", false);
		seedPenalty(this.employeeA, "Applied", "1", true);

		assertThat(body("/admin/penalties?applied=0")).contains("Unapplied").doesNotContain("Applied<");
		assertThat(body("/admin/penalties?applied=1")).contains("Applied");
		assertThat(body("/admin/penalties")).contains("Unapplied").contains("Applied");
	}

	// ------------------------------------------------------------------
	// R-046
	// ------------------------------------------------------------------

	@Test
	void markingAppliedOutsideTheCurrentFilterIsRefused() {
		long betaPenalty = seedPenalty(this.employeeB, "Beta", "1", false);
		seedPenalty(this.employeeA, "Alpha", "1", false);
		body("/admin/penalties?company_id=" + this.companyA);

		assertThat(post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "mark_applied", "id", String.valueOf(betaPenalty))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT applied_to_payroll FROM penalties WHERE id = " + betaPenalty, Boolean.class))
				.isFalse();
	}

	@Test
	void deletingOutsideTheCurrentFilterIsRefused() {
		long betaPenalty = seedPenalty(this.employeeB, "Beta", "1", false);
		seedPenalty(this.employeeA, "Alpha", "1", false);
		body("/admin/penalties?company_id=" + this.companyA);

		assertThat(post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "delete_penalty", "id", String.valueOf(betaPenalty))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM penalties WHERE id = " + betaPenalty, Integer.class)).isEqualTo(1);
	}

	@Test
	void anUnfilteredAdministratorReachesEveryCompany() {
		long betaPenalty = seedPenalty(this.employeeB, "Beta", "1", false);
		body("/admin/penalties?company_id=");

		post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "mark_applied", "id", String.valueOf(betaPenalty));
		assertThat(this.jdbc.queryForObject(
				"SELECT applied_to_payroll FROM penalties WHERE id = " + betaPenalty, Boolean.class))
				.isTrue();
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "add_penalty", "employee_id", String.valueOf(this.employeeA),
				"penalty_type", "Lateness", "penalty_days", "1");

		assertThat(this.jdbc.queryForList(
				"SELECT event_type FROM platform_admin_audit_events WHERE target_type = 'penalty'"))
				.singleElement()
				.satisfies(row -> assertThat(row.get("event_type")).isEqualTo("ORG_CREATED"));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/penalties", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	@Test
	void anEditKeepsItsRowsEmployeeAfterThatEmployeeIsDeactivated() {
		long id = seedPenalty(this.employeeA, "Lateness", "1", false);
		this.jdbc.update("UPDATE employees SET is_active = 0 WHERE id = ?", this.employeeA);

		String html = body("/admin/penalties");
		Matcher trigger = Pattern.compile("<button[^>]*data-dialog=\"penalty-edit\"[^>]*data-dialog-id=\""
				+ id + "\"[^>]*>").matcher(html);
		assertThat(trigger.find()).as("the row offers Edit").isTrue();
		assertThat(trigger.group())
				.as("Edit carries the row's employee and the label legacy shows for it")
				.contains("data-dialog-employee_id=\"" + this.employeeA + "\"")
				.contains("data-dialog-employee_label=\"Aya Alpha (A100)\"");

		String dialog = html.substring(html.indexOf("id=\"penalty-edit\""));
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

		ResponseEntity<String> saved = post("/admin/penalties", this.cookie, page("/admin/penalties", this.cookie).csrf(),
				"action", "edit_penalty", "id", String.valueOf(id),
				"employee_id", String.valueOf(this.employeeA),
				"penalty_type", "Lateness", "penalty_days", "1");
		assertThat(saved.getHeaders().getLocation()).asString().doesNotContain("error=");
		assertThat(this.jdbc.queryForObject("SELECT employee_id FROM penalties WHERE id = " + id, Long.class))
				.as("saving keeps the deactivated employee, as legacy's edit does")
				.isEqualTo(this.employeeA);
	}

	@Test
	void thePickerListsEveryActiveEmployeeUnderLegacysLabels() {
		insertActiveEmployees(this.companyB, 520);
		long active = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE is_active = 1", Long.class);

		Map<Long, String> everyone = pickerLabels(body("/admin/penalties"));
		assertThat(everyone).as("no company chosen: every active employee, not the first 500")
				.hasSize((int) active);
		assertThat(everyone.get(this.employeeB)).as("a list across companies names the company")
				.isEqualTo("Basma Beta (B100) — Beta Co");
		assertThat(pickerLabels(body("/admin/penalties?company_id=" + this.companyB)).get(this.employeeB))
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

	/** One select's options: from the tag carrying {@code marker} to its closing tag. */
	private static String picker(String html, String marker) {
		int start = html.indexOf(marker);
		assertThat(start).as("the select marked %s", marker).isGreaterThanOrEqualTo(0);
		return html.substring(start, html.indexOf("</select>", start));
	}

	private long seedPenalty(long employeeId, String type, String days, boolean applied) {
		this.jdbc.update("INSERT INTO penalties (employee_id, penalty_type, penalty_days,"
				+ " penalty_date, applied_to_payroll, created_at)"
				+ " VALUES (?, ?, ?, '2026-03-02', ?, NOW())",
				employeeId, type, new java.math.BigDecimal(days), applied ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT MAX(id) FROM penalties WHERE employee_id = ?", Long.class, employeeId);
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
