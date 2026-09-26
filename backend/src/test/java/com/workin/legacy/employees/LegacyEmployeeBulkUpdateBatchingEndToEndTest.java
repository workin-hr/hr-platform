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
	private static final int EMPLOYEES = 3800;

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
		// One row past the chunk is a second chunk: its five reference reads
		// (shift, department links, departments, titles, phone holders), its
		// re-read and its three batches -- the lone row already has a
		// contract, so it patches rather than inserts.
		assertThat(overflow).as("statements for %d rows: %s", CHUNK + 1, overflow).hasSize(full.size() + 9);
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

	// ---------------- sheets whose rows differ ----------------

	/**
	 * Real sheets leave cells empty -- "leave this field alone" -- so their
	 * rows write different columns. Five hundred rows, each filling a seeded
	 * random subset of thirteen cells, cost exactly what five hundred rows
	 * filling all of them cost; and every row stores exactly what the
	 * per-row legacy path stores: the filled cells as the sheet resolves
	 * them, every other column as it was.
	 */
	@Test
	void rowsFillingDifferentCellsCostTheSameAsUniformRowsAndStoreTheSameValues() throws Exception {
		int uniformFrom = 2600;
		// Every cell but the password: bcrypt five hundred times costs a minute
		// and changes nothing about the statements.
		String uniform = rows(uniformFrom, CHUNK,
				n -> heterogeneousRow(n, ALL_CELLS.stream().filter(cell -> !"password".equals(cell)).toList()));
		AtomicReference<Map<String, Object>> uniformData = new AtomicReference<>();
		List<String> uniformIssued = measure(() -> uniformData.set(update(uniform)));
		assertThat(failed(uniformData.get())).isEmpty();

		int from = 2100;
		java.util.Random random = new java.util.Random(359);
		List<Map<String, Object>> sheet = new ArrayList<>();
		Map<Integer, Map<String, Object>> before = new LinkedHashMap<>();
		for (int n = from; n < from + CHUNK; n++) {
			List<String> cells = new ArrayList<>();
			for (String cell : ALL_CELLS) {
				if (random.nextInt(100) < ("password".equals(cell) ? 4 : 45)) {
					cells.add(cell);
				}
			}
			if (cells.isEmpty()) {
				cells.add("address");
			}
			sheet.add(heterogeneousRow(n, cells));
			before.put(n, employeeRow(n));
		}
		long shapes = sheet.stream().map(Map::keySet).distinct().count();
		assertThat(shapes).as("the rows really do differ").isGreaterThan(400);

		AtomicReference<Map<String, Object>> data = new AtomicReference<>();
		List<String> issued = measure(() -> data.set(update(rowsJson(sheet))));

		assertThat(failed(data.get())).as("every row applies").isEmpty();
		for (int index = 0; index < sheet.size(); index++) {
			int n = from + index;
			assertStored(n, sheet.get(index), before.get(n));
		}
		// Asserted after the values, so a run against the per-shape writes
		// shows they store the same thing and differ only in what they cost.
		assertThat(issued).as("statements for %d differing rows: %s; for uniform rows: %s", CHUNK, issued, uniformIssued)
				.hasSameSizeAs(uniformIssued);
	}

	/**
	 * Row 2 moves an employee off a number and row 3 takes it, while row 1 --
	 * with row 3's column set -- comes first. Grouping the writes by column
	 * set wrote row 3 before row 2 and hit the phone's unique key, and the
	 * chunk was replayed. In sheet order it is one clean chunk.
	 */
	@Test
	void aNumberReassignedInsideAChunkCostsNoReplay() throws Exception {
		int first = 3100;
		String held = column(FIRST + first + 1, "phone");
		String reassigned = rows(
				phoneOnlyRow(first, "01077100000"),
				phoneRow(first + 1, "01077100001", "moved"),
				phoneOnlyRow(first + 2, held));
		String control = rows(
				phoneOnlyRow(first + 3, "01077100003"),
				phoneRow(first + 4, "01077100004", "moved"),
				phoneOnlyRow(first + 5, "01077100005"));

		AtomicReference<Map<String, Object>> data = new AtomicReference<>();
		List<String> issued = measure(() -> data.set(update(reassigned)));
		List<String> controlIssued = measure(() -> update(control));

		assertThat(failed(data.get())).isEmpty();
		assertThat(column(FIRST + first + 2, "phone")).isEqualTo(held);
		assertThat(column(FIRST + first + 1, "phone")).isEqualTo("01077100001");
		assertThat(issued).as("the reassignment's statements against a sheet without one: %s", issued)
				.hasSameSizeAs(controlIssued);
	}

	/**
	 * One row the database refuses among five hundred costs a few extra
	 * transactions -- the failed chunk is halved until the row is alone --
	 * not five hundred.
	 */
	@Test
	void oneRefusedRowAmongFiveHundredCostsLogarithmicallyManyExtraStatements() throws Exception {
		int from = 3200;
		int bad = 250;
		String clean = rows(from, CHUNK, n -> fullRow(n, "Clean" + n));
		List<String> cleanIssued = measure(() -> update(clean));
		String body = rows(from, CHUNK, n -> {
			Map<String, Object> row = fullRow(n, "Again" + n);
			if (n == from + bad) {
				row.put("address", "boom");
			}
			return row;
		});

		AtomicReference<Map<String, Object>> data = new AtomicReference<>();
		List<String> issued = measure(() -> data.set(update(body)));

		assertThat(data.get()).containsEntry("updated", CHUNK - 1);
		assertThat(failed(data.get())).singleElement().satisfies(row -> {
			assertThat(row).containsEntry("row_index", bad + 1);
			assertThat(row).containsEntry("errors", List.of("employee_update_failed"));
		});
		assertThat(column(FIRST + from + bad, "first_name")).isEqualTo("Clean" + (from + bad));
		for (int n = from; n < from + CHUNK; n++) {
			if (n != from + bad) {
				assertThat(column(FIRST + n, "first_name")).isEqualTo("Again" + n);
			}
		}
		// Halving 500 rows down to one takes 9 levels; each level writes two
		// halves of at most five statements (a re-read and four batches).
		System.out.println("BUDGET bisect " + cleanIssued.size() + " " + issued.size());
		assertThat(issued.size()).as("statements with one refused row, against %d clean", cleanIssued.size())
				.isLessThanOrEqualTo(cleanIssued.size() + 2 * 9 * 5 + 5);
	}

	private static final List<String> ALL_CELLS = List.of("first_name", "last_name", "address", "national_id",
			"expected_daily_hours", "is_mobile_attendance_enabled", "birth_date", "gender", "hire_date",
			"contract_duration_years", "salary_basic", "salary_transport", "move", "shift_name", "phone",
			"password");

	private static Map<String, Object> heterogeneousRow(int n, List<String> cells) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("employee_code", String.valueOf(70000 + n));
		for (String cell : cells) {
			switch (cell) {
				case "first_name" -> row.put(cell, "F" + n);
				case "last_name" -> row.put(cell, "L" + n);
				case "address" -> row.put(cell, "Addr " + n);
				case "national_id" -> row.put(cell, String.valueOf(29000000000000L + n));
				case "expected_daily_hours" -> row.put(cell, n % 2 == 0 ? "7.5" : "9");
				case "is_mobile_attendance_enabled" -> row.put(cell, n % 2 == 0 ? "0" : "yes");
				case "birth_date" -> row.put(cell, "1990-05-" + String.format("%02d", 1 + n % 28));
				case "gender" -> row.put(cell, n % 2 == 0 ? "female" : "m");
				case "hire_date" -> row.put(cell, "2023-01-" + String.format("%02d", 1 + n % 28));
				case "contract_duration_years" -> row.put(cell, n % 2 == 0 ? "2" : "1.5");
				case "salary_basic" -> row.put(cell, String.valueOf(4000 + n));
				case "salary_transport" -> row.put(cell, "300.25");
				case "move" -> {
					row.put("branch_name", "North");
					row.put("department_name", "Field");
					row.put("job_title_name", "Driver");
				}
				case "shift_name" -> row.put(cell, "Day");
				case "phone" -> row.put(cell, "0107" + String.format("%07d", n));
				case "password" -> row.put(cell, "pw-" + n);
				default -> throw new IllegalArgumentException(cell);
			}
		}
		return row;
	}

	/** What the legacy per-row path stores for {@code row}, against the employee as it was. */
	private void assertStored(int n, Map<String, Object> row, Map<String, Object> before) throws Exception {
		Map<String, Object> expected = new LinkedHashMap<>(before);
		expected.remove("updated_at");
		expected.remove("password_hash");
		row.forEach((cell, value) -> {
			String text = String.valueOf(value);
			switch (cell) {
				case "first_name", "last_name", "address", "national_id", "birth_date", "hire_date" ->
					expected.put(cell, text);
				case "expected_daily_hours" -> expected.put(cell, new java.math.BigDecimal(text).setScale(2).toPlainString());
				case "is_mobile_attendance_enabled" -> expected.put(cell, "0".equals(text) ? "0" : "1");
				case "gender" -> expected.put(cell, "m".equals(text) ? "male" : text);
				case "contract_duration_years" -> expected.put("contract_duration_months",
						String.valueOf(Math.round(Double.parseDouble(text) * 12)));
				case "branch_name" -> expected.put("branch_id", "359012");
				case "department_name" -> expected.put("department_id", "359022");
				case "job_title_name" -> expected.put("job_title_id", "359032");
				case "phone" -> {
					expected.put("phone", text);
					expected.put("country_code", "+20");
				}
				default -> { }
			}
		});
		Map<String, Object> after = employeeRow(n);
		String hash = (String) after.remove("password_hash");
		after.remove("updated_at");
		assertThat(after).as("employee %d after %s", n, row).isEqualTo(expected);
		if (row.containsKey("password")) {
			assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
					.matches(String.valueOf(row.get("password")), hash)).as("employee %d's password", n).isTrue();
		} else {
			assertThat(hash).as("employee %d's password untouched", n).isEqualTo(before.get("password_hash"));
		}
		assertThat(scalar("SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + (FIRST + n)))
				.as("employee %d's shift assignments", n).isEqualTo(row.containsKey("shift_name") ? 1 : 0);
		boolean salary = row.containsKey("salary_basic") || row.containsKey("salary_transport");
		Map<String, Object> contract = row("SELECT CAST(basic_salary AS CHAR) AS basic,"
				+ " CAST(transport_allowance AS CHAR) AS transport, CAST(effective_from AS CHAR) AS effective,"
				+ " (SELECT COUNT(*) FROM salary_contracts c WHERE c.employee_id = " + (FIRST + n) + ") AS contracts"
				+ " FROM salary_contracts WHERE employee_id = " + (FIRST + n) + " ORDER BY effective_from DESC, id DESC LIMIT 1");
		boolean hadContract = n % 2 == 0;
		if (!salary) {
			assertThat(contract.isEmpty() ? 0L : ((Number) contract.get("contracts")).longValue())
					.as("employee %d's contracts", n).isEqualTo(hadContract ? 1L : 0L);
			return;
		}
		assertThat(((Number) contract.get("contracts")).longValue()).as("employee %d's contracts", n).isEqualTo(1L);
		String basic = row.containsKey("salary_basic") ? row.get("salary_basic") + ".00" : hadContract ? "1000.00" : "0.00";
		String transport = row.containsKey("salary_transport") ? "300.25" : "0.00";
		assertThat(contract.get("basic")).as("employee %d's basic salary", n).isEqualTo(basic);
		assertThat(contract.get("transport")).as("employee %d's transport", n).isEqualTo(transport);
		// A new contract starts on the hire date as it was before this row's own write.
		assertThat(contract.get("effective")).as("employee %d's contract start", n).isEqualTo("2024-02-01");
	}

	private static Map<String, Object> employeeRow(int n) throws Exception {
		Map<String, Object> row = new LinkedHashMap<>();
		try (Connection connection = connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT * FROM employees WHERE id = " + (FIRST + n))) {
			rs.next();
			for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
				row.put(rs.getMetaData().getColumnLabel(i), rs.getString(i));
			}
		}
		return row;
	}

	private static Map<String, Object> phoneOnlyRow(int n, String phone) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("employee_code", String.valueOf(70000 + n));
		row.put("phone", phone);
		return row;
	}

	// ---------------- helpers ----------------

	private List<String> measureUpdate(int from, int count) throws Exception {
		String body = rows(from, count, n -> fullRow(n, "Budget" + n));
		AtomicReference<Map<String, Object>> data = new AtomicReference<>();
		List<String> issued = measure(() -> data.set(update(body)));
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
		List<String> issued = measure(() -> body.set(analyze(file)));
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

	/**
	 * The statements {@code operation} issued, less Spring Session's
	 * once-a-minute expiry sweep: it shares the DataSource and lands inside a
	 * long measurement now and then, which is not the sheet's cost.
	 */
	private static List<String> measure(Runnable operation) {
		return COUNTER.measure(operation).stream().filter(sql -> !sql.contains("SPRING_SESSION")).toList();
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
