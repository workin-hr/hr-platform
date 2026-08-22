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
 * Wave 12.5 slice 3: all five {@code /apis/api/request_types/*.php} endpoints.
 *
 * <p>Weighted towards what a clean-room implementation would get wrong: the
 * four-value truthy set, {@code counts_as_paid_leave}'s opposite default,
 * three authority levels in one module, and D-088's ordering -- a foreign id
 * with an empty body must still answer {@code nothing_to_update}, because the
 * fix scopes the re-read rather than adding a pre-check.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyRequestTypeEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/request_types/list.php";
	private static final String ONE = "/apis/api/request_types/one.php";
	private static final String CREATE = "/apis/api/request_types/create.php";
	private static final String UPDATE = "/apis/api/request_types/update.php";
	private static final String DELETE = "/apis/api/request_types/delete.php";

	private static final long COMPANY_1 = 20601L;
	private static final long COMPANY_2 = 20602L;
	private static final long COMPANY_SUSPENDED = 20603L;

	private static final long ADMIN_1 = 206011L;
	private static final long HR_WITH_SETTINGS = 206012L;
	private static final long HR_NO_SETTINGS = 206013L;
	private static final long MANAGER_1 = 206014L;
	private static final long EMPLOYEE_1 = 206015L;
	private static final long ADMIN_2 = 206021L;
	private static final long EMPLOYEE_2 = 206022L;
	private static final long ADMIN_SUSPENDED = 206031L;

	private static final long TYPE_OLDEST = 206101L;
	private static final long TYPE_MIDDLE = 206102L;
	private static final long TYPE_MIDDLE_TWIN = 206103L;
	private static final long TYPE_NEWEST = 206104L;
	private static final long TYPE_INACTIVE = 206105L;
	private static final long TYPE_OTHER_COMPANY = 206106L;
	private static final long TYPE_IN_USE = 206107L;
	private static final long TYPE_USED_BY_OTHER_COMPANY = 206108L;
	private static final long TYPE_SCRATCH = 206109L;

	private static final long EXCEPTION_TYPE_1 = 206201L;
	private static final long EXCEPTION_TYPE_INACTIVE = 206202L;
	private static final long EXCEPTION_TYPE_OTHER_COMPANY = 206203L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("legacy/phase1_extensions.schema.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the request_types fixture", ex);
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
	void listDefaultsToActiveOnlyNewestFirstWithTheIdTiebreak() {
		Map<String, Object> body = get(LIST + "?limit=100", ADMIN_1, 200);

		assertThat(idsOf(body)).containsSubsequence(
				TYPE_NEWEST, TYPE_MIDDLE_TWIN, TYPE_MIDDLE, TYPE_OLDEST);
		assertThat(idsOf(body)).doesNotContain(TYPE_INACTIVE, TYPE_OTHER_COMPANY);
	}

	@Test
	void listCarriesThePaginationMetaInPhpsKeyOrder() {
		Map<String, Object> body = get(LIST + "?limit=2", ADMIN_1, 200);

		assertThat(body.keySet()).containsExactly("success", "message", "data", "meta");
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) body.get("meta");
		assertThat(meta.keySet()).containsExactly(
				"page", "limit", "total", "total_pages", "has_next", "has_previous");
		assertThat(number(meta.get("limit"))).isEqualTo(2);
		assertThat(meta.get("has_previous")).isEqualTo(false);
		assertThat(meta.get("has_next")).isEqualTo(true);
	}

	@Test
	void anIsActiveKeyOverridesTheDefaultAndIsIntCast() {
		// isset() decides, then (int) coerces -- so is_active=0 really filters.
		assertThat(idsOf(get(LIST + "?is_active=0&limit=100", ADMIN_1, 200))).contains(TYPE_INACTIVE);

		// ...and a non-numeric value casts to 0, so it selects the inactive set
		// rather than falling back to the default of 1.
		assertThat(idsOf(get(LIST + "?is_active=abc&limit=100", ADMIN_1, 200))).contains(TYPE_INACTIVE);
		assertThat(idsOf(get(LIST + "?is_active=abc&limit=100", ADMIN_1, 200))).doesNotContain(TYPE_NEWEST);

		// present-but-empty is isset()-true and also casts to 0
		assertThat(idsOf(get(LIST + "?is_active=&limit=100", ADMIN_1, 200))).contains(TYPE_INACTIVE);
	}

	@Test
	void searchFiltersOnNameAndAnEmptySearchDoesNot() {
		assertThat(idsOf(get(LIST + "?search=Newest&limit=100", ADMIN_1, 200))).containsExactly(TYPE_NEWEST);
		// trim() then "" -> null, so ?search= is no filter at all.
		assertThat(idsOf(get(LIST + "?search=&limit=100", ADMIN_1, 200)))
				.hasSameSizeAs(idsOf(get(LIST + "?limit=100", ADMIN_1, 200)));
		assertThat(idsOf(get(LIST + "?search=%20%20&limit=100", ADMIN_1, 200)))
				.hasSameSizeAs(idsOf(get(LIST + "?limit=100", ADMIN_1, 200)));
	}

	@Test
	void anyAuthenticatedRoleCanReadTheList() {
		// requireAuth() with no role list -- an ordinary employee included.
		for (long actor : new long[] { ADMIN_1, HR_NO_SETTINGS, MANAGER_1, EMPLOYEE_1 }) {
			get(LIST, actor, 200);
		}
	}

	@Test
	void aCompanySeesOnlyItsOwnTypes() {
		assertThat(idsOf(get(LIST + "?limit=100", ADMIN_2, 200)))
				.containsOnly(TYPE_OTHER_COMPANY, TYPE_USED_BY_OTHER_COMPANY);
	}

	// ------------------------------------------------------------------
	// one.php
	// ------------------------------------------------------------------

	@Test
	void oneIsReadableByAnyRoleAndScopedToTheCompany() {
		assertThat(number(dataOf(get(ONE + "?id=" + TYPE_NEWEST, EMPLOYEE_1, 200)).get("id")))
				.isEqualTo(TYPE_NEWEST);
		// an inactive type is still readable by id -- one.php has no is_active predicate
		assertThat(number(dataOf(get(ONE + "?id=" + TYPE_INACTIVE, ADMIN_1, 200)).get("id")))
				.isEqualTo(TYPE_INACTIVE);
		get(ONE + "?id=" + TYPE_OTHER_COMPANY, ADMIN_1, 404);
	}

	@Test
	void oneUsesRequiredNotTheShiftsIdGuard() {
		// required($_GET, ['id']): only missing or exactly empty fails, and it
		// fails with the {field} replacement rather than shifts' id_required.
		Map<String, Object> missing = get(ONE, ADMIN_1, 400);
		assertThat(missing).doesNotContainKey("data");
		get(ONE + "?id=", ADMIN_1, 400);

		// "0" and "abc" both PASS required() and then cast to 0, which simply
		// misses -- where shifts rejects both at the guard with a 400.
		get(ONE + "?id=0", ADMIN_1, 404);
		get(ONE + "?id=abc", ADMIN_1, 404);
	}

	// ------------------------------------------------------------------
	// create.php
	// ------------------------------------------------------------------

	@Test
	void createStoresTheDefaultsWhenOnlyANameIsGiven() {
		Map<String, Object> row = dataOf(created("{\"name\":\"Defaults\"}"));

		assertThat(number(row.get("is_active"))).isEqualTo(1);
		assertThat(number(row.get("deduct_balance"))).isZero();
		// The asymmetric one: absent means 1 here and 0 for its two siblings.
		assertThat(number(row.get("counts_as_paid_leave"))).isEqualTo(1);
		assertThat(number(row.get("add_attendance_exception"))).isZero();
		assertThat(row.get("exception_type_id")).isNull();
	}

	@Test
	void onlyFourValuesAreTruthyForTheBooleanFlags() {
		for (String truthy : new String[] { "true", "1", "\"1\"", "\"true\"" }) {
			Map<String, Object> row = dataOf(created(
					"{\"name\":\"Truthy " + truthy.replace("\"", "") + "\",\"deduct_balance\":" + truthy + "}"));
			assertThat(number(row.get("deduct_balance")))
					.withFailMessage("%s should be truthy", truthy).isEqualTo(1);
		}
		// Everything else is 0 -- including the ones a reasonable coercion accepts.
		String[] falsy = { "\"yes\"", "\"TRUE\"", "\"on\"", "2", "1.0", "\"0\"", "false", "null" };
		for (String value : falsy) {
			Map<String, Object> row = dataOf(created(
					"{\"name\":\"Falsy " + value.replace("\"", "") + "\",\"deduct_balance\":" + value + "}"));
			assertThat(number(row.get("deduct_balance")))
					.withFailMessage("%s should be falsy", value).isZero();
		}
	}

	@Test
	void countsAsPaidLeaveFollowsTheSameTruthySetOnceItIsPresent() {
		// Present but not truthy -> 0, which is the opposite of its absent default.
		assertThat(number(dataOf(created(
				"{\"name\":\"Paid Explicit False\",\"counts_as_paid_leave\":false}"))
				.get("counts_as_paid_leave"))).isZero();
		assertThat(number(dataOf(created(
				"{\"name\":\"Paid Explicit Yes\",\"counts_as_paid_leave\":\"yes\"}"))
				.get("counts_as_paid_leave"))).isZero();
	}

	@Test
	void isActiveIsAPlainIntCastNotTheBooleanHelper() {
		// (int) ($body['is_active'] ?? 1) -- so "2" stores 2 and "true" stores 0,
		// neither of which the boolean helper would have produced.
		assertThat(number(dataOf(created("{\"name\":\"Active Two\",\"is_active\":2}")).get("is_active")))
				.isEqualTo(2);
		assertThat(number(dataOf(created("{\"name\":\"Active True\",\"is_active\":\"true\"}"))
				.get("is_active"))).isZero();
	}

	@Test
	void theExceptionTypeIsOnlyResolvedWhenTheFlagIsTruthy() {
		// flag off -> null, whatever the id says
		assertThat(dataOf(created("{\"name\":\"No Flag\",\"exception_type_id\":" + EXCEPTION_TYPE_1 + "}"))
				.get("exception_type_id")).isNull();

		// flag on with a valid same-company active id -> stored
		assertThat(number(dataOf(created("{\"name\":\"With Exception\",\"add_attendance_exception\":true,"
				+ "\"exception_type_id\":" + EXCEPTION_TYPE_1 + "}")).get("exception_type_id")))
				.isEqualTo(EXCEPTION_TYPE_1);
	}

	@Test
	void anUnusableExceptionTypeIsNulledRatherThanRejected() {
		// The validator returns null instead of failing, so a foreign or
		// deactivated id silently produces a type with no exception type --
		// a 201, not a 404.
		for (long id : new long[] { EXCEPTION_TYPE_OTHER_COMPANY, EXCEPTION_TYPE_INACTIVE, 999_999L }) {
			Map<String, Object> row = dataOf(created("{\"name\":\"Bad Exception " + id + "\","
					+ "\"add_attendance_exception\":true,\"exception_type_id\":" + id + "}"));
			assertThat(row.get("exception_type_id"))
					.withFailMessage("exception type %d should have been nulled", id).isNull();
			// ...and the flag itself is still stored as 1
			assertThat(number(row.get("add_attendance_exception"))).isEqualTo(1);
		}
	}

	@Test
	void createRequiresAName() {
		Map<String, Object> body = send(CREATE, HttpMethod.POST, ADMIN_1, "{\"is_active\":1}").getBody();
		assertThat(body.get("success")).isEqualTo(false);
		assertThat(body).doesNotContainKey("data");
	}

	@Test
	void createIsCompanyAdminOrHrOnly() {
		assertThat(status(send(CREATE, HttpMethod.POST, MANAGER_1, "{\"name\":\"By Manager\"}"))).isEqualTo(403);
		assertThat(status(send(CREATE, HttpMethod.POST, EMPLOYEE_1, "{\"name\":\"By Employee\"}"))).isEqualTo(403);
		// HR without can_company_settings still creates: no gate on this verb.
		assertThat(status(send(CREATE, HttpMethod.POST, HR_NO_SETTINGS, "{\"name\":\"By Ungated HR\"}")))
				.isEqualTo(201);
	}

	// ------------------------------------------------------------------
	// update.php -- including D-088
	// ------------------------------------------------------------------

	@Test
	void updateWritesOnlyTheWhitelistedFields() {
		send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"name\":\"Renamed\",\"company_id\":" + COMPANY_2 + ",\"id\":999}");

		Map<String, Object> row = typeRow(TYPE_SCRATCH);
		assertThat(row.get("name")).isEqualTo("Renamed");
		// company_id and id are not in $allowed_fields, so neither moved.
		assertThat(number(row.get("company_id"))).isEqualTo(COMPANY_1);
		assertThat(number(row.get("id"))).isEqualTo(TYPE_SCRATCH);
	}

	@Test
	void anEmptyWhitelistIsNothingToUpdate() {
		assertThat(status(send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, ADMIN_1, "{}"))).isEqualTo(400);
		// A body with only unrecognised keys is the same branch.
		assertThat(status(send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"nonsense\":1,\"company_id\":7}"))).isEqualTo(400);
		// So is a malformed document, which body() decodes to [].
		assertThat(status(send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, ADMIN_1, "not json")))
				.isEqualTo(400);
	}

	@Test
	void updateNormalisesTheFlagsBeforeWritingThem() {
		send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"deduct_balance\":\"true\",\"counts_as_paid_leave\":\"yes\"}");
		Map<String, Object> row = typeRow(TYPE_SCRATCH);
		assertThat(number(row.get("deduct_balance"))).isEqualTo(1);
		assertThat(number(row.get("counts_as_paid_leave"))).isZero();
	}

	@Test
	void sendingOnlyAnExceptionTypeIdClearsItBecauseTheFlagIsAbsent() {
		// Either key triggers the resolution, and the resolution reads
		// add_attendance_exception -- absent means falsy, so the column is nulled.
		send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"add_attendance_exception\":true,\"exception_type_id\":" + EXCEPTION_TYPE_1 + "}");
		assertThat(number(typeRow(TYPE_SCRATCH).get("exception_type_id"))).isEqualTo(EXCEPTION_TYPE_1);

		send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, ADMIN_1,
				"{\"exception_type_id\":" + EXCEPTION_TYPE_1 + "}");
		assertThat(typeRow(TYPE_SCRATCH).get("exception_type_id")).isNull();
	}

	@Test
	void aForeignIdWithAValidBodyIsNotFoundAndLeaksNothing() {
		Map<String, Object> before = typeRow(TYPE_OTHER_COMPANY);

		ResponseEntity<Map<String, Object>> response = send(
				UPDATE + "?id=" + TYPE_OTHER_COMPANY, HttpMethod.PUT, ADMIN_1, "{\"name\":\"Cross Tenant\"}");

		// D-088: the company-scoped re-read comes back empty, so 404 -- where
		// PHP's unscoped re-read returns the other company's row at 200.
		assertThat(status(response)).isEqualTo(404);
		assertThat(response.getBody()).doesNotContainKey("data");
		// The UPDATE was already company-scoped, so nothing was written either.
		assertThat(typeRow(TYPE_OTHER_COMPANY)).isEqualTo(before);
	}

	@Test
	void aForeignIdWithAnEmptyBodyIsNothingToUpdateNotNotFound() {
		// The ordering test D-088 exists to protect: an early existence check
		// would answer 404 here, because it would run before the whitelist.
		// PHP reaches NOTHING_TO_UPDATE first, and so does this.
		assertThat(status(send(UPDATE + "?id=" + TYPE_OTHER_COMPANY, HttpMethod.PUT, ADMIN_1, "{}")))
				.isEqualTo(400);
		assertThat(status(send(UPDATE + "?id=" + TYPE_OTHER_COMPANY, HttpMethod.PUT, ADMIN_1,
				"{\"unknown_field\":1}"))).isEqualTo(400);
		// Same for an id that exists nowhere at all.
		assertThat(status(send(UPDATE + "?id=999999", HttpMethod.PUT, ADMIN_1, "{}"))).isEqualTo(400);
	}

	@Test
	void updateIsCompanyAdminOrHrOnly() {
		assertThat(status(send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, MANAGER_1, "{\"name\":\"No\"}")))
				.isEqualTo(403);
		assertThat(status(send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, EMPLOYEE_1, "{\"name\":\"No\"}")))
				.isEqualTo(403);
		assertThat(status(send(UPDATE + "?id=" + TYPE_SCRATCH, HttpMethod.PUT, HR_NO_SETTINGS,
				"{\"name\":\"Ungated HR\"}"))).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// delete.php
	// ------------------------------------------------------------------

	@Test
	void deleteNeedsCanCompanySettingsUnlikeCreateAndUpdate() {
		long id = insertScratchType("Gated Delete");

		// HR can create and update without the permission, but not delete.
		assertThat(status(send(DELETE + "?id=" + id, HttpMethod.DELETE, HR_NO_SETTINGS, null))).isEqualTo(403);
		assertThat(typeRow(id)).isNotNull();

		assertThat(status(send(DELETE + "?id=" + id, HttpMethod.DELETE, HR_WITH_SETTINGS, null))).isEqualTo(200);
		assertThat(typeRow(id)).isNull();
	}

	@Test
	void theSettingsGateRunsBeforeTheIdIsEvenRead() {
		// PHP places the gate above required($_GET, ['id']), so a caller lacking
		// the permission gets 403 rather than the 400 a missing id would give.
		assertThat(status(send(DELETE, HttpMethod.DELETE, HR_NO_SETTINGS, null))).isEqualTo(403);
		assertThat(status(send(DELETE + "?id=999999", HttpMethod.DELETE, HR_NO_SETTINGS, null))).isEqualTo(403);
	}

	@Test
	void deleteIsAHardDelete() {
		long id = insertScratchType("Hard Delete");
		assertThat(status(send(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(200);
		// gone from the table, not merely deactivated
		assertThat(typeRow(id)).isNull();
	}

	@Test
	void aTypeInUseByThisCompanyCannotBeDeleted() {
		assertThat(status(send(DELETE + "?id=" + TYPE_IN_USE, HttpMethod.DELETE, ADMIN_1, null))).isEqualTo(409);
		assertThat(typeRow(TYPE_IN_USE)).isNotNull();
	}

	/**
	 * The distinction the discovery originally got wrong (§E.5): a foreign
	 * company's request does not trigger the <em>409 pre-check</em>, but it
	 * does still block the delete -- at the database, not in the application.
	 */
	@Test
	void aForeignReferenceMissesThePreCheckAndIsRefusedByTheForeignKeyInstead() {
		// The in-use count joins through employees, so only this company's
		// requests count. A request row from company 2 referencing this id must
		// not protect it.
		assertThat(requestCountFor(TYPE_USED_BY_OTHER_COMPANY)).isPositive();
		assertThat(status(send(DELETE + "?id=" + TYPE_USED_BY_OTHER_COMPANY, HttpMethod.DELETE, ADMIN_2, null)))
				.isEqualTo(409);

		// ...and the mirror case, which is where legacy's own check turns out to
		// be narrower than the database's.
		//
		// Company 1 owns a type that ONLY company 2's employee references. The
		// in-use count joins through employees and so sees nothing, meaning the
		// 409 branch does not fire -- exactly as the discovery describes. But
		// fk_request_request_type (schema:1688) references request_types(id)
		// with no ON DELETE clause, so it is RESTRICT, and the DELETE that
		// follows violates it.
		//
		// PHP does not catch that: the PDO exception propagates out of
		// delete.php, so the client gets whatever the error handler renders.
		// Phase 1 answers D-084's deterministic 500. The row survives either
		// way, so "another company's request does not block deletion" is true
		// of the 409 check and false of the outcome.
		long id = insertScratchType("Referenced By Other Company");
		insertRequest(id, EMPLOYEE_2);

		ResponseEntity<Map<String, Object>> response =
				send(DELETE + "?id=" + id, HttpMethod.DELETE, ADMIN_1, null);
		assertThat(status(response)).isEqualTo(500);
		assertThat(response.getBody().get("success")).isEqualTo(false);
		assertThat(response.getBody().get("message")).isEqualTo("Internal server error");
		// D-084: no data key, and nothing of the SQL error reaches the client.
		assertThat(response.getBody()).doesNotContainKey("data");

		// Not a 409, and not deleted either.
		assertThat(typeRow(id)).isNotNull();
	}

	@Test
	void deleteOfAForeignTypeIsNotFound() {
		assertThat(status(send(DELETE + "?id=" + TYPE_OTHER_COMPANY, HttpMethod.DELETE, ADMIN_1, null)))
				.isEqualTo(404);
		assertThat(typeRow(TYPE_OTHER_COMPANY)).isNotNull();
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

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> created(String json) {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1, json);
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		return response.getBody();
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

	private static List<Long> idsOf(Map<String, Object> body) {
		List<Long> ids = new ArrayList<>();
		for (Object row : (List<?>) body.get("data")) {
			ids.add(((Number) ((Map<?, ?>) row).get("id")).longValue());
		}
		return ids;
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	private String tokenFor(long employeeId) {
		String role = employeeId == MANAGER_1 ? "manager"
				: employeeId == HR_WITH_SETTINGS || employeeId == HR_NO_SETTINGS ? "hr"
				: employeeId == EMPLOYEE_1 || employeeId == EMPLOYEE_2 ? "employee" : "company_admin";
		long companyId = employeeId == ADMIN_2 || employeeId == EMPLOYEE_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static Map<String, Object> typeRow(long id) {
		return queryOne("SELECT id, company_id, name, CAST(is_active AS SIGNED) AS is_active,"
				+ " CAST(deduct_balance AS SIGNED) AS deduct_balance,"
				+ " CAST(counts_as_paid_leave AS SIGNED) AS counts_as_paid_leave,"
				+ " CAST(add_attendance_exception AS SIGNED) AS add_attendance_exception,"
				+ " exception_type_id FROM request_types WHERE id = " + id);
	}

	private static int requestCountFor(long requestTypeId) {
		return count("SELECT COUNT(*) FROM requests WHERE request_type_id = " + requestTypeId);
	}

	private static long insertScratchType(String name) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.executeUpdate("INSERT INTO request_types (company_id, name) VALUES ("
					+ COMPANY_1 + ", '" + name + "')", Statement.RETURN_GENERATED_KEYS);
			try (ResultSet keys = st.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void insertRequest(long requestTypeId, long employeeId) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO requests (employee_id, request_type_id, status, created_at)"
					+ " VALUES (" + employeeId + ", " + requestTypeId + ", 'pending', '2025-06-01 09:00:00')");
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
					  (20601, 'RT Co', '+201000020601', 'active', '2025-01-15 09:00:00'),
					  (20602, 'RT Other Co', '+201000020602', 'active', '2025-01-15 09:00:00'),
					  (20603, 'RT Suspended Co', '+201000020603', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20611, 20601, 'Main', 1, '2025-03-01 10:00:00'),
					  (20612, 20602, 'Other', 1, '2025-03-01 10:00:00'),
					  (20613, 20603, 'Suspended', 1, '2025-03-01 10:00:00')
					""");

			employee(st, ADMIN_1, COMPANY_1, 20611L, "company_admin", "+201000206011", "Admin One");
			employee(st, HR_WITH_SETTINGS, COMPANY_1, 20611L, "hr", "+201000206012", "Hr Gated");
			employee(st, HR_NO_SETTINGS, COMPANY_1, 20611L, "hr", "+201000206013", "Hr Ungated");
			employee(st, MANAGER_1, COMPANY_1, 20611L, "manager", "+201000206014", "Manager One");
			employee(st, EMPLOYEE_1, COMPANY_1, 20611L, "employee", "+201000206015", "Employee One");
			employee(st, ADMIN_2, COMPANY_2, 20612L, "company_admin", "+201000206021", "Admin Two");
			employee(st, EMPLOYEE_2, COMPANY_2, 20612L, "employee", "+201000206022", "Employee Two");
			employee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, 20613L, "company_admin",
					"+201000206031", "Admin Suspended");

			// ADMIN_1, ADMIN_2 and HR_WITH_SETTINGS carry can_company_settings;
			// HR_NO_SETTINGS has no hr_permissions row at all, which the
			// enforcer denies by default.
			//
			// The company admins need the row because Phase 1 issues only
			// employee-scoped legacy tokens (D-042), so
			// require_company_settings_access()'s company-session bypass has no
			// equivalent here and the gate applies to every role that reaches
			// it. That is pre-existing, documented on
			// LegacyHrPermissionEnforcer, and the same shape Wave 12.1's
			// exception-type fixture uses -- not something this slice
			// introduces.
			st.execute("INSERT INTO hr_permissions (employee_id, can_company_settings) VALUES ("
					+ ADMIN_1 + ", 1), (" + ADMIN_2 + ", 1), (" + HR_WITH_SETTINGS + ", 1)");

			st.execute("""
					INSERT INTO exception_types (id, company_id, name, is_active, created_at) VALUES
					  (206201, 20601, 'RT Late', 1, '2025-02-01 09:00:00'),
					  (206202, 20601, 'RT Deactivated', 0, '2025-02-01 09:00:00'),
					  (206203, 20602, 'RT Other Company', 1, '2025-02-01 09:00:00')
					""");

			requestType(st, TYPE_OLDEST, COMPANY_1, "Oldest", 1, "2025-01-01 08:00:00");
			requestType(st, TYPE_MIDDLE, COMPANY_1, "Middle", 1, "2025-02-01 08:00:00");
			requestType(st, TYPE_MIDDLE_TWIN, COMPANY_1, "Middle Twin", 1, "2025-02-01 08:00:00");
			requestType(st, TYPE_NEWEST, COMPANY_1, "Newest", 1, "2025-03-01 08:00:00");
			requestType(st, TYPE_INACTIVE, COMPANY_1, "Inactive", 0, "2025-03-02 08:00:00");
			requestType(st, TYPE_OTHER_COMPANY, COMPANY_2, "Other Company", 1, "2025-03-03 08:00:00");
			requestType(st, TYPE_IN_USE, COMPANY_1, "In Use", 1, "2025-03-04 08:00:00");
			requestType(st, TYPE_USED_BY_OTHER_COMPANY, COMPANY_2, "Used By Two", 1, "2025-03-05 08:00:00");
			requestType(st, TYPE_SCRATCH, COMPANY_1, "Scratch", 1, "2025-03-06 08:00:00");

			// company 1's own employee references TYPE_IN_USE -> blocks
			st.execute("INSERT INTO requests (employee_id, request_type_id, status, created_at) VALUES ("
					+ EMPLOYEE_1 + ", " + TYPE_IN_USE + ", 'pending', '2025-06-01 09:00:00')");
			// company 2's employee references company 2's own type -> blocks for company 2
			st.execute("INSERT INTO requests (employee_id, request_type_id, status, created_at) VALUES ("
					+ EMPLOYEE_2 + ", " + TYPE_USED_BY_OTHER_COMPANY + ", 'pending', '2025-06-01 09:00:00')");
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

	private static void requestType(
			Statement st, long id, long companyId, String name, int isActive, String createdAt)
			throws Exception {
		st.execute("INSERT INTO request_types (id, company_id, name, is_active, created_at) VALUES ("
				+ id + ", " + companyId + ", '" + name + "', " + isActive + ", '" + createdAt + "')");
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
		try (InputStream stream = LegacyRequestTypeEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
