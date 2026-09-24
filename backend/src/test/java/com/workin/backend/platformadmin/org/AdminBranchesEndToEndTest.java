package com.workin.backend.platformadmin.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * {@code /admin/branches} over real HTTP against a real MariaDB.
 *
 * <p>The first org page, so this covers the machinery the other three inherit
 * as well as the page itself: the session company filter, the pagination
 * shape, the write gates, and the tenant check on a row id from a form post.
 *
 * <p>Weighted towards what a fresh implementation gets wrong. The list is easy
 * and is not what breaks; the filter surviving a request that does not mention
 * it, an out-of-range page reporting the last page with no rows, and a
 * generated code refusing an expiry in the past are.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminBranchesEndToEndTest {

	/** A database of this class's own, inside the shared container. */
	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String PASSWORD = "correct horse battery staple";

	private static final Pattern CSRF = Pattern.compile("name=\"([^\"]*_csrf[^\"]*)\" value=\"([^\"]+)\"");

	private static final DateTimeFormatter LOCAL =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

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

	/**
	 * The clock the page dates from, which is the database's offset and not the
	 * JVM's. It is request-scoped, so a test reaching it has to stand a request
	 * up around the call; {@link #legacyNow()} does.
	 */
	@Autowired
	private com.workin.legacy.LegacyClock clock;

	/** {@code LegacyClock.now()} from outside a request, which is where tests are. */
	private java.time.LocalDateTime legacyNow() {
		org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
				new org.springframework.web.context.request.ServletRequestAttributes(
						new org.springframework.mock.web.MockHttpServletRequest()));
		try {
			return this.clock.now();
		}
		finally {
			org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
		}
	}

	private JdbcTemplate jdbc;

	private String cookie;

	private long companyA;

	private long companyB;

	@BeforeEach
	void signIn() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
		this.jdbc = new JdbcTemplate(this.legacyDataSource);
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
	}

	@Test
	void theListShowsEveryCompanysBranchesUntilTheFilterIsSet() {
		seedBranch(this.companyA, "Alpha HQ");
		seedBranch(this.companyB, "Beta HQ");

		String all = body("/admin/branches");
		assertThat(all).contains("Alpha HQ").contains("Beta HQ");
		assertThat(all).as("the company column appears only when unfiltered").contains("Alpha Co");

		// Setting the filter narrows it, and -- the part a stateless reading
		// gets wrong -- a later request that does not mention company_id keeps
		// it rather than resetting to everything.
		assertThat(body("/admin/branches?company_id=" + this.companyA))
				.contains("Alpha HQ").doesNotContain("Beta HQ");
		assertThat(body("/admin/branches"))
				.as("the filter outlives the request that set it")
				.contains("Alpha HQ").doesNotContain("Beta HQ");

		// And an empty value clears it. isset() would treat this as absent and
		// make "show me everything again" unreachable.
		assertThat(body("/admin/branches?company_id="))
				.contains("Alpha HQ").contains("Beta HQ");
	}

	@Test
	void theSearchMatchesNameAndAddressAndTheStatusFilterNarrows() {
		seedBranch(this.companyA, "Cairo Office", "Nasr City", true);
		seedBranch(this.companyA, "Giza Office", "Dokki", false);

		assertThat(body("/admin/branches?search=Cairo"))
				.contains("Cairo Office").doesNotContain("Giza Office");
		// The address half of the OR, which a name-only search would miss.
		assertThat(body("/admin/branches?search=Dokki"))
				.contains("Giza Office").doesNotContain("Cairo Office");
		assertThat(body("/admin/branches?filter=inactive"))
				.contains("Giza Office").doesNotContain("Cairo Office");
		// An unrecognised filter value behaves as `all`, not as an error.
		assertThat(body("/admin/branches?filter=sideways"))
				.contains("Cairo Office").contains("Giza Office");
	}

	@Test
	void anActiveBranchShowsAGreenActiveBadgeAndASuspendedOneAGreySuspendedBadge() {
		// branches/page.php:174: badge(!empty($row['is_active']) ? 'active' : 'suspended').
		seedBranch(this.companyA, "Cairo Office", "Nasr City", true);
		seedBranch(this.companyA, "Giza Office", "Dokki", false);

		String html = body("/admin/branches?lang=en");
		assertThat(row(html, "Cairo Office")).containsPattern("<span class=\"badge badge-green\">Active</span>");
		assertThat(row(html, "Giza Office")).containsPattern("<span class=\"badge badge-gray\">Suspended</span>");
	}

	private static String row(String html, String name) {
		return java.util.regex.Pattern.compile("(?s)<tr\\b[^>]*>(.*?)</tr>").matcher(html).results()
				.map(match -> match.group(1)).filter(cells -> cells.contains(">" + name + "<")).findFirst()
				.orElseThrow(() -> new AssertionError("no row for " + name));
	}

	@Test
	void anOutOfRangePageReportsTheLastPageAndShowsNothing() {
		for (int index = 10; index < 22; index++) {
			seedBranch(this.companyA, "Branch " + index);
		}
		assertThat(body("/admin/branches?per_page=10")).contains("Branch 21");

		// dbPaginate() clamps the page number after taking the offset, so this
		// is the legacy behaviour: the pager says the last page, the table is
		// empty.
		assertThat(body("/admin/branches?per_page=10&page=99"))
				.contains("data-table-empty").doesNotContain("Branch 21");
	}

	@Test
	void theEmptyStateRendersRatherThanBreaking() {
		// The label resolves, so asserting on the key would pass only while the
		// translation was missing. The empty row's own class is the stable mark.
		assertThat(body("/admin/branches")).contains("data-table-empty");
	}

	@Test
	void everyPageRendersAUsableCsrfToken() {
		// Not a formality. The token comes from an advice rather than from each
		// controller because four pages had already forgotten to expose it, and
		// the symptom -- an empty value in the hidden field -- is a 403 on
		// submit that reads as a permissions problem.
		//
		// A row is seeded first: an empty list renders no form at all, so
		// checking it would prove nothing either way.
		seedBranch(this.companyA, "Has A Form");
		for (String path : List.of("/admin/branches", "/admin/branches?action=add",
				"/admin/faqs", "/admin/banners", "/admin/notifications", "/admin/phone_countries")) {
			Matcher matcher = CSRF.matcher(body(path));
			assertThat(matcher.find()).as("a token on %s", path).isTrue();
			assertThat(matcher.group(2)).as("a non-empty token on %s", path).isNotEmpty();
		}
	}

	@Test
	void addingABranchWritesItAndLeavesTheFilterOnItsCompany() {
		Page form = page("/admin/branches?action=add", this.cookie);
		assertThat(post("/admin/branches", this.cookie, form.csrf(),
				"action", "add", "company_id", String.valueOf(this.companyB),
				"name", "New Branch", "address", "Somewhere",
				"lat", "30.044", "lng", "31.235", "radius_meters", "150")
				.getStatusCode()).isEqualTo(HttpStatus.FOUND);

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, name, address, radius_meters, is_active"
						+ " FROM branches WHERE name = 'New Branch'");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyB));
		assertThat(row.get("address")).isEqualTo("Somewhere");
		assertThat(((Number) row.get("radius_meters")).intValue()).isEqualTo(150);
		// tinyint(1) comes back from MariaDB as a Boolean, not a Number.
		assertThat(row.get("is_active")).as("a new branch is active").isEqualTo(Boolean.TRUE);

		// org_redirect() sets the filter to the company just written, so the
		// operator lands looking at what they made rather than at everything.
		assertThat(body("/admin/branches")).contains("New Branch").doesNotContain("Alpha HQ");
	}

	@Test
	void anAddWithNoCompanyIsRefusedWithLegacysOwnMessage() {
		Page form = page("/admin/branches?action=add", this.cookie);
		ResponseEntity<String> refused = post("/admin/branches", this.cookie, form.csrf(),
				"action", "add", "company_id", "0", "name", "Nowhere");

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(refused.getHeaders().getLocation()).asString()
				.as("back to the form they were in, not to the list")
				.contains("action=add").contains("error=select_company_first");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM branches", Integer.class)).isZero();
	}

	@Test
	void theRadiusIsClampedNotRejected() {
		post("/admin/branches", this.cookie, page("/admin/branches?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Tiny",
				"radius_meters", "0");
		post("/admin/branches", this.cookie, page("/admin/branches?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Huge",
				"radius_meters", "999999");

		assertThat(this.jdbc.queryForObject(
				"SELECT radius_meters FROM branches WHERE name = 'Tiny'", Integer.class))
				.as("below 1 becomes the 200 m default").isEqualTo(200);
		assertThat(this.jdbc.queryForObject(
				"SELECT radius_meters FROM branches WHERE name = 'Huge'", Integer.class))
				.as("above 5 km is capped, not refused").isEqualTo(5000);
	}

	@Test
	void aNonNumericCoordinateIsStoredAsNullRatherThanZero() {
		// Zero would put the branch in the Gulf of Guinea and make
		// hasCoordinates() true, so the map link would appear and be wrong.
		post("/admin/branches", this.cookie, page("/admin/branches?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "No Fix",
				"lat", "not a number", "lng", "");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT latitude, longitude FROM branches WHERE name = 'No Fix'");
		assertThat(row.get("latitude")).isNull();
		assertThat(row.get("longitude")).isNull();
		assertThat(body("/admin/branches"))
				.as("no coordinates means no map link, rather than one pointing at 0,0")
				.doesNotContain("branch-map-link");
	}

	@Test
	void editingKeepsTheBranchInItsCompany() {
		long id = seedBranch(this.companyA, "Original");
		Page form = page("/admin/branches?action=edit&id=" + id, this.cookie);
		assertThat(form.response().getBody()).contains("Original");

		post("/admin/branches", this.cookie, form.csrf(), "action", "save_edit",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyB),
				"name", "Renamed", "radius_meters", "300", "is_active", "1");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, name FROM branches WHERE id = " + id);
		assertThat(row.get("name")).isEqualTo("Renamed");
		assertThat(row.get("company_id").toString())
				.as("company_id is not among the updated columns, so a posted one cannot move it")
				.isEqualTo(String.valueOf(this.companyA));
	}

	/**
	 * A save that changes nothing, and a delete of a branch already inactive, still flash their
	 * success (D-253). Legacy flashes {@code error_required} for both, because its {@code dbUpdate()}
	 * counts changed rows; this connection counts matched ones ({@code LegacyRowCountStartupCheck}),
	 * and the row is in the state asked for.
	 */
	@Test
	void anUnchangedSaveAndARepeatDeleteStillFlashTheirSuccess() {
		long id = seedBranch(this.companyA, "Steady");
		for (int round = 1; round <= 2; round++) {
			assertThat(post("/admin/branches", this.cookie, page("/admin/branches?action=edit&id=" + id, this.cookie).csrf(),
					"action", "save_edit", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
					"name", "Steady", "address", "Same", "radius_meters", "300", "is_active", "1")
					.getHeaders().getLocation()).as("save %d", round).asString().doesNotContain("action=edit");
			assertThat(body("/admin/branches")).as("save %d", round).contains("<div class=\"flash flash-success\">تم الحفظ بنجاح ✓</div>");
		}
		for (int round = 1; round <= 2; round++) {
			post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
					"action", "delete", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA));
			assertThat(body("/admin/branches")).as("delete %d", round).contains("<div class=\"flash flash-error\">تم الحذف</div>");
		}
	}

	@Test
	void deleteDeactivatesRatherThanRemoving() {
		long id = seedBranch(this.companyA, "Closing");
		post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM branches WHERE id = " + id, Integer.class))
				.as("employees and attendance rows point at it; a hard delete would orphan them")
				.isEqualTo(1);
		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM branches WHERE id = " + id, Integer.class)).isZero();
	}

	/**
	 * Legacy's {@code flash()}: a write leaves its message for the page it returns to, shown
	 * once, and a delete's as an error (D-253). A refused write leaves none.
	 */
	@Test
	void aWriteFlashesLegacysMessageOnceOnThePageItReturnsTo() {
		Page form = page("/admin/branches?action=add", this.cookie);
		assertThat(post("/admin/branches", this.cookie, form.csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA),
				"name", "Flashed", "address", "Somewhere",
				"lat", "30.044", "lng", "31.235", "radius_meters", "150")
				.getHeaders().getLocation()).asString().doesNotContain("error");

		assertThat(body("/admin/branches")).contains("<div class=\"flash flash-success\">تم الحفظ بنجاح ✓</div>");
		assertThat(body("/admin/branches")).as("shown once, then gone").doesNotContain("flash-success");

		long id = this.jdbc.queryForObject("SELECT id FROM branches WHERE name = 'Flashed'", Long.class);
		post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id), "company_id", String.valueOf(this.companyA));
		assertThat(body("/admin/branches")).contains("<div class=\"flash flash-error\">تم الحذف</div>");

		// A refusal the remembered filter cannot turn into a success: an expiry in the past.
		long coded = seedBranch(this.companyA, "Coded");
		java.net.URI refused = post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "generate_qr", "id", String.valueOf(coded), "company_id", String.valueOf(this.companyA),
				"expires_at", "2020-01-01T00:00").getHeaders().getLocation();
		assertThat(refused).asString().contains("error=branch_qr_invalid_expiry");
		assertThat(body(refused.getRawPath() + "?" + refused.getRawQuery()))
				.as("a refusal shows its error and flashes nothing")
				.contains("flash-error").doesNotContain("flash-success");
	}

	@Test
	void generatingACodeStoresThirtyTwoHexCharactersAndItsExpiry() {
		long id = seedBranch(this.companyA, "Coded");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);
		assertThat(qr.response().getBody())
				.as("no code yet, so no image tag to a third party")
				.doesNotContain("api.qrserver.com");

		assertThat(post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", LocalDateTime.now().plusDays(1).format(LOCAL))
				.getHeaders().getLocation()).asString()
				.as("back to the QR panel, so the operator sees the code").contains("action=qr");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT qr_code, expires_at FROM branches WHERE id = " + id);
		assertThat((String) row.get("qr_code")).matches("[0-9a-f]{32}");
		assertThat(row.get("expires_at")).isNotNull();
		assertThat(body("/admin/branches?action=qr&id=" + id))
				.as("the panel now renders the code through legacy's own third-party renderer")
				.contains("api.qrserver.com");
	}

	@Test
	void anExpiryInThePastIsRefusedAndNothingIsWritten() {
		// A code that is already expired is indistinguishable from no code, so
		// generating one would look like it worked and do nothing.
		long id = seedBranch(this.companyA, "Stale");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);
		assertThat(post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", "2020-01-01T00:00").getHeaders().getLocation()).asString()
				.contains("error=branch_qr_invalid_expiry");
		assertThat(this.jdbc.queryForObject(
				"SELECT qr_code FROM branches WHERE id = " + id, String.class)).isNull();
	}

	@Test
	void anUnparseableExpiryIsRefusedRatherThanBecomingNow() {
		long id = seedBranch(this.companyA, "Garbled");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);
		assertThat(post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", "whenever").getHeaders().getLocation()).asString()
				.contains("error=branch_qr_invalid_expiry");
		assertThat(this.jdbc.queryForObject(
				"SELECT qr_code FROM branches WHERE id = " + id, String.class)).isNull();
	}

	@Test
	void aCodeForABranchOfAnotherCompanyIsRefused() {
		// generate_qr runs org_assert_company_row() itself, unconditionally --
		// unlike the write actions, which skip the check for an administrator.
		long id = seedBranch(this.companyB, "Beta Branch");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);

		assertThat(post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", LocalDateTime.now().plusDays(1).format(LOCAL))
				.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(this.jdbc.queryForObject(
				"SELECT qr_code FROM branches WHERE id = " + id, String.class)).isNull();
	}

	/** {@code org_branch_table_row_actions()} (org_helper.php:834-858): Edit, Delete, QR. */
	@Test
	void theRowActionsOfferEditThenDeleteThenQrAsLegacyOrders() {
		seedBranch(this.companyA, "Ordered");
		String actions = row(body("/admin/branches"), "Ordered");
		int edit = actions.indexOf("action=edit");
		int delete = actions.indexOf("value=\"delete\"");
		int qr = actions.indexOf("action=qr");
		assertThat(edit).as("an edit link").isGreaterThanOrEqualTo(0);
		assertThat(delete).as("delete after edit, as legacy orders them").isGreaterThan(edit);
		assertThat(qr).as("qr last, after delete").isGreaterThan(delete);
	}

	/**
	 * {@code _branch_qr_modal.php}: {@code modal-bg open > modal modal--org-form modal--branch-qr},
	 * not the data-table-card the port had drawn inline in the page flow.
	 */
	@Test
	void theQrPanelIsLegacysModalNotAnInlineCard() {
		long id = seedBranch(this.companyA, "Modalled");
		String html = body("/admin/branches?action=qr&id=" + id);
		assertThat(html)
				.contains("<div class=\"modal-bg open\">")
				.contains("<div class=\"modal modal--org-form modal--branch-qr\" role=\"dialog\" aria-modal=\"true\"")
				.contains("aria-labelledby=\"br-qr-title\"")
				.contains("<h2 id=\"br-qr-title\">")
				.contains("<p class=\"branch-qr-branch-name\">Modalled</p>");

		// Bounded to the modal's own form: the page's filter bar carries a btn-blue submit of
		// its own, so an unbounded contains() passes whatever colour the modal's button is.
		int form = html.indexOf("branch-qr-form");
		assertThat(html.substring(form, html.indexOf("</form>", form)))
				.as("legacy's own submit variant (_branch_qr_modal.php:40), not the port's yellow")
				.contains("<button type=\"submit\" class=\"btn btn-blue\">");
	}

	/**
	 * Legacy's first arm is {@code $qrActive && $qrImage !== ''} (`_branch_qr_modal.php:12`),
	 * and {@code org_branch_qr_image_url()} returns {@code ''} for a code that trims to
	 * nothing. {@code qrActive} alone is {@code empty()}-based, so a blank-but-not-empty code
	 * is active to it -- the port rendered the active block with {@code <img src="">}, which a
	 * browser resolves to the page itself and re-requests, where legacy renders "expired".
	 * Only a hand-edited row reaches it, which is the same standard {@code Branch.qrActive}
	 * holds for a code of {@code "0"}.
	 */
	@Test
	void aBlankCodeIsExpiredAsLegacyRendersIt() {
		long id = seedBranch(this.companyA, "Blank Code");
		this.jdbc.update("UPDATE branches SET qr_code = ?, expires_at = ? WHERE id = ?",
				"   ", legacyNow().plusDays(1).format(LOCAL).replace('T', ' '), id);

		String html = body("/admin/branches?action=qr&id=" + id);
		assertThat(html).as("legacy's second arm: a code that is there but renders nothing")
				.contains("branch-qr-status--expired")
				.doesNotContain("branch-qr-status--active")
				.doesNotContain("<img src=\"\"");
	}

	/**
	 * The active block's order (`_branch_qr_modal.php:96-105`): status, image, then meta in
	 * {@code <strong dir="ltr">}. The port had the meta before the image and no {@code dir="ltr"}.
	 */
	@Test
	void theActiveQrBlockOrdersStatusImageThenMetaWithLtrExpiry() {
		long id = seedBranch(this.companyA, "Coded Order");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);
		post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA),
				"expires_at", LocalDateTime.now().plusDays(1).format(LOCAL));

		String html = body("/admin/branches?action=qr&id=" + id);
		int status = html.indexOf("branch-qr-status--active");
		int image = html.indexOf("branch-qr-image");
		int meta = html.indexOf("branch-qr-meta");
		int strong = html.indexOf("<strong dir=\"ltr\">");
		assertThat(status).as("an active status").isGreaterThanOrEqualTo(0);
		assertThat(image).as("the image after the status").isGreaterThan(status);
		assertThat(meta).as("the meta after the image").isGreaterThan(image);
		assertThat(strong).as("the expiry in an ltr strong, inside the meta").isGreaterThan(meta);
	}

	/**
	 * {@code org_branch_qr_expires_input_value()} (org_helper.php:782-788): the current expiry
	 * while a code is active, or today at 23:59 otherwise. The port left the field blank.
	 */
	@Test
	void theQrExpiryFieldIsPrefilledAsLegacyPrefillsIt() {
		long freshId = seedBranch(this.companyA, "Fresh");
		// The page dates this from LegacyClock, which is the database's offset, not the
		// JVM's: with the JVM in UTC and legacy at UTC+2 these disagree from 22:00 UTC,
		// and the test would fail for two hours a day (#305's review round 1, Codex).
		String today = legacyNow().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
		assertThat(body("/admin/branches?action=qr&id=" + freshId))
				.contains("id=\"br_qr_expires\"")
				.contains("value=\"" + today + "T23:59\"");

		long id = seedBranch(this.companyA, "Coded Prefill");
		Page qr = page("/admin/branches?action=qr&id=" + id, this.cookie);
		String expiry = legacyNow().plusDays(1).format(LOCAL);
		post("/admin/branches", this.cookie, qr.csrf(), "action", "generate_qr",
				"id", String.valueOf(id), "company_id", String.valueOf(this.companyA), "expires_at", expiry);
		assertThat(body("/admin/branches?action=qr&id=" + id)).contains("value=\"" + expiry + "\"");
	}

	/** {@code _branch_form.php}: legacy's {@code br_*} input ids, and {@code dir="ltr"} on coordinates. */
	@Test
	void theFormFieldsCarryLegacysBrIdsAndTheCoordinatesAndRadiusAreLtr() {
		String html = body("/admin/branches?action=add");
		assertThat(html)
				.contains("for=\"br_name\"").contains("id=\"br_name\"")
				.contains("for=\"br_address\"").contains("id=\"br_address\"")
				.contains("for=\"br_lat\"").contains("id=\"br_lat\" name=\"lat\" dir=\"ltr\"")
				.contains("for=\"br_lng\"").contains("id=\"br_lng\" name=\"lng\" dir=\"ltr\"")
				.contains("for=\"br_radius\"")
				.contains("id=\"br_radius\" name=\"radius_meters\" min=\"1\" max=\"5000\" step=\"1\"")
				.contains("dir=\"ltr\" inputmode=\"numeric\"");
	}

	@Test
	void theEditFormRefusesARowOutsideTheCurrentFilter() {
		long id = seedBranch(this.companyB, "Beta Only");
		body("/admin/branches?company_id=" + this.companyA);

		// Filtered to Alpha, an edit link for a Beta branch shows no form --
		// rather than silently editing a company the operator is not looking at.
		assertThat(body("/admin/branches?action=edit&id=" + id)).doesNotContain("Beta Only");
		// Unfiltered, it is reachable: that is what "all companies" means.
		body("/admin/branches?company_id=");
		assertThat(body("/admin/branches?action=edit&id=" + id)).contains("Beta Only");
	}

	/**
	 * When the administrator writes across companies, the audit row says whose row
	 * it was -- not where the operator was standing.
	 *
	 * <p>`assertWritable` resolves the company a write is made *against*, and for
	 * an unscoped administrator that is the posted `company_id`, checked against
	 * nothing: R-061 records that as ruled-on parity, because a platform
	 * administrator is cross-company by design. The write is therefore correct and
	 * is deliberately left alone here. What was wrong is what it recorded. Posting
	 * company A while editing a branch of company B wrote "branch updated in
	 * company A" -- an entry that names the wrong company and reads as
	 * authoritative, which is worse than one that names none.
	 *
	 * <p>The one case an auditor most needs to find was the one case the record
	 * hid, so it now names the owner and says the administrator posted the other.
	 */
	@Test
	void anAdministratorsCrossCompanyEditIsAuditedAgainstTheRowsOwnCompany() {
		long beta = seedBranch(this.companyB, "Beta Owned");
		// The scenario is an UNSCOPED administrator, so say so rather than
		// inheriting whatever company the session was last filtered to: with the
		// scope still on B, assertWritable ignores the posted field entirely and
		// there is no cross-company edit to audit. That is how this test first
		// failed.
		body("/admin/branches?company_id=");

		post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(beta),
				"company_id", String.valueOf(this.companyA),
				"name", "Beta Renamed", "is_active", "1");

		assertThat(this.jdbc.queryForObject(
				"SELECT name FROM branches WHERE id = ?", String.class, beta))
				.as("the write itself is unchanged: R-061 rules this parity, and this test does "
						+ "not relitigate it")
				.isEqualTo("Beta Renamed");
		assertThat(this.jdbc.queryForObject(
				"SELECT company_id FROM branches WHERE id = ?", Long.class, beta))
				.as("and it stayed company B's row")
				.isEqualTo(this.companyB);

		String detail = this.jdbc.queryForObject(
				"SELECT detail FROM platform_admin_audit_events WHERE target_type = 'branch'"
						+ " AND target_id = ? ORDER BY id DESC LIMIT 1",
				String.class, String.valueOf(beta));
		assertThat(detail)
				.as("the affected company is the row's owner")
				.contains("in company " + this.companyB);
		assertThat(detail)
				.as("and the posted company is named as the administrator's, not as the subject")
				.contains("the administrator posted company " + this.companyA);
	}

	/** The ordinary case is unchanged: one company, one number, no parenthetical. */
	@Test
	void anEditWithinOneCompanyRecordsThatCompanyPlainly() {
		long alpha = seedBranch(this.companyA, "Alpha Owned");

		post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(alpha),
				"company_id", String.valueOf(this.companyA),
				"name", "Alpha Renamed", "is_active", "1");

		String detail = this.jdbc.queryForObject(
				"SELECT detail FROM platform_admin_audit_events WHERE target_type = 'branch'"
						+ " AND target_id = ? ORDER BY id DESC LIMIT 1",
				String.class, String.valueOf(alpha));
		assertThat(detail).isEqualTo("branch updated in company " + this.companyA);
		assertThat(detail)
				.as("nothing is added when there is nothing to disambiguate")
				.doesNotContain("administrator posted");
	}

	/**
	 * The same for a delete, which is a different audit call carrying a different
	 * event type -- and one that can only name the owner because the delete is
	 * soft.
	 *
	 * <p>{@code deactivate} sets {@code is_active = 0} and leaves the row, so the
	 * owner is still there to resolve after the write. A hard delete would find
	 * nothing, fall back silently to the posted company, and restore exactly the
	 * defect this fixes -- which is why the surviving row is asserted here and not
	 * taken for granted.
	 */
	@Test
	void anAdministratorsCrossCompanyDeleteIsAuditedAgainstTheRowsOwnCompany() {
		long beta = seedBranch(this.companyB, "Beta Closing");
		// The scenario is an UNSCOPED administrator, so say so rather than
		// inheriting whatever company the session was last filtered to: with the
		// scope still on B, assertWritable ignores the posted field entirely and
		// there is no cross-company write to audit.
		body("/admin/branches?company_id=");

		post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(beta),
				"company_id", String.valueOf(this.companyA));

		assertThat(this.jdbc.queryForObject(
				"SELECT is_active FROM branches WHERE id = ?", Integer.class, beta))
				.as("deactivated rather than removed -- the row the audit resolves its owner from")
				.isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT company_id FROM branches WHERE id = ?", Long.class, beta))
				.as("and it stayed company B's row")
				.isEqualTo(this.companyB);

		String detail = this.jdbc.queryForObject(
				"SELECT detail FROM platform_admin_audit_events WHERE target_type = 'branch'"
						+ " AND target_id = ? ORDER BY id DESC LIMIT 1",
				String.class, String.valueOf(beta));
		assertThat(detail)
				.as("the affected company is the row's owner")
				.contains("in company " + this.companyB);
		assertThat(detail)
				.as("and the posted company is named as the administrator's, not as the subject")
				.contains("the administrator posted company " + this.companyA);
	}

	@Test
	void everyWriteLeavesAnAuditRow() {
		post("/admin/branches", this.cookie, page("/admin/branches?action=add", this.cookie).csrf(),
				"action", "add", "company_id", String.valueOf(this.companyA), "name", "Audited");
		long id = this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE name = 'Audited'", Long.class);
		post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(id),
				"company_id", String.valueOf(this.companyA));

		List<Map<String, Object>> events = this.jdbc.queryForList(
				"SELECT event_type, target_type, target_id FROM platform_admin_audit_events"
						+ " WHERE target_type = 'branch' ORDER BY id");
		assertThat(events).hasSize(2);
		assertThat(events.get(0).get("event_type")).isEqualTo("ORG_CREATED");
		assertThat(events.get(1).get("event_type")).isEqualTo("ORG_DELETED");
		assertThat(events.get(1).get("target_id")).isEqualTo(String.valueOf(id));
	}

	@Test
	void anAnonymousRequestNeverReachesThePage() {
		ResponseEntity<String> response = this.restTemplate.exchange(
				"/admin/branches", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).asString().contains("/admin/login");
	}

	private record Csrf(String name, String value) {
	}

	private record Page(ResponseEntity<String> response, String cookie, Csrf csrf) {
	}

	@Test
	void anAddWithNoCompanyChosenAsksWhichActiveCompany() {
		long suspended = createCompany("Zeta Suspended");
		this.jdbc.update("UPDATE companies SET status = 'suspended' WHERE id = ?", suspended);

		String form = body("/admin/branches?action=add&company_id=");
		Matcher select = Pattern.compile(
				"<select name=\"company_id\" id=\"br_add_company\" required>(.*?)</select>", Pattern.DOTALL).matcher(form);
		assertThat(select.find()).as("with no company chosen, the add form asks for one").isTrue();
		assertThat(select.group(1))
				.as("every active company, and no other")
				.contains("<option value=\"" + this.companyA + "\">Alpha Co</option>")
				.contains("<option value=\"" + this.companyB + "\">Beta Co</option>")
				.doesNotContain("value=\"" + suspended + "\"");
		assertThat(form).as("instead of posting a company of 0").doesNotContain("name=\"company_id\" value=\"0\"");

		assertThat(addForm(body("/admin/branches?action=add&company_id=" + this.companyA), "add"))
				.as("the add form on a page already filtered to a company keeps that company, hidden")
				.doesNotContain("<select name=\"company_id\"")
				.contains("<input type=\"hidden\" name=\"company_id\" value=\"" + this.companyA + "\">");
	}

	@Test
	void theToolbarNamesTheCompanyFromAListOfActiveCompanies() {
		// D-252, org_render_admin_company_filter_field(): a select of the active companies,
		// "All companies" first, with the filtered company chosen, where the port had a number
		// box an administrator typed a company id into.
		long suspended = createCompany("Zeta Suspended");
		this.jdbc.update("UPDATE companies SET status = 'suspended' WHERE id = ?", suspended);

		String page = body("/admin/branches?company_id=" + this.companyB);
		Matcher select = Pattern.compile(
				"<select name=\"company_id\" id=\"org_company\" data-filter-company>(.*?)</select>", Pattern.DOTALL).matcher(page);
		assertThat(select.find()).as("the toolbar names the company from a list").isTrue();
		assertThat(select.group(1).strip()).as("every company first").startsWith("<option value=\"\">");
		assertThat(select.group(1))
				.as("the active companies, with the filtered one chosen, and no other")
				.contains("<option value=\"" + this.companyA + "\">Alpha Co</option>")
				.contains("<option value=\"" + this.companyB + "\" selected>Beta Co</option>")
				.doesNotContain("value=\"" + suspended + "\"");
		assertThat(page).doesNotContain("<input type=\"number\" id=\"br_company\"");
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

	@Test
	void aPageAtTheEndOfTheIntRangeListsNothingRatherThanFailing() {
		// ?page=1e10 is page 10000000000 in PHP; the port bounds it to Integer.MAX_VALUE, whose
		// offset wrapped to a negative OFFSET in an int.
		seedBranch(this.companyA, "Alpha Far");
		for (String page : List.of("2147483647", "1e10")) {
			ResponseEntity<String> response = get("/admin/branches?page=" + page, this.cookie);
			assertThat(response.getStatusCode()).as("page=%s", page).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).doesNotContain("Alpha Far");
		}
	}

	private long seedBranch(long companyId, String name) {
		return seedBranch(companyId, name, null, true);
	}

	private long seedBranch(long companyId, String name, String address, boolean active) {
		this.jdbc.update("INSERT INTO branches (company_id, name, address, is_active, created_at)"
				+ " VALUES (?, ?, ?, ?, NOW())", companyId, name, address, active ? 1 : 0);
		return this.jdbc.queryForObject(
				"SELECT id FROM branches WHERE company_id = ? AND name = ?", Long.class,
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
	void aSaveOrDeleteForAnIdThatMatchesNoRowIsRefusedAndAuditsNothing() {
		long missing = 987654L;
		assertThat(post("/admin/branches", this.cookie,
				page("/admin/branches?action=edit&id=" + missing, this.cookie).csrf(),
				"action", "save_edit", "id", String.valueOf(missing),
				"company_id", String.valueOf(this.companyA), "name", "Ghost",
				"radius_meters", "300", "is_active", "1")
				.getHeaders().getLocation()).asString().contains("error=no_data");
		assertThat(post("/admin/branches", this.cookie, page("/admin/branches", this.cookie).csrf(),
				"action", "delete", "id", String.valueOf(missing),
				"company_id", String.valueOf(this.companyA))
				.getHeaders().getLocation()).asString().contains("error=no_data");

		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_events"
				+ " WHERE target_type = 'branch'", Integer.class))
				.as("no audit row for a branch that is not there").isZero();
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM branches WHERE name = 'Ghost'",
				Integer.class)).isZero();
	}

}
