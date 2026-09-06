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
 * Wave 12.5 slice 2: all five {@code /apis/api/shifts/*.php} endpoints.
 *
 * <p>The suite is weighted towards the behaviours a clean-room implementation
 * would "fix" -- the unconditional merged-window validation, the soft-deleted
 * row that stays writable, {@code days_off} being un-clearable, the broadcast
 * that fires without a change (D-089), and the module's own {@code id_required}
 * guard. Each of those is a test that fails if the port is tidied up.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyShiftEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/shifts/list.php";
	private static final String ONE = "/apis/api/shifts/one.php";
	private static final String CREATE = "/apis/api/shifts/create.php";
	private static final String UPDATE = "/apis/api/shifts/update.php";
	private static final String DELETE = "/apis/api/shifts/delete.php";

	private static final long COMPANY_1 = 20501L;
	private static final long COMPANY_2 = 20502L;
	private static final long COMPANY_SUSPENDED = 20503L;

	private static final long ADMIN_1 = 205011L;
	private static final long HR_1 = 205012L;
	private static final long MANAGER_1 = 205013L;
	private static final long EMPLOYEE_1 = 205014L;
	private static final long STAFF_EXTRA = 205015L;
	private static final long STAFF_INACTIVE = 205016L;
	private static final long ADMIN_2 = 205021L;
	private static final long ADMIN_SUSPENDED = 205031L;

	/** Company 1's active shifts, oldest {@code created_at} first by id. */
	private static final long SHIFT_OLDEST = 205101L;
	private static final long SHIFT_MIDDLE = 205102L;
	/** Shares {@code created_at} with {@link #SHIFT_MIDDLE}, to exercise the id tiebreak. */
	private static final long SHIFT_MIDDLE_TWIN = 205103L;
	private static final long SHIFT_NEWEST = 205104L;
	private static final long SHIFT_SOFT_DELETED = 205105L;
	private static final long SHIFT_OTHER_COMPANY = 205106L;
	private static final long SHIFT_NO_COMPANY = 205107L;
	/** Reserved for the mutation tests so the ordering fixture stays stable. */
	private static final long SHIFT_SCRATCH = 205108L;
	private static final long SHIFT_SCRATCH_TWO = 205109L;
	private static final long SHIFT_OUT_OF_RANGE_TIMES = 205110L;

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
			throw new IllegalStateException("could not prepare the shifts fixture", ex);
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
	// list.php
	// ------------------------------------------------------------------

	@Test
	void listReturnsOnlyActiveSameCompanyRowsNewestFirstWithTheIdTiebreak() {
		Map<String, Object> body = get(LIST, ADMIN_1, 200);

		// ORDER BY created_at DESC, id DESC. The two middle rows share a
		// created_at, so only the id tiebreak separates them -- dropping it
		// would let the pair swap between runs.
		//
		// A subsequence rather than an exact list: other tests in this class
		// create shifts, and a row created now sorts ahead of every seeded one.
		// The relative order of the fixture is what this asserts, and it is the
		// part the ORDER BY owns.
		assertThat(idsOf(body)).containsSubsequence(
				SHIFT_NEWEST, SHIFT_MIDDLE_TWIN, SHIFT_MIDDLE, SHIFT_OUT_OF_RANGE_TIMES, SHIFT_OLDEST);

		// is_active = 1 AND company_id = ?: the soft-deleted row, the other
		// company's row and the company-less row are all absent.
		assertThat(idsOf(body))
				.doesNotContain(SHIFT_SOFT_DELETED, SHIFT_OTHER_COMPANY, SHIFT_NO_COMPANY);
	}

	@Test
	void listRendersThePhpEnvelopeAndTheEightColumnProjection() {
		Map<String, Object> body = get(LIST, ADMIN_1, 200);

		assertThat(body.keySet()).containsExactly("success", "message", "data");
		assertThat(body.get("success")).isEqualTo(true);

		Map<String, Object> row = rowFor(body, SHIFT_NEWEST);
		// public_row() returns the PDO row itself, so key order is contract.
		assertThat(row.keySet()).containsExactly(
				"id", "company_id", "name", "start_time", "end_time", "days_off", "is_active", "created_at");
		assertThat(row.get("id")).isInstanceOf(Number.class);
		assertThat(row.get("company_id")).isInstanceOf(Number.class);
		assertThat(row.get("is_active")).isInstanceOf(Number.class);
		assertThat(row.get("start_time")).isInstanceOf(String.class);
	}

	@Test
	void aCompanySeesOnlyItsOwnShifts() {
		assertThat(idsOf(get(LIST, ADMIN_2, 200))).containsExactly(SHIFT_OTHER_COMPANY);
	}

	// ------------------------------------------------------------------
	// one.php
	// ------------------------------------------------------------------

	@Test
	void oneReturnsAnActiveShiftOfTheCompany() {
		Map<String, Object> body = get(ONE + "?id=" + SHIFT_NEWEST, ADMIN_1, 200);
		assertThat(number(dataOf(body).get("id"))).isEqualTo(SHIFT_NEWEST);
		// list and one share the same message key.
		assertThat(body.get("message")).isEqualTo(get(LIST, ADMIN_1, 200).get("message"));
	}

	@Test
	void oneAnswersForbiddenAtFourOhFourForEveryKindOfMiss() {
		// The key is LangKey::FORBIDDEN while the status is 404 -- legacy's own
		// pairing, and the reason a caller cannot tell "not yours" from
		// "not there" from "deleted".
		String forbidden = messageOf(get(ONE + "?id=999999", ADMIN_1, 404));
		for (long id : new long[] { SHIFT_SOFT_DELETED, SHIFT_OTHER_COMPANY, SHIFT_NO_COMPANY, 999_999L }) {
			Map<String, Object> body = get(ONE + "?id=" + id, ADMIN_1, 404);
			assertThat(body.get("success")).isEqualTo(false);
			// Indistinguishable: not yours, not there, and soft-deleted all
			// render the same message and the same status.
			assertThat(messageOf(body)).isEqualTo(forbidden);
		}
	}

	@Test
	void oneRejectsAnIdThatCastsToZeroAndAcceptsOneThatDoesNot() {
		// (int) ($_GET['id'] ?? 0) then if (!$id) -- so the cast decides.
		for (String raw : new String[] { "", "0", "abc", "0.4" }) {
			Map<String, Object> body = get(ONE + "?id=" + raw, ADMIN_1, 400);
			assertThat(body.get("message")).isNotNull();
		}
		// absent entirely is the same branch
		get(ONE, ADMIN_1, 400);

		// "12abc" casts to 12, and a negative casts to itself: both get past the
		// guard and reach the lookup, which then misses.
		get(ONE + "?id=-5", ADMIN_1, 404);
		assertThat(number(dataOf(get(ONE + "?id=" + SHIFT_NEWEST + "abc", ADMIN_1, 200)).get("id")))
				.isEqualTo(SHIFT_NEWEST);
	}

	@Test
	void theIdRequiredKeyIsNotTheFieldRequiredOneTheSiblingModulesUse() {
		// shifts fails with LangKey::ID_REQUIRED and no {field} placeholder,
		// where request_types and company_official_holidays call required() and
		// fail with FIELD_REQUIRED. Different key, different body.
		Map<String, Object> body = get(ONE + "?id=0", ADMIN_1, 400);
		assertThat(body).doesNotContainKey("data");
		assertThat(String.valueOf(body.get("message"))).doesNotContain("{field}");
	}

	// ------------------------------------------------------------------
	// create.php
	// ------------------------------------------------------------------

	@Test
	void createInsertsAndReturnsTheRowAtTwoOhOne() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Created Shift\",\"start_time\":\"09:00\",\"end_time\":\"17:00\"}");
		assertThat(response.getStatusCode().value()).isEqualTo(201);

		Map<String, Object> row = dataOf(response.getBody());
		assertThat(row.get("name")).isEqualTo("Created Shift");
		assertThat(number(row.get("company_id"))).isEqualTo(COMPANY_1);
		assertThat(number(row.get("is_active"))).isEqualTo(1);
		// days_off ?? null -- absent means SQL NULL, not an empty string.
		assertThat(row.get("days_off")).isNull();
	}

	@Test
	void createRejectsAMissingRequiredFieldBeforeTouchingTheWindow() {
		Map<String, Object> body = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"start_time\":\"09:00\",\"end_time\":\"17:00\"}").getBody();
		assertThat(body.get("success")).isEqualTo(false);
		// required() fails with the field as a {field} placeholder and no data key.
		assertThat(body).doesNotContainKey("data");
	}

	@Test
	void createAppliesTheWindowRules() {
		// equal times -> shift_end_must_be_after_start, not invalid_input
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Zero\",\"start_time\":\"09:00\",\"end_time\":\"09:00\"}"))).isEqualTo(400);
		// beyond sixteen hours
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Long\",\"start_time\":\"08:00\",\"end_time\":\"00:01\"}"))).isEqualTo(400);
		// exactly sixteen hours is accepted
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Sixteen\",\"start_time\":\"08:00\",\"end_time\":\"00:00\"}"))).isEqualTo(201);
		// overnight is accepted
		assertThat(status(send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Night\",\"start_time\":\"22:00\",\"end_time\":\"06:00\"}"))).isEqualTo(201);
	}

	@Test
	void createValidatesCastCopiesButStoresTheRequestValues() {
		// The seconds are ignored by the validator, and the value that reaches
		// the column is the one the request sent -- not the validator's view of
		// it. MariaDB stores a TIME as H:i:s, so the seconds survive.
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1,
				"{\"name\":\"Seconds\",\"start_time\":\"09:00:45\",\"end_time\":\"17:00:45\"}");
		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(dataOf(response.getBody()).get("start_time")).isEqualTo("09:00:45");
	}

	// ------------------------------------------------------------------
	// update.php -- the no-op paths
	// ------------------------------------------------------------------

	@Test
	void anEmptyBodyIsASuccessfulNoOpBecauseShiftsHasNoNothingToUpdateBranch() {
		Map<String, Object> before = shiftRow(SHIFT_SCRATCH);

		ResponseEntity<Map<String, Object>> response =
				send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1, "{}");

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("success")).isEqualTo(true);
		// Every COALESCE binds null, so nothing changes and the row comes back
		// unchanged. request_types would answer NOTHING_TO_UPDATE here; shifts
		// has no such branch at all.
		assertThat(shiftRow(SHIFT_SCRATCH)).isEqualTo(before);
	}

	@Test
	void malformedJsonTakesTheSameNoOpSuccessPath() {
		// body() decodes anything that is not a JSON object to [], so a broken
		// document is indistinguishable from {} -- and with valid stored times
		// the merged window still validates.
		Map<String, Object> before = shiftRow(SHIFT_SCRATCH);

		assertThat(status(send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1, "not json at all")))
				.isEqualTo(200);
		assertThat(status(send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1, "[1,2,3]")))
				.isEqualTo(200);
		assertThat(status(send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1, "")))
				.isEqualTo(200);

		assertThat(shiftRow(SHIFT_SCRATCH)).isEqualTo(before);
	}

	@Test
	void aNameOnlyChangeStillValidatesTheMergedWindow() {
		// The stored times are outside the validator's range, so a rename that
		// sends no time at all is still rejected: the assertion runs on the
		// merged pair unconditionally.
		Map<String, Object> body = send(UPDATE + "?id=" + SHIFT_OUT_OF_RANGE_TIMES, HttpMethod.PUT, ADMIN_1,
				"{\"name\":\"Rename Attempt\"}").getBody();
		assertThat(body.get("success")).isEqualTo(false);
		assertThat(shiftRow(SHIFT_OUT_OF_RANGE_TIMES).get("name")).isNotEqualTo("Rename Attempt");

		// Supplying a valid window in the same request is the only way through.
		assertThat(status(send(UPDATE + "?id=" + SHIFT_OUT_OF_RANGE_TIMES, HttpMethod.PUT, ADMIN_1,
				"{\"name\":\"Rename Attempt\",\"start_time\":\"09:00\",\"end_time\":\"17:00\"}")))
				.isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// update.php -- COALESCE and the broadcast
	// ------------------------------------------------------------------

	@Test
	void daysOffCannotBeClearedThroughThisEndpoint() {
		assertThat(shiftRow(SHIFT_SCRATCH_TWO).get("days_off")).isEqualTo("Fri,Sat");

		// An explicit null and an absent key are the same bind, and COALESCE
		// keeps the stored value for both.
		send(UPDATE + "?id=" + SHIFT_SCRATCH_TWO, HttpMethod.PUT, ADMIN_1, "{\"days_off\":null}");
		assertThat(shiftRow(SHIFT_SCRATCH_TWO).get("days_off")).isEqualTo("Fri,Sat");

		send(UPDATE + "?id=" + SHIFT_SCRATCH_TWO, HttpMethod.PUT, ADMIN_1, "{\"name\":\"Still Fri,Sat\"}");
		assertThat(shiftRow(SHIFT_SCRATCH_TWO).get("days_off")).isEqualTo("Fri,Sat");

		// An empty string does write, because it is not null.
		send(UPDATE + "?id=" + SHIFT_SCRATCH_TWO, HttpMethod.PUT, ADMIN_1, "{\"days_off\":\"\"}");
		assertThat(shiftRow(SHIFT_SCRATCH_TWO).get("days_off")).isEqualTo("");
	}

	@Test
	void anUnchangedTimeStillBroadcastsToEveryActiveEmployee() {
		String stored = String.valueOf(shiftRow(SHIFT_SCRATCH).get("start_time"));
		int before = notificationCount();

		// D-089: presence, not comparison. Re-sending exactly what is stored
		// notifies the whole company.
		assertThat(status(send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"start_time\":\"" + stored + "\"}"))).isEqualTo(200);

		// Every active employee of company 1 -- including the acting admin, who
		// is not excluded -- and no one from company 2 or the inactive row.
		assertThat(notificationCount() - before).isEqualTo(activeEmployeeCount(COMPANY_1));
		assertThat(notificationCountFor(COMPANY_2)).isZero();
		assertThat(notificationCountTo(STAFF_INACTIVE)).isZero();
		assertThat(notificationCountTo(ADMIN_1)).isPositive();
	}

	@Test
	void anExplicitNullTimeBroadcastsNothing() {
		int before = notificationCount();
		assertThat(status(send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"start_time\":null,\"end_time\":null}"))).isEqualTo(200);
		// ($body['start_time'] ?? null) !== null is false for an explicit null,
		// exactly as it is for an absent key.
		assertThat(notificationCount()).isEqualTo(before);
	}

	@Test
	void aNameOnlyChangeBroadcastsNothing() {
		int before = notificationCount();
		send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1, "{\"name\":\"Renamed Again\"}");
		assertThat(notificationCount()).isEqualTo(before);
	}

	@Test
	void theBroadcastCarriesTheShiftReference() {
		send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1, "{\"start_time\":\"10:00\"}");
		Map<String, Object> notification = latestNotification();
		assertThat(notification.get("notification_type")).isEqualTo("shift_time_changed");
		assertThat(notification.get("reference_type")).isEqualTo("shift");
		assertThat(number(notification.get("reference_id"))).isEqualTo(SHIFT_SCRATCH);
		assertThat(number(notification.get("from_employee_id"))).isEqualTo(ADMIN_1);
	}

	// ------------------------------------------------------------------
	// update.php -- lookup scope and tenancy
	// ------------------------------------------------------------------

	@Test
	void aSoftDeletedShiftIsStillUpdatable() {
		// The lookup is id + company and omits is_active, so a row that list
		// and one both hide is still writable here.
		assertThat(status(send(UPDATE + "?id=" + SHIFT_SOFT_DELETED, HttpMethod.PUT, ADMIN_1,
				"{\"name\":\"Touched While Deleted\"}"))).isEqualTo(200);
		assertThat(shiftRow(SHIFT_SOFT_DELETED).get("name")).isEqualTo("Touched While Deleted");
		// ...and it stays hidden from the read endpoints.
		assertThat(idsOf(get(LIST, ADMIN_1, 200))).doesNotContain(SHIFT_SOFT_DELETED);
		get(ONE + "?id=" + SHIFT_SOFT_DELETED, ADMIN_1, 404);
	}

	@Test
	void updateOfAForeignShiftIsShiftNotFoundAndWritesNothing() {
		Map<String, Object> before = shiftRow(SHIFT_OTHER_COMPANY);

		Map<String, Object> body = send(UPDATE + "?id=" + SHIFT_OTHER_COMPANY, HttpMethod.PUT, ADMIN_1,
				"{\"name\":\"Cross Tenant\"}").getBody();
		assertThat(body.get("success")).isEqualTo(false);

		// The pre-read is company-scoped, so the id-only UPDATE below it is
		// never reached. The other company's row is untouched and, unlike
		// request_types before D-088, is never echoed back either.
		assertThat(shiftRow(SHIFT_OTHER_COMPANY)).isEqualTo(before);
		assertThat(body).doesNotContainKey("data");
	}

	@Test
	void updateOfACompanyLessShiftIsAlsoAMiss() {
		// company_id IS NULL never equals a bound company id, so such a row is
		// unreachable through every endpoint in the module.
		assertThat(status(send(UPDATE + "?id=" + SHIFT_NO_COMPANY, HttpMethod.PUT, ADMIN_1,
				"{\"name\":\"Global\"}"))).isEqualTo(404);
	}

	@Test
	void updateRejectsANonStringMergedTimeBeforeParsingIt() {
		// is_string(), not a cast: a JSON number fails here rather than being
		// stringified into something the validator would accept.
		assertThat(status(send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"start_time\":900}"))).isEqualTo(400);
		assertThat(status(send(UPDATE + "?id=" + SHIFT_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"end_time\":true}"))).isEqualTo(400);
	}

	// ------------------------------------------------------------------
	// delete.php
	// ------------------------------------------------------------------

	@Test
	void deleteSoftDeletesAndOmitsTheDataKeyEntirely() {
		long id = insertScratchShift("To Delete");

		Map<String, Object> body = send(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN_1, null).getBody();
		assertThat(body.get("success")).isEqualTo(true);
		// ok(SHIFT_DELETED) passes no $data at all, so the key is absent --
		// not present with a null value.
		assertThat(body.keySet()).containsExactly("success", "message");

		assertThat(number(shiftRow(id).get("is_active"))).isZero();
		assertThat(idsOf(get(LIST, ADMIN_1, 200))).doesNotContain(id);
	}

	@Test
	void deletingAnAlreadyDeletedShiftSucceedsAgain() {
		long id = insertScratchShift("Delete Twice");

		assertThat(status(send(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(200);
		// The lookup does not filter is_active, so the row is still found and
		// the second soft delete is a successful no-op.
		assertThat(status(send(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(200);
		assertThat(number(shiftRow(id).get("is_active"))).isZero();
	}

	@Test
	void deleteOfAForeignShiftIsShiftNotFoundAndLeavesItActive() {
		assertThat(status(send(DELETE + "?id=" + SHIFT_OTHER_COMPANY, HttpMethod.DELETE, ADMIN_1, null)))
				.isEqualTo(404);
		assertThat(number(shiftRow(SHIFT_OTHER_COMPANY).get("is_active"))).isEqualTo(1);
	}

	@Test
	void deleteRejectsAFalsyId() {
		assertThat(status(send(DELETE + "?id=0", HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(400);
	}

	// ------------------------------------------------------------------
	// Guards: method, role, permission, active company
	// ------------------------------------------------------------------

	@Test
	void theMethodGuardRunsBeforeAuthentication() {
		// PHP checks REQUEST_METHOD as its first statement, so an
		// unauthenticated request to the wrong verb is 405, never 401.
		assertThat(anonymous(LIST, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(CREATE, HttpMethod.GET)).isEqualTo(405);
		assertThat(anonymous(UPDATE, HttpMethod.POST)).isEqualTo(405);
		assertThat(anonymous(DELETE, HttpMethod.GET)).isEqualTo(405);
		// ...and the right verb without a token is 401.
		assertThat(anonymous(LIST, HttpMethod.GET)).isEqualTo(401);
	}

	@Test
	void companyAdminHrAndManagerAllReachAllFiveEndpoints() {
		for (long actor : new long[] { ADMIN_1, HR_1, MANAGER_1 }) {
			get(LIST, actor, 200);
			get(ONE + "?id=" + SHIFT_NEWEST, actor, 200);

			long created = number(dataOf(send(CREATE, HttpMethod.POST, actor,
					"{\"name\":\"By " + actor + "\",\"start_time\":\"09:00\",\"end_time\":\"17:00\"}")
					.getBody()).get("id"));
			assertThat(status(send(UPDATE + "?id=" + created, HttpMethod.PUT, actor,
					"{\"name\":\"Edited\"}"))).isEqualTo(200);
			assertThat(status(send(DELETE + "?id=" + created, HttpMethod.DELETE, actor, null))).isEqualTo(200);
		}
	}

	@Test
	void noEndpointGatesOnCanCompanySettings() {
		// D-087 preserves the absence of a permission gate here. HR_1 has no
		// hr_permissions row at all -- which the enforcer denies by default --
		// so if any of these called it, every one of these would be 403.
		assertThat(hasNoPermissionsRow(HR_1)).isTrue();
		get(LIST, HR_1, 200);
		long created = number(dataOf(send(CREATE, HttpMethod.POST, HR_1,
				"{\"name\":\"HR No Perms\",\"start_time\":\"09:00\",\"end_time\":\"17:00\"}").getBody())
				.get("id"));
		assertThat(status(send(DELETE + "?id=" + created, HttpMethod.DELETE, HR_1, null))).isEqualTo(200);
	}

	@Test
	void aPlainEmployeeIsForbiddenEverywhere() {
		get(LIST, EMPLOYEE_1, 403);
		get(ONE + "?id=" + SHIFT_NEWEST, EMPLOYEE_1, 403);
		assertThat(status(send(CREATE, HttpMethod.POST, EMPLOYEE_1,
				"{\"name\":\"Nope\",\"start_time\":\"09:00\",\"end_time\":\"17:00\"}"))).isEqualTo(403);
		assertThat(status(send(UPDATE + "?id=" + SHIFT_NEWEST, HttpMethod.PUT, EMPLOYEE_1, "{}"))).isEqualTo(403);
		assertThat(status(send(DELETE + "?id=" + SHIFT_NEWEST, HttpMethod.DELETE, EMPLOYEE_1, null)))
				.isEqualTo(403);
	}

	@Test
	void aSuspendedCompanyIsRefusedAfterAuthentication() {
		get(LIST, ADMIN_SUSPENDED, 403);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/**
	 * D-096 retrospective for a second delivered package.
	 *
	 * <p>Not because shifts need their own zero-date specification -- that
	 * rule lives in LegacyJdbcValues and has its own test. This proves the
	 * workforce package actually consumes the shared reader, so the
	 * correction did not silently become an employees-only fix.
	 */
	@Test
	void aZeroCreatedAtReachesTheWireAsItsLiteralString() {
		long id = 205199L;
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = \'\'");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time,"
					+ " is_active, created_at) VALUES (" + id + ", " + COMPANY_1
					+ ", \'Zero Dated\', \'09:00:00\', \'17:00:00\', 1,"
					+ " \'0000-00-00 00:00:00\')");
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}

		Map<String, Object> row = dataOf(get(ONE + "?id=" + id, ADMIN_1, 200));
		assertThat(row.get("created_at"))
				.isEqualTo("0000-00-00 00:00:00");
	}
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

	private static String messageOf(Map<String, Object> body) {
		return String.valueOf(body.get("message"));
	}

	private static List<Long> idsOf(Map<String, Object> body) {
		List<Long> ids = new ArrayList<>();
		for (Object row : (List<?>) body.get("data")) {
			ids.add(((Number) ((Map<?, ?>) row).get("id")).longValue());
		}
		return ids;
	}

	private static Map<String, Object> rowFor(Map<String, Object> body, long shiftId) {
		for (Object row : (List<?>) body.get("data")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> shift = (Map<String, Object>) row;
			if (((Number) shift.get("id")).longValue() == shiftId) {
				return shift;
			}
		}
		throw new IllegalStateException("no row for " + shiftId);
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == HR_1 ? "hr"
				: employeeId == EMPLOYEE_1 ? "employee" : "company_admin";
		long companyId = employeeId == ADMIN_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	// ---- direct database reads, so assertions do not depend on the endpoints --

	/**
	 * Read directly, so an assertion about stored state never depends on the
	 * endpoint that wrote it. {@code is_active} is cast in SQL because the
	 * driver hands back {@code tinyint(1)} as a Boolean through
	 * {@code getObject}, which would make this helper disagree with the
	 * response body for no reason that belongs to the port.
	 */
	private static Map<String, Object> shiftRow(long id) {
		return queryOne("SELECT id, company_id, name, start_time, end_time, days_off,"
				+ " CAST(is_active AS SIGNED) AS is_active FROM shifts WHERE id = " + id);
	}

	private static Map<String, Object> latestNotification() {
		return queryOne("SELECT notification_type, reference_type, reference_id, from_employee_id"
				+ " FROM notifications ORDER BY id DESC LIMIT 1");
	}

	private static int notificationCount() {
		return count("SELECT COUNT(*) FROM notifications");
	}

	private static int notificationCountFor(long companyId) {
		return count("SELECT COUNT(*) FROM notifications WHERE company_id = " + companyId);
	}

	private static int notificationCountTo(long employeeId) {
		return count("SELECT COUNT(*) FROM notifications WHERE to_employee_id = " + employeeId);
	}

	private static int activeEmployeeCount(long companyId) {
		return count("SELECT COUNT(*) FROM employees WHERE company_id = " + companyId + " AND is_active = 1");
	}

	private static boolean hasNoPermissionsRow(long employeeId) {
		return count("SELECT COUNT(*) FROM hr_permissions WHERE employee_id = " + employeeId) == 0;
	}

	private static long insertScratchShift(String name) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.executeUpdate("INSERT INTO shifts (company_id, name, start_time, end_time, is_active)"
					+ " VALUES (" + COMPANY_1 + ", '" + name + "', '09:00:00', '17:00:00', 1)",
					Statement.RETURN_GENERATED_KEYS);
			try (ResultSet keys = st.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static int count(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
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
			Map<String, Object> row = new java.util.LinkedHashMap<>();
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
					  (20501, 'Shift Co', '+201000020501', 'active', '2025-01-15 09:00:00'),
					  (20502, 'Shift Other Co', '+201000020502', 'active', '2025-01-15 09:00:00'),
					  (20503, 'Shift Suspended Co', '+201000020503', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20511, 20501, 'Main', 1, '2025-03-01 10:00:00'),
					  (20512, 20502, 'Other', 1, '2025-03-01 10:00:00'),
					  (20513, 20503, 'Suspended', 1, '2025-03-01 10:00:00')
					""");

			employee(st, ADMIN_1, COMPANY_1, 20511L, "company_admin", 1, "+201000205011", "Admin One");
			employee(st, HR_1, COMPANY_1, 20511L, "hr", 1, "+201000205012", "Hr One");
			employee(st, MANAGER_1, COMPANY_1, 20511L, "manager", 1, "+201000205013", "Manager One");
			employee(st, EMPLOYEE_1, COMPANY_1, 20511L, "employee", 1, "+201000205014", "Employee One");
			employee(st, STAFF_EXTRA, COMPANY_1, 20511L, "employee", 1, "+201000205015", "Staff Extra");
			// inactive: must not receive a broadcast
			employee(st, STAFF_INACTIVE, COMPANY_1, 20511L, "employee", 0, "+201000205016", "Staff Inactive");
			employee(st, ADMIN_2, COMPANY_2, 20512L, "company_admin", 1, "+201000205021", "Admin Two");
			employee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 20513L, "company_admin", 1,
					"+201000205031", "Admin Suspended");

			// Ordering fixture: created_at DESC, id DESC. MIDDLE and
			// MIDDLE_TWIN share a timestamp so only the id tiebreak separates them.
			shift(st, SHIFT_OLDEST, COMPANY_1, "Oldest", "09:00:00", "17:00:00", "'Fri'", 1,
					"2025-01-01 08:00:00");
			shift(st, SHIFT_OUT_OF_RANGE_TIMES, COMPANY_1, "Out Of Range", "800:00:00", "801:00:00", "NULL", 1,
					"2025-01-02 08:00:00");
			shift(st, SHIFT_MIDDLE, COMPANY_1, "Middle", "10:00:00", "18:00:00", "NULL", 1,
					"2025-02-01 08:00:00");
			shift(st, SHIFT_MIDDLE_TWIN, COMPANY_1, "Middle Twin", "10:00:00", "18:00:00", "NULL", 1,
					"2025-02-01 08:00:00");
			shift(st, SHIFT_NEWEST, COMPANY_1, "Newest", "11:00:00", "19:00:00", "'Sat'", 1,
					"2025-03-01 08:00:00");
			shift(st, SHIFT_SOFT_DELETED, COMPANY_1, "Soft Deleted", "09:00:00", "17:00:00", "NULL", 0,
					"2025-03-02 08:00:00");
			shift(st, SHIFT_OTHER_COMPANY, COMPANY_2, "Other Company", "09:00:00", "17:00:00", "NULL", 1,
					"2025-03-03 08:00:00");
			// company_id IS NULL -- unreachable through every endpoint
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, is_active, created_at)"
					+ " VALUES (" + SHIFT_NO_COMPANY + ", NULL, 'No Company', '09:00:00', '17:00:00', 1,"
					+ " '2025-03-04 08:00:00')");
			// scratch rows for the mutation tests, kept out of the ordering fixture
			shift(st, SHIFT_SCRATCH, COMPANY_1, "Scratch", "09:00:00", "17:00:00", "NULL", 0,
					"2025-04-01 08:00:00");
			shift(st, SHIFT_SCRATCH_TWO, COMPANY_1, "Scratch Two", "09:00:00", "17:00:00", "'Fri,Sat'", 0,
					"2025-04-02 08:00:00");
		}
	}

	private static void employee(
			Statement st, long id, long companyId, long branchId, String role, int isActive,
			String phone, String name) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, role, is_active,"
				+ " join_request_status, phone, first_name, last_name, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", '" + id + "', '" + role + "', " + isActive
				+ ", 'accepted', '" + phone + "', '" + name + "', 'Test', '2025-01-20 09:00:00')");
	}

	private static void shift(
			Statement st, long id, long companyId, String name, String start, String end, String daysOff,
			int isActive, String createdAt) throws Exception {
		st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active,"
				+ " created_at) VALUES (" + id + ", " + companyId + ", '" + name + "', '" + start + "', '"
				+ end + "', " + daysOff + ", " + isActive + ", '" + createdAt + "')");
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
		try (InputStream stream = LegacyShiftEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
