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
 * Wave 12.7 slice 1: six of {@code requests}' seven endpoints --
 * {@code approve.php} is a separate slice, see
 * {@link LegacyRequestService}'s class javadoc.
 *
 * <p>Weighted towards the measured asymmetries a clean-room port would miss:
 * {@code one.php}'s explicit 404/403 against every other endpoint's default
 * 400 for the identical "not found"/"already decided" conditions; the
 * whitelist-before-lookup ordering that answers {@code nothing_to_update}
 * for an empty body even against a foreign id; {@code reject.php} storing an
 * empty-string reply verbatim rather than normalising it to {@code NULL};
 * and {@code create.php}'s type check accepting a deactivated
 * {@code request_type}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyRequestEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/requests/list.php";
	private static final String ONE = "/apis/api/requests/one.php";
	private static final String CREATE = "/apis/api/requests/create.php";
	private static final String UPDATE = "/apis/api/requests/update.php";
	private static final String DELETE = "/apis/api/requests/delete.php";
	private static final String REJECT = "/apis/api/requests/reject.php";

	private static final long COMPANY_1 = 20701L;
	private static final long COMPANY_2 = 20702L;
	private static final long COMPANY_SUSPENDED = 20703L;

	private static final long BRANCH_1A = 20711L;
	private static final long BRANCH_1B = 20712L;
	private static final long BRANCH_2 = 20713L;

	private static final long ADMIN_1 = 207011L;
	private static final long HR_1 = 207012L;
	private static final long MANAGER_1A = 207013L;
	private static final long EMPLOYEE_1A = 207014L;
	private static final long EMPLOYEE_1A_TWIN = 207015L;
	private static final long EMPLOYEE_1B = 207016L;
	private static final long ADMIN_2 = 207021L;
	private static final long EMPLOYEE_2 = 207022L;
	private static final long ADMIN_SUSPENDED = 207031L;

	private static final long TYPE_1 = 207101L;
	private static final long TYPE_1_INACTIVE = 207102L;
	private static final long TYPE_2 = 207103L;

	private static final long REQUEST_PENDING_1A = 207201L;
	private static final long REQUEST_PENDING_1B = 207202L;
	private static final long REQUEST_APPROVED_1A = 207203L;
	private static final long REQUEST_PENDING_2 = 207204L;
	private static final long REQUEST_PENDING_1A_MARCH = 207205L;

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
			throw new IllegalStateException("could not prepare the requests fixture", ex);
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
	void employeeListSeesOnlyItsOwnRequests() {
		Map<String, Object> body = get(LIST + "?limit=100", EMPLOYEE_1A, 200);

		assertThat(idsOf(body)).contains(REQUEST_PENDING_1A, REQUEST_APPROVED_1A);
		assertThat(idsOf(body)).doesNotContain(REQUEST_PENDING_1B, REQUEST_PENDING_2);
	}

	@Test
	void companyAdminListSeesTheWholeCompanyNotOtherCompanies() {
		Map<String, Object> body = get(LIST + "?limit=100", ADMIN_1, 200);

		assertThat(idsOf(body)).contains(REQUEST_PENDING_1A, REQUEST_PENDING_1B, REQUEST_APPROVED_1A);
		assertThat(idsOf(body)).doesNotContain(REQUEST_PENDING_2);
	}

	@Test
	void managerListIsScopedToTheirOwnBranch() {
		Map<String, Object> body = get(LIST + "?limit=100", MANAGER_1A, 200);

		assertThat(idsOf(body)).contains(REQUEST_PENDING_1A, REQUEST_APPROVED_1A);
		assertThat(idsOf(body)).doesNotContain(REQUEST_PENDING_1B);
	}

	@Test
	void listDateRangeFilterUsesTheLegacyFromToWireKeysNotDateFromDateTo() {
		Map<String, Object> body = get(LIST + "?limit=100&from=2026-02-01&to=2026-04-01", ADMIN_1, 200);

		assertThat(idsOf(body)).containsExactly(REQUEST_PENDING_1A_MARCH);
	}

	@Test
	void listIgnoresTheNonLegacyDateFromDateToAliasesEntirely() {
		Map<String, Object> body = get(
				LIST + "?limit=100&date_from=2026-02-01&date_to=2026-04-01", ADMIN_1, 200);

		// The aliases are not read at all, so the list stays unbounded rather
		// than filtering (or erroring) on them.
		assertThat(idsOf(body)).contains(REQUEST_PENDING_1A, REQUEST_PENDING_1A_MARCH);
	}

	@Test
	void statusFilterNarrowsTheCompanyList() {
		Map<String, Object> body = get(LIST + "?limit=100&status=approved", ADMIN_1, 200);

		assertThat(idsOf(body)).containsExactly(REQUEST_APPROVED_1A);
	}

	@Test
	void anInvalidStatusValueIsSilentlyIgnoredNotAnError() {
		Map<String, Object> body = get(LIST + "?limit=100&status=bogus", ADMIN_1, 200);

		assertThat(idsOf(body)).contains(REQUEST_PENDING_1A, REQUEST_PENDING_1B, REQUEST_APPROVED_1A);
	}

	@Test
	void employeeIdFilterAppliesOnlyForCompanyRoles() {
		Map<String, Object> body = get(LIST + "?limit=100&employee_id=" + EMPLOYEE_1B, ADMIN_1, 200);

		assertThat(idsOf(body)).containsExactly(REQUEST_PENDING_1B);
	}

	@Test
	void listCarriesThePaginationMetaInPhpsKeyOrder() {
		Map<String, Object> body = get(LIST + "?limit=2", ADMIN_1, 200);

		assertThat(body.keySet()).containsExactly("success", "message", "data", "meta");
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) body.get("meta");
		assertThat(meta.keySet()).containsExactly(
				"page", "limit", "total", "total_pages", "has_next", "has_previous");
	}

	@Test
	void anUnauthenticatedListCallIsRefused() {
		assertThat(anonymous(LIST, HttpMethod.GET)).isEqualTo(401);
	}

	@Test
	void aSuspendedCompanyIsRefusedAfterAuthentication() {
		get(LIST, ADMIN_SUSPENDED, 403);
	}

	// ------------------------------------------------------------------
	// one.php
	// ------------------------------------------------------------------

	@Test
	void ownerEmployeeCanReadItsOwnRequest() {
		Map<String, Object> body = get(ONE + "?id=" + REQUEST_PENDING_1A, EMPLOYEE_1A, 200);

		assertThat(number(dataOf(body).get("id"))).isEqualTo(REQUEST_PENDING_1A);
		assertThat(dataOf(body)).containsKeys("employee_name", "employee_code", "request_type_name");
		assertThat(dataOf(body)).doesNotContainKey("photo_url");
	}

	@Test
	void anEmployeeCannotReadAnotherEmployeesRequest() {
		get(ONE + "?id=" + REQUEST_PENDING_1B, EMPLOYEE_1A, 403);
	}

	@Test
	void companyAdminCanReadAnyRequestInTheCompany() {
		get(ONE + "?id=" + REQUEST_PENDING_1B, ADMIN_1, 200);
	}

	@Test
	void companyAdminCannotReadAnotherCompanysRequest() {
		get(ONE + "?id=" + REQUEST_PENDING_2, ADMIN_1, 403);
	}

	@Test
	void managerCannotReadARequestOutsideTheirBranch() {
		get(ONE + "?id=" + REQUEST_PENDING_1B, MANAGER_1A, 403);
	}

	@Test
	void aMissingIdIsFieldRequired() {
		Map<String, Object> body = get(ONE, ADMIN_1, 400);
		assertThat(body.get("message")).asString().isNotEmpty();
	}

	@Test
	void aForeignOrMissingIdIsAnExplicitFourOhFourUnlikeEveryOtherEndpoint() {
		get(ONE + "?id=999999", ADMIN_1, 404);
	}

	// ------------------------------------------------------------------
	// create.php
	// ------------------------------------------------------------------

	@Test
	void employeeCreatesAPendingRequestAndItNotifiesTheCompany() {
		int notificationsBefore = count(
				"SELECT COUNT(*) FROM notifications WHERE recipient_kind = 'company'"
						+ " AND notification_type = 'request_submitted' AND company_id = " + COMPANY_1);

		Map<String, Object> body = created("""
				{"request_type_id": %d, "from_date": "2026-05-01", "to_date": "2026-05-02", "notes": "trip"}
				""".formatted(TYPE_1), EMPLOYEE_1A);

		assertThat(dataOf(body).get("status")).isEqualTo("pending");
		assertThat(dataOf(body)).containsKeys("employee_name", "request_type_name");
		assertThat(dataOf(body)).doesNotContainKeys("photo_url", "employee_code");
		assertThat(count(
				"SELECT COUNT(*) FROM notifications WHERE recipient_kind = 'company'"
						+ " AND notification_type = 'request_submitted' AND company_id = " + COMPANY_1))
				.isEqualTo(notificationsBefore + 1);
	}

	@Test
	void createNormalisesFromTimeAndToTimeToHiSZero() {
		Map<String, Object> body = created("""
				{"request_type_id": %d, "from_date": "2026-05-01", "to_date": "2026-05-01",
				 "from_time": "9:05", "to_time": "17:30:59"}
				""".formatted(TYPE_1), EMPLOYEE_1A);

		assertThat(dataOf(body).get("from_time")).isEqualTo("09:05:00");
		assertThat(dataOf(body).get("to_time")).isEqualTo("17:30:00");
	}

	@Test
	void createRejectsAnUnparseableTime() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, EMPLOYEE_1A, """
				{"request_type_id": %d, "from_date": "2026-05-01", "to_date": "2026-05-01", "from_time": "25:99"}
				""".formatted(TYPE_1));
		assertThat(status(response)).isEqualTo(400);
	}

	@Test
	void createRequiresTypeFromDateAndToDate() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, EMPLOYEE_1A, "{}");
		assertThat(status(response)).isEqualTo(400);
	}

	@Test
	void createAcceptsADeactivatedRequestTypeUnvalidatedByIsActive() {
		created("""
				{"request_type_id": %d, "from_date": "2026-05-01", "to_date": "2026-05-01"}
				""".formatted(TYPE_1_INACTIVE), EMPLOYEE_1A);
	}

	@Test
	void createWithAForeignRequestTypeIsFourHundredNotFoundNeverFourOhFour() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, EMPLOYEE_1A, """
				{"request_type_id": %d, "from_date": "2026-05-01", "to_date": "2026-05-01"}
				""".formatted(TYPE_2));
		assertThat(status(response)).isEqualTo(400);
	}

	@Test
	void aCompanyAdminCannotCreateARequest() {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, ADMIN_1, """
				{"request_type_id": %d, "from_date": "2026-05-01", "to_date": "2026-05-01"}
				""".formatted(TYPE_1));
		assertThat(status(response)).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// update.php
	// ------------------------------------------------------------------

	@Test
	void ownerCanUpdateItsOwnPendingRequest() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");

		ResponseEntity<Map<String, Object>> response = send(UPDATE + "?id=" + id, HttpMethod.PUT, EMPLOYEE_1A,
				"""
				{"notes": "updated"}
				""");

		assertThat(status(response)).isEqualTo(200);
		assertThat(dataOf(response.getBody()).get("notes")).isEqualTo("updated");
	}

	@Test
	void updateWithAnEmptyBodyIsNothingToUpdateEvenAgainstAForeignId() {
		ResponseEntity<Map<String, Object>> response = send(UPDATE + "?id=999999", HttpMethod.PUT, EMPLOYEE_1A, "{}");
		assertThat(status(response)).isEqualTo(400);
	}

	@Test
	void updateOfAnotherEmployeesRequestIsFourHundredNotFound() {
		ResponseEntity<Map<String, Object>> response = send(
				UPDATE + "?id=" + REQUEST_PENDING_1B, HttpMethod.PUT, EMPLOYEE_1A, """
				{"notes": "nope"}
				""");
		assertThat(status(response)).isEqualTo(400);
	}

	@Test
	void updateOfAnAlreadyDecidedRequestIsFourHundredAlreadyDecided() {
		ResponseEntity<Map<String, Object>> response = send(
				UPDATE + "?id=" + REQUEST_APPROVED_1A, HttpMethod.PUT, EMPLOYEE_1A, """
				{"notes": "too late"}
				""");
		assertThat(status(response)).isEqualTo(400);
	}

	@Test
	void updateRejectsAnotherCompanysRequestTypeUnlikeLegacy() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");

		ResponseEntity<Map<String, Object>> response = send(UPDATE + "?id=" + id, HttpMethod.PUT, EMPLOYEE_1A, """
				{"request_type_id": %d}
				""".formatted(TYPE_2));

		assertThat(status(response)).isEqualTo(400);
		assertThat(queryOne("SELECT request_type_id FROM requests WHERE id = " + id).get("request_type_id"))
				.isEqualTo(TYPE_1);
	}

	@Test
	void updateAcceptsAnOwnCompanyRequestType() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");
		long otherOwnType = insertRequestTypeForCompany1();

		ResponseEntity<Map<String, Object>> response = send(UPDATE + "?id=" + id, HttpMethod.PUT, EMPLOYEE_1A, """
				{"request_type_id": %d}
				""".formatted(otherOwnType));

		assertThat(status(response)).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// delete.php
	// ------------------------------------------------------------------

	@Test
	void ownerCanDeleteItsOwnPendingRequest() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");

		ResponseEntity<Map<String, Object>> response = send(DELETE + "?id=" + id, HttpMethod.DELETE, EMPLOYEE_1A, null);

		assertThat(status(response)).isEqualTo(200);
		assertThat(count("SELECT COUNT(*) FROM requests WHERE id = " + id)).isZero();
	}

	@Test
	void deleteOfAnAlreadyDecidedRequestIsRefused() {
		ResponseEntity<Map<String, Object>> response =
				send(DELETE + "?id=" + REQUEST_APPROVED_1A, HttpMethod.DELETE, EMPLOYEE_1A, null);
		assertThat(status(response)).isEqualTo(400);
		assertThat(count("SELECT COUNT(*) FROM requests WHERE id = " + REQUEST_APPROVED_1A)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// reject.php
	// ------------------------------------------------------------------

	@Test
	void hrCanRejectAPendingRequestWithAReply() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");

		ResponseEntity<Map<String, Object>> response = send(REJECT + "?id=" + id, HttpMethod.POST, HR_1, """
				{"reply": "not this time"}
				""");

		assertThat(status(response)).isEqualTo(200);
		Map<String, Object> row = queryOne(
				"SELECT status, reply, decided_at, approver_id FROM requests WHERE id = " + id);
		assertThat(row.get("status")).isEqualTo("rejected");
		assertThat(row.get("reply")).isEqualTo("not this time");
		assertThat(row.get("decided_at")).isNotNull();
		// Unlike legacy, which never writes this column -- see D-100.
		assertThat(number(row.get("approver_id"))).isEqualTo(HR_1);
	}

	@Test
	void rejectStoresAnEmptyReplyAsAnEmptyStringNotNull() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");

		send(REJECT + "?id=" + id, HttpMethod.POST, HR_1, "{}");

		Map<String, Object> row = queryOne("SELECT reply FROM requests WHERE id = " + id);
		assertThat(row.get("reply")).isEqualTo("");
	}

	@Test
	void managerCanRejectARequestInTheirBranch() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");
		send(REJECT + "?id=" + id, HttpMethod.POST, MANAGER_1A, "{}");
		assertThat(queryOne("SELECT status FROM requests WHERE id = " + id).get("status")).isEqualTo("rejected");
	}

	@Test
	void anEmployeeCannotReject() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");
		ResponseEntity<Map<String, Object>> response = send(REJECT + "?id=" + id, HttpMethod.POST, EMPLOYEE_1A, "{}");
		assertThat(status(response)).isEqualTo(403);
	}

	@Test
	void rejectOfAForeignCompanysRequestIsFourHundredNotFound() {
		ResponseEntity<Map<String, Object>> response =
				send(REJECT + "?id=" + REQUEST_PENDING_2, HttpMethod.POST, ADMIN_1, "{}");
		assertThat(status(response)).isEqualTo(400);
	}

	@Test
	void rejectOfAnAlreadyDecidedRequestIsFourHundredAlreadyDecided() {
		ResponseEntity<Map<String, Object>> response =
				send(REJECT + "?id=" + REQUEST_APPROVED_1A, HttpMethod.POST, ADMIN_1, "{}");
		assertThat(status(response)).isEqualTo(400);
	}

	@Test
	void rejectNotifiesTheRequestingEmployee() {
		long id = insertRequest(TYPE_1, EMPLOYEE_1A, "pending");
		int before = count(
				"SELECT COUNT(*) FROM notifications WHERE to_employee_id = " + EMPLOYEE_1A
						+ " AND reference_type = 'request' AND reference_id = " + id);

		send(REJECT + "?id=" + id, HttpMethod.POST, HR_1, "{}");

		assertThat(count(
				"SELECT COUNT(*) FROM notifications WHERE to_employee_id = " + EMPLOYEE_1A
						+ " AND reference_type = 'request' AND reference_id = " + id))
				.isEqualTo(before + 1);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Map<String, Object> created(String json, long employeeId) {
		ResponseEntity<Map<String, Object>> response = send(CREATE, HttpMethod.POST, employeeId, json);
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
		String role = employeeId == MANAGER_1A ? "manager"
				: employeeId == HR_1 ? "hr"
				: employeeId == EMPLOYEE_1A || employeeId == EMPLOYEE_1A_TWIN || employeeId == EMPLOYEE_1B
						|| employeeId == EMPLOYEE_2 ? "employee"
				: "company_admin";
		long companyId = employeeId == ADMIN_2 || employeeId == EMPLOYEE_2 ? COMPANY_2
				: employeeId == ADMIN_SUSPENDED ? COMPANY_SUSPENDED : COMPANY_1;
		return jwtService.issueAccessToken(
				employeeId, employeeId, companyId, "test-session", Map.of("role", role, "token_version", 1L));
	}

	private static long insertRequest(long requestTypeId, long employeeId, String status) {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("INSERT INTO requests (employee_id, request_type_id, from_date, to_date, status, created_at)"
					+ " VALUES (" + employeeId + ", " + requestTypeId + ", '2026-01-01', '2026-01-02', '"
					+ status + "', '2025-06-01 09:00:00')", Statement.RETURN_GENERATED_KEYS);
			try (ResultSet keys = st.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static long insertRequestTypeForCompany1() {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("INSERT INTO request_types (company_id, name, is_active, created_at) VALUES ("
					+ COMPANY_1 + ", 'Scratch Type', 1, '2025-02-01 08:00:00')", Statement.RETURN_GENERATED_KEYS);
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
					  (20701, 'Req Co', '+201000020701', 'active', '2025-01-15 09:00:00'),
					  (20702, 'Req Other Co', '+201000020702', 'active', '2025-01-15 09:00:00'),
					  (20703, 'Req Suspended Co', '+201000020703', 'suspended', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20711, 20701, 'Branch A', 1, '2025-03-01 10:00:00'),
					  (20712, 20701, 'Branch B', 1, '2025-03-01 10:00:00'),
					  (20713, 20702, 'Other Branch', 1, '2025-03-01 10:00:00')
					""");

			employee(st, ADMIN_1, COMPANY_1, BRANCH_1A, "company_admin", "+201000207011", "Admin One");
			employee(st, HR_1, COMPANY_1, BRANCH_1A, "hr", "+201000207012", "Hr One");
			employee(st, MANAGER_1A, COMPANY_1, BRANCH_1A, "manager", "+201000207013", "Manager A");
			employee(st, EMPLOYEE_1A, COMPANY_1, BRANCH_1A, "employee", "+201000207014", "Employee A");
			employee(st, EMPLOYEE_1A_TWIN, COMPANY_1, BRANCH_1A, "employee", "+201000207015", "Employee A Twin");
			employee(st, EMPLOYEE_1B, COMPANY_1, BRANCH_1B, "employee", "+201000207016", "Employee B");
			employee(st, ADMIN_2, COMPANY_2, BRANCH_2, "company_admin", "+201000207021", "Admin Two");
			employee(st, EMPLOYEE_2, COMPANY_2, BRANCH_2, "employee", "+201000207022", "Employee Two");
			employee(st, ADMIN_SUSPENDED, COMPANY_SUSPENDED, BRANCH_2, "company_admin",
					"+201000207031", "Admin Suspended");

			st.execute("""
					INSERT INTO request_types (id, company_id, name, is_active, created_at) VALUES
					  (207101, 20701, 'Vacation', 1, '2025-02-01 08:00:00'),
					  (207102, 20701, 'Deactivated Type', 0, '2025-02-01 08:00:00'),
					  (207103, 20702, 'Other Company Type', 1, '2025-02-01 08:00:00')
					""");

			st.execute("INSERT INTO requests (id, employee_id, request_type_id, from_date, to_date, status,"
					+ " created_at) VALUES"
					+ " (207201, " + EMPLOYEE_1A + ", " + TYPE_1 + ", '2026-01-01', '2026-01-02', 'pending',"
					+ "  '2025-06-01 09:00:00'),"
					+ " (207202, " + EMPLOYEE_1B + ", " + TYPE_1 + ", '2026-01-01', '2026-01-02', 'pending',"
					+ "  '2025-06-02 09:00:00'),"
					+ " (207203, " + EMPLOYEE_1A + ", " + TYPE_1 + ", '2026-01-01', '2026-01-02', 'approved',"
					+ "  '2025-06-03 09:00:00'),"
					+ " (207204, " + EMPLOYEE_2 + ", " + TYPE_2 + ", '2026-01-01', '2026-01-02', 'pending',"
					+ "  '2025-06-04 09:00:00'),"
					+ " (207205, " + EMPLOYEE_1A + ", " + TYPE_1 + ", '2026-03-01', '2026-03-02', 'pending',"
					+ "  '2025-06-05 09:00:00')");
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
		try (InputStream stream = LegacyRequestEndToEndTest.class.getClassLoader()
				.getResourceAsStream(name)) {
			if (stream == null) {
				throw new IllegalStateException("missing test resource " + name);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
