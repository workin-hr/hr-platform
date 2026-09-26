package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.workin.backend.BackendApplication;
import com.workin.backend.identity.JwtService;
import com.workin.backend.perf.QueryCounter;
import com.workin.legacy.LegacyMariaDb;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetColumns;
import com.workin.legacy.spreadsheet.LegacyCsvWriter;

import tools.jackson.databind.ObjectMapper;

/**
 * The bulk-update sheet reads in sets and writes in batches, and still fails
 * one row at a time (D-294).
 *
 * <p>Both endpoints validated each row with its own statements -- shift,
 * department-in-branch, title-in-department, the phone countries, the global
 * phone check -- and {@code update_bulk.php} then re-read, wrote and re-read
 * again per row: a dozen or more round trips a row, against a database 106 ms
 * away. The budget tests count what one request issues through the
 * application's own {@code legacyDataSource} and assert that a 500-row sheet
 * costs exactly what a 10-row sheet costs; a per-row statement anywhere on the
 * path makes the two differ. Batched writes are one prepared statement each
 * however many rows they carry, so they count once too.
 *
 * <p>The rest pins what batching must not change: a row that fails after its
 * first write rolls back only itself; a number freed by an earlier row is
 * free for a later one only if that earlier row really applied; one number
 * claimed twice in a sheet, in two spellings, is refused the second time.
 */
