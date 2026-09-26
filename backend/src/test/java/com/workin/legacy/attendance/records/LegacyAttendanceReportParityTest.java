package com.workin.legacy.attendance.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.workin.backend.perf.QueryCounter;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyMariaDb;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.calendar.LegacyAttendancePeriodStats;
import com.workin.legacy.attendance.calendar.LegacyAttendanceRangeRows;
import com.workin.legacy.attendance.calendar.LegacyAttendanceRangeCalendar;
import com.workin.legacy.attendance.calendar.LegacyAttendanceReportDetails;
import com.workin.legacy.attendance.calendar.LegacyAttendanceWorkedMinutes;
import com.workin.legacy.attendance.calendar.LegacyWeeklyRestCredit;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.payroll.LegacyPayrollAttendanceFigures;
import com.workin.legacy.payroll.LegacyPayrollFiscalSettings;

import tools.jackson.databind.ObjectMapper;

/**
 * The report endpoints answer exactly what they answered before D-292 made
 * their reads set-based -- byte for byte, key order included.
 *
 * <h2>The oracle is the old code, run beside the new</h2>
 * <p>{@code com.workin.legacy.attendance.baseline} is the per-employee,
 * per-day implementation as it stood at {@code 8f7505a0}, copied into test
 * scope with its Spring stereotypes removed. Each case here builds both object
 * graphs fresh -- one cold request each, as {@code @RequestScope} gives them in
 * production -- over the same database and the same fixed clock, and compares
 * their answers serialized through the same {@link ObjectMapper}. That is
 * stronger than golden files: the fixture can be regenerated from any seed and
 * grown without re-capturing anything, and "the same" means the same at this
 * instant, open punches and today's clamp included.
 *
 * <p>The oracle is frozen on purpose. A later, deliberate change to what these
 * endpoints answer will make this test fail; that change's own decision entry
 * is where the oracle is updated or retired, not a reason to edit it silently.
 */
class LegacyAttendanceReportParityTest {

	/** Inside the fixture's range, so the open-period clamp and open punches are exercised. */
	private static final LocalDateTime NOW = LocalDateTime.parse("2026-03-18T12:00:00");

	private static final String REST_LABEL = "Weekly rest";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final Map<Long, DataSource> DATABASES = new ConcurrentHashMap<>();

	private static final List<String[]> RANGES = List.of(
			new String[] { "2026-01-01", "2026-01-31" },
			new String[] { "2026-02-01", "2026-03-31" },
			new String[] { "2026-03-10", "2026-03-16" },
			new String[] { "2025-12-20", "2026-03-18" },
			new String[] { "2026-03-15", "2026-04-15" });

	private final QueryCounter counter = new QueryCounter();

	@ParameterizedTest
	@ValueSource(longs = { 356L, 20260318L })
	void theListWithFillDaysIsUnchanged(long seed) {
		DataSource db = database(seed);
		for (long company : new long[] { AttendanceReportFixture.COMPANY_A, AttendanceReportFixture.COMPANY_B }) {
			long admin = company == AttendanceReportFixture.COMPANY_A
					? AttendanceReportFixture.ADMIN_A : AttendanceReportFixture.ADMIN_B;
			for (String[] range : RANGES) {
				String query = "fill_days=1&date_from=" + range[0] + "&date_to=" + range[1];
				assertFillDaysSame(db, context(admin, company, LegacyEmployee.Role.COMPANY_ADMIN), query);
			}
			String month = "fill_days=1&date_from=2026-02-01&date_to=2026-03-31";
			assertFillDaysSame(db, context(admin, company, LegacyEmployee.Role.COMPANY_ADMIN),
					month + "&branch_id=" + AttendanceReportFixture.BRANCH_A2);
			assertFillDaysSame(db, context(admin, company, LegacyEmployee.Role.COMPANY_ADMIN), month + "&search=Emp1");
			assertFillDaysSame(db, context(admin, company, LegacyEmployee.Role.COMPANY_ADMIN), month + "&search=10");
			// No dates at all: the current month.
			assertFillDaysSame(db, context(admin, company, LegacyEmployee.Role.COMPANY_ADMIN), "fill_days=1");
		}
		long self = AttendanceReportFixture.FIRST_A;
		assertFillDaysSame(db, context(self, AttendanceReportFixture.COMPANY_A, LegacyEmployee.Role.EMPLOYEE),
				"fill_days=1&date_from=2026-02-01&date_to=2026-03-31");
	}

