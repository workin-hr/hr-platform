package com.workin.legacy.people;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;

/** Wave 13.4c: {@code employee_docs}, {@code complaints}, {@code company_join_requests}. */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyPeopleEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String DOC_LIST = "/apis/api/employee_docs/list.php";
	private static final String DOC_UPDATE = "/apis/api/employee_docs/update.php";
	private static final String DOC_DELETE = "/apis/api/employee_docs/delete.php";
	private static final String COMPLAINT_CREATE = "/apis/api/complaints/create.php";
	private static final String COMPLAINT_LIST = "/apis/api/complaints/list.php";
	private static final String COMPLAINT_UPDATE = "/apis/api/complaints/update.php";
	private static final String COMPLAINT_DELETE = "/apis/api/complaints/delete.php";
	private static final String JOIN_LIST = "/apis/api/company_join_requests/list.php";
	private static final String JOIN_ACCEPT = "/apis/api/company_join_requests/accept.php";
	private static final String JOIN_REJECT = "/apis/api/company_join_requests/reject.php";

	private static final long COMPANY = 29001L;
	private static final long ADMIN = 290011L;
	private static final long MANAGER = 290012L;
	private static final long STAFF = 290013L;
	private static final long OTHER_STAFF = 290014L;
	private static final long PENDING_JOIN = 290015L;
	private static final long BLANK_STATUS_JOIN = 290016L;
	private static final long ACCEPTED_JOIN = 290017L;
	private static final long BRANCH = 29011L;

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
			throw new IllegalStateException("could not prepare the people fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ---------------- employee_docs: the MANAGER asymmetry ----------------

	/**
	 * MANAGER passes the {@code list}/{@code upload} check (which tests
	 * {@code role === EMPLOYEE}) but fails the {@code update}/{@code delete} one
	 * (which tests {@code role not in [ADMIN, HR]}). So a manager can read
	 * another employee's documents and cannot touch them.
	 */
	@Test
	@Order(1)
	@SuppressWarnings("unchecked")
	void aManagerMayListAnotherEmployeesDocumentsButNotUpdateOrDeleteThem() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(send(
				DOC_LIST + "?employee_id=" + STAFF, HttpMethod.GET, token(MANAGER, "manager"), null));
		assertThat(rows).as("the list check only excludes EMPLOYEE, so a manager passes").hasSize(1);

		assertThat(send(DOC_UPDATE + "?id=1&doc_type=changed", HttpMethod.POST,
				token(MANAGER, "manager"), null).getStatusCode().value())
				.as("the update check excludes everyone who is not ADMIN or HR")
				.isEqualTo(403);
		assertThat(send(DOC_DELETE + "?id=1", HttpMethod.DELETE, token(MANAGER, "manager"), null)
				.getStatusCode().value()).isEqualTo(403);
	}

	@Test
	@Order(2)
	@SuppressWarnings("unchecked")
	void anEmployeeIsPinnedToTheirOwnDocumentsAndDefaultsToThem() {
		assertThat(send(DOC_LIST + "?employee_id=" + OTHER_STAFF, HttpMethod.GET,
				token(STAFF, "employee"), null).getStatusCode().value())
				.as("naming another employee is forbidden for an EMPLOYEE")
				.isEqualTo(403);

		List<Map<String, Object>> own = (List<Map<String, Object>>) data(
				send(DOC_LIST, HttpMethod.GET, token(STAFF, "employee"), null));
		assertThat(own).as("no employee_id falls back to the caller's own").hasSize(1);
		assertThat(own.get(0).keySet())
				.as("four columns only, not the whole row")
				.containsExactly("id", "doc_type", "file_url", "uploaded_at");
	}

	/**
	 * {@code employee_docs/upload.php} is multipart, and the only route in this
	 * wave that is. It was mapped and counted toward the delivered total before
	 * anything exercised it -- so a mismatch in the field names, the 201, the
	 * stored row or the {@code doc_type} default could not have failed the
	 * build. This closes that.
	 */
	@Test
	@Order(3)
	@SuppressWarnings("unchecked")
	void uploadingADocumentStoresTheRowAndAnswersTwoZeroOne() {
		ResponseEntity<Map<String, Object>> response = upload(
				"passport", "passport.pdf", "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));

		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		Map<String, Object> row = (Map<String, Object>) response.getBody().get("data");
		assertThat(row).containsEntry("doc_type", "passport");
		assertThat(((Number) row.get("employee_id")).longValue()).isEqualTo(STAFF);
		assertThat((String) row.get("file_url")).endsWith(".pdf");

		assertThat(countRows("SELECT COUNT(*) FROM employee_docs WHERE employee_id=" + STAFF
				+ " AND doc_type='passport'"))
				.as("the row is really persisted, not just echoed back")
				.isEqualTo(1);
	}

	/** {@code $_POST['doc_type'] ?? 'other'} -- absent means the literal "other". */
	@Test
	@Order(4)
	@SuppressWarnings("unchecked")
	void anUploadWithNoDocTypeDefaultsToOther() {
		// A real PNG signature: uploadFile() sniffs the content type rather than
		// trusting the extension, so arbitrary bytes named .png are rejected.
		ResponseEntity<Map<String, Object>> response = upload(null, "scan.png", PNG_BYTES);

		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		assertThat((Map<String, Object>) response.getBody().get("data"))
				.containsEntry("doc_type", "other");
	}

	@Test
	@Order(5)
	void aDocumentOfAnotherCompanysEmployeeIsNotFound() {
		assertThat(send(DOC_DELETE + "?id=99", HttpMethod.DELETE, token(ADMIN, "company_admin"), null)
				.getStatusCode().value()).isEqualTo(404);
	}

	// ---------------- complaints ----------------

	/**
	 * The public write. An anonymous complaint is stored with a null
	 * {@code company_id}, and no company's list can then return it.
	 */
	@Test
	@Order(6)
	@SuppressWarnings("unchecked")
	void anAnonymousComplaintIsStoredAndThenUnreachableThroughTheApi() {
		assertThat(send(COMPLAINT_CREATE, HttpMethod.POST, null,
				"{\"name\":\"Anon\",\"phone\":\"+201000000000\",\"message\":\"Ghost\"}")
				.getStatusCode().value())
				.as("no token required")
				.isEqualTo(200);

		assertThat(countRows("SELECT COUNT(*) FROM complaints WHERE company_id IS NULL"))
				.as("the row really is written")
				.isEqualTo(1);

		List<Map<String, Object>> visible = (List<Map<String, Object>>) data(send(
				COMPLAINT_LIST + "?status=all", HttpMethod.GET, token(ADMIN, "company_admin"), null));
		assertThat(visible)
				.as("list.php filters company_id = ?, so the anonymous row is invisible to everyone")
				.noneMatch(row -> "Ghost".equals(row.get("message")));
	}

	/** An authenticated admin's own submission is tagged {@code company_support} and also hidden. */
	@Test
	@Order(7)
	@SuppressWarnings("unchecked")
	void anAdminsOwnComplaintIsTaggedCompanySupportAndExcludedFromTheList() {
		send(COMPLAINT_CREATE, HttpMethod.POST, token(ADMIN, "company_admin"),
				"{\"name\":\"Boss\",\"phone\":\"+201000000001\",\"message\":\"Support please\"}");

		assertThat(countRows("SELECT COUNT(*) FROM complaints WHERE source='company_support'"))
				.isEqualTo(1);
		List<Map<String, Object>> visible = (List<Map<String, Object>>) data(send(
				COMPLAINT_LIST + "?status=all", HttpMethod.GET, token(ADMIN, "company_admin"), null));
		assertThat(visible).noneMatch(row -> "Support please".equals(row.get("message")));
	}

	/** The status filter is applied by default, and {@code all} is the escape hatch. */
	@Test
	@Order(8)
	@SuppressWarnings("unchecked")
	void theComplaintsListFiltersToPendingUnlessAllIsAsked() {
		List<Map<String, Object>> byDefault = (List<Map<String, Object>>) data(
				send(COMPLAINT_LIST, HttpMethod.GET, token(ADMIN, "company_admin"), null));
		List<Map<String, Object>> all = (List<Map<String, Object>>) data(send(
				COMPLAINT_LIST + "?status=all", HttpMethod.GET, token(ADMIN, "company_admin"), null));
		List<Map<String, Object>> nonsense = (List<Map<String, Object>>) data(send(
				COMPLAINT_LIST + "?status=zzz", HttpMethod.GET, token(ADMIN, "company_admin"), null));

		assertThat(byDefault).as("no parameter means pending only").hasSize(1);
		assertThat(all).as("status=all lifts the filter").hasSize(2);
		assertThat(nonsense)
				.as("an unrecognised status also lifts it, so a typo is wider than the default")
				.hasSize(2);
	}

	@Test
	@Order(9)
	@SuppressWarnings("unchecked")
	void updatingAComplaintAcceptsReplyStatusOrBothAndRejectsNeither() {
		Map<String, Object> replied = (Map<String, Object>) data(send(COMPLAINT_UPDATE + "?id=1",
				HttpMethod.POST, token(ADMIN, "company_admin"), "{\"reply\":\"  Noted  \"}"));
		assertThat(replied).containsEntry("reply", "Noted");

		// An empty reply clears the column, because the guard is array_key_exists.
		Map<String, Object> cleared = (Map<String, Object>) data(send(COMPLAINT_UPDATE + "?id=1",
				HttpMethod.POST, token(ADMIN, "company_admin"), "{\"reply\":\"\"}"));
		assertThat(cleared).containsEntry("reply", null);

		assertThat(send(COMPLAINT_UPDATE + "?id=1", HttpMethod.POST, token(ADMIN, "company_admin"),
				"{\"status\":\"nonsense\"}").getStatusCode().value()).isEqualTo(400);
		assertThat(send(COMPLAINT_UPDATE + "?id=1", HttpMethod.POST, token(ADMIN, "company_admin"),
				"{}").getStatusCode().value())
				.as("neither field supplied is field_required")
				.isEqualTo(400);
		assertThat(send(COMPLAINT_UPDATE + "?id=1", HttpMethod.POST, token(ADMIN, "company_admin"),
				"{\"status\":\"\"}").getStatusCode().value())
				.as("an EMPTY status is ignored by !empty(), so this is the same as supplying nothing")
				.isEqualTo(400);
	}

	@Test
	@Order(10)
	void complaintsUseInvalidIdWhereTheRestOfTheWaveUsesFieldRequired() {
		ResponseEntity<Map<String, Object>> response =
				send(COMPLAINT_DELETE, HttpMethod.DELETE, token(ADMIN, "company_admin"), null);
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().get("message")).asString()
				.as("invalid_id, not field_required")
				.doesNotContain("required");
	}

	// ---------------- company_join_requests ----------------

	@Test
	@Order(11)
	@SuppressWarnings("unchecked")
	void theJoinRequestListDefaultsToPendingAndCarriesFiveColumns() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(
				send(JOIN_LIST, HttpMethod.GET, token(ADMIN, "company_admin"), null));

		// The list matches the literal string 'pending', so the row with a BLANK
		// status is absent -- even though join_request_is_pending() treats blank
		// as pending, which is what makes that same row rejectable. Two
		// definitions of "pending" in one module: a request can be invisible to
		// the list and still rejectable through the endpoint beside it.
		assertThat(rows).extracting(row -> ((Number) row.get("id")).longValue())
				.containsExactly(PENDING_JOIN);
		assertThat(rows.get(0).keySet())
				.containsExactly("id", "name", "phone", "created_at", "join_request_status");
	}

	@Test
	@Order(12)
	@SuppressWarnings("unchecked")
	void acceptingFlipsTheStatusAndActivatesTheEmployee() {
		Map<String, Object> accepted = (Map<String, Object>) data(send(
				JOIN_ACCEPT + "?id=" + PENDING_JOIN, HttpMethod.POST, token(ADMIN, "company_admin"), null));

		assertThat(accepted).containsEntry("join_request_status", "accepted")
				.containsEntry("is_active", 1);
		assertThat(countRows("SELECT COUNT(*) FROM notifications WHERE to_employee_id=" + PENDING_JOIN))
				.as("the employee is notified")
				.isEqualTo(1);
	}

	/** Accept has no pendingness check, so an already-accepted request succeeds again. */
	@Test
	@Order(13)
	void acceptingAnAlreadyAcceptedRequestSucceedsAndRenotifies() {
		assertThat(send(JOIN_ACCEPT + "?id=" + ACCEPTED_JOIN, HttpMethod.POST,
				token(ADMIN, "company_admin"), null).getStatusCode().value())
				.isEqualTo(200);
		assertThat(countRows("SELECT COUNT(*) FROM notifications WHERE to_employee_id=" + ACCEPTED_JOIN))
				.isEqualTo(1);
	}

	/**
	 * Rejection <b>deletes</b> the employee row, so the phone becomes reusable.
	 * It is not the inverse of accept.
	 */
	@Test
	@Order(14)
	void rejectingDeletesTheProvisionalEmployeeRowRatherThanMarkingIt() {
		assertThat(send(JOIN_REJECT + "?id=" + BLANK_STATUS_JOIN, HttpMethod.POST,
				token(ADMIN, "company_admin"), null).getStatusCode().value())
				.as("a BLANK join_request_status counts as pending")
				.isEqualTo(200);

		assertThat(countRows("SELECT COUNT(*) FROM employees WHERE id=" + BLANK_STATUS_JOIN))
				.as("the row is gone, not flagged")
				.isZero();
	}

	@Test
	@Order(15)
	void rejectingAnAcceptedRequestIsNotFound() {
		assertThat(send(JOIN_REJECT + "?id=" + ACCEPTED_JOIN, HttpMethod.POST,
				token(ADMIN, "company_admin"), null).getStatusCode().value())
				.as("only reject checks pendingness")
				.isEqualTo(404);
	}

	@Test
	@Order(16)
	void everyRouteChecksItsMethodFirst() {
		assertThat(send(DOC_LIST, HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(COMPLAINT_CREATE, HttpMethod.GET, null, null).getStatusCode().value())
				.as("even the public one checks the method before anything else")
				.isEqualTo(405);
		assertThat(send(JOIN_ACCEPT, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
	}

	// ---------------- fixture ----------------

	private static Object data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return response.getBody().get("data");
	}

	private ResponseEntity<Map<String, Object>> send(
			String path, HttpMethod method, String token, String body) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), method,
				new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private String token(long employeeId, String role) {
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY, "test-session",
				Map.of("role", role, "token_version", 1L));
	}

	/** The eight-byte PNG signature, enough for a content-type sniff to accept. */
	private static final byte[] PNG_BYTES = {
		(byte) 0x89, 'P', 'N', 'G', '\r', '\n', (byte) 0x1a, '\n',
		0, 0, 0, 13, 'I', 'H', 'D', 'R',
	};

	private ResponseEntity<Map<String, Object>> upload(String docType, String filename, byte[] body) {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("employee_id", String.valueOf(STAFF));
		if (docType != null) {
			form.add("doc_type", docType);
		}
		form.add("file", new ByteArrayResource(body) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token(ADMIN, "company_admin"));
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/employee_docs/upload.php"),
				HttpMethod.POST, new HttpEntity<>(form, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
	}

	private static long countRows(String sql) {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0L;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'People Co', '+201000029001', 'active', '2019-01-15 09:00:00'),"
					+ " (" + (COMPANY + 1) + ", 'Other Co', '+201000029002', 'active',"
					+ " '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00'),"
					+ " (" + (BRANCH + 1) + ", " + (COMPANY + 1) + ", 'Main', 1, '2019-03-01 10:00:00')");

			employee(st, ADMIN, COMPANY, BRANCH, "company_admin", "'accepted'");
			employee(st, MANAGER, COMPANY, BRANCH, "manager", "'accepted'");
			employee(st, STAFF, COMPANY, BRANCH, "employee", "'accepted'");
			employee(st, OTHER_STAFF, COMPANY, BRANCH, "employee", "'accepted'");
			employee(st, PENDING_JOIN, COMPANY, BRANCH, "employee", "'pending'");
			employee(st, BLANK_STATUS_JOIN, COMPANY, BRANCH, "employee", "''");
			employee(st, ACCEPTED_JOIN, COMPANY, BRANCH, "employee", "'accepted'");
			// A foreign employee, so document id 99 is out of reach.
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, join_request_status, created_at) VALUES"
					+ " (2900199, " + (COMPANY + 1) + ", " + (BRANCH + 1) + ", '2900199', 'F', 'L',"
					+ " '+2010002900199', 'employee', 1, 'accepted', '2019-04-01 08:00:00')");

			st.execute("INSERT INTO employee_docs (id, employee_id, doc_type, file_url) VALUES"
					+ " (1, " + STAFF + ", 'id_card', '/uploads/docs/a.pdf'),"
					+ " (99, 2900199, 'foreign', '/uploads/docs/b.pdf')");

			st.execute("INSERT INTO complaints (id, employee_id, company_id, source, name, phone,"
					+ " message, status) VALUES"
					+ " (1, " + STAFF + ", " + COMPANY + ", 'employee', 'Staff', '+2010', 'Pending one',"
					+ " 'pending'),"
					+ " (2, " + STAFF + ", " + COMPANY + ", 'employee', 'Staff', '+2010', 'Done one',"
					+ " 'done')");
		}
	}

	private static void employee(Statement st, long id, long companyId, long branchId, String role,
			String joinStatus) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, join_request_status, created_at) VALUES ("
				+ id + ", " + companyId + ", " + branchId + ", '" + id + "', 'F', 'L',"
				+ " '+2010000" + id + "', '" + role + "', 1, " + joinStatus + ","
				+ " '2019-04-01 08:00:00')");
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
		try (InputStream in = LegacyPeopleEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
