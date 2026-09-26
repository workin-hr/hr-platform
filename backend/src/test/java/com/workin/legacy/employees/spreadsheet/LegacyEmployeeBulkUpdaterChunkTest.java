package com.workin.legacy.employees.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyMariaDb;
import com.workin.legacy.LegacyPhpArray;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.phone.LegacyPhoneCountries;

/**
 * The write chunks at a size small enough to put rows on either side of a
 * boundary (D-294). {@code LegacyEmployeeBulkUpdateBatchingEndToEndTest}
 * covers the production chunk over HTTP; this drives the updater directly so
 * the chunk can be three rows and a commit can be made to fail.
 */
class LegacyEmployeeBulkUpdaterChunkTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final long COMPANY = 35951L;
	private static final long ADMIN = 3595999L;
	private static final long FIRST = 3595000L;
	private static final int EMPLOYEES = 12;
	private static final int CHUNK = 3;

	/** Commits seen, and the one to fail (0: none). */
	private final AtomicInteger commits = new AtomicInteger();
	private final AtomicInteger failCommit = new AtomicInteger();

	private LegacyEmployeeBulkUpdater updater;
	private LegacyEmployeeStore store;

	@BeforeEach
	void seed() throws Exception {
		try (Connection connection = MARIADB.connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			for (String table : List.of("employee_shift_assignments", "salary_contracts", "employees")) {
				st.execute("DELETE FROM " + table);
			}
			st.execute("DROP TRIGGER IF EXISTS bulk359_refuse_shift");
			st.execute("DROP TRIGGER IF EXISTS bulk359_retire_title");
			st.execute("INSERT IGNORE INTO companies (id, company_name, phone, status, created_at) VALUES"
					+ " (35951, 'Chunk Co', '+201000035951', 'active', '2025-01-15 09:00:00')");
			st.execute("INSERT IGNORE INTO branches (id, company_id, name, is_active, created_at) VALUES"
					+ " (359511, 35951, 'Main', 1, '2025-03-01 10:00:00')");
			st.execute("INSERT IGNORE INTO shifts (id, company_id, name, start_time, end_time, created_at) VALUES"
					+ " (359541, 35951, 'Day', '09:00:00', '17:00:00', '2025-04-12 10:00:00')");
			st.execute("INSERT IGNORE INTO departments (id, company_id, name, is_active, created_at) VALUES"
					+ " (359521, 35951, 'Ops', 1, '2025-04-10 10:00:00')");
			st.execute("INSERT IGNORE INTO job_titles (id, company_id, department_id, name, is_active, created_at)"
					+ " VALUES (359531, 35951, 359521, 'Welder', 1, '2025-04-10 10:00:00')");
			st.execute("UPDATE job_titles SET is_active = 1 WHERE id = 359531");
			StringBuilder values = new StringBuilder("(" + ADMIN + ", 35951, 359511, '1', 'Rana', 'Admin',"
					+ " '01059599999', '+20', 'company_admin', 1, '2025-04-01 08:00:00')");
			for (int n = 0; n < EMPLOYEES; n++) {
				values.append(", (").append(FIRST + n).append(", 35951, 359511, '").append(80000 + n)
						.append("', 'Original', 'Row', '0105950").append(String.format("%04d", n))
						.append("', '+20', 'employee', 1, '2025-04-01 08:00:00')");
			}
			st.execute("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
					+ " phone, country_code, role, is_active, created_at) VALUES " + values);
			st.execute("UPDATE employees SET department_id = 359521 WHERE company_id = 35951");
		}

		DataSource plain = new DriverManagerDataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(),
				MARIADB.getPassword());
		DataSource dataSource = failingCommits(plain);
		this.store = new LegacyEmployeeStore(dataSource);
		LegacyClock clock = new LegacyClock(dataSource);
		LegacyEmployeeUpdateSheetStore sheetStore = new LegacyEmployeeUpdateSheetStore(dataSource);
		LegacyEmployeeUpdateAnalyzer analyzer = new LegacyEmployeeUpdateAnalyzer(
				this.store, new LegacyPhoneCountries(dataSource), clock, sheetStore, dataSource);
		this.updater = new LegacyEmployeeBulkUpdater(analyzer, sheetStore, clock, dataSource,
				new BCryptPasswordEncoder(4), CHUNK);
	}

	/** A chunk of exactly the chunk size, and one row past it, all land. */
	@Test
	void rowsOnBothSidesOfAChunkBoundaryAllApply() throws Exception {
		Map<String, Object> exactly = update(0, CHUNK);
		assertThat(exactly).containsEntry("updated", (long) CHUNK);
		Map<String, Object> onePast = update(CHUNK, CHUNK + 1);
		assertThat(onePast).containsEntry("updated", (long) CHUNK + 1);
		assertThat((List<?>) onePast.get("failed")).isEmpty();
		for (int n = 0; n < 2 * CHUNK + 1; n++) {
			assertThat(firstName(n)).isEqualTo("Renamed" + n);
			assertThat(assignments(n)).isEqualTo(1);
		}
		assertThat(firstName(2 * CHUNK + 1)).as("a row not in either sheet").isEqualTo("Original");
	}

	/** Row 5 is the middle of the second chunk; its shift insert is refused after its employee update ran. */
	@Test
	void aFailingRowInTheMiddleOfAChunkFailsAloneAndOtherChunksAreUntouched() throws Exception {
		try (Connection connection = MARIADB.connect(); Statement st = connection.createStatement()) {
			st.execute("CREATE TRIGGER bulk359_refuse_shift BEFORE INSERT ON employee_shift_assignments"
					+ " FOR EACH ROW BEGIN IF NEW.employee_id = " + (FIRST + 4) + " THEN"
					+ " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'; END IF; END");
		}

		Map<String, Object> result = update(0, 7);

		assertThat(result).containsEntry("updated", 6L);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
		assertThat(failed).singleElement().satisfies(row -> {
			assertThat(row).containsEntry("row_index", 5L);
			assertThat(row).containsEntry("errors", List.of("employee_update_failed"));
		});
		assertThat(firstName(4)).isEqualTo("Original");
		assertThat(assignments(4)).isZero();
		for (int n : new int[] {0, 1, 2, 3, 5, 6}) {
			assertThat(firstName(n)).as("row %d", n + 1).isEqualTo("Renamed" + n);
			assertThat(assignments(n)).as("row %d", n + 1).isEqualTo(1);
		}
		assertThat(result.get("updated_ids")).isEqualTo(
				List.of(FIRST, FIRST + 1, FIRST + 2, FIRST + 3, FIRST + 5, FIRST + 6));
	}

	/**
	 * A job title retired while the sheet is being applied is seen by the
	 * next chunk, as the per-row check saw it on the next row: each chunk's
	 * references are read just before it is validated, not once per request.
	 */
	@Test
	void aReferenceRetiredWhileTheSheetAppliesIsSeenByTheNextChunk() throws Exception {
		try (Connection connection = MARIADB.connect(); Statement st = connection.createStatement()) {
			// The first chunk's shift assignment retires the title the second chunk names.
			st.execute("CREATE TRIGGER bulk359_retire_title AFTER INSERT ON employee_shift_assignments"
					+ " FOR EACH ROW UPDATE job_titles SET is_active = 0 WHERE id = 359531");
		}
		List<Object> rows = new ArrayList<>();
		for (int n = 0; n < CHUNK + 1; n++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("employee_code", String.valueOf(80000 + n));
			row.put("first_name", "Renamed" + n);
			row.put(n < CHUNK ? "shift_name" : "job_title_name", n < CHUNK ? "Day" : "Welder");
			rows.add(row);
		}

		Map<String, Object> result = update(rows);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
		assertThat(failed).singleElement().satisfies(row -> {
			assertThat(row).containsEntry("row_index", (long) CHUNK + 1);
			assertThat(row).containsEntry("errors", List.of("job_title_department_mismatch"));
		});
		assertThat(firstName(CHUNK)).isEqualTo("Original");
	}

	/**
	 * A chunk whose commit fails may or may not have landed, so it is not
	 * replayed -- a replay could write each row's shift assignment twice.
	 * Its rows are reported failed, as PHP reports a row whose commit failed;
	 * the next chunk is written as usual.
	 */
	@Test
	void aChunkWhoseCommitFailsIsReportedFailedAndNotReplayed() throws Exception {
		// The first commit is the sheet's read transaction; the second is the first chunk's.
		this.commits.set(0);
		this.failCommit.set(2);

		Map<String, Object> result = update(0, CHUNK + 1);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
		assertThat(failed).extracting(row -> row.get("row_index")).containsExactly(1L, 2L, 3L);
		assertThat(failed).allSatisfy(row -> assertThat(row).containsEntry("errors", List.of("employee_update_failed")));
		assertThat(result).containsEntry("updated", 1L);
		// The simulated failure throws before the driver's commit, and restoring
		// autocommit on release then commits the chunk anyway -- exactly the
		// case where the client cannot know. It landed once; a replay would
		// have written every assignment a second time.
		for (int n = 0; n < CHUNK; n++) {
			assertThat(assignments(n)).as("row %d was not written a second time", n + 1).isEqualTo(1);
		}
		assertThat(firstName(CHUNK)).isEqualTo("Renamed" + CHUNK);
	}

	private Map<String, Object> update(int from, int count) {
		List<Object> rows = new ArrayList<>();
		for (int n = from; n < from + count; n++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("employee_code", String.valueOf(80000 + n));
			row.put("first_name", "Renamed" + n);
			row.put("shift_name", "Day");
			rows.add(row);
		}
		return update(rows);
	}

	private Map<String, Object> update(List<Object> rows) {
		LegacyRequestContext admin = new LegacyRequestContext(ADMIN, COMPANY, LegacyEmployee.Role.COMPANY_ADMIN, "");
		LegacyEmployeeSpreadsheetLookups lookups = new LegacyEmployeeSpreadsheetLookups(
				this.store.spreadsheetLookup("branches", COMPANY, true),
				this.store.spreadsheetLookup("departments", COMPANY, true),
				this.store.spreadsheetLookup("job_titles", COMPANY, true),
				this.store.spreadsheetLookup("shifts", COMPANY, false));
		return this.updater.updateRows(admin, LegacyPhpArray.of(rows), lookups);
	}

	private static String firstName(int n) throws Exception {
		try (Connection connection = MARIADB.connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT first_name FROM employees WHERE id = " + (FIRST + n))) {
			rs.next();
			return rs.getString(1);
		}
	}

	private static long assignments(int n) throws Exception {
		try (Connection connection = MARIADB.connect(); Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT COUNT(*) FROM employee_shift_assignments WHERE employee_id = " + (FIRST + n))) {
			rs.next();
			return rs.getLong(1);
		}
	}

	/** {@code delegate}, with the {@link #failCommit}th {@code commit()} throwing. */
	private DataSource failingCommits(DataSource delegate) {
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
					Object result = invoke(delegate, method, args);
					if (!(result instanceof Connection connection)) {
						return result;
					}
					return Proxy.newProxyInstance(Connection.class.getClassLoader(),
							new Class<?>[] {Connection.class}, (inner, call, callArgs) -> {
								if ("commit".equals(call.getName())
										&& this.commits.incrementAndGet() == this.failCommit.get()) {
									throw new SQLException("simulated lost commit");
								}
								return invoke(connection, call, callArgs);
							});
				});
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			throw ex.getCause();
		}
	}
}