	@ParameterizedTest
	@ValueSource(longs = { 356L, 20260318L })
	void theOverallReportIsUnchanged(long seed) {
		DataSource db = database(seed);
		for (long company : new long[] { AttendanceReportFixture.COMPANY_A, AttendanceReportFixture.COMPANY_B }) {
			for (String[] range : RANGES) {
				assertOverallSame(db, company, null, null, range[0], range[1], 0, 0, 0, 0, null);
			}
			assertOverallSame(db, company, null, null, "", "", 2, 2026, 0, 0, null);
			assertOverallSame(db, company, null, null, "2026-02-01", "2026-03-31", 0, 0, 0,
					AttendanceReportFixture.BRANCH_A2, null);
			assertOverallSame(db, company, null, null, "2026-02-01", "2026-03-31", 0, 0, 0, 0, "Emp1");
		}
		assertOverallSame(db, AttendanceReportFixture.COMPANY_A, null, AttendanceReportFixture.MANAGER_A2,
				"2026-01-01", "2026-03-31", 0, 0, 0, 0, null);
		long self = AttendanceReportFixture.FIRST_A;
		assertOverallSame(db, AttendanceReportFixture.COMPANY_A, self, null, "2026-01-01", "2026-03-31", 0, 0, 0, 0,
				null);
	}

	@ParameterizedTest
	@ValueSource(longs = { 356L, 20260318L })
	void theExportSheetsAreUnchanged(long seed) {
		DataSource db = database(seed);
		for (long company : new long[] { AttendanceReportFixture.COMPANY_A, AttendanceReportFixture.COMPANY_B }) {
			for (String[] range : RANGES) {
				assertSheetsSame(db, company, null, null, range[0], range[1], 0, null, false);
			}
			assertSheetsSame(db, company, null, null, "2025-12-20", "2026-03-18", AttendanceReportFixture.BRANCH_A2,
					null, true);
			assertSheetsSame(db, company, null, null, "2026-02-01", "2026-03-31", 0, "10", false);
			assertSheetsSame(db, company, null, null, "", "", 0, null, false);
		}
		assertSheetsSame(db, AttendanceReportFixture.COMPANY_A, null, AttendanceReportFixture.MANAGER_A2,
				"2026-01-01", "2026-03-31", 0, null, false);
	}

	@ParameterizedTest
	@ValueSource(longs = { 356L, 20260318L })
	void theSingleEmployeeViewsAreUnchanged(long seed) {
		DataSource db = database(seed);
		AttendanceReportFixture shape = AttendanceReportFixture.varied(seed, 14);
		List<Long> employees = new ArrayList<>(shape.employeesA());
		employees.addAll(shape.employeesB());
		for (long employee : employees) {
			long company = shape.employeesA().contains(employee)
					? AttendanceReportFixture.COMPANY_A : AttendanceReportFixture.COMPANY_B;
			LegacyRequestContext admin = context(company == AttendanceReportFixture.COMPANY_A
					? AttendanceReportFixture.ADMIN_A : AttendanceReportFixture.ADMIN_B, company,
					LegacyEmployee.Role.COMPANY_ADMIN);
			for (String query : List.of(
					"employee_id=" + employee + "&date_from=2026-02-01&date_to=2026-03-31",
					"employee_id=" + employee + "&month=1&year=2026",
					"employee_id=" + employee)) {
				assertSame("stats " + query, db, graph -> graph.stats(admin, query));
			}
			for (String query : List.of(
					"id=" + employee + "&month=2&year=2026&full_month=1",
					"id=" + employee + "&month=3&year=2026&full_month=1",
					"id=" + employee + "&month=3&year=2026")) {
				assertSame("monthly " + query, db, graph -> graph.monthly(admin, query));
			}
		}
	}

