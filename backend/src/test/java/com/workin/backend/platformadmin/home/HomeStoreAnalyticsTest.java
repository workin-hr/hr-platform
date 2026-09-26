package com.workin.backend.platformadmin.home;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.backend.perf.QueryCounter;
import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * The home page's summary and its two newer charts, against a real MariaDB
 * (D-290).
 *
 * <p>Two properties: the numbers are this company's and nobody else's, and the
 * sixteen summary figures cost one round trip. A second company is seeded with
 * a row for every figure, so a subquery that lost its company predicate counts
 * it and the exact values below stop matching.
 *
 * <p>Dates are the database's own {@code CURDATE()}, not the JVM's, because
 * the queries compare against it.
 */
class HomeStoreAnalyticsTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 990401L;
	private static final long OTHER = 990402L;

	private QueryCounter counter;
	private HomeStore store;
	private LocalDate today;

	@BeforeEach
	void setUp() throws Exception {
		DataSource plain = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
		this.counter = new QueryCounter();
		this.store = new HomeStore(new JdbcTemplate(this.counter.wrap(plain)));
		try (Connection c = connect(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("SELECT CURDATE()")) {
			rs.next();
			this.today = rs.getDate(1).toLocalDate();
		}

		seedAsLegacyWould(
				"DELETE FROM payslips WHERE employee_id BETWEEN 990400 AND 990499",
				"DELETE FROM payroll_batches WHERE company_id BETWEEN 990400 AND 990499",
				"DELETE FROM complaints WHERE company_id BETWEEN 990400 AND 990499",
				"DELETE FROM advances WHERE employee_id BETWEEN 990400 AND 990499",
				"DELETE FROM requests WHERE employee_id BETWEEN 990400 AND 990499",
				"DELETE FROM request_types WHERE id BETWEEN 990400 AND 990499",
				"DELETE FROM attendance WHERE employee_id BETWEEN 990400 AND 990499",
				"DELETE FROM salary_contracts WHERE employee_id BETWEEN 990400 AND 990499",
				"DELETE FROM penalties WHERE employee_id BETWEEN 990400 AND 990499",
				"DELETE FROM employees WHERE id BETWEEN 990400 AND 990499",
				"DELETE FROM branches WHERE id BETWEEN 990400 AND 990499",
				"DELETE FROM companies WHERE id BETWEEN 990400 AND 990499",
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
						+ " (" + COMPANY + ", 'Home C', '+201000990401', 'active', '2019-01-15 09:00:00'),"
						+ " (" + OTHER + ", 'Home D', '+201000990402', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
						+ " (990411, " + COMPANY + ", 'C', 1, '2019-03-01 10:00:00'),"
						+ " (990413, " + COMPANY + ", 'C2', 1, '2019-03-01 10:00:00'),"
						+ " (990414, " + COMPANY + ", 'C3', 1, '2019-03-01 10:00:00'),"
						+ " (990415, " + COMPANY + ", 'C4', 1, '2019-03-01 10:00:00'),"
						+ " (990416, " + COMPANY + ", 'C5', 0, '2019-03-01 10:00:00'),"
						+ " (990412, " + OTHER + ", 'D', 1, '2019-03-01 10:00:00')",
				// Hired this month, and two months ago: two hires in the window.
				employee(990421, COMPANY, 1, "accepted", "CURDATE()", "CURDATE()"),
				employee(990422, COMPANY, 1, "accepted",
						"DATE_SUB(CURDATE(), INTERVAL 2 MONTH)", "DATE_SUB(CURDATE(), INTERVAL 2 MONTH)"),
				// Hired long ago, deactivated this month: one exit, and the year's resignation.
				employee(990423, COMPANY, 0, "accepted", "'2020-01-01'", "CURDATE()"),
				// Applicants: inactive by construction, and neither a hire nor an exit.
				employee(990424, COMPANY, 0, "pending", "CURDATE()", "CURDATE()"),
				employee(990425, COMPANY, 0, "rejected", "CURDATE()", "CURDATE()"),
				// Hired seven months ago: before the six-month window.
				employee(990426, COMPANY, 1, "accepted",
						"DATE_SUB(CURDATE(), INTERVAL 7 MONTH)", "DATE_SUB(CURDATE(), INTERVAL 7 MONTH)"),
				// A long-serving active employee, outside every window.
				employee(990427, COMPANY, 1, "accepted", "'2019-05-01'", "'2019-05-01'"),
				// The other company: a hire, an exit, and a row for every figure below.
				employee(990431, OTHER, 1, "accepted", "CURDATE()", "CURDATE()"),
				employee(990432, OTHER, 0, "accepted", "'2020-01-01'", "CURDATE()"),
				"INSERT INTO request_types (id, company_id, name) VALUES"
						+ " (990401, " + COMPANY + ", 'Leave'), (990402, " + OTHER + ", 'Leave')",
				"INSERT INTO requests (employee_id, request_type_id, from_date, to_date, status) VALUES"
						+ " (990421, 990401, CURDATE(), CURDATE(), 'approved'),"
						+ " (990421, 990401, CURDATE(), CURDATE(), 'pending'),"
						+ " (990422, 990401, CURDATE(), CURDATE(), 'pending'),"
						+ " (990422, 990401, CURDATE(), CURDATE(), 'rejected'),"
						+ " (990431, 990402, CURDATE(), CURDATE(), 'pending'),"
						+ " (990431, 990402, CURDATE(), CURDATE(), 'approved')",
				"INSERT INTO attendance (employee_id, check_in) VALUES"
						+ " (990421, CONCAT(CURDATE(), ' 08:00:00')),"
						+ " (990421, CONCAT(CURDATE(), ' 13:00:00')),"
						+ " (990431, CONCAT(CURDATE(), ' 08:00:00'))",
				"INSERT INTO salary_contracts (employee_id, basic_salary, transport_allowance, food_allowance,"
						+ " risk_allowance, incentives, effective_from) VALUES"
						+ " (990421, 1000, 100, 0, 0, 0, '2024-01-01'),"
						+ " (990421, 2000, 200, 0, 0, 0, '2025-01-01'),"
						+ " (990422, 3000, 0, 0, 0, 0, '2025-01-01'),"
						+ " (990431, 9000, 0, 0, 0, 0, '2025-01-01')",
				"INSERT INTO penalties (employee_id, penalty_type, penalty_date, applied_to_payroll) VALUES"
						+ " (990421, 'late', CURDATE(), 0), (990422, 'late', CURDATE(), 1),"
						+ " (990421, 'late', CURDATE(), 0), (990421, 'late', CURDATE(), 0),"
						+ " (990421, 'late', CURDATE(), 0), (990421, 'late', CURDATE(), 0),"
						+ " (990421, 'late', CURDATE(), 0), (990421, 'late', CURDATE(), 0),"
						+ " (990421, 'late', CURDATE(), 0),"
						+ " (990431, 'late', CURDATE(), 0)",
				// Five of this company's employees' complaints are open; a closed
				// one, one addressed to the platform, and the other company's are not.
				"INSERT INTO complaints (company_id, source, message, status) VALUES"
						+ " (" + COMPANY + ", 'employee', 'm', 'pending'), (" + COMPANY + ", 'employee', 'm', 'pending'),"
						+ " (" + COMPANY + ", 'employee', 'm', 'pending'), (" + COMPANY + ", 'employee', 'm', 'pending'),"
						+ " (" + COMPANY + ", 'employee', 'm', 'pending'), (" + COMPANY + ", 'employee', 'm', 'done'),"
						+ " (" + COMPANY + ", 'company_support', 'm', 'pending'),"
						+ " (" + OTHER + ", 'employee', 'm', 'pending')",
				"INSERT INTO advances (employee_id, amount, remaining, status, request_date) VALUES"
						+ " (990421, 10, 10, 'pending', CURDATE()), (990421, 10, 10, 'pending', CURDATE()),"
						+ " (990421, 10, 10, 'pending', CURDATE()), (990422, 10, 10, 'pending', CURDATE()),"
						+ " (990422, 10, 10, 'pending', CURDATE()), (990422, 10, 10, 'pending', CURDATE()),"
						+ " (990422, 10, 10, 'approved', CURDATE()), (990431, 10, 10, 'pending', CURDATE())",
				// Ten drafts in past years, and this month's finalized run with one payslip.
				"INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to, status)"
						+ " SELECT 990400 + seq, " + COMPANY + ", 1, 2000 + seq, '2000-01-01', '2000-01-31', 'draft'"
						+ " FROM seq_1_to_10",
				"INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to, status) VALUES"
						+ " (990421, " + COMPANY + ", MONTH(CURDATE()), YEAR(CURDATE()), CURDATE(), CURDATE(), 'finalized'),"
						+ " (990431, " + OTHER + ", MONTH(CURDATE()), YEAR(CURDATE()), CURDATE(), CURDATE(), 'draft')",
				"INSERT INTO payslips (batch_id, employee_id, net_salary) VALUES"
						+ " (990421, 990421, 1234.50), (990431, 990431, 9999)");
	}

	@Test
	void theSummaryIsThisCompanysSixteenFiguresInOneRoundTrip() {
		HomeSummary[] summary = new HomeSummary[1];
		List<String> statements = this.counter.measure(() -> summary[0] = this.store.summary(COMPANY));

		assertThat(statements).as("sixteen figures, one statement").hasSize(1);
		HomeSummary s = summary[0];
		// Every count that the company predicate does not pin to 0 or 1 has a
		// value of its own, so two columns swapped in the row mapper, or a
		// subquery that lost its predicate, changes an assertion here.
		assertThat(s.companiesTotal()).isEqualTo(1);
		assertThat(s.companiesActive()).isEqualTo(1);
		assertThat(s.companiesPending()).isZero();
		assertThat(s.employeesTotal()).as("990421, 990422, 990426, 990427").isEqualTo(4);
		assertThat(s.branchesTotal()).as("every branch, active or not, as legacy counts").isEqualTo(5);
		assertThat(s.checkedInToday()).as("two punches, one person").isEqualTo(1);
		assertThat(s.pendingRequests()).isEqualTo(2);
		assertThat(s.openComplaints()).as("the employees' own queue when scoped").isEqualTo(5);
		assertThat(s.pendingAdvances()).isEqualTo(6);
		assertThat(s.penaltiesTotal()).isEqualTo(9);
		assertThat(s.penaltiesUnapplied()).isEqualTo(8);
		assertThat(s.payrollDraft()).isEqualTo(10);
		assertThat(s.monthlyNet()).as("this month's payslips, finalized or not, this company's only")
				.isEqualByComparingTo(new BigDecimal("1234.50"));
		assertThat(s.grossSalaries()).as("the latest contract each: 2000+200 and 3000")
				.isEqualByComparingTo(new BigDecimal("5200"));
		assertThat(s.basicSalaries()).isEqualByComparingTo(new BigDecimal("5000"));
		assertThat(s.resignations()).as("every inactive row updated this year, as legacy counts it")
				.isEqualTo(3);
	}

	@Test
	void theUnfilteredSummaryIsAlsoOneRoundTripAndReadsThePlatformsQueue() {
		HomeSummary[] summary = new HomeSummary[1];
		List<String> statements = this.counter.measure(() -> summary[0] = this.store.summary(0L));

		assertThat(statements).hasSize(1);
		assertThat(summary[0].employeesTotal()).as("both companies, and whatever else the database holds")
				.isGreaterThanOrEqualTo(4 + 1);
		assertThat(summary[0].openComplaints())
				.as("unfiltered, the queue is the one addressed to the platform: the seeded company_support row")
				.isEqualTo(new JdbcTemplate(new DriverManagerDataSource(
						MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword())).queryForObject(
								"SELECT COUNT(*) FROM complaints WHERE status='pending' AND source='company_support'",
								Long.class))
				.isGreaterThanOrEqualTo(1);
	}

	@Test
	void requestsByStatusCountsThisCompanysRequestsInTheQueueOrder() {
		HomeChart chart = this.store.requestsByStatus(COMPANY);

		assertThat(chart.labels()).containsExactly("pending", "approved", "rejected");
		assertThat(chart.values()).containsExactly(2d, 1d, 1d);
	}

	@Test
	void hiresAndExitsCoverSixMonthsAndCountNoApplicant() {
		List<HomeChart> series = this.store.hiresAndExits(COMPANY, this.today);

		HomeChart hires = series.get(0);
		HomeChart exits = series.get(1);
		String thisMonth = this.today.toString().substring(0, 7);
		String twoAgo = this.today.withDayOfMonth(1).minusMonths(2).toString().substring(0, 7);
		assertThat(hires.labels()).as("six months, oldest first, ending with this one")
				.hasSize(6).endsWith(thisMonth)
				.startsWith(this.today.withDayOfMonth(1).minusMonths(5).toString().substring(0, 7));
		assertThat(exits.labels()).isEqualTo(hires.labels());
		assertThat(hires.values().get(5))
				.as("990421 only: the pending and rejected applicants are not hires").isEqualTo(1d);
		assertThat(hires.values().get(hires.labels().indexOf(twoAgo))).isEqualTo(1d);
		assertThat(hires.values().stream().mapToDouble(Double::doubleValue).sum())
				.as("the seven-month-old hire is outside the window").isEqualTo(2d);
		assertThat(exits.values().get(5))
				.as("990423 only: an applicant who is inactive has not left").isEqualTo(1d);
		assertThat(exits.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(1d);
	}

	private static String employee(long id, long companyId, int active, String join,
			String hireDate, String updatedAt) {
		long branch = companyId == COMPANY ? 990411 : 990412;
		return "INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
				+ " phone, role, is_active, join_request_status, hire_date, created_at, updated_at) VALUES ("
				+ id + ", " + companyId + ", " + branch + ", '" + id + "', 'F', 'L', '+2010" + id
				+ "', 'employee', " + active + ", '" + join + "', " + hireDate + ", '2019-04-01 08:00:00', "
				+ "CONCAT(" + updatedAt + ", ' 12:00:00'))";
	}
}
