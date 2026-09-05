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
			applySchema("db/phase1-mysql/phase1_extensions.sql");
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
		// LegacyFileUploads defaults to the relative path "uploads", which in a
		// Gradle run resolves under backend/ and leaves randomly named files in
		// the worktree. Point it at a temp directory so the suite writes nothing
		// it does not clean up.
		registry.add("app.legacy-uploads.path", () -> UPLOAD_DIR.toString());
	}

	private static final java.nio.file.Path UPLOAD_DIR = createUploadDir();

	private static java.nio.file.Path createUploadDir() {
		try {
			java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("legacy-uploads-test-");
			dir.toFile().deleteOnExit();
			return dir;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
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

		assertThat(postForm(DOC_UPDATE, token(MANAGER, "manager"), "id=1&doc_type=changed")
				.getStatusCode().value())
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

	/**
	 * {@code ??} fires only on an <b>absent</b> key. An explicitly empty
	 * {@code doc_type=} field exists, so PHP stores the empty string rather than
	 * defaulting to {@code "other"} -- and an explicitly empty
	 * {@code employee_id=} casts to 0 and fails the guard rather than falling
	 * back to the caller's own id.
	 */
	@Test
	@Order(5)
	@SuppressWarnings("unchecked")
	void anExplicitlyEmptyFieldIsNotTheSameAsAnAbsentOne() {
		ResponseEntity<Map<String, Object>> emptyDocType = upload("", "blank.png", PNG_BYTES);
		assertThat(emptyDocType.getStatusCode().value()).as("%s", emptyDocType.getBody()).isEqualTo(201);
		assertThat((Map<String, Object>) emptyDocType.getBody().get("data"))
				.as("an empty doc_type is stored as empty, not defaulted to other")
				.containsEntry("doc_type", "");

		assertThat(send(DOC_LIST + "?employee_id=", HttpMethod.GET, token(ADMIN, "company_admin"), null)
				.getStatusCode().value())
				.as("an explicit empty employee_id is employee_id_required, not a fallback to self")
				.isEqualTo(400);
	}

	/**
	 * {@code required($_POST, [ID, DOC_TYPE])} reads the request <b>body</b>.
	 * A query-string-only POST is {@code field_required} in legacy, while
	 * Spring's {@code @RequestParam} would have merged the query string with
	 * the form and accepted it -- making the port strictly more permissive than
	 * the endpoint it reproduces.
	 */
	@Test
	@Order(6)
	@SuppressWarnings("unchecked")
	void updateReadsItsFieldsFromTheFormBodyAndNotTheQueryString() {
		assertThat(send(DOC_UPDATE + "?id=1&doc_type=from_query", HttpMethod.POST,
				token(ADMIN, "company_admin"), null).getStatusCode().value())
				.as("query parameters are not $_POST")
				.isEqualTo(400);

		ResponseEntity<Map<String, Object>> viaForm =
				postForm(DOC_UPDATE, token(ADMIN, "company_admin"), "id=1&doc_type=from_body");
		assertThat(viaForm.getStatusCode().value()).as("%s", viaForm.getBody()).isEqualTo(200);
		assertThat((Map<String, Object>) viaForm.getBody().get("data"))
				.containsEntry("doc_type", "from_body");

		// Restore for the ordered tests that follow.
		postForm(DOC_UPDATE, token(ADMIN, "company_admin"), "id=1&doc_type=id_card");
	}

	/**
	 * The upload reads {@code employee_id} and {@code doc_type} from
	 * {@code $_POST} — the multipart <b>body</b> — not the query string.
	 *
	 * <p>Not cosmetic on this route: {@code employee_id} selects <em>whose</em>
	 * file the document is attached to, so honouring a query parameter legacy
	 * ignores would let a caller aim an upload at a coworker's record while
	 * sending only the file part.
	 */
	@Test
	@Order(7)
	void uploadReadsItsFieldsFromTheMultipartBodyAndNotTheQueryString() {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("file", new ByteArrayResource(PNG_BYTES) {
			@Override
			public String getFilename() {
				return "aimed.png";
			}
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token(ADMIN, "company_admin"));
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);

		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/employee_docs/upload.php"
						+ "?employee_id=" + OTHER_STAFF + "&doc_type=from_query"),
				HttpMethod.POST, new HttpEntity<>(form, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });

		// With no employee_id in the BODY, legacy falls back to the caller's own
		// id -- it never sees the query parameter at all.
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		@SuppressWarnings("unchecked")
		Map<String, Object> row = (Map<String, Object>) response.getBody().get("data");
		assertThat(((Number) row.get("employee_id")).longValue())
				.as("the query parameter must not aim the upload at another employee")
				.isEqualTo(ADMIN);
		assertThat(row).as("nor supply the doc type").containsEntry("doc_type", "other");
	}

	/**
	 * PHP normalizes an external field name before populating {@code $_POST}
	 * and keeps the <b>last</b> duplicate. An exact-name, first-match
	 * {@code getPart()} loses both rules, and on this route both decide whose
	 * record the document lands on.
	 *
	 * <p>{@code employee.id} normalizes to {@code employee_id}, so legacy sees
	 * it; and the later plain {@code employee_id} wins over it, so the document
	 * attaches to the caller rather than to {@code OTHER_STAFF}.
	 */
	@Test
	@Order(7)
	void uploadNormalizesMultipartFieldNamesAndKeepsTheLastDuplicate() {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		// employee_id appears twice under its PLAIN name: an exact-name lookup
		// finds the first (OTHER_STAFF), parse_str() keeps the last (ADMIN). The
		// dotted alias covers normalization separately.
		form.add("employee_id", String.valueOf(OTHER_STAFF));
		form.add("doc.type", "from_dotted");
		form.add("employee_id", String.valueOf(ADMIN));
		form.add("file", new ByteArrayResource(PNG_BYTES) {
			@Override
			public String getFilename() {
				return "dupes.png";
			}
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token(ADMIN, "company_admin"));
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);

		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/employee_docs/upload.php"),
				HttpMethod.POST, new HttpEntity<>(form, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });

		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		@SuppressWarnings("unchecked")
		Map<String, Object> row = (Map<String, Object>) response.getBody().get("data");
		assertThat(((Number) row.get("employee_id")).longValue())
				.as("parse_str() keeps the last duplicate, so employee_id beats employee.id")
				.isEqualTo(ADMIN);
		assertThat(row)
				.as("a dotted name is still normalized and read")
				.containsEntry("doc_type", "from_dotted");
	}

	@Test
	@Order(8)
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
	@Order(9)
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
	@Order(10)
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
	@Order(11)
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

	/**
	 * {@code list.php} selects {@code e.employee_code AS employee_code}; Java's
	 * list query did not, so the column was absent from every row. Found by the
	 * parity harness once the authenticated sweep compared bodies against a
	 * company that actually has a complaint.
	 *
	 * <p>The second half is the reason this is one assertion and not two files:
	 * {@code update.php} re-reads the same join and selects {@code employee_name}
	 * and {@code photo_url} but <em>not</em> {@code employee_code}. Adding the
	 * column to the shared re-read would have fixed the list and broken update,
	 * so the two queries stay deliberately different and this test pins both
	 * sides of that difference.
	 */
	@Test
	@Order(11)
	@SuppressWarnings("unchecked")
	void theComplaintsListCarriesEmployeeCodeButTheUpdateReReadDoesNot() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(
				send(COMPLAINT_LIST, HttpMethod.GET, token(ADMIN, "company_admin"), null));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0))
				.as("list.php selects e.employee_code, so the key must be present")
				.containsKey("employee_code");
		assertThat(rows.get(0))
				.containsEntry("employee_code", String.valueOf(STAFF))
				.containsEntry("employee_name", "F L");

		Map<String, Object> updated = (Map<String, Object>) data(send(COMPLAINT_UPDATE + "?id=2",
				HttpMethod.POST, token(ADMIN, "company_admin"), "{\"reply\":\"ack\"}"));
		assertThat(updated)
				.as("update.php selects employee_name and photo_url only -- adding "
						+ "employee_code here would be a divergence, not a fix")
				.doesNotContainKey("employee_code")
				.containsKey("employee_name")
				.containsKey("photo_url");
	}

	@Test
	@Order(12)
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
	@Order(13)
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
	@Order(14)
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
	@Order(15)
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
	@Order(16)
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
	@Order(17)
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
	@Order(18)
	void rejectingAnAcceptedRequestIsNotFound() {
		assertThat(send(JOIN_REJECT + "?id=" + ACCEPTED_JOIN, HttpMethod.POST,
				token(ADMIN, "company_admin"), null).getStatusCode().value())
				.as("only reject checks pendingness")
				.isEqualTo(404);
	}

	/**
	 * Three ways the {@code $_POST} contract can be got wrong, all asserted here.
	 */
	@Test
	@Order(19)
	@SuppressWarnings("unchecked")
	void theFormReaderMatchesPhpsPostSemantics() {
		// 1. PHP normalizes dots and spaces in external field names, so
		//    `doc.type` populates $_POST['doc_type'] and the guard passes.
		ResponseEntity<Map<String, Object>> dotted = postForm(DOC_UPDATE,
				token(ADMIN, "company_admin"), "id=1&doc.type=via_dot");
		assertThat(dotted.getStatusCode().value()).as("%s", dotted.getBody()).isEqualTo(200);
		assertThat((Map<String, Object>) dotted.getBody().get("data"))
				.containsEntry("doc_type", "via_dot");

		// 2. A part carrying a filename is a FILE. PHP puts it in $_FILES and
		//    never in $_POST, so it must not satisfy a required form field.
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("employee_id", new ByteArrayResource(
				String.valueOf(OTHER_STAFF).getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return "sneaky.txt";
			}
		});
		form.add("file", new ByteArrayResource(PNG_BYTES) {
			@Override
			public String getFilename() {
				return "doc.png";
			}
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token(ADMIN, "company_admin"));
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		ResponseEntity<Map<String, Object>> filePart = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + "/apis/api/employee_docs/upload.php"),
				HttpMethod.POST, new HttpEntity<>(form, headers),
				new ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(filePart.getStatusCode().value()).isEqualTo(201);
		assertThat(((Number) ((Map<String, Object>) filePart.getBody().get("data")).get("employee_id"))
				.longValue())
				.as("a filename-bearing part is $_FILES, not $_POST, so the caller's own id stands")
				.isEqualTo(ADMIN);

		// Restore for the ordered tests that follow.
		postForm(DOC_UPDATE, token(ADMIN, "company_admin"), "id=1&doc_type=id_card");
	}

	/**
	 * The method check runs before anything else, including argument
	 * resolution -- a scalar {@code ?file=x} must not be converted to a
	 * {@code MultipartFile} and fail ahead of it.
	 */
	@Test
	@Order(20)
	void aScalarFileParameterStillAnswersInvalidMethodOnAGet() {
		assertThat(send("/apis/api/employee_docs/upload.php?file=x", HttpMethod.GET, null, null)
				.getStatusCode().value())
				.isEqualTo(405);
	}

	@Test
	@Order(21)
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

	/**
	 * A form-encoded POST. {@code employee_docs/update.php} reads
	 * {@code $_POST}, so its values must be in the <b>body</b> -- a query-string
	 * request is {@code field_required} in legacy, and building the test with
	 * query parameters would have locked in a port that is more permissive than
	 * the endpoint it reproduces.
	 */
	private ResponseEntity<Map<String, Object>> postForm(String path, String token, String form) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + path), HttpMethod.POST,
				new HttpEntity<>(form, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
	}

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