	// ------------------------------------------------------------------

	private void assertFillDaysSame(DataSource db, LegacyRequestContext context, String query) {
		assertSame("fill_days " + context + " " + query, db, graph -> graph.fillDays(context, query));
	}

	private void assertOverallSame(DataSource db, long company, Long self, Long manager, String from, String to,
			int month, int year, long employee, long branch, String search) {
		assertSame("overall " + company + " " + from + ".." + to + " m" + month + " b" + branch + " s" + search
				+ " self" + self + " mgr" + manager, db,
				graph -> graph.overall(company, self, manager, from, to, month, year, employee, branch, search));
	}

	private void assertSheetsSame(DataSource db, long company, Long self, Long manager, String from, String to,
			long branch, String search, boolean arabic) {
		String label = company + " " + from + ".." + to + " b" + branch + " s" + search;
		assertSame("fingerprints " + label, db,
				graph -> graph.fingerprints(company, self, manager, from, to, branch, search, arabic));
		assertSame("overall sheet " + label, db,
				graph -> graph.overallSheet(company, self, manager, from, to, branch, search));
	}

	private void assertSame(String what, DataSource db, java.util.function.Function<Graph, Object> call) {
		DataSource counted = counter.wrap(db);
		Object[] baseline = new Object[1];
		Object[] current = new Object[1];
		List<String> before = counter.measure(() -> baseline[0] = outcome(() -> call.apply(Graph.baseline(counted))));
		List<String> after = counter.measure(() -> current[0] = outcome(() -> call.apply(Graph.current(counted))));
		String expected = JSON.writeValueAsString(sameDateExceptionsUnordered(baseline[0]));
		String actual = JSON.writeValueAsString(sameDateExceptionsUnordered(current[0]));
		assertThat(actual).as(what).isEqualTo(expected);
		assertThat(sameDateExceptionsUnordered(current[0])).as(what)
				.isEqualTo(sameDateExceptionsUnordered(baseline[0]));
		System.out.println("[parity] " + what + ": " + before.size() + " -> " + after.size() + " statements, "
				+ expected.length() + " bytes");
	}

