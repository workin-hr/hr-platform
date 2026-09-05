package com.workin.legacy.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
import com.workin.legacy.LegacyRuntimeOffset;
import com.workin.legacy.auth.LegacyPhpJwtService;

/**
 * Wave 13.2's six {@code notifications/*.php} endpoints.
 *
 * <p>The tests concentrate on the three things a "cleaner" port would silently
 * change: the two inboxes never overlap; a falsy {@code id} means <em>all</em>
 * rather than 400; and reading a notification writes to it.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyNotificationEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String LIST = "/apis/api/notifications/list.php";
	private static final String UNREAD = "/apis/api/notifications/unread_count.php";
	private static final String ONE = "/apis/api/notifications/one.php";
	private static final String MARK_READ = "/apis/api/notifications/mark_read.php";
	private static final String DELETE = "/apis/api/notifications/delete.php";
	private static final String SEND = "/apis/api/notifications/send.php";

	private static final long COMPANY = 28001L;
	private static final long OTHER_COMPANY = 28002L;
	private static final long ADMIN = 280011L;
	private static final long MANAGER = 280012L;
	private static final long STAFF = 280013L;
	private static final long SECOND_STAFF = 280014L;
	private static final long INACTIVE_STAFF = 280015L;
	private static final long OTHER_STAFF = 280021L;
	private static final long BRANCH = 28011L;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private LegacyPhpJwtService legacyPhpJwtService;

	static {
		MARIADB.start();
		try {
			applySchema("legacy/mysql_workin.schema.sql");
			applySchema("db/phase1-mysql/phase1_extensions.sql");
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the notifications fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	// ---------------- the two inboxes ----------------

	/**
	 * {@code notification_inbox_filter()} branches on the auth <em>type</em>,
	 * and both branches pin {@code recipient_kind}. So the company inbox and
	 * the employee inbox are disjoint: neither can see the other's rows even
	 * though every row carries the same {@code company_id}.
	 */
	@Test
	@Order(1)
	void theCompanyInboxAndTheEmployeeInboxDoNotOverlap() {
		assertThat(titles(list(companyToken()))).containsExactly("Company two", "Company one");
		assertThat(titles(list(employeeToken(STAFF))))
				.containsExactly("Staff three", "Staff two", "Staff one");
		assertThat(titles(list(employeeToken(SECOND_STAFF)))).containsExactly("Second staff one");
	}

	/**
	 * A cross-tenant read is refused by the same filter and not by a separate
	 * check: the other company's employee sees only their own row.
	 */
	@Test
	@Order(2)
	void anEmployeeOfAnotherCompanySeesOnlyTheirOwnInbox() {
		assertThat(titles(list(employeeToken(OTHER_STAFF)))).containsExactly("Foreign one");
		assertThat(send(ONE + "?id=1", HttpMethod.GET, employeeToken(OTHER_STAFF), null)
				.getStatusCode().value())
				.as("a row in another inbox is 404, never 403")
				.isEqualTo(404);
	}

	/** {@code unread_count.php} counts only the caller's unread rows. */
	@Test
	@Order(3)
	void unreadCountIsScopedToTheCallersInbox() {
		assertThat(count(send(UNREAD, HttpMethod.GET, employeeToken(STAFF), null))).isEqualTo(2);
		assertThat(count(send(UNREAD, HttpMethod.GET, companyToken(), null))).isEqualTo(1);
	}

	// ---------------- one.php writes ----------------

	/**
	 * {@code one.php} is a GET that marks the row read, and the body it returns
	 * carries the post-update {@code is_read = 1} that PHP assigns in memory --
	 * not a re-read.
	 */
	@Test
	@Order(4)
	@SuppressWarnings("unchecked")
	void readingANotificationMarksItRead() throws Exception {
		Map<String, Object> row = (Map<String, Object>) data(
				send(ONE + "?id=1", HttpMethod.GET, employeeToken(STAFF), null));
		assertThat(row).containsEntry("title", "Staff one").containsEntry("is_read", 1);
		assertThat(row).as("one.php joins from_name but not from_phone").containsKey("from_name");
		assertThat(row).doesNotContainKey("from_phone");
		assertThat(isRead(1L)).isEqualTo(1);
		assertThat(count(send(UNREAD, HttpMethod.GET, employeeToken(STAFF), null)))
				.as("the unread count drops by one")
				.isEqualTo(1);
	}

	/** {@code list.php} carries {@code from_phone} and {@code from_name}. */
	@Test
	@Order(5)
	@SuppressWarnings("unchecked")
	void theListCarriesTheSendersPhoneAndDisplayName() {
		List<Map<String, Object>> rows = (List<Map<String, Object>>) data(list(employeeToken(STAFF)));
		Map<String, Object> fromAdmin = rows.stream()
				.filter(candidate -> "Staff two".equals(candidate.get("title")))
				.findFirst().orElseThrow();
		assertThat(fromAdmin).containsEntry("from_name", "Ada Admin");
		assertThat(fromAdmin).containsEntry("from_phone", "+2010000" + ADMIN);

		Map<String, Object> fromNobody = rows.stream()
				.filter(candidate -> "Staff three".equals(candidate.get("title")))
				.findFirst().orElseThrow();
		assertThat(fromNobody)
				.as("the LEFT JOIN leaves both null when there is no sender")
				.containsEntry("from_phone", null);
	}

	// ---------------- the falsy-id rule ----------------

	/**
	 * {@code $id = isset($_GET[ID]) ? (int) $_GET[ID] : null;} then
	 * {@code if ($id)}. {@code ?id=abc} casts to 0, which is falsy, so the
	 * request marks the caller's <b>whole inbox</b> read instead of answering
	 * 400. Legacy's behaviour, ported under D-058 and asserted here so it
	 * cannot be "fixed" without a decision.
	 */
	@Test
	@Order(6)
	void anUnparseableIdMarksTheWholeInboxRead() {
		assertThat(send(MARK_READ + "?id=abc", HttpMethod.PUT, employeeToken(STAFF), null)
				.getStatusCode().value()).isEqualTo(200);
		assertThat(count(send(UNREAD, HttpMethod.GET, employeeToken(STAFF), null))).isZero();
		assertThat(count(send(UNREAD, HttpMethod.GET, employeeToken(SECOND_STAFF), null)))
				.as("another inbox is untouched")
				.isEqualTo(1);
	}

	/** A notification in someone else's inbox is 404 on mark_read, not 403. */
	@Test
	@Order(7)
	void markingAnotherInboxesNotificationReadIs404() {
		assertThat(send(MARK_READ + "?id=1", HttpMethod.PUT, employeeToken(SECOND_STAFF), null)
				.getStatusCode().value()).isEqualTo(404);
	}

	/** The same falsy-id rule on {@code delete.php}, where it empties the inbox. */
	@Test
	@Order(8)
	void anUnparseableIdDeletesTheWholeInbox() {
		assertThat(send(DELETE + "?id=0", HttpMethod.DELETE, employeeToken(SECOND_STAFF), null)
				.getStatusCode().value()).isEqualTo(200);
		assertThat(titles(list(employeeToken(SECOND_STAFF)))).isEmpty();
		assertThat(titles(list(employeeToken(STAFF))))
				.as("only the caller's own inbox is emptied")
				.hasSize(3);
	}

	/** A single-id delete removes exactly that row. */
	@Test
	@Order(9)
	void aSingleIdDeleteRemovesOnlyThatRow() {
		assertThat(send(DELETE + "?id=3", HttpMethod.DELETE, employeeToken(STAFF), null)
				.getStatusCode().value()).isEqualTo(200);
		assertThat(titles(list(employeeToken(STAFF)))).containsExactly("Staff two", "Staff one");
		assertThat(send(DELETE + "?id=3", HttpMethod.DELETE, employeeToken(STAFF), null)
				.getStatusCode().value())
				.as("gone means 404 on the second attempt")
				.isEqualTo(404);
	}

	// ---------------- send.php ----------------

	/** {@code send.php} is the only route with a role list and an active-company gate. */
	@Test
	@Order(10)
	void sendIsTheOnlyRouteThatRestrictsRoles() {
		assertThat(send(SEND, HttpMethod.POST, employeeToken(STAFF),
				"{\"title\":\"Hi\",\"to_employee_id\":" + SECOND_STAFF + "}")
				.getStatusCode().value())
				.as("EMPLOYEE is not on send.php's list")
				.isEqualTo(403);
		assertThat(send(LIST, HttpMethod.GET, employeeToken(STAFF), null).getStatusCode().value())
				.as("but every read route takes a bare requireAuth()")
				.isEqualTo(200);
	}

	@Test
	@Order(11)
	@SuppressWarnings("unchecked")
	void aManagerMaySendToOneEmployeeAndTheRowComesBack() {
		ResponseEntity<Map<String, Object>> response = send(SEND, HttpMethod.POST, employeeToken(MANAGER),
				"{\"title\":\"Direct\",\"body\":\"Please read\",\"to_employee_id\":" + SECOND_STAFF + "}");
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
		Map<String, Object> row = (Map<String, Object>) response.getBody().get("data");
		assertThat(row)
				.containsEntry("title", "Direct")
				.containsEntry("body", "Please read")
				.containsEntry("notification_type", "manual")
				.containsEntry("recipient_kind", "employee")
				.containsEntry("to_employee_id", (int) SECOND_STAFF)
				.containsEntry("from_employee_id", (int) MANAGER);
		assertThat(titles(list(employeeToken(SECOND_STAFF)))).containsExactly("Direct");
	}

	/** An employee of another company is not a valid target: 404, not 403. */
	@Test
	@Order(12)
	void sendingToAnotherCompanysEmployeeIs404() {
		assertThat(send(SEND, HttpMethod.POST, employeeToken(ADMIN),
				"{\"title\":\"Direct\",\"to_employee_id\":" + OTHER_STAFF + "}")
				.getStatusCode().value()).isEqualTo(404);
	}

	/**
	 * {@code !empty($body[TO_ALL_COMPANY])}. A falsy flag takes the
	 * single-recipient path, so {@code "0"} still requires
	 * {@code to_employee_id}; a truthy one broadcasts and never looks at
	 * {@code to_employee_id} at all.
	 */
	@Test
	@Order(13)
	void toAllCompanyIsAnEmptynessTestAndTheBroadcastIgnoresTheRecipient() {
		ResponseEntity<Map<String, Object>> falsy = send(SEND, HttpMethod.POST, employeeToken(ADMIN),
				"{\"title\":\"Broadcast\",\"to_all_company\":\"0\"}");
		assertThat(falsy.getStatusCode().value())
				.as("\"0\" is empty in PHP, so this falls through to the required(to_employee_id)")
				.isEqualTo(400);

		ResponseEntity<Map<String, Object>> broadcast = send(SEND, HttpMethod.POST, employeeToken(ADMIN),
				"{\"title\":\"Broadcast\",\"to_all_company\":true,\"to_employee_id\":" + OTHER_STAFF + "}");
		assertThat(broadcast.getStatusCode().value())
				.as("200, not 201 -- the broadcast returns a count, not a row")
				.isEqualTo(200);
		assertThat(((Number) ((Map<?, ?>) broadcast.getBody().get("data")).get("count")).intValue())
				.as("every active employee of the company, the sender included, and no inactive one")
				.isEqualTo(4);
		assertThat(titles(list(employeeToken(ADMIN))))
				.as("the sender is not excluded from their own broadcast")
				.contains("Broadcast");
	}

	/** {@code required($body, [TITLE])} runs before anything else in send.php. */
	@Test
	@Order(14)
	void sendRequiresATitle() {
		ResponseEntity<Map<String, Object>> response = send(SEND, HttpMethod.POST, employeeToken(ADMIN),
				"{\"to_employee_id\":" + SECOND_STAFF + "}");
		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody()).containsEntry("message", "Field 'title' is required");
	}

	// ---------------- method guards ----------------

	/** The method check precedes {@code requireAuth()}, so an anonymous wrong method is 405. */
	@Test
	@Order(15)
	void theMethodCheckRunsBeforeAuthentication() {
		assertThat(send(LIST, HttpMethod.POST, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(MARK_READ, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(DELETE, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(SEND, HttpMethod.GET, null, null).getStatusCode().value()).isEqualTo(405);
		assertThat(send(LIST, HttpMethod.GET, null, null).getStatusCode().value())
				.as("the right method with no token is the 401")
				.isEqualTo(401);
	}

	/** {@code one.php} is the only route whose id is {@code required()}. */
	@Test
	@Order(16)
	void oneRequiresAnIdWhileMarkReadAndDeleteDoNot() {
		ResponseEntity<Map<String, Object>> missing =
				send(ONE, HttpMethod.GET, employeeToken(STAFF), null);
		assertThat(missing.getStatusCode().value()).isEqualTo(400);
		assertThat(missing.getBody()).containsEntry("message", "Field 'id' is required");
	}

	// ---------------- fixture ----------------

	private ResponseEntity<Map<String, Object>> list(String token) {
		return send(LIST, HttpMethod.GET, token, null);
	}

	@SuppressWarnings("unchecked")
	private List<String> titles(ResponseEntity<Map<String, Object>> response) {
		return ((List<Map<String, Object>>) data(response)).stream()
				.map(row -> (String) row.get("title"))
				.toList();
	}

	private long count(ResponseEntity<Map<String, Object>> response) {
		return ((Number) ((Map<?, ?>) data(response)).get("count")).longValue();
	}

	private static Object data(ResponseEntity<Map<String, Object>> response) {
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("success", true);
		return response.getBody().get("data");
	}

	private static int isRead(long id) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT is_read FROM notifications WHERE id = " + id)) {
			return rs.next() ? rs.getInt(1) : -1;
		}
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

	private String employeeToken(long employeeId) {
		long company = employeeId == OTHER_STAFF ? OTHER_COMPANY : COMPANY;
		String role = switch ((int) employeeId) {
			case (int) ADMIN -> "company_admin";
			case (int) MANAGER -> "manager";
			default -> "employee";
		};
		return legacyPhpJwtService.issueEmployeeToken(employeeId, company, role, 1L);
	}

	/** A {@code type=company} session: no employee id, and its own inbox. */
	private String companyToken() {
		return legacyPhpJwtService.issueCompanyToken(COMPANY, "company_admin");
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("SET time_zone = '" + LegacyRuntimeOffset.DEFAULT + "'");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (" + COMPANY + ", 'Notify Co', '+201000028001', 'active', '2019-01-15 09:00:00'),"
					+ " (" + OTHER_COMPANY + ", 'Other Co', '+201000028002', 'active', '2019-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00'),"
					+ " (" + (BRANCH + 1) + ", " + OTHER_COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')");

			employee(st, ADMIN, COMPANY, BRANCH, "company_admin", "Ada", "Admin", 1);
			employee(st, MANAGER, COMPANY, BRANCH, "manager", "Mo", "Manager", 1);
			employee(st, STAFF, COMPANY, BRANCH, "employee", "Sam", "Staff", 1);
			employee(st, SECOND_STAFF, COMPANY, BRANCH, "employee", "Sue", "Staff", 1);
			employee(st, INACTIVE_STAFF, COMPANY, BRANCH, "employee", "Ivan", "Idle", 0);
			employee(st, OTHER_STAFF, OTHER_COMPANY, BRANCH + 1, "employee", "Otto", "Other", 1);

			notification(st, 1, COMPANY, "employee", null, STAFF, "Staff one", 0, "2026-02-01 09:00:00");
			notification(st, 2, COMPANY, "employee", ADMIN, STAFF, "Staff two", 1, "2026-02-02 09:00:00");
			notification(st, 3, COMPANY, "employee", null, STAFF, "Staff three", 0, "2026-02-03 09:00:00");
			notification(st, 4, COMPANY, "employee", ADMIN, SECOND_STAFF,
					"Second staff one", 0, "2026-02-04 09:00:00");
			notification(st, 5, COMPANY, "company", ADMIN, null, "Company one", 1, "2026-02-05 09:00:00");
			notification(st, 6, COMPANY, "company", null, null, "Company two", 0, "2026-02-06 09:00:00");
			notification(st, 7, OTHER_COMPANY, "employee", null, OTHER_STAFF,
					"Foreign one", 0, "2026-02-07 09:00:00");
		}
	}

	private static void notification(
			Statement st, long id, long companyId, String kind, Long from, Long to,
			String title, int isRead, String createdAt) throws Exception {
		st.execute("INSERT INTO notifications (id, company_id, recipient_kind, from_employee_id,"
				+ " to_employee_id, title, body, notification_type, is_read, created_at) VALUES ("
				+ id + ", " + companyId + ", '" + kind + "', "
				+ (from == null ? "NULL" : from) + ", " + (to == null ? "NULL" : to)
				+ ", '" + title + "', 'Body', 'manual', " + isRead + ", '" + createdAt + "')");
	}

	private static void employee(
			Statement st, long id, long companyId, long branchId, String role,
			String firstName, String lastName, int active) throws Exception {
		st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, token_version, created_at) VALUES (" + id + ", "
				+ companyId + ", " + branchId + ", '" + id + "', '" + firstName + "', '" + lastName
				+ "', '+2010000" + id + "', '" + role + "', " + active
				+ ", 1, '2019-04-01 08:00:00')");
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
		try (InputStream in =
				LegacyNotificationEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
