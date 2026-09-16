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
 * {@code /admin/attendance} over real HTTP against a real MariaDB.
 *
 * <p>Attendance rows carry no {@code company_id}: ownership arrives through the
 * employee, so every write here is an R-046 shape and legacy guards them since
 * {@code 505004f} (R-059). D-176 holds by construction, and the exception-type
 * case below is what proves it rather than assuming it.
 *
 * <p>The parity cases at the end matter most. This page is not the payroll
 * page and its two tables are not each other; the fixtures pin all three
 * deliberate differences so a later tidy-up cannot merge them.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminAttendanceEndToEndTest {

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

	@Autowired
	private AttendanceStore store;

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
		this.jdbc.update("DELETE FROM attendance");
		this.jdbc.update("DELETE FROM exception_types");
		this.jdbc.update("DELETE FROM company_official_holidays");
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

	/**
	 * The worked-minutes calendar is request-scoped, so a test that calls the
	 * store directly rather than through the page has to stand up a request
	 * first. The parity fixtures below do exactly that: they compare two
	 * computations against one row, which is not something a rendered page can
	 * show.
	 */
	private <T> T inRequestScope(java.util.function.Supplier<T> work) {
		org.springframework.mock.web.MockHttpServletRequest request =
				new org.springframework.mock.web.MockHttpServletRequest();
		org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
				new org.springframework.web.context.request.ServletRequestAttributes(request));
		try {
			return work.get();
		} finally {
			org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
		}
	}

	/** The filter shape the page builds, without going through a request. */
	private static com.workin.backend.platformadmin.web.DashboardListFilters filtersFor(long companyId) {
		return new com.workin.backend.platformadmin.web.DashboardListFilters(
				companyId, "", "all", 0L, 0L, 1, 10, false);
	}

	private static final String PATH = "/admin/attendance";

	private long attendance(long employeeId, String checkIn, String checkOut, Long exceptionTypeId) {
		this.jdbc.update("INSERT INTO attendance (employee_id, check_in, check_out, method,"
				+ " exception_type_id) VALUES (?, ?, ?, 'app', ?)",
				employeeId, checkIn, checkOut, exceptionTypeId);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM attendance", Long.class);
	}

	private long exceptionType(long companyId, String name) {
		this.jdbc.update("INSERT INTO exception_types (company_id, name) VALUES (?, ?)",
				companyId, name);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM exception_types", Long.class);
	}

	/** The markup from one select's opening tag to its closing tag. */
	private static String selectMarkup(String html, String openingTag) {
		int start = html.indexOf(openingTag);
		assertThat(start).as("the select %s", openingTag).isGreaterThanOrEqualTo(0);
		return html.substring(start, html.indexOf("</select>", start));
	}

	private String range() {
		return "?from=2026-03-01&to=2026-03-31";
	}

	@Test
	void bothTablesRenderTheFilteredRange() {
		attendance(this.employeeA, "2026-03-02 09:00:00", "2026-03-02 17:00:00", null);
		String html = body(PATH + range());
		assertThat(html).contains("\u0633\u062c\u0644\u0627\u062a \u0627\u0644\u0628\u0635\u0645\u0627\u062a").contains("Aya");
		assertThat(html).as("the aggregate table is on the same page")
				.contains("\u0627\u0644\u062a\u0642\u0631\u064a\u0631 \u0627\u0644\u0625\u062c\u0645\u0627\u0644\u064a");
	}

	/**
	 * Legacy's cell formats (page.php:202-206, 248-250) and its payroll-page wrapper, which
	 * payroll-pages.css scopes the overtime badge to. With no shift, an employee is expected to
	 * work eight hours, so each employee here lands on one side of that: over, under, exactly.
	 */
	@Test
	void theRowsCarryLegacysDateTimeAndHoursFormats() {
		long exact = createEmployee(this.companyA, "A200", "Amal", "Alpha");
		attendance(this.employeeA, "2026-03-02 09:00:00", "2026-03-02 18:15:00", null);
		attendance(this.employeeA, "2026-03-04 09:30:00", null, null);
		attendance(this.employeeB, "2026-03-03 09:00:00", "2026-03-03 16:30:00", null);
		attendance(exact, "2026-03-05 09:00:00", "2026-03-05 17:00:00", null);
		String html = body(PATH + range());
		assertThat(html).contains("<div class=\"content payroll-page hr-page\">");
		assertThat(html).as("2 March 2026, a Monday, 09:00 to 18:15")
				.contains("<td>2 \u0645\u0627\u0631\u0633 2026</td>")
				.contains("<td>\u0627\u0644\u0627\u062b\u0646\u064a\u0646</td>")
				.contains("<td dir=\"ltr\">09:00</td>")
				.contains("<td dir=\"ltr\">18:15</td>")
				.doesNotContain("2026-03-02 09:00:00</td>");
		assertThat(html).as("an open punch: its time, and a muted dash for the check-out")
				.contains("<td dir=\"ltr\">09:30</td>")
				.contains("<td dir=\"ltr\"><span class=\"text-muted\">\u2014</span></td>");
		assertThat(html).as("worked hours as legacy's zero-padded HH:MM")
				.containsPattern("<td dir=\"ltr\">18:15</td>\\s*<td>\\d{2}:\\d{2}</td>");
		assertThat(html).as("the summary's hours in words")
				.contains("<td>9 \u0633\u0627\u0639\u0629 \u0648 15 \u062f\u0642\u064a\u0642\u0629</td>")
				.contains("<td>7 \u0633\u0627\u0639\u0629 \u0648 30 \u062f\u0642\u064a\u0642\u0629</td>")
				.contains("<td>8 \u0633\u0627\u0639\u0629</td>");
		assertThat(html).as("overtime in minutes, over and under, and a muted dash for neither")
				.contains("<span class=\"att-overtime-badge att-overtime-badge--plus\">+75 \u062f\u0642\u064a\u0642\u0629</span>")
				.contains("<span class=\"att-overtime-badge att-overtime-badge--minus\">-30 \u062f\u0642\u064a\u0642\u0629</span>")
				.containsPattern("<td class=\"col-center\">\\s*<span class=\"text-muted\">\u2014</span>\\s*</td>");
	}

	@Test
	void addingAPunchWritesItAgainstTheEmployeesCompany() {
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "add_attendance", "employee_id", String.valueOf(this.employeeA),
				"check_in", "2026-03-02 09:00:00", "check_out", "2026-03-02 17:00:00");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT employee_id, check_in, check_out, method, exception_type_id FROM attendance");
		assertThat(row.get("employee_id").toString()).isEqualTo(String.valueOf(this.employeeA));
		assertThat(row.get("method")).as("legacy writes ATTEND_APP").isEqualTo("app");
		assertThat(row.get("exception_type_id")).isNull();
	}

	@Test
	void anEmptyCheckOutStoresNullRatherThanAnEmptyString() {
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "add_attendance", "employee_id", String.valueOf(this.employeeA),
				"check_in", "2026-03-02 09:00:00", "check_out", "");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM attendance WHERE check_out IS NULL", Integer.class)).isEqualTo(1);
	}

	@Test
	void addingWithNoEmployeeWritesNothing() {
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "add_attendance", "employee_id", "0",
				"check_in", "2026-03-02 09:00:00");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
	}

	@Test
	void editingChangesTheTimesAndNeverTheEmployee() {
		long id = attendance(this.employeeA, "2026-03-02 09:00:00", null, null);
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(id),
				// employee_id is posted and must be ignored: D-176(a). The
				// column that decides ownership is not reachable from an edit.
				"employee_id", String.valueOf(this.employeeB),
				"check_in", "2026-03-02 10:00:00", "check_out", "2026-03-02 18:00:00");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT employee_id, check_in, check_out FROM attendance WHERE id = " + id);
		assertThat(row.get("employee_id").toString())
				.as("an edit cannot move a row to another company's employee")
				.isEqualTo(String.valueOf(this.employeeA));
		assertThat(row.get("check_out")).isNotNull();
	}

	@Test
	void deletingRemovesOnlyThatRow() {
		long kept = attendance(this.employeeA, "2026-03-02 09:00:00", null, null);
		long removed = attendance(this.employeeA, "2026-03-03 09:00:00", null, null);
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(removed));
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject(
				"SELECT id FROM attendance", Long.class)).isEqualTo(kept);
	}

	@Test
	void aRangeDeleteClearsOneCompanysRowsAndLeavesTheOthers() {
		attendance(this.employeeA, "2026-03-02 09:00:00", null, null);
		attendance(this.employeeA, "2026-03-20 09:00:00", null, null);
		attendance(this.employeeA, "2026-04-02 09:00:00", null, null);
		attendance(this.employeeB, "2026-03-02 09:00:00", null, null);

		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "delete_range", "company_id", String.valueOf(this.companyA),
				"from", "2026-03-01", "to", "2026-03-31");

		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance a"
				+ " JOIN employees e ON e.id = a.employee_id WHERE e.company_id = " + this.companyA,
				Integer.class)).as("March cleared, April kept").isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance a"
				+ " JOIN employees e ON e.id = a.employee_id WHERE e.company_id = " + this.companyB,
				Integer.class)).as("another company is untouched").isEqualTo(1);
	}

	/** {@code str_replace('{count}', $deleted, __('att_range_deleted'))}, flashed as an error (D-253). */
	@Test
	void aRangeDeleteFlashesTheCountItRemovedAsAnError() {
		attendance(this.employeeA, "2026-03-02 09:00:00", null, null);
		attendance(this.employeeA, "2026-03-20 09:00:00", null, null);

		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "delete_range", "company_id", String.valueOf(this.companyA),
				"from", "2026-03-01", "to", "2026-03-31");

		assertThat(body(PATH)).contains("<div class=\"flash flash-error\">تم حذف 2 بصمة");
	}

	@Test
	void aReversedRangeDeletesNothing() {
		attendance(this.employeeA, "2026-03-02 09:00:00", null, null);
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "delete_range", "company_id", String.valueOf(this.companyA),
				"from", "2026-03-31", "to", "2026-03-01");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// Tenant guards (R-046 / R-059)
	// ------------------------------------------------------------------

	@Test
	void onlyActiveExceptionTypesAreOfferedAndNoneWithoutACompany() {
		// payroll_list_helper.php:118-128 offers only active types. With no
		// company chosen the port offers none: legacy's global list is what let an
		// exception type cross companies (R-059), and D-176(b) refuses one that does.
		long active = exceptionType(this.companyA, "Field mission");
		long inactive = exceptionType(this.companyA, "Retired leave kind");
		this.jdbc.update("UPDATE exception_types SET is_active = 0 WHERE id = ?", inactive);

		String scoped = selectMarkup(body("/admin/attendance?company_id=" + this.companyA),
				"<select id=\"exception_type_id\" name=\"exception_type_id\">");
		assertThat(scoped).as("the add form offers an active type")
				.contains("<option value=\"" + active + "\">Field mission</option>");
		assertThat(scoped).as("an inactive type is not")
				.doesNotContain("<option value=\"" + inactive + "\"");

		String unfiltered = selectMarkup(body("/admin/attendance?company_id="),
				"<select id=\"exception_type_id\" name=\"exception_type_id\">");
		assertThat(unfiltered).as("with no company chosen, no type is offered")
				.doesNotContain("<option value=\"" + active + "\"")
				.doesNotContain("<option value=\"" + inactive + "\"");
	}

	@Test
	void editingAPunchKeepsAnExceptionTypeRetiredSinceItWasSaved() {
		// The add form offers only active types. The edit dialog must still offer
		// a row's own type once it is retired: a select with no option for the
		// stored value submits nothing, and the save would clear the type. Legacy
		// loses it that way (attendance-form.js:196-198).
		long retired = exceptionType(this.companyA, "Field mission");
		long id = attendance(this.employeeA, "2026-03-02 09:00:00", null, retired);
		// A second retired type that no listed row carries, which is not offered as a new choice.
		long unused = exceptionType(this.companyA, "Old mission");
		this.jdbc.update("UPDATE exception_types SET is_active = 0 WHERE id IN (?, ?)", retired, unused);

		String html = body(PATH + range() + "&company_id=" + this.companyA);
		int dialog = html.indexOf("<dialog class=\"row-dialog\" id=\"attendance-edit\"");
		assertThat(dialog).as("the edit dialog renders").isPositive();
		assertThat(selectMarkup(html.substring(dialog), "<select name=\"exception_type_id\""))
				.as("the edit dialog offers the row's retired type")
				.contains("<option value=\"" + retired + "\" data-dialog-current-only=\"1\">Field mission");
		assertThat(selectMarkup(html.substring(dialog), "<select name=\"exception_type_id\""))
				.as("but not a retired type that no listed row carries")
				.doesNotContain("<option value=\"" + unused + "\"");
		assertThat(selectMarkup(html, "<select id=\"exception_type_id\" name=\"exception_type_id\">"))
				.as("the add form still does not")
				.doesNotContain("<option value=\"" + retired + "\"");

		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(id),
				"check_in", "2026-03-02 08:30:00", "check_out", "2026-03-02 18:00:00",
				"exception_type_id", String.valueOf(retired));
		assertThat(this.jdbc.queryForObject(
				"SELECT exception_type_id FROM attendance WHERE id = ?", Long.class, id))
				.as("saving what the dialog now submits keeps the type")
				.isEqualTo(retired);
	}

	@Test
	void withNoCompanyChosenEditingARowKeepsItsExceptionType() {
		// With no company chosen the table lists every company's rows, and a type
		// belongs to one company. The edit dialog then offers no type choice: it
		// carries the row's type back, so saving keeps it and no other company's
		// type is offered. Changing a type needs a company.
		long type = exceptionType(this.companyA, "Field mission");
		long other = exceptionType(this.companyB, "Beta only");
		long id = attendance(this.employeeA, "2026-03-02 09:00:00", null, type);
		attendance(this.employeeB, "2026-03-03 09:00:00", null, other);

		String html = body(PATH + range() + "&company_id=");
		int dialog = html.indexOf("<dialog class=\"row-dialog\" id=\"attendance-edit\"");
		assertThat(dialog).as("the edit dialog renders").isPositive();
		String markup = html.substring(dialog, html.indexOf("</dialog>", dialog));
		assertThat(markup).as("with no company chosen, the edit dialog offers no type to choose")
				.doesNotContain("<select name=\"exception_type_id\"")
				.doesNotContain("<option value=\"" + other + "\"");
		assertThat(markup).as("it carries the row's type back in a hidden field")
				.contains("<input type=\"hidden\" name=\"exception_type_id\" data-dialog-field=\"exception_type_id\">");
		assertThat(html).as("the row's Edit carries its type and the type's name")
				.contains("data-dialog-exception_type_id=\"" + type + "\"")
				.contains("data-dialog-exception_name=\"Field mission\"");
		assertThat(selectMarkup(html, "<select id=\"exception_type_id\" name=\"exception_type_id\">"))
				.as("the add form still offers none")
				.doesNotContain("<option value=\"" + type + "\"");

		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(id),
				"check_in", "2026-03-02 08:30:00", "check_out", "2026-03-02 18:00:00",
				"exception_type_id", String.valueOf(type));
		assertThat(this.jdbc.queryForObject(
				"SELECT exception_type_id FROM attendance WHERE id = ?", Long.class, id))
				.as("saving what the dialog carries keeps the type")
				.isEqualTo(type);
	}

	@Test
	void aRetiredTypeStaysWithTheRowsThatHaveIt() {
		// With a company chosen, a retired type that one listed row carries appears in
		// the shared edit dialog for every row. The save keeps it on the row that has
		// it and refuses it for any other row and for a new punch; the dialog marks
		// the option so row-dialog.js disables it for the other rows.
		long retired = exceptionType(this.companyA, "Field mission");
		long carrier = attendance(this.employeeA, "2026-03-02 09:00:00", null, retired);
		long other = attendance(this.employeeA, "2026-03-03 09:00:00", null, null);
		this.jdbc.update("UPDATE exception_types SET is_active = 0 WHERE id = ?", retired);

		assertThat(post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(other),
				"check_in", "2026-03-03 08:30:00", "check_out", "2026-03-03 18:00:00",
				"exception_type_id", String.valueOf(retired))
				.getHeaders().getLocation()).as("another row cannot take the retired type")
				.asString().contains("error=exception_type_inactive");
		assertThat(this.jdbc.queryForObject(
				"SELECT exception_type_id FROM attendance WHERE id = ?", Long.class, other))
				.as("and it still has none").isNull();

		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(carrier),
				"check_in", "2026-03-02 08:30:00", "check_out", "2026-03-02 18:00:00",
				"exception_type_id", String.valueOf(retired));
		assertThat(this.jdbc.queryForObject(
				"SELECT exception_type_id FROM attendance WHERE id = ?", Long.class, carrier))
				.as("the row that has it keeps it").isEqualTo(retired);

		int rows = this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class);
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "add_attendance", "employee_id", String.valueOf(this.employeeA),
				"check_in", "2026-03-04 09:00:00", "check_out", "2026-03-04 17:00:00",
				"exception_type_id", String.valueOf(retired));
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class))
				.as("a new punch cannot take the retired type").isEqualTo(rows);

		String html = body(PATH + range() + "&company_id=" + this.companyA);
		int dialog = html.indexOf("<dialog class=\"row-dialog\" id=\"attendance-edit\"");
		assertThat(dialog).as("the edit dialog renders").isPositive();
		assertThat(selectMarkup(html.substring(dialog), "<select name=\"exception_type_id\""))
				.as("the dialog marks the retired option for row-dialog.js")
				.contains("<option value=\"" + retired + "\" data-dialog-current-only=\"1\">Field mission");
	}

	@Test
	void anExceptionTypeFromAnotherCompanyIsRefusedOnEdit() {
		// D-176(b) in one case: exception_type_id IS editable, so it is checked
		// against the company of the row already stored, never against the
		// request. A type belonging to B cannot be written onto A's row.
		long id = attendance(this.employeeA, "2026-03-02 09:00:00", null, null);
		long foreign = exceptionType(this.companyB, "Beta only");

		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(id),
				"check_in", "2026-03-02 09:00:00",
				"exception_type_id", String.valueOf(foreign));

		assertThat(this.jdbc.queryForObject(
				"SELECT exception_type_id FROM attendance WHERE id = " + id, Long.class))
				.as("another company's exception type is refused").isNull();
	}

	@Test
	void anExceptionTypeFromTheRowsOwnCompanyIsAccepted() {
		long id = attendance(this.employeeA, "2026-03-02 09:00:00", null, null);
		long own = exceptionType(this.companyA, "Alpha only");

		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(id),
				"check_in", "2026-03-02 09:00:00",
				"exception_type_id", String.valueOf(own));

		assertThat(this.jdbc.queryForObject(
				"SELECT exception_type_id FROM attendance WHERE id = " + id, Long.class))
				.isEqualTo(own);
	}

	@Test
	void aWriteDoesNotMoveAnUnfilteredAdministratorsFilter() {
		// `payroll_redirect('attendance', $cid)` passes the filter already in
		// force, not the company just written to. Its sibling pages pass
		// `hr_post_company_id()` and do move the filter, so it would have been
		// easy to give this page the same behaviour by reflex. Adding a punch
		// for company A must leave an unfiltered administrator able to add one
		// for company B on the very next request.
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "add_attendance", "employee_id", String.valueOf(this.employeeA),
				"check_in", "2026-03-02 09:00:00");
		post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "add_attendance", "employee_id", String.valueOf(this.employeeB),
				"check_in", "2026-03-02 09:00:00");

		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance a"
				+ " JOIN employees e ON e.id = a.employee_id WHERE e.company_id = " + this.companyB,
				Integer.class))
				.as("R-044: the first write must not have narrowed the session to company A")
				.isEqualTo(1);
	}

	@Test
	void theSameRequestUnderTheSameFilterSucceedsOnTheSessionsOwnRow() {
		// The control for the two refusals below. Identical session state --
		// same filter, same action, same field set -- with the row's owner as
		// the only variable. Without it, a refusal could be coming from the
		// filter itself rather than from the ownership check.
		long own = attendance(this.employeeA, "2026-03-02 09:00:00", "2026-03-02 17:00:00", null);

		get(PATH + "?company_id=" + this.companyA, this.cookie);
		ResponseEntity<String> response = post(PATH, this.cookie,
				page(PATH + "?company_id=" + this.companyA, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(own),
				"check_in", "2026-03-02 03:00:00", "check_out", "2026-03-02 23:00:00");

		assertThat(response.getHeaders().getLocation()).asString().doesNotContain("error");
		assertThat(this.jdbc.queryForObject(
				"SELECT check_in FROM attendance WHERE id = " + own, String.class))
				.as("the edit landed").startsWith("2026-03-02 03:00");
	}

	@Test
	void editingAnotherCompanysRowIsRefusedAndLeavesItExactlyAsItWas() {
		// R-059's core: the id is the whole authorization in legacy before
		// 505004f. Filtering the session to A must not let A's operator reach
		// B's punch -- and attendance is what payroll is computed from, so a
		// half-applied edit would change what another company pays.
		long victim = attendance(this.employeeB, "2026-03-02 09:00:00", "2026-03-02 17:00:00", null);
		Map<String, Object> before = this.jdbc.queryForMap(
				"SELECT * FROM attendance WHERE id = " + victim);

		get(PATH + "?company_id=" + this.companyA, this.cookie);
		ResponseEntity<String> response = post(PATH, this.cookie,
				page(PATH + "?company_id=" + this.companyA, this.cookie).csrf(),
				"action", "edit_attendance", "id", String.valueOf(victim),
				"check_in", "2026-03-02 03:00:00", "check_out", "2026-03-02 23:00:00");

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForMap("SELECT * FROM attendance WHERE id = " + victim))
				.as("every column of the foreign row survives the refused edit")
				.isEqualTo(before);
	}

	@Test
	void deletingAnotherCompanysRowIsRefusedAndLeavesItExactlyAsItWas() {
		long victim = attendance(this.employeeB, "2026-03-02 09:00:00", "2026-03-02 17:00:00", null);
		Map<String, Object> before = this.jdbc.queryForMap(
				"SELECT * FROM attendance WHERE id = " + victim);

		get(PATH + "?company_id=" + this.companyA, this.cookie);
		ResponseEntity<String> response = post(PATH, this.cookie,
				page(PATH + "?company_id=" + this.companyA, this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(victim));

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForMap("SELECT * FROM attendance WHERE id = " + victim))
				.as("the foreign row is still there, unchanged")
				.isEqualTo(before);
	}

	@Test
	void addingAPunchAgainstAnotherCompanysEmployeeIsRefused() {
		// The insert side, which is what makes this more than another R-046
		// page: employee_id is attacker-chosen and it DECIDES the new row's
		// owner. There is no existing row to check, so the check is against
		// the employee the session is entitled to.
		get(PATH + "?company_id=" + this.companyA, this.cookie);
		ResponseEntity<String> response = post(PATH, this.cookie,
				page(PATH + "?company_id=" + this.companyA, this.cookie).csrf(),
				"action", "add_attendance", "employee_id", String.valueOf(this.employeeB),
				"check_in", "2026-03-02 09:00:00", "check_out", "2026-03-02 17:00:00");

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class))
				.as("nothing was written against the other company's employee").isZero();
	}

	@Test
	void anUnknownRowIsRefusedRatherThanSilentlyDoingNothing() {
		ResponseEntity<String> response = post(PATH, this.cookie, page(PATH, this.cookie).csrf(),
				"action", "delete", "id", "987654");
		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
	}

	// ------------------------------------------------------------------
	// Parity fixtures: the three places this page deliberately differs
	// ------------------------------------------------------------------

	@Test
	void theAggregateHoursAreRawSqlAndTheDetailHoursAreTheEngine() {
		// One punch, deliberately open-ended in the middle of a shift the
		// worked-minutes engine has an opinion about. The aggregate sums raw
		// TIMESTAMPDIFF; the detail list asks the engine. Both are read here so
		// the two paths are exercised against the same row, and a refactor that
		// pointed one at the other would have to change this file.
		attendance(this.employeeA, "2026-03-02 09:00:00", "2026-03-02 17:00:00", null);

		var filters = filtersFor(this.companyA);
		var detail = inRequestScope(() ->
				this.store.paginateDetail(filters, "2026-03-01", "2026-03-31", "Weekly rest"));
		var aggregate = inRequestScope(() ->
				this.store.aggregate(filters, "2026-03-01", "2026-03-31", 1, 0, "2026-03-31"));

		assertThat(aggregate.data()).isNotEmpty();
		assertThat(aggregate.data().get(0).totalMinutes())
				.as("the aggregate reports the raw 480, whatever the engine says")
				.isEqualTo(480L);
		assertThat(detail.data()).isNotEmpty();
	}

	@Test
	void theHolidayCreditIsTheDashboardCountNotThePayrollWorkingCredit() {
		// The dashboard helper counts holidays in range the employee did not
		// attend. The API helper weighs working days and is suppressed below a
		// coverage floor, so for one holiday and no attendance the two do not
		// agree -- this pins the dashboard's answer.
		this.jdbc.update("INSERT INTO company_official_holidays (company_id, name, holiday_date,"
				+ " created_at) VALUES (?, 'Founders Day', '2026-03-10', NOW())", this.companyA);

		assertThat(this.store.officialHolidayCreditForEmployee(
				this.companyA, this.employeeA, "2026-03-01", "2026-03-31"))
				.as("one holiday in range, unattended").isEqualTo(1);

		// Attending it removes the credit, which is the whole of the rule.
		attendance(this.employeeA, "2026-03-10 09:00:00", "2026-03-10 17:00:00", null);
		assertThat(this.store.officialHolidayCreditForEmployee(
				this.companyA, this.employeeA, "2026-03-01", "2026-03-31"))
				.as("attended, so no credit").isZero();
	}

	@Test
	void absenceUsesThePeriodLengthWithNoCompanyAndExpectedWorkdaysWithOne() {
		// The two arms of the dashboard's own formula. Neither is
		// attendanceDisplay's: there is no as-of cap and void weekly-rest days
		// are not added.
		attendance(this.employeeA, "2026-03-02 09:00:00", "2026-03-02 17:00:00", null);
		var filters = filtersFor(this.companyA);

		var scoped = inRequestScope(() ->
				this.store.aggregate(filters, "2026-03-01", "2026-03-07", 1, 0, "2026-03-31"));
		assertThat(scoped.data()).isNotEmpty();
		assertThat(scoped.data().get(0).daysInPeriod()).isEqualTo(7);
		assertThat(scoped.data().get(0).presentDays()).isEqualTo(1);
		assertThat(scoped.data().get(0).absentDays())
				.as("expected workdays minus present, floored at zero")
				.isGreaterThanOrEqualTo(0);

		var unscoped = filtersFor(0);
		var all = inRequestScope(() ->
				this.store.aggregate(unscoped, "2026-03-01", "2026-03-07", 1, 0, "2026-03-31"));
		assertThat(all.data()).as("with no company the period length is the fallback").isNotEmpty();
	}

	@Test
	void thePickerListsEveryActiveEmployeeUnderLegacysLabels() {
		long company = createCompany("Picker Co");
		long employee = createEmployee(company, "PK1", "Pia", "Kerr");
		insertActiveEmployees(company, 520);
		long active = this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM employees WHERE is_active = 1", Long.class);

		String page = body("/admin/attendance");
		assertThat(page).as("the add form picks an employee by search, not from a select")
				.doesNotContain("<select id=\"employee_id\"");
		Map<Long, String> everyone = pickerLabels(page);
		assertThat(everyone).as("no company chosen: every active employee").hasSize((int) active);
		assertThat(everyone.get(employee)).as("a list across companies names the company")
				.isEqualTo("Pia Kerr (PK1) — Picker Co");
		assertThat(pickerLabels(body("/admin/attendance?company_id=" + company)).get(employee))
				.as("one company chosen: the label leaves it out")
				.isEqualTo("Pia Kerr (PK1)");
	}

	@Test
	void anEmployeeWithNoNameIsListedByCodeAlone() {
		long company = createCompany("Nameless Co");
		long employee = createEmployee(company, "NN1", "", "");

		assertThat(pickerLabels(body("/admin/attendance?company_id=" + company)).get(employee))
				.as("one company chosen: the code alone, as the attendance list showed before the picker")
				.isEqualTo("NN1");
		// ?company_id= clears the company the dashboard remembers from the request above.
		assertThat(pickerLabels(body("/admin/attendance?company_id=")).get(employee))
				.as("across companies: the code, then the company")
				.isEqualTo("NN1 — Nameless Co");
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