	/**
	 * The one relation weaker than equality, and it is as narrow as it can be.
	 *
	 * <p>{@code exception_details} was {@code ORDER BY exception_date} and
	 * nothing else, so two exception rows on one date came back in whatever
	 * order MariaDB's sort left them -- measured on this fixture, the same
	 * statement over the same rows returned one date's two entries in check-in
	 * order when run alone and reversed inside this test. D-292 orders them by
	 * check-in and id instead; that order is not reproducible from a read of more
	 * than one employee, because it was never a property of the rows. So each
	 * run of equal dates is compared as a sorted group: the sequence of dates,
	 * how many entries each date has and which names they carry are all still
	 * compared exactly, and only the order inside one date is not.
	 */
	@SuppressWarnings("unchecked")
	private static Object sameDateExceptionsUnordered(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<Object, Object> copy = new LinkedHashMap<>();
			map.forEach((key, entry) -> copy.put(key, "exception_details".equals(key) && entry instanceof List<?> list
					? groupedByDate((List<Map<String, Object>>) list) : sameDateExceptionsUnordered(entry)));
			return copy;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(LegacyAttendanceReportParityTest::sameDateExceptionsUnordered).toList();
		}
		return value;
	}

	private static List<List<String>> groupedByDate(List<Map<String, Object>> details) {
		List<List<String>> groups = new ArrayList<>();
		String date = null;
		List<String> names = null;
		for (Map<String, Object> detail : details) {
			if (names == null || !detail.get("date").equals(date)) {
				date = (String) detail.get("date");
				names = new ArrayList<>();
				names.add(date);
				groups.add(names);
			}
			names.add((String) detail.get("exception_name"));
		}
		for (List<String> group : groups) {
			java.util.Collections.sort(group.subList(1, group.size()));
		}
		return groups;
	}

	/** An exception is an answer too: both sides must refuse the same way. */
	private static Object outcome(java.util.function.Supplier<Object> call) {
		try {
			return call.get();
		} catch (RuntimeException ex) {
			return Map.of("threw", ex.getClass().getName(), "message", String.valueOf(ex.getMessage()));
		}
	}

	private static LegacyRequestContext context(long employee, long company, LegacyEmployee.Role role) {
		return new LegacyRequestContext(employee, company, role, "employee");
	}

	private static synchronized DataSource database(long seed) {
		return DATABASES.computeIfAbsent(seed, key -> {
			LegacyMariaDb.Handle handle = LegacyMariaDb.freshDatabase();
			try (Connection connection = handle.connect(); Statement st = connection.createStatement()) {
				st.execute("SET SESSION sql_mode = ''");
				for (String sql : AttendanceReportFixture.varied(key, 14).statements()) {
					st.execute(sql);
				}
			} catch (Exception ex) {
				throw new IllegalStateException("could not seed the parity fixture", ex);
			}
			// One connection, kept open: a DriverManagerDataSource connects afresh for
			// every statement, and the baseline issues tens of thousands of them.
			SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
					handle.getJdbcUrl(), handle.getUsername(), handle.getPassword(), true);
			dataSource.setAutoCommit(true);
			return dataSource;
		});
	}

	/** {@code today()} and {@code now()} pinned, so both sides answer for the same instant. */
	static final class FixedClock extends LegacyClock {

		FixedClock(DataSource dataSource) {
			super(dataSource);
		}

		@Override
		public LocalDate today() {
			return NOW.toLocalDate();
		}

		@Override
		public LocalDateTime now() {
			return NOW;
		}

		@Override
		public String todayAsString() {
			return NOW.toLocalDate().toString();
		}
	}

	/** One cold request's object graph, either the frozen baseline or the code under test. */
	interface Graph {

		Object fillDays(LegacyRequestContext context, String query);

		Object overall(long company, Long self, Long manager, String from, String to, int month, int year,
				long employee, long branch, String search);

		Object fingerprints(long company, Long self, Long manager, String from, String to, long branch,
				String search, boolean arabic);

		Object overallSheet(long company, Long self, Long manager, String from, String to, long branch,
				String search);

		Object stats(LegacyRequestContext context, String query);

		Object monthly(LegacyRequestContext context, String query);

		static Graph baseline(DataSource ds) {
			return new BaselineGraph(ds);
		}

		static Graph current(DataSource ds) {
			return new CurrentGraph(ds);
		}
	}

	private static Map<String, Object> listing(List<Map<String, Object>> rows, Map<String, Object> meta) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("rows", rows);
		out.put("meta", meta);
		return out;
	}

	private static final class CurrentGraph implements Graph {

		private final LegacyAttendanceReportService reports;
		private final LegacyOverallReportService overall;
		private final LegacyAttendanceExportService export;

		CurrentGraph(DataSource ds) {
			LegacyClock clock = new FixedClock(ds);
			LegacyWeeklyOffDays offDays = new LegacyWeeklyOffDays(ds);
			LegacyAttendanceCalendar calendar = new LegacyAttendanceCalendar(ds, offDays);
			LegacyAttendanceSessions sessions = new LegacyAttendanceSessions(ds, calendar, clock);
			LegacyAttendanceWorkedMinutes worked = new LegacyAttendanceWorkedMinutes(ds, calendar, sessions, clock);
			LegacyWeeklyRestCredit credit = new LegacyWeeklyRestCredit(ds, calendar);
			LegacyPayrollFiscalSettings fiscal = new LegacyPayrollFiscalSettings(ds);
			LegacyAttendanceRangeRows rows = new LegacyAttendanceRangeRows(ds);
			LegacyAttendanceRangeCalendar range = new LegacyAttendanceRangeCalendar(calendar, credit, worked, rows,
					fiscal);
			LegacyPayrollAttendanceFigures figures = new LegacyPayrollAttendanceFigures(ds, calendar, credit, worked,
					offDays);
			LegacyAttendanceReportDetails details = new LegacyAttendanceReportDetails(calendar, worked, credit,
					figures, rows);
			LegacyAttendancePeriodStats stats = new LegacyAttendancePeriodStats(ds, calendar, credit, worked);
			LegacyOverallReportStore store = new LegacyOverallReportStore(ds);
			this.overall = new LegacyOverallReportService(store, details, figures, calendar, rows, clock);
			this.export = new LegacyAttendanceExportService(overall, store, range, clock);
			this.reports = new LegacyAttendanceReportService(new LegacyAttendanceReportStore(ds), range, worked,
					stats, sessions, new LegacyEmployeeStore(ds), calendar, clock, fiscal);
		}

		@Override
		public Object fillDays(LegacyRequestContext context, String query) {
			LegacyAttendanceReportService.Listing listing =
					reports.list(context, LegacyQueryParameters.parse(query), REST_LABEL);
			return listing(listing.rows(), listing.meta());
		}

		@Override
		public Object overall(long company, Long self, Long manager, String from, String to, int month, int year,
				long employee, long branch, String search) {
			return overall.build(company, self, manager,
					new LegacyOverallReportService.Filters(from, to, month, year, employee, branch, 0, search),
					labels());
		}

		@Override
		public Object fingerprints(long company, Long self, Long manager, String from, String to, long branch,
				String search, boolean arabic) {
			return export.fingerprintsSheet(company, self, manager,
					new LegacyOverallReportService.Filters(from, to, 0, 0, 0, branch, 0, search), REST_LABEL, arabic);
		}

		@Override
		public Object overallSheet(long company, Long self, Long manager, String from, String to, long branch,
				String search) {
			return export.overallSheet(company, self, manager,
					new LegacyOverallReportService.Filters(from, to, 0, 0, 0, branch, 0, search), labels());
		}

		@Override
		public Object stats(LegacyRequestContext context, String query) {
			return reports.stats(context, LegacyQueryParameters.parse(query), REST_LABEL);
		}

		@Override
		public Object monthly(LegacyRequestContext context, String query) {
			LegacyAttendanceReportService.MonthlyAttendance monthly =
					reports.employeeMonthlyAttendance(context, LegacyQueryParameters.parse(query), REST_LABEL);
			return List.of(monthly.data(), monthly.meta());
		}

		private static LegacyOverallReportService.Labels labels() {
			return new LegacyOverallReportService.Labels(
					"Official holidays", "Leave days", "Paid rest", "Absent", "Present", "Void rest", REST_LABEL);
		}
	}

	private static final class BaselineGraph implements Graph {

		private final com.workin.legacy.attendance.baseline.LegacyAttendanceReportService reports;
		private final com.workin.legacy.attendance.baseline.LegacyOverallReportService overall;
		private final com.workin.legacy.attendance.baseline.LegacyAttendanceExportService export;

		BaselineGraph(DataSource ds) {
			LegacyClock clock = new FixedClock(ds);
			LegacyWeeklyOffDays offDays = new LegacyWeeklyOffDays(ds);
			var calendar = new com.workin.legacy.attendance.baseline.LegacyAttendanceCalendar(ds, offDays);
			var sessions = new com.workin.legacy.attendance.baseline.LegacyAttendanceSessions(ds, calendar, clock);
			var worked = new com.workin.legacy.attendance.baseline.LegacyAttendanceWorkedMinutes(
					ds, calendar, sessions, clock);
			var credit = new com.workin.legacy.attendance.baseline.LegacyWeeklyRestCredit(ds, calendar);
			LegacyPayrollFiscalSettings fiscal = new LegacyPayrollFiscalSettings(ds);
			var range = new com.workin.legacy.attendance.baseline.LegacyAttendanceRangeCalendar(
					ds, calendar, credit, worked, fiscal);
			var figures = new com.workin.legacy.attendance.baseline.LegacyPayrollAttendanceFigures(
					ds, calendar, credit, worked, offDays);
			var details = new com.workin.legacy.attendance.baseline.LegacyAttendanceReportDetails(
					ds, calendar, worked, credit, figures);
			var stats = new com.workin.legacy.attendance.baseline.LegacyAttendancePeriodStats(
					ds, calendar, credit, worked);
			LegacyOverallReportStore store = new LegacyOverallReportStore(ds);
			this.overall = new com.workin.legacy.attendance.baseline.LegacyOverallReportService(
					store, details, figures, calendar, credit, clock);
			this.export = new com.workin.legacy.attendance.baseline.LegacyAttendanceExportService(
					overall, store, range, clock);
			this.reports = new com.workin.legacy.attendance.baseline.LegacyAttendanceReportService(
					new LegacyAttendanceReportStore(ds), range, worked, stats, sessions, new LegacyEmployeeStore(ds),
					clock, fiscal);
		}

		@Override
		public Object fillDays(LegacyRequestContext context, String query) {
			var listing = reports.list(context, LegacyQueryParameters.parse(query), REST_LABEL);
			return listing(listing.rows(), listing.meta());
		}

		@Override
		public Object overall(long company, Long self, Long manager, String from, String to, int month, int year,
				long employee, long branch, String search) {
			return overall.build(company, self, manager,
					new com.workin.legacy.attendance.baseline.LegacyOverallReportService.Filters(
							from, to, month, year, employee, branch, 0, search),
					labels());
		}

		@Override
		public Object fingerprints(long company, Long self, Long manager, String from, String to, long branch,
				String search, boolean arabic) {
			var sheet = export.fingerprintsSheet(company, self, manager,
					new com.workin.legacy.attendance.baseline.LegacyOverallReportService.Filters(
							from, to, 0, 0, 0, branch, 0, search),
					REST_LABEL, arabic);
			return new LegacyAttendanceExportService.Sheet(sheet.filename(), sheet.rows(), sheet.rowStyles());
		}

		@Override
		public Object overallSheet(long company, Long self, Long manager, String from, String to, long branch,
				String search) {
			var sheet = export.overallSheet(company, self, manager,
					new com.workin.legacy.attendance.baseline.LegacyOverallReportService.Filters(
							from, to, 0, 0, 0, branch, 0, search),
					labels());
			return new LegacyAttendanceExportService.Sheet(sheet.filename(), sheet.rows(), sheet.rowStyles());
		}

		@Override
		public Object stats(LegacyRequestContext context, String query) {
			return reports.stats(context, LegacyQueryParameters.parse(query), REST_LABEL);
		}

		@Override
		public Object monthly(LegacyRequestContext context, String query) {
			var monthly = reports.employeeMonthlyAttendance(context, LegacyQueryParameters.parse(query), REST_LABEL);
			return List.of(monthly.data(), monthly.meta());
		}

		private static com.workin.legacy.attendance.baseline.LegacyOverallReportService.Labels labels() {
			return new com.workin.legacy.attendance.baseline.LegacyOverallReportService.Labels(
					"Official holidays", "Leave days", "Paid rest", "Absent", "Present", "Void rest", REST_LABEL);
		}
	}
}
