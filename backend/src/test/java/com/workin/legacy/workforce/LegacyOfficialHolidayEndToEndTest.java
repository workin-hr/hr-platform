package com.workin.legacy.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/**
 * Wave 12.5 slice 4: all five
 * {@code /apis/api/company_official_holidays/*.php} endpoints.
 *
 * <p>Weighted towards the shapes that are easy to normalise away: the upsert
 * that returns an array at 201 whether or not anything was inserted, the
 * three-branch date-source precedence whose winner is chosen before
 * normalisation, the update path that has no nothing-to-update branch, and
 * D-087's deliberate broadening of the list endpoint.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyOfficialHolidayEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/company_official_holidays/list.php";
	private static final String ONE = "/apis/api/company_official_holidays/one.php";
	private static final String CREATE = "/apis/api/company_official_holidays/create.php";
	private static final String UPDATE = "/apis/api/company_official_holidays/update.php";
	private static final String DELETE = "/apis/api/company_official_holidays/delete.php";

	private static final long COMPANY_1 = 20701L;
	private static final long COMPANY_2 = 20702L;
	private static final long COMPANY_SUSPENDED = 20703L;

	private static final long ADMIN_1 = 207011L;
	private static final long HR_WITH_SETTINGS = 207012L;
	private static final long HR_NO_SETTINGS = 207013L;
	private static final long MANAGER_1 = 207014L;
	private static final long EMPLOYEE_1 = 207015L;
	private static final long ADMIN_2 = 207021L;
	private static final long ADMIN_SUSPENDED = 207031L;

	private static final long HOLIDAY_JAN = 207101L;
	private static final long HOLIDAY_MAR = 207102L;
	private static final long HOLIDAY_FEB = 207103L;
	private static final long HOLIDAY_OTHER_COMPANY = 207104L;
	private static final long HOLIDAY_SCRATCH = 207105L;
	private static final long HOLIDAY_CONFLICT_TARGET = 207106L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the official-holidays fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ------------------------------------------------------------------
	// list.php -- including D-087
	// ------------------------------------------------------------------

	@Test
	void listIsOrderedByDateAscendingWithTheIdTiebreak() {
		Map<String, Object> body = get(LIST + "?limit=100", ADMIN_1, 200);
		// ORDER BY holiday_date ASC, id ASC -- the only ascending order in the
		// wave, so the seeded ids are deliberately not in date order.
		assertThat(idsOf(body)).containsSubsequence(HOLIDAY_JAN, HOLIDAY_FEB, HOLIDAY_MAR);
		assertThat(idsOf(body)).doesNotContain(HOLIDAY_OTHER_COMPANY);
	}

	@Test
	void everyAuthenticatedRoleMayListIncludingHrWithoutTheSettingsPermission() {
		// D-087: PHP applies require_company_settings_access() only when the
		// role is CA or HR, so an HR user lacking the flag is refused a list an
		// EMPLOYEE may read. That gate is removed.
		assertThat(hasNoPermissionsRow(HR_NO_SETTINGS)).isTrue();

		for (long actor : new long[] { ADMIN_1, HR_WITH_SETTINGS, HR_NO_SETTINGS, MANAGER_1, EMPLOYEE_1 }) {
			get(LIST, actor, 200);
		}
	}

	@Test
	void theOtherFourEndpointsStillGateOnTheSettingsPermission() {
		// The correction is scoped to list.php; mutation authority is untouched.
		get(ONE + "?id=" + HOLIDAY_JAN, HR_NO_SETTINGS, 403);
		assertThat(status(send(CREATE, HttpMethod.POST, HR_NO_SETTINGS,
				"{\"name\":\"Nope\",\"holiday_date\":\"2027-01-01\"}"))).isEqualTo(403);
		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_JAN, HttpMethod.PUT, HR_NO_SETTINGS, "{}")))
				.isEqualTo(403);
		assertThat(status(send(DELETE + "?id=" + HOLIDAY_JAN, HttpMethod.DELETE, HR_NO_SETTINGS, null)))
				.isEqualTo(403);
	}

	@Test
	void theSettingsGateBeatsAMissingOrForeignId() {
		// PHP places the gate above required($_GET,['id']), so the permission
		// answer never leaks whether the id was usable.
		assertThat(status(send(DELETE, HttpMethod.DELETE, HR_NO_SETTINGS, null))).isEqualTo(403);
		assertThat(status(send(DELETE + "?id=999999", HttpMethod.DELETE, HR_NO_SETTINGS, null)))
				.isEqualTo(403);
		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_OTHER_COMPANY, HttpMethod.PUT, HR_NO_SETTINGS,
				"{\"name\":\"X\"}"))).isEqualTo(403);
	}

	@Test
	void theDateBoundsAreNamedFromAndTo() {
		// The wire keys are `from` and `to`. PHP reads
		// $_GET[Request::DATE_FROM], and Request::DATE_FROM is declared as the
		// literal 'from' in apis/config/request.php:26 -- the constant's
		// identifier is not its value.
		assertThat(idsOf(get(LIST + "?from=2026-02-01&limit=100", ADMIN_1, 200)))
				.contains(HOLIDAY_FEB, HOLIDAY_MAR).doesNotContain(HOLIDAY_JAN);
		assertThat(idsOf(get(LIST + "?to=2026-01-31&limit=100", ADMIN_1, 200)))
				.containsExactly(HOLIDAY_JAN);
	}

	@Test
	void dateFromAndDateToAreNotAliasesAndFilterNothing() {
		// Guarding the defect this test previously hid: the endpoint used to
		// read date_from/date_to, and a test written with the same wrong keys
		// confirmed the wrong contract. These names are not aliases in PHP --
		// they are simply unknown query parameters, so they must be ignored.
		int all = idsOf(get(LIST + "?limit=100", ADMIN_1, 200)).size();
		assertThat(idsOf(get(LIST + "?date_from=2026-02-01&limit=100", ADMIN_1, 200))).hasSize(all);
		assertThat(idsOf(get(LIST + "?date_to=2026-01-31&limit=100", ADMIN_1, 200))).hasSize(all);
	}

	@Test
	void theDateBoundsUseEmptyNotPresence() {
		// !empty(): "0" and an empty value add no clause at all, unlike
		// request_types' isset()-driven is_active.
		int all = idsOf(get(LIST + "?limit=100", ADMIN_1, 200)).size();
		assertThat(idsOf(get(LIST + "?from=&limit=100", ADMIN_1, 200))).hasSize(all);
		assertThat(idsOf(get(LIST + "?from=0&limit=100", ADMIN_1, 200))).hasSize(all);
		assertThat(idsOf(get(LIST + "?to=0&limit=100", ADMIN_1, 200))).hasSize(all);
	}

	@Test
	void searchAndPaginationComeFromTheSharedHelpers() {
		assertThat(idsOf(get(LIST + "?search=New%20Year&limit=100", ADMIN_1, 200)))
				.containsExactly(HOLIDAY_JAN);

		Map<String, Object> page = get(LIST + "?limit=1", ADMIN_1, 200);
		assertThat(page.keySet()).containsExactly("success", "message", "data", "meta");
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) page.get("meta");
		assertThat(meta.keySet()).containsExactly(
				"page", "limit", "total", "total_pages", "has_next", "has_previous");
		assertThat(number(meta.get("limit"))).isEqualTo(1);
	}

	@Test
	void listIsCompanyScoped() {
		assertThat(idsOf(get(LIST + "?limit=100", ADMIN_2, 200))).containsExactly(HOLIDAY_OTHER_COMPANY);
	}

	@Test
	void theRowCarriesCreatedAtWhichIsWhyDzeroEightThreeStaysOpen() {
		// SELECT * exposes created_at, a TIMESTAMP MariaDB renders in the
		// session timezone. This slice does not set that timezone, so D-083 is
		// still open; the assertion records the exposure rather than the value.
		Map<String, Object> row = rowFor(get(LIST + "?limit=100", ADMIN_1, 200), HOLIDAY_JAN);
		assertThat(row.keySet()).containsExactly("id", "company_id", "name", "holiday_date", "created_at");
		assertThat(row.get("created_at")).isInstanceOf(String.class);
	}

	// ------------------------------------------------------------------
	// one.php
	// ------------------------------------------------------------------

	@Test
	void oneReturnsTheCompanysRowAndRefusesEverythingElse() {
		assertThat(number(dataOf(get(ONE + "?id=" + HOLIDAY_JAN, ADMIN_1, 200)).get("id")))
				.isEqualTo(HOLIDAY_JAN);
		get(ONE + "?id=" + HOLIDAY_OTHER_COMPANY, ADMIN_1, 404);
		get(ONE + "?id=999999", ADMIN_1, 404);
	}

	@Test
	void oneUsesRequiredRatherThanTheShiftsIdGuard() {
		// Only missing or exactly empty is field_required; "0" and "abc" pass
		// required(), cast to 0, and are refused by the helper's own
		// non-positive check -- a 404, not a 400.
		get(ONE, ADMIN_1, 400);
		get(ONE + "?id=", ADMIN_1, 400);
		get(ONE + "?id=0", ADMIN_1, 404);
		get(ONE + "?id=abc", ADMIN_1, 404);
	}

	// ------------------------------------------------------------------
	// create.php -- upsert, precedence, normalisation
	// ------------------------------------------------------------------

	@Test
	void createReturnsAnArrayAtTwoOhOneEvenForASingleDate() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Single\",\"holiday_date\":\"2030-01-05\"}");
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody().get("data")).isInstanceOf(List.class);
		assertThat(rowsOf(response.getBody())).hasSize(1);
	}

	@Test
	void createUpsertsAndStillAnswersTwoOhOneWhenNothingWasInserted() {
		// The date already exists, so this only renames it -- and the status is
		// still 201, because create.php does not distinguish the two.
		long before = holidayCount(COMPANY_1);
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Renamed New Year\",\"holiday_date\":\"2026-01-01\"}");

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(holidayCount(COMPANY_1)).isEqualTo(before);
		assertThat(holidayRow(HOLIDAY_JAN).get("name")).isEqualTo("Renamed New Year");
	}

	@Test
	void aMixedListCreatesTheValidDatesAndReportsNothingAboutTheRest() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Mixed\",\"holiday_dates\":[\"2030-02-01\",\"oops\",\"2030-02-30\",\"2030-02-02\"]}");

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		// Two survived; the malformed one and the rolled-over 30 February did not,
		// and no error mentions them.
		assertThat(rowsOf(response.getBody())).hasSize(2);
		assertThat(datesOf(response.getBody())).containsExactly("2030-02-01", "2030-02-02");
	}

	@Test
	void duplicatesCollapseKeepingTheFirstOccurrence() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Dupes\",\"holiday_dates\":[\"2030-03-02\",\"2030-03-01\",\"2030-03-02\"]}");
		assertThat(datesOf(response.getBody())).containsExactly("2030-03-02", "2030-03-01");
	}

	@Test
	void theDateSourcePrecedenceIsDecidedBeforeNormalisation() {
		// A non-empty holiday_dates ARRAY wins the first branch outright. Even
		// when every entry is unusable it does NOT fall through to
		// holiday_date -- it reaches field_required naming holiday_dates.
		Map<String, Object> body = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Precedence\",\"holiday_dates\":[\"oops\"],\"holiday_date\":\"2030-04-01\"}")
				.getBody();
		assertThat(body.get("success")).isEqualTo(false);
		assertThat(holidayIdForDate(COMPANY_1, "2030-04-01")).isNull();

		// A non-empty holiday_dates SCALAR fails the is_array test, so the
		// second branch runs and the fallback date is used.
		ResponseEntity<Map<String, Object>> scalar = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Scalar\",\"holiday_dates\":\"nonsense\",\"holiday_date\":\"2030-04-02\"}");
		assertThat(scalar.getStatusCode().value()).isEqualTo(201);
		assertThat(datesOf(scalar.getBody())).containsExactly("2030-04-02");

		// An EMPTY holiday_dates array falls through as well, because !empty()
		// is false for [].
		ResponseEntity<Map<String, Object>> emptyArray = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Empty Array\",\"holiday_dates\":[],\"holiday_date\":\"2030-04-03\"}");
		assertThat(emptyArray.getStatusCode().value()).isEqualTo(201);
		assertThat(datesOf(emptyArray.getBody())).containsExactly("2030-04-03");
	}

	@Test
	void theThirdBranchIsDatesAndOnlyWhenTheFirstTwoAreEmpty() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Third Branch\",\"dates\":[\"2030-05-01\"]}");
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(datesOf(response.getBody())).containsExactly("2030-05-01");

		// holiday_date outranks dates.
		ResponseEntity<Map<String, Object>> both = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Both\",\"holiday_date\":\"2030-05-02\",\"dates\":[\"2030-05-03\"]}");
		assertThat(datesOf(both.getBody())).containsExactly("2030-05-02");
		assertThat(holidayIdForDate(COMPANY_1, "2030-05-03")).isNull();
	}

	@Test
	void aFormFeedPaddedDateIsSkippedWhereASpacePaddedOneIsNot() {
		// PHP's trim charlist leaves form feed in place, so the round trip then
		// rejects the date. Java's String.trim() would have accepted it.
		ResponseEntity<Map<String, Object>> padded = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Padded\",\"holiday_dates\":[\" 2030-06-01 \",\"\\t2030-06-02\\t\"]}");
		assertThat(datesOf(padded.getBody())).containsExactly("2030-06-01", "2030-06-02");

		Map<String, Object> formFeed = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Form Feed\",\"holiday_dates\":[\"\\f2030-06-03\"]}").getBody();
		assertThat(formFeed.get("success")).isEqualTo(false);
		assertThat(holidayIdForDate(COMPANY_1, "2030-06-03")).isNull();
	}

	@Test
	void aSignedYearIsNotAValidDateThroughEitherWritePath() {
		// PHP's Y field is four unsigned digits, so a signed year never
		// normalises. Java's proleptic year parser would accept it, which is
		// why the lexical field-shape guard exists.
		Map<String, Object> created = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Signed Year\",\"holiday_dates\":[\"-0001-01-01\"]}").getBody();
		assertThat(created.get("success")).isEqualTo(false);
		assertThat(holidayIdForDate(COMPANY_1, "-0001-01-01")).isNull();

		// A non-empty date that normalises to nothing is invalid_input, not a
		// silent no-op -- the update path distinguishes those.
		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"holiday_date\":\"-0001-01-01\"}"))).isEqualTo(400);
	}

	@Test
	void createRejectsABlankNameAndAnAbsentDateSource() {
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"   \",\"holiday_date\":\"2030-07-01\"}"))).isEqualTo(400);
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1, "{\"name\":\"No Dates\"}"))).isEqualTo(400);
		// required() fires before the trim, so a missing name is also a 400.
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"holiday_date\":\"2030-07-02\"}"))).isEqualTo(400);
	}

	@Test
	void eachDateIsWrittenIndependentlyRatherThanAsOneStatement() {
		// What this proves: the loop issues a separate probe and write per
		// date, so the rows get independent auto-increment ids.
		//
		// What it deliberately does NOT claim: that a failure mid-list leaves
		// earlier dates committed. That is true of the port -- there is no
		// transaction around the loop -- but the only failure reachable through
		// this endpoint is a lost race on the unique pair, which cannot be
		// forced deterministically from a single-threaded test. Naming this
		// test after non-transactionality would have claimed evidence it does
		// not have.
		send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Independent\",\"holiday_dates\":[\"2031-01-01\",\"2031-01-02\"]}");

		// Both rows exist with independent ids, written by separate statements.
		Long first = holidayIdForDate(COMPANY_1, "2031-01-01");
		Long second = holidayIdForDate(COMPANY_1, "2031-01-02");
		assertThat(first).isNotNull();
		assertThat(second).isNotNull();
		assertThat(second).isNotEqualTo(first);
	}

	// ------------------------------------------------------------------
	// update.php
	// ------------------------------------------------------------------

	@Test
	void anEmptyBodyIsASuccessfulNoOpBecauseThereIsNoNothingToUpdateBranch() {
		Map<String, Object> before = holidayRow(HOLIDAY_SCRATCH);

		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1, "{}")))
				.isEqualTo(200);
		// Malformed JSON decodes to [] and takes the same path.
		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1, "not json")))
				.isEqualTo(200);

		assertThat(holidayRow(HOLIDAY_SCRATCH)).isEqualTo(before);
	}

	@Test
	void anEmptyLikeDateKeepsTheStoredDateRatherThanFailing() {
		String stored = String.valueOf(holidayRow(HOLIDAY_SCRATCH).get("holiday_date"));

		// !empty(): null, "" and 0 all mean "no replacement offered".
		for (String value : new String[] { "null", "\"\"", "0" }) {
			assertThat(status(send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1,
					"{\"holiday_date\":" + value + "}"))).isEqualTo(200);
			assertThat(String.valueOf(holidayRow(HOLIDAY_SCRATCH).get("holiday_date"))).isEqualTo(stored);
		}
	}

	@Test
	void aNonEmptyUnparseableDateIsInvalidInput() {
		Map<String, Object> body = send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"holiday_date\":\"2026-02-30\"}").getBody();
		assertThat(body.get("success")).isEqualTo(false);
	}

	@Test
	void anAbsentNameKeepsTheStoredOneAndABlankOneIsRejected() {
		String stored = String.valueOf(holidayRow(HOLIDAY_SCRATCH).get("name"));

		send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1, "{}");
		assertThat(holidayRow(HOLIDAY_SCRATCH).get("name")).isEqualTo(stored);

		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"name\":\"   \"}"))).isEqualTo(400);
	}

	@Test
	void movingOntoAnotherRowsDateIsAFourZeroNine() {
		// The pre-check on (company_id, holiday_date) excluding this id.
		Map<String, Object> body = send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"holiday_date\":\"2026-12-25\"}").getBody();
		assertThat(body.get("success")).isEqualTo(false);
		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"holiday_date\":\"2026-12-25\"}"))).isEqualTo(409);
		// unchanged
		assertThat(String.valueOf(holidayRow(HOLIDAY_SCRATCH).get("holiday_date")))
				.isNotEqualTo("2026-12-25");
	}

	@Test
	void keepingItsOwnDateIsNotAConflict() {
		String stored = String.valueOf(holidayRow(HOLIDAY_SCRATCH).get("holiday_date"));
		// id <> ? excludes the row itself, so re-sending its own date is fine.
		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"holiday_date\":\"" + stored + "\",\"name\":\"Same Date\"}"))).isEqualTo(200);
		assertThat(holidayRow(HOLIDAY_SCRATCH).get("name")).isEqualTo("Same Date");
	}

	@Test
	void updateOfAForeignRowIsNotFoundAndWritesNothing() {
		Map<String, Object> before = holidayRow(HOLIDAY_OTHER_COMPANY);
		assertThat(status(send(UPDATE + "?id=" + HOLIDAY_OTHER_COMPANY, HttpMethod.PUT, ADMIN_1,
				"{\"name\":\"Cross Tenant\"}"))).isEqualTo(404);
		assertThat(holidayRow(HOLIDAY_OTHER_COMPANY)).isEqualTo(before);
	}

	// ------------------------------------------------------------------
	// delete.php
	// ------------------------------------------------------------------

	@Test
	void deleteIsAHardDeleteAndOmitsTheDataKeyEntirely() {
		long id = insertScratchHoliday("To Delete", "2032-01-01");

		Map<String, Object> body = send(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN_1, null).getBody();
		// ok(OK, null): the builder omits a null $data, so the wire shape is
		// exactly success + message.
		assertThat(body.keySet()).containsExactly("success", "message");
		assertThat(body.get("success")).isEqualTo(true);

		assertThat(holidayRow(id)).isNull();
	}

	@Test
	void deleteOfAForeignOrMissingRowIsNotFound() {
		assertThat(status(send(DELETE + "?id=" + HOLIDAY_OTHER_COMPANY, HttpMethod.DELETE, ADMIN_1, null)))
				.isEqualTo(404);
		assertThat(holidayRow(HOLIDAY_OTHER_COMPANY)).isNotNull();
		assertThat(status(send(DELETE + "?id=999999", HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(404);
	}

	// ------------------------------------------------------------------
	// Guards
	// ------------------------------------------------------------------

	@Test
	void theMethodGuardRunsBeforeAuthentication() {
		assertThat(anonymous(LIST, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(CREATE, HttpMethod.GET)).isEqualTo(405);
		assertThat(anonymous(UPDATE, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(DELETE, HttpMethod.GET)).isEqualTo(405);
		assertThat(anonymous(LIST, HttpMethod.GET)).isEqualTo(401);
	}

	@Test
	void aSuspendedCompanyIsRefusedAfterAuthentication() {
		get(LIST, ADMIN_SUSPENDED, 403);
	}

	@Test
	void aPlainEmployeeCannotMutate() {
		assertThat(status(send(CREATE, HttpMethod.POST, EMPLOYEE_1,
				"{\"name\":\"Nope\",\"holiday_date\":\"2033-01-01\"}"))).isEqualTo(403);
		assertThat(status(send(DELETE + "?id=" + HOLIDAY_JAN, HttpMethod.DELETE, MANAGER_1, null)))
				.isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> get(String path, long employeeId, int expectedStatus) {
		ResponseEntity<Map<String, Object>> response = send(path, HttpMethod.GET, employeeId, null);
		assertThat(response.getStatusCode().value()).as("%s: %s", path, response.getBody())
				.isEqualTo(expectedStatus);
		return response.getBody();
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, long employeeId, String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(employeeId));
		headers.set("Accept-Language", "en");
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>(body, headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private int anonymous(String path, HttpMethod method) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method, new HttpEntity<>("{}", headers),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
				.getStatusCode().value();
	}

	private static int status(ResponseEntity<Map<String, Object>> response) {
		return response.getStatusCode().value();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dataOf(Map<String, Object> body) {
		return (Map<String, Object>) body.get("data");
	}

	private static List<?> rowsOf(Map<String, Object> body) {
		return (List<?>) body.get("data");
	}

	private static List<String> datesOf(Map<String, Object> body) {
		List<String> dates = new ArrayList<>();
		for (Object row : rowsOf(body)) {
			dates.add(String.valueOf(((Map<?, ?>) row).get("holiday_date")));
		}
		return dates;
	}

	private static List<Long> idsOf(Map<String, Object> body) {
		List<Long> ids = new ArrayList<>();
		for (Object row : (List<?>) body.get("data")) {
			ids.add(((Number) ((Map<?, ?>) row).get("id")).longValue());
		}
		return ids;
	}

	private static Map<String, Object> rowFor(Map<String, Object> body, long id) {
		for (Object row : (List<?>) body.get("data")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> holiday = (Map<String, Object>) row;
			if (((Number) holiday.get("id")).longValue() == id) {
				return holiday;
			}
		}
		throw new IllegalStateException("no row for " + id);
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == HR_WITH_SETTINGS || employeeId == HR_NO_SETTINGS ? "hr"
				: employeeId == EMPLOYEE_1 ? "employee" : "company_admin";
		long companyId = employeeId == ADMIN_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static Map<String, Object> holidayRow(long id) {
		return queryOne("SELECT id, company_id, name, holiday_date"
				+ " FROM company_official_holidays WHERE id = " + id);
	}

	private static Long holidayIdForDate(long companyId, String date) {
		Map<String, Object> row = queryOne("SELECT id FROM company_official_holidays"
				+ " WHERE company_id = " + companyId + " AND holiday_date = '" + date + "'");
		return row == null ? null : ((Number) row.get("id")).longValue();
	}

	private static long holidayCount(long companyId) {
		return count("SELECT COUNT(*) FROM company_official_holidays WHERE company_id = " + companyId);
	}

	private static boolean hasNoPermissionsRow(long employeeId) {
		return count("SELECT COUNT(*) FROM hr_permissions WHERE employee_id = " + employeeId) == 0;
	}

	private static long insertScratchHoliday(String name, String date) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.executeUpdate("INSERT INTO company_official_holidays (company_id, name, holiday_date)"
					+ " VALUES (" + COMPANY_1 + ", '" + name + "', '" + date + "')",
					Statement.RETURN_GENERATED_KEYS);
			try (ResultSet keys = st.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static long count(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Map<String, Object> queryOne(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			if (!rs.next()) {
				return null;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
				row.put(rs.getMetaData().getColumnLabel(column), rs.getObject(column));
			}
			return row;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20701, 'Holiday Co', '+201000020701', 'active', '2025-01-15 09:00:00'),
					  (20702, 'Holiday Other Co', '+201000020702', 'active', '2025-01-15 09:00:00'),
					  (20703, 'Holiday Suspended Co', '+201000020703', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20711, 20701, 'Main', 1, '2025-03-01 10:00:00'),
					  (20712, 20702, 'Other', 1, '2025-03-01 10:00:00'),
					  (20713, 20703, 'Suspended', 1, '2025-03-01 10:00:00')
					""");

			employee(st, ADMIN_1, COMPANY_1, 20711L, "company_admin", "+201000207011", "Admin One");
			employee(st, HR_WITH_SETTINGS, COMPANY_1, 20711L, "hr", "+201000207012", "Hr Gated");
			employee(st, HR_NO_SETTINGS, COMPANY_1, 20711L, "hr", "+201000207013", "Hr Ungated");
			employee(st, MANAGER_1, COMPANY_1, 20711L, "manager", "+201000207014", "Manager One");
			employee(st, EMPLOYEE_1, COMPANY_1, 20711L, "employee", "+201000207015", "Employee One");
			employee(st, ADMIN_2, COMPANY_2, 20712L, "company_admin", "+201000207021", "Admin Two");
			employee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 20713L, "company_admin",
					"+201000207031", "Admin Suspended");

			// The company admins and one HR carry can_company_settings; Phase 1
			// issues only employee-scoped tokens (D-042), so the gate applies to
			// every role that reaches it. HR_NO_SETTINGS has no row at all.
			st.execute("INSERT INTO hr_permissions (employee_id, can_company_settings) VALUES ("
					+ ADMIN_1 + ", 1), (" + ADMIN_2 + ", 1), (" + HR_WITH_SETTINGS + ", 1)");

			// Ids deliberately out of date order, so ORDER BY holiday_date is
			// doing the work rather than the primary key.
			holiday(st, HOLIDAY_JAN, COMPANY_1, "New Year", "2026-01-01");
			holiday(st, HOLIDAY_MAR, COMPANY_1, "Spring Day", "2026-03-01");
			holiday(st, HOLIDAY_FEB, COMPANY_1, "Founders Day", "2026-02-01");
			holiday(st, HOLIDAY_OTHER_COMPANY, COMPANY_2, "Other Co Day", "2026-01-01");
			holiday(st, HOLIDAY_SCRATCH, COMPANY_1, "Scratch Day", "2026-09-01");
			holiday(st, HOLIDAY_CONFLICT_TARGET, COMPANY_1, "Conflict Target", "2026-12-25");
		}
	}

	private static void employee(
			Statement st, long id, long companyId, long branchId, String role, String phone, String name)
			throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, role, is_active,"
				+ " join_request_status, phone, first_name, last_name, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", '" + id + "', '" + role + "', 1,"
				+ " 'accepted', '" + phone + "', '" + name + "', 'Test', '2025-01-20 09:00:00')");
	}

	private static void holiday(Statement st, long id, long companyId, String name, String date)
			throws Exception {
		st.execute("INSERT INTO company_official_holidays (id, company_id, name, holiday_date, created_at)"
				+ " VALUES (" + id + ", " + companyId + ", '" + name + "', '" + date + "',"
				+ " '2025-06-01 09:00:00')");
	}

	private static void applySchema(String resourceName) throws Exception {
		String schema = readResource(resourceName);
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			for (String statement : schema.split(";\\s*\\R")) {
				if (!statement.isBlank()) {
					st.execute(statement);
				}
			}
		}
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static String readResource(String name) throws Exception {
		try (InputStream stream = LegacyOfficialHolidayEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