@SpringBootTest(classes = {BackendApplication.class, LegacyEmployeeBulkUpdateBatchingEndToEndTest.Counting.class},
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class LegacyEmployeeBulkUpdateBatchingEndToEndTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final QueryCounter COUNTER = new QueryCounter();

	private static final String UPDATE = "/apis/api/employees/update_bulk.php";
	private static final String ANALYZE = "/apis/api/employees/analyze_excel_update.php";
	private static final String TEMPLATE = "/apis/api/employees/template_excel.php";

	private static final long COMPANY = 35901L;
	private static final long ADMIN = 3599999L;
	/** Employees {@code FIRST} to {@code FIRST + EMPLOYEES - 1}, code {@code 70000 + n}. */
	private static final long FIRST = 3590000L;
	private static final int EMPLOYEES = 2400;

	/** Employees whose shift-assignment insert the database refuses. */
	private static final int REFUSED_SHIFT = 1600;

	/** The write chunk ({@code LegacyEmployeeUpdateSheetStore.CHUNK}). */
	private static final int CHUNK = 500;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtService jwtService;

	static {
		try {
			seed();
		} catch (Exception ex) {
			throw new IllegalStateException("could not prepare the bulk-update batching fixture", ex);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("app.jwt.secret", () -> "test-only-secret-not-used-in-production-000000000000");
		registry.add("app.legacy-db.jdbc-url", MARIADB::getJdbcUrl);
		registry.add("app.legacy-db.username", MARIADB::getUsername);
		registry.add("app.legacy-db.password", MARIADB::getPassword);
	}

	/** Counts every statement prepared through the application's legacy DataSource. */
	@TestConfiguration
	static class Counting {

		@Bean
		static BeanPostProcessor countLegacyStatements() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					return "legacyDataSource".equals(beanName) && bean instanceof DataSource dataSource
							? COUNTER.wrap(dataSource) : bean;
				}
			};
		}
	}

	// ---------------- the budget ----------------

	/**
	 * Ten rows, a full chunk of five hundred, and one row past it, every
	 * validated cell filled on every row. The first two cost the same
	 * statements; the third costs one more chunk, and nothing per row.
	 */
	@Test
	void updateBulkCostsTheSameStatementsForTenRowsAsForAFullChunk() throws Exception {
		List<String> ten = measureUpdate(0, 10);
		List<String> full = measureUpdate(10, CHUNK);
		List<String> overflow = measureUpdate(10 + CHUNK, CHUNK + 1);

		assertThat(full).as("statements for %d rows against those for 10: %s", CHUNK, full).hasSameSizeAs(ten);
		// The request guard reads the actor twice whatever the sheet holds;
		// anything else repeated would be a statement per row.
		assertThat(repeated(full)).as("no statement is issued once per row").isEqualTo(repeated(ten));
		// One row past the chunk is a second chunk: its re-read and its three
		// batches (the lone row already has a contract, so it patches rather
		// than inserts), plus the second chunk of the phone-holder read -- the
		// only identifier set here that grows with the rows.
		assertThat(overflow).as("statements for %d rows: %s", CHUNK + 1, overflow).hasSize(full.size() + 5);
		assertThat(QueryCounter.busiestRepeat(overflow)).isEqualTo(2);
	}

	/** The analyze step shares the reads, so it shares the budget. */
	@Test
	void analyzeCostsTheSameStatementsForTenRowsAsForAFullChunk() {
		List<String> ten = measureAnalyze(10);
		List<String> full = measureAnalyze(CHUNK);

		assertThat(full).as("statements for %d rows against those for 10: %s", CHUNK, full).hasSameSizeAs(ten);
		// The request guard reads the actor twice whatever the sheet holds;
		// anything else repeated would be a statement per row.
		assertThat(repeated(full)).as("no statement is issued once per row").isEqualTo(repeated(ten));
	}

	// ---------------- what batching must not change ----------------

	/**
	 * The row's employee update is written, then its shift assignment is
	 * refused by the database: the row's own update must not survive, and
	 * the nine rows sharing its chunk must all land.
	 */
	@Test
	void aRowThatFailsAfterItsFirstWriteMidChunkRollsBackOnlyItself() throws Exception {
		int from = REFUSED_SHIFT - 4;
		String before = column(FIRST + REFUSED_SHIFT, "first_name");
		long assignmentsBefore = scalar("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = "
				+ (FIRST + REFUSED_SHIFT));

		Map<String, Object> data = update(rows(from, 10, n -> fullRow(n, "Batched" + n)));

		assertThat(data).containsEntry("updated", 9);
		List<Map<String, Object>> failed = failed(data);
		assertThat(failed).hasSize(1);
		assertThat(failed.get(0)).containsEntry("row_index", 5)
				.containsEntry("errors", List.of("employee_update_failed"));
		assertThat(column(FIRST + REFUSED_SHIFT, "first_name")).as("the failed row's first write").isEqualTo(before);
		assertThat(scalar("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = "
				+ (FIRST + REFUSED_SHIFT))).isEqualTo(assignmentsBefore);
		for (int n = from; n < from + 10; n++) {
			if (n != REFUSED_SHIFT) {
				assertApplied(n, "Batched" + n);
			}
		}
		assertThat(updatedIds(data)).doesNotContain(FIRST + REFUSED_SHIFT).hasSize(9);
	}

	/**
	 * Row 1 moves an employee off a number and row 2 gives that number to
	 * someone else: allowed, because PHP validates row 2 after row 1 wrote.
	 */
	@Test
	void aNumberAnEarlierRowFreesIsFreeForALaterRow() throws Exception {
		int holder = 2000;
		int taker = 2001;
		String held = column(FIRST + holder, "phone");

		Map<String, Object> data = update(rows(
				phoneRow(holder, "01077000001", "moved"),
				phoneRow(taker, "+2" + held, "took it")));

		assertThat(failed(data)).isEmpty();
		assertThat(column(FIRST + taker, "phone")).isEqualTo(held);
		assertThat(column(FIRST + holder, "phone")).isEqualTo("01077000001");
	}

	/**
	 * The same two rows, with row 1 refused by the database: the number was
	 * never freed, so row 2 is refused as legacy refuses it. Validating the
	 * chunk assumed row 1 would land, and the replay must not keep that
	 * assumption.
	 */
	@Test
	void aNumberIsFreedOnlyByARowThatReallyApplied() throws Exception {
		int holder = 2010;
		int taker = 2011;
		String held = column(FIRST + holder, "phone");

		Map<String, Object> data = update(rows(
				phoneRow(holder, "01077000011", "boom"),
				phoneRow(taker, "+2" + held, "took it")));

		List<Map<String, Object>> failed = failed(data);
		assertThat(failed).extracting(row -> row.get("row_index")).containsExactly(1, 2);
		assertThat(failed.get(0)).containsEntry("errors", List.of("employee_update_failed"));
		assertThat(errorsOf(failed.get(1))).contains("phone_exists");
		assertThat(column(FIRST + holder, "phone")).isEqualTo(held);
		assertThat(column(FIRST + taker, "address")).isNotEqualTo("took it");
	}

	/** One new number claimed by two rows, spelled two ways: the second is a duplicate. */
	@Test
	void oneNumberClaimedTwiceInASheetInTwoSpellingsIsRefusedTheSecondTime() throws Exception {
		int first = 2020;
		int second = 2021;

		Map<String, Object> data = update(rows(
				phoneRow(first, "01077000021", "first"),
				phoneRow(second, "+201077000021", "second")));

		List<Map<String, Object>> failed = failed(data);
		assertThat(failed).hasSize(1);
		assertThat(failed.get(0)).containsEntry("row_index", 2);
		assertThat(errorsOf(failed.get(0))).contains("phone_exists");
		assertThat(column(FIRST + first, "phone")).isEqualTo("01077000021");
	}

	// ---------------- helpers ----------------

	private List<String> measureUpdate(int from, int count) throws Exception {
		String body = rows(from, count, n -> fullRow(n, "Budget" + n));
		AtomicReference<Map<String, Object>> data = new AtomicReference<>();
		List<String> issued = COUNTER.measure(() -> data.set(update(body)));
		assertThat(failed(data.get())).as("every row applies").isEmpty();
		assertThat(data.get()).containsEntry("updated", count);
		for (int n = from; n < from + count; n++) {
			assertApplied(n, "Budget" + n);
		}
		return issued;
	}

	private List<String> measureAnalyze(int count) {
		List<Map<String, String>> sheet = new ArrayList<>();
		for (int n = 0; n < count; n++) {
			Map<String, String> row = new LinkedHashMap<>();
			fullRow(n + 1000, "Analyze").forEach((key, value) -> row.put(key, String.valueOf(value)));
			sheet.add(row);
		}
		byte[] file = csv(sheet);
		AtomicReference<Map<String, Object>> body = new AtomicReference<>();
		List<String> issued = COUNTER.measure(() -> body.set(analyze(file)));
		@SuppressWarnings("unchecked")
		Map<String, Object> summary = (Map<String, Object>) ((Map<String, Object>) body.get().get("data")).get("summary");
		assertThat(summary).as("every row is valid: %s", body.get()).containsEntry("valid", count);
		return issued;
	}

	/** Every validated cell filled: shift, branch, department, title, a new phone, a salary. */
	private static Map<String, Object> fullRow(int n, String firstName) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("employee_code", String.valueOf(70000 + n));
		row.put("first_name", firstName);
		row.put("shift_name", "Day");
		row.put("branch_name", "North");
		row.put("department_name", "Field");
		row.put("job_title_name", "Driver");
		row.put("phone", "0106" + String.format("%07d", n));
		row.put("salary_basic", "5000");
		row.put("address", "Address " + n);
		return row;
	}

	private static Map<String, Object> phoneRow(int n, String phone, String address) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("employee_code", String.valueOf(70000 + n));
		row.put("phone", phone);
		row.put("address", address);
		return row;
	}

	private void assertApplied(int n, String firstName) throws Exception {
		Map<String, Object> employee = row("SELECT first_name, phone, branch_id, department_id, job_title_id"
				+ " FROM employees WHERE id = " + (FIRST + n));
		assertThat(employee.get("first_name")).as("employee %d", n).isEqualTo(firstName);
		assertThat(employee.get("phone")).as("employee %d", n).isEqualTo("0106" + String.format("%07d", n));
		assertThat(((Number) employee.get("department_id")).longValue()).isEqualTo(359022L);
		assertThat(((Number) employee.get("job_title_id")).longValue()).isEqualTo(359032L);
		assertThat(scalar("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + (FIRST + n)))
				.as("employee %d's shift", n).isGreaterThanOrEqualTo(1);
		assertThat(scalar("SELECT COUNT(*) FROM salary_contracts WHERE employee_id = " + (FIRST + n)
				+ " AND basic_salary = 5000")).as("employee %d's salary", n).isEqualTo(1);
	}

	private static String rows(int from, int count, IntFunction<Map<String, Object>> row) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int n = from; n < from + count; n++) {
			rows.add(row.apply(n));
		}
		return rowsJson(rows);
	}

	@SafeVarargs
	private static String rows(Map<String, Object>... rows) {
		return rowsJson(List.of(rows));
	}

	private static String rowsJson(List<Map<String, Object>> rows) {
		try {
			return new ObjectMapper().writeValueAsString(Map.of("rows", rows));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Map<String, Object> update(String body) {
		ResponseEntity<Map<String, Object>> response = this.restTemplate.exchange(
				URI.create(this.restTemplate.getRootUri() + UPDATE), HttpMethod.POST,
				new HttpEntity<>(body, headers(MediaType.APPLICATION_JSON)),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		return data;
	}

	private Map<String, Object> analyze(byte[] file) {
		MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
		HttpHeaders partHeaders = new HttpHeaders();
		partHeaders.setContentDispositionFormData("file", "sheet.csv");
		parts.add("file", new HttpEntity<>(new ByteArrayResource(file) {
			@Override
			public String getFilename() {
				return "sheet.csv";
			}
		}, partHeaders));
		ResponseEntity<Map<String, Object>> response = this.restTemplate.exchange(
				URI.create(this.restTemplate.getRootUri() + ANALYZE), HttpMethod.POST,
				new HttpEntity<>(parts, headers(MediaType.MULTIPART_FORM_DATA)),
				new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
		assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
		return response.getBody();
	}

	private byte[] csv(List<Map<String, String>> rows) {
		HttpHeaders plain = new HttpHeaders();
		plain.setBearerAuth(token());
		plain.set("Accept-Language", "en");
		byte[] template = this.restTemplate.exchange(
				URI.create(this.restTemplate.getRootUri() + TEMPLATE + "?format=csv"), HttpMethod.GET,
				new HttpEntity<>(plain), byte[].class).getBody();
		List<String> headers = LegacyEmployeeSpreadsheetColumns.columns().stream()
				.map(LegacyEmployeeSpreadsheetColumns.Column::key).toList();
		StringBuilder appended = new StringBuilder(new String(template, StandardCharsets.UTF_8));
		for (Map<String, String> row : rows) {
			List<String> cells = new ArrayList<>(headers.size());
			for (String header : headers) {
				cells.add(row.getOrDefault(header, ""));
			}
			appended.append(LegacyCsvWriter.record(cells));
		}
		return appended.toString().getBytes(StandardCharsets.UTF_8);
	}

	private HttpHeaders headers(MediaType type) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token());
		headers.setContentType(type);
		headers.set("Accept-Language", "en");
		return headers;
	}

	private String token() {
		return this.jwtService.issueAccessToken(ADMIN, ADMIN, COMPANY, "test-session",
				Map.of("role", "company_admin", "token_version", 1L));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> failed(Map<String, Object> data) {
		return (List<Map<String, Object>>) data.get("failed");
	}

	/** The statements issued more than once, with how many times. */
	private static Map<String, Long> repeated(List<String> issued) {
		Map<String, Long> counts = new LinkedHashMap<>();
		issued.forEach(sql -> counts.merge(sql, 1L, Long::sum));
		counts.values().removeIf(count -> count < 2);
		return counts;
	}

	@SuppressWarnings("unchecked")
	private static List<String> errorsOf(Map<String, Object> failure) {
		return (List<String>) failure.get("errors");
	}

	private static List<Long> updatedIds(Map<String, Object> data) {
		return ((List<?>) data.get("updated_ids")).stream().map(id -> ((Number) id).longValue()).toList();
	}

	private static String column(long employeeId, String column) throws Exception {
		Object value = row("SELECT " + column + " FROM employees WHERE id = " + employeeId).get(column);
		return value == null ? "" : String.valueOf(value);
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	private static Map<String, Object> row(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			Map<String, Object> out = new LinkedHashMap<>();
			if (rs.next()) {
				for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
					out.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
				}
			}
			return out;
		}
	}

	private static long scalar(String sql) throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0L;
		}
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (35901, 'Batch Co', '+201000035901', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (359011, 35901, 'Main', 1, '2025-03-01 10:00:00'),"
					+ " (359012, 35901, 'North', 1, '2025-03-01 10:00:00')");
			st.execute("INSERT INTO departments (id, company_id, name, is_active, created_at) VALUES"
					+ " (359021, 35901, 'Ops', 1, '2025-04-10 10:00:00'),"
					+ " (359022, 35901, 'Field', 1, '2025-04-10 10:00:00')");
			st.execute("INSERT INTO department_branches (department_id, branch_id) VALUES"
					+ " (359021, 359011), (359022, 359012)");
			st.execute("INSERT INTO job_titles (id, company_id, department_id, name, is_active, created_at) VALUES"
					+ " (359031, 35901, 359021, 'Welder', 1, '2025-04-10 10:00:00'),"
					+ " (359032, 35901, 359022, 'Driver', 1, '2025-04-10 10:00:00')");
			st.execute("INSERT INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES"
					+ " (359041, 35901, 'Day', '09:00:00', '17:00:00', '2025-04-12 10:00:00')");
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
					+ " phone, country_code, role, is_active, created_at) VALUES (" + ADMIN + ", 35901, 359011,"
					+ " '1', 'Rana', 'Admin', '01059999999', '+20', 'company_admin', 1, '2025-04-01 08:00:00')");
			StringBuilder employees = new StringBuilder();
			StringBuilder contracts = new StringBuilder();
			for (int n = 0; n < EMPLOYEES; n++) {
				employees.append(employees.isEmpty() ? "" : ",").append("(").append(FIRST + n)
						.append(", 35901, 359011, 359021, 359031, '").append(70000 + n).append("', 'Original', 'Row',")
						.append(" '0105").append(String.format("%07d", n)).append("', '+20', 'employee', 1,")
						.append(" 'Original address', '2024-02-01', '2025-04-01 08:00:00')");
				if (n % 2 == 0) {
					// Half start with a contract, so a sheet both patches and inserts.
					contracts.append(contracts.isEmpty() ? "" : ",").append("(").append(FIRST + n)
							.append(", 1000, '2024-02-01')");
				}
			}
			st.execute("INSERT INTO employees (id, company_id, branch_id, department_id, job_title_id, employee_code,"
					+ " first_name, last_name, phone, country_code, role, is_active, address, hire_date, created_at)"
					+ " VALUES " + employees);
			st.execute("INSERT INTO salary_contracts (employee_id, basic_salary, effective_from) VALUES " + contracts);
			// A failure the validation cannot see: the database refuses the
			// write itself, after the row's employee update already ran.
			st.execute("CREATE TRIGGER bulk359_refuse_shift BEFORE INSERT ON employee_shift_assignments"
					+ " FOR EACH ROW BEGIN IF NEW.employee_id = " + (FIRST + REFUSED_SHIFT) + " THEN"
					+ " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'; END IF; END");
			st.execute("CREATE TRIGGER bulk359_refuse_boom BEFORE UPDATE ON employees"
					+ " FOR EACH ROW BEGIN IF NEW.address = 'boom' THEN"
					+ " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'; END IF; END");
		}
	}
}
