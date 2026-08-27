package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
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
import com.workin.backend.identity.JwtService;

/**
 * {@code advances/pay.php} previously read {@code remaining}, computed the new balance in Java,
 * and wrote it back unconditionally -- two genuinely concurrent payments against the same
 * advance could both read the same stale {@code remaining}, both pass the overpayment check
 * against it, and the second write would silently clobber the first's result, losing one
 * payment (PR #120 review). {@link LegacyAdvanceStore#payIfSufficientBalance} closes this with
 * one atomic {@code UPDATE ... WHERE remaining >= ?}; this proves it against real MariaDB the
 * same way {@code LegacyPayrollBatchCalculateEndToEndTest}'s D-114 test proves the finalize
 * race is closed -- two threads released by a shared barrier, then the actual outcome checked
 * against the real column, not just the HTTP status codes.
 *
 * <p>It also covers {@code advances/update.php}, whose employee-edit guard depends on the same
 * real-database row-count semantics that no mock can reproduce -- see
 * {@link #employeeEditResubmittingStoredValuesSucceedsInsteadOfLookingLikeALostRace()}.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("phase1-mysql")
class LegacyAdvancePayEndToEndTest {

	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.8");

	private static final String PAY = "/apis/api/advances/pay.php";
	private static final String UPDATE = "/apis/api/advances/update.php";

	private static final long COMPANY_1 = 21501L;
	private static final long ADMIN_1 = 215011L;
	private static final long EMPLOYEE_1 = 215012L;
	private static final long BRANCH_1 = 21511L;
	private static final long ADVANCE_1 = 2150100L;
	private static final long ADVANCE_2 = 2150101L;
	private static final long ADVANCE_3 = 2150102L;

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
			throw new IllegalStateException("could not prepare the advance pay fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	/**
	 * The advance starts with a 1000.00 remaining balance, well above one 60.00 payment, so a
	 * second genuinely-applied payment is distinguishable from a single one in the final
	 * balance -- 880.00 rather than 940.00 -- not just by checking whether both HTTP calls
	 * returned 200.
	 */
	@Test
	void concurrentPaymentsForTheSameAdvanceAreBothAppliedExactlyOnce() throws Exception {
		assertThat(decimalString(queryScalar("SELECT remaining FROM advances WHERE id=" + ADVANCE_1)))
				.isEqualTo("1000.00");

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<ResponseEntity<Map<String, Object>>>> results = pool.invokeAll(List.of(
					() -> payRacing(barrier), () -> payRacing(barrier)));
			long succeeded = 0;
			for (Future<ResponseEntity<Map<String, Object>>> result : results) {
				ResponseEntity<Map<String, Object>> response = result.get(30, TimeUnit.SECONDS);
				if (response.getStatusCode().value() == 200) {
					succeeded++;
				}
			}
			assertThat(succeeded).as("both concurrent 60.00 payments against a 1000.00 balance must succeed").isEqualTo(2);
			assertThat(decimalString(queryScalar("SELECT remaining FROM advances WHERE id=" + ADVANCE_1)))
					.as("both payments must be reflected in the balance -- a lost update would leave 940.00")
					.isEqualTo("880.00");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void paymentExceedingTheRemainingBalanceIsRejectedAndLeavesItUnchanged() throws Exception {
		Map<String, Object> body = send(HttpMethod.PUT, "{\"amount\":\"1000.01\"}", 400, "?id=" + ADVANCE_2);
		assertThat(body.get("success")).isEqualTo(false);
		assertThat(decimalString(queryScalar("SELECT remaining FROM advances WHERE id=" + ADVANCE_2)))
				.isEqualTo("1000.00");
	}

	/**
	 * {@link LegacyAdvanceStore#updateEmployee} folds {@code AND status='pending'} into the write
	 * and {@link LegacyAdvanceService} reads a zero affected-row count as "an approval won the
	 * race". That inference holds only while the connection reports rows *matched* by the WHERE
	 * clause rather than rows actually *changed* -- MariaDB returns changed rows unless
	 * CLIENT_FOUND_ROWS is in effect, which Connector/J controls via {@code useAffectedRows}.
	 *
	 * <p>An edit that resubmits the stored values changes no columns, so under changed-row
	 * semantics the guard would misread a perfectly legal no-op edit as a lost race and return
	 * {@code 400 cannot_edit_non_pending_advance}. The mock-based service tests cannot observe
	 * this: they stub the row count that is exactly what is in question here. This pins the real
	 * driver/server behavior the four PR #120 concurrency guards depend on.
	 */
	@Test
	void employeeEditResubmittingStoredValuesSucceedsInsteadOfLookingLikeALostRace() throws Exception {
		assertThat(queryScalar("SELECT status FROM advances WHERE id=" + ADVANCE_3).toString())
				.isEqualTo("pending");

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(employeeToken());
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + UPDATE + "?id=" + ADVANCE_3), HttpMethod.PUT,
				new HttpEntity<>("{\"amount\":\"1000.00\",\"reason\":null}", headers), mapType());

		assertThat(response.getStatusCode().value())
				.as("a no-op edit of a still-pending advance must succeed; 400 here means the "
						+ "connection reports changed rows instead of matched rows: %s", response.getBody())
				.isEqualTo(200);
		assertThat(decimalString(queryScalar("SELECT amount FROM advances WHERE id=" + ADVANCE_3)))
				.isEqualTo("1000.00");
		assertThat(queryScalar("SELECT status FROM advances WHERE id=" + ADVANCE_3).toString())
				.as("the edit must not disturb the pending status")
				.isEqualTo("pending");
	}

	private String employeeToken() {
		return jwtService.issueAccessToken(EMPLOYEE_1, EMPLOYEE_1, COMPANY_1, "test-session",
				Map.of("role", "employee", "token_version", 1L));
	}

	private ResponseEntity<Map<String, Object>> payRacing(CyclicBarrier barrier) throws Exception {
		barrier.await(10, TimeUnit.SECONDS);
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(ADMIN_1));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + PAY + "?id=" + ADVANCE_1), HttpMethod.PUT,
				new HttpEntity<>("{\"amount\":\"60.00\"}", headers), mapType());
	}

	private Map<String, Object> send(HttpMethod method, String json, int expectedStatus, String query) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(tokenFor(ADMIN_1));
		headers.set("Accept-Language", "en");
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
				URI.create(restTemplate.getRootUri() + PAY + query), method, new HttpEntity<>(json, headers), mapType());
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(expectedStatus);
		return response.getBody();
	}

	private String tokenFor(long employeeId) {
		return jwtService.issueAccessToken(employeeId, employeeId, COMPANY_1, "test-session",
				Map.of("role", "company_admin", "token_version", 1L));
	}

	private Object queryScalar(String sql) throws Exception {
		try (Connection connection = connect();
				Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(sql)) {
			assertThat(rs.next()).as("query returned a row: %s", sql).isTrue();
			return rs.getObject(1);
		}
	}

	private String decimalString(Object raw) {
		return raw == null ? null : new java.math.BigDecimal(raw.toString()).setScale(2).toString();
	}

	private static ParameterizedTypeReference<Map<String, Object>> mapType() {
		return new ParameterizedTypeReference<Map<String, Object>>() { };
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (21501, 'Advance Pay Co', '+201000021501', 'active', '2019-01-15 09:00:00')
					""");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (" + BRANCH_1 + ", " + COMPANY_1 + ", 'Main', 1, '2019-03-01 10:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + ADMIN_1 + ", " + COMPANY_1
					+ ", " + BRANCH_1 + ", " + ADMIN_1 + ", 'Admin', 'One', '+201000215011', 'company_admin',"
					+ " 1, '2019-04-01 08:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
					+ " last_name, phone, role, is_active, created_at) VALUES (" + EMPLOYEE_1 + ", " + COMPANY_1
					+ ", " + BRANCH_1 + ", " + EMPLOYEE_1 + ", 'Emp', 'One', '+201000215012', 'employee',"
					+ " 1, '2019-04-01 08:00:00')");
			st.execute("INSERT INTO advances (id, employee_id, amount, remaining, deduction_mode,"
					+ " status, request_date, created_at) VALUES"
					+ " (" + ADVANCE_1 + ", " + EMPLOYEE_1 + ", 1000.00, 1000.00, 'single_payroll_month',"
					+ " 'approved', '2020-05-20', '2020-05-20 08:00:00'),"
					+ " (" + ADVANCE_2 + ", " + EMPLOYEE_1 + ", 1000.00, 1000.00, 'single_payroll_month',"
					+ " 'approved', '2020-05-20', '2020-05-20 08:00:00'),"
					+ " (" + ADVANCE_3 + ", " + EMPLOYEE_1 + ", 1000.00, 1000.00, 'single_payroll_month',"
					+ " 'pending', '2020-05-20', '2020-05-20 08:00:00')");
		}
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
		try (InputStream in = LegacyAdvancePayEndToEndTest.class.getClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("missing test resource: " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
