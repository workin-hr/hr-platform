package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * D-2's true merge gate for PR 12.2: real {@code EXPLAIN} evidence, at
 * realistic row volume, for P-1b's two highest-volume consumers ({@code
 * attendance}, {@code payslips} -- named explicitly in the Item 12
 * specification §8's PR 12.2 row). "If attendance or payslips show an
 * unacceptable plan, it is resolved inside 12.2 before merge -- with an
 * evidence-backed index or a revised policy implementation -- rather
 * than merging the mechanism and treating performance correctness as
 * later debt."
 *
 * <p><b>Why real volume, not the handful of rows other tenancy tests
 * seed.</b> A cost-based optimizer can and does choose a full table
 * scan over an index on a tiny table regardless of what is indexed --
 * that would pass trivially and prove nothing. This seeds 30 companies,
 * 100 employees each (3,000 total), 20 attendance rows and 10 payslip
 * rows per employee (60,000 and 30,000 rows respectively) -- enough that
 * one company's share (~3.3%) is a real selectivity question for the
 * optimizer, not a rounding error.
 *
 * <p>Every index this plan could use already exists in the vendored
 * schema (verified directly against {@code mysql_workin.schema.sql}'s
 * {@code ALTER TABLE ... ADD KEY} sections, not assumed): {@code
 * employees.company_id} (`fk_employee_company`), {@code
 * attendance.employee_id} (`fk_attendance_employee`, plus {@code
 * index_employee_date`}), {@code payslips.employee_id}
 * (`fk_payslip_employee`). This test's job is to prove MariaDB's
 * optimizer actually uses them for the specific query shape {@link
 * EmployeeDerivedTenantFilter#CONDITION} emits -- a non-correlated
 * {@code IN} subquery -- not to add anything.
 */
class TenantFilterQueryPlanTest extends AbstractLegacyMySqlTest {

	private static final int COMPANIES = 30;
	private static final int EMPLOYEES_PER_COMPANY = 100;
	private static final int ATTENDANCE_PER_EMPLOYEE = 20;
	private static final int PAYSLIPS_PER_EMPLOYEE = 10;
	private static final int BATCHES_PER_COMPANY = PAYSLIPS_PER_EMPLOYEE;

	private static final long COMPANY_ID_BASE = 9_700_000L;
	private static final long BRANCH_ID_BASE = 9_710_000L;
	private static final long EMPLOYEE_ID_BASE = 9_720_000L;
	private static final long BATCH_ID_BASE = 9_740_000L;
	private static final long ATTENDANCE_ID_BASE = 9_800_000L;
	private static final long PAYSLIP_ID_BASE = 9_900_000L;

	/** The company whose plan is inspected -- an arbitrary mid-range one, not the first or last row inserted. */
	private static final long PROBE_COMPANY_ID = COMPANY_ID_BASE + 14;

	@BeforeAll
	static void seedRealisticVolume() throws Exception {
		try (Connection connection = connect()) {
			connection.setAutoCommit(false);
			try (Statement mode = connection.createStatement()) {
				mode.execute("SET SESSION sql_mode = ''");
			}

			try (PreparedStatement companyStmt = connection.prepareStatement(
					"INSERT INTO companies (id, company_name, phone, status, created_at) "
							+ "VALUES (?, ?, ?, 'active', '2025-01-01 00:00:00')");
					PreparedStatement branchStmt = connection.prepareStatement(
							"INSERT INTO branches (id, company_id, name, is_active, created_at) "
									+ "VALUES (?, ?, 'HQ', 1, '2025-01-01 00:00:00')");
					PreparedStatement employeeStmt = connection.prepareStatement(
							"INSERT INTO employees (id, company_id, branch_id, first_name, last_name, phone, "
									+ "role, is_active, is_mobile_attendance_enabled, can_check_in_any_branch, "
									+ "join_request_status, token_version, created_at) "
									+ "VALUES (?, ?, ?, 'Vol', 'Employee', ?, 'employee', 1, 1, 0, 'accepted', 1, "
									+ "'2025-01-01 00:00:00')");
					PreparedStatement batchStmt = connection.prepareStatement(
							"INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to, "
									+ "status, created_at) "
									+ "VALUES (?, ?, ?, 2025, '2025-01-01', '2025-01-31', 'draft', "
									+ "'2025-01-01 00:00:00')");
					PreparedStatement attendanceStmt = connection.prepareStatement(
							"INSERT INTO attendance (id, employee_id, check_in, method, created_at, updated_at) "
									+ "VALUES (?, ?, ?, 'app', ?, ?)");
					PreparedStatement payslipStmt = connection.prepareStatement(
							"INSERT INTO payslips (id, batch_id, employee_id) VALUES (?, ?, ?)")) {

				long employeeId = EMPLOYEE_ID_BASE;
				long attendanceId = ATTENDANCE_ID_BASE;
				long payslipId = PAYSLIP_ID_BASE;

				for (int c = 0; c < COMPANIES; c++) {
					long companyId = COMPANY_ID_BASE + c;
					long branchId = BRANCH_ID_BASE + c;

					companyStmt.setLong(1, companyId);
					companyStmt.setString(2, "Volume Co " + c);
					companyStmt.setString(3, "+20190" + (1000000 + c));
					companyStmt.addBatch();

					branchStmt.setLong(1, branchId);
					branchStmt.setLong(2, companyId);
					branchStmt.addBatch();

					long[] batchIds = new long[BATCHES_PER_COMPANY];
					for (int b = 0; b < BATCHES_PER_COMPANY; b++) {
						long batchId = BATCH_ID_BASE + (long) c * BATCHES_PER_COMPANY + b;
						batchIds[b] = batchId;
						batchStmt.setLong(1, batchId);
						batchStmt.setLong(2, companyId);
						batchStmt.setInt(3, b + 1);
						batchStmt.addBatch();
					}

					for (int e = 0; e < EMPLOYEES_PER_COMPANY; e++) {
						long thisEmployeeId = employeeId++;

						employeeStmt.setLong(1, thisEmployeeId);
						employeeStmt.setLong(2, companyId);
						employeeStmt.setLong(3, branchId);
						employeeStmt.setString(4, "+20191" + thisEmployeeId);
						employeeStmt.addBatch();

						for (int a = 0; a < ATTENDANCE_PER_EMPLOYEE; a++) {
							long thisAttendanceId = attendanceId++;
							String checkIn = String.format("2025-%02d-%02d 09:00:00", (a % 12) + 1, (a % 27) + 1);
							attendanceStmt.setLong(1, thisAttendanceId);
							attendanceStmt.setLong(2, thisEmployeeId);
							attendanceStmt.setString(3, checkIn);
							attendanceStmt.setString(4, checkIn);
							attendanceStmt.setString(5, checkIn);
							attendanceStmt.addBatch();
						}

						for (int p = 0; p < PAYSLIPS_PER_EMPLOYEE; p++) {
							long thisPayslipId = payslipId++;
							payslipStmt.setLong(1, thisPayslipId);
							payslipStmt.setLong(2, batchIds[p]);
							payslipStmt.setLong(3, thisEmployeeId);
							payslipStmt.addBatch();
						}
					}

					companyStmt.executeBatch();
					branchStmt.executeBatch();
					batchStmt.executeBatch();
					employeeStmt.executeBatch();
					attendanceStmt.executeBatch();
					payslipStmt.executeBatch();
				}
			}
			connection.commit();
		}
	}

	private static List<Row> explain(String sql, long companyId) throws Exception {
		List<Row> rows = new ArrayList<>();
		try (Connection connection = connect();
				PreparedStatement st = connection.prepareStatement("EXPLAIN " + sql)) {
			st.setLong(1, companyId);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					rows.add(new Row(
							rs.getString("table"),
							rs.getString("type"),
							rs.getString("possible_keys"),
							rs.getString("key"),
							rs.getLong("rows"),
							rs.getString("Extra")));
				}
			}
		}
		return rows;
	}

	private record Row(String table, String type, String possibleKeys, String key, long rows, String extra) {
	}

	/**
	 * The exact shape {@link EmployeeDerivedTenantFilter#CONDITION}
	 * emits, against {@code attendance} -- named explicitly in the Item
	 * 12 specification's PR 12.2 merge gate.
	 */
	@Test
	void explainAttendanceUnderTheEmployeeDerivedFilterUsesIndexesNotAFullScan() throws Exception {
		List<Row> plan = explain(
				"SELECT a.id, a.employee_id FROM attendance a "
						+ "WHERE a.employee_id IN (SELECT e.id FROM employees e WHERE e.company_id = ?)",
				PROBE_COMPANY_ID);

		System.out.println("=== EXPLAIN: attendance under P-1b, company " + PROBE_COMPANY_ID + " ===");
		plan.forEach(r -> System.out.println(
				"table=" + r.table() + " type=" + r.type() + " possible_keys=" + r.possibleKeys()
						+ " key=" + r.key() + " rows=" + r.rows() + " Extra=" + r.extra()));

		assertThat(plan).isNotEmpty();

		Row attendanceRow = plan.stream().filter(r -> "a".equals(r.table())).findFirst()
				.orElseThrow(() -> new AssertionError("no plan row for the outer `attendance` alias: " + plan));
		assertThat(attendanceRow.type())
				.describedAs("outer attendance scan must not be a full table scan against 60,000 rows "
						+ "for a ~3.3%%-selective company -- full plan: %s", plan)
				.isNotEqualTo("ALL");
		assertThat(attendanceRow.key())
				.describedAs("outer attendance scan must use an index on employee_id -- full plan: %s", plan)
				.isNotNull();

		Row employeesRow = plan.stream().filter(r -> "e".equals(r.table()) || (r.table() != null && r.table().contains("employees"))).findFirst()
				.orElse(null);
		if (employeesRow != null) {
			assertThat(employeesRow.key())
					.describedAs("subquery scan of employees should use company_id's index when it appears "
							+ "as its own plan row (materialised/semi-join strategies may fold it in instead) "
							+ "-- full plan: %s", plan)
					.isNotNull();
		}
	}

	/** The other named table, {@code payslips}. */
	@Test
	void explainPayslipsUnderTheEmployeeDerivedFilterUsesIndexesNotAFullScan() throws Exception {
		List<Row> plan = explain(
				"SELECT p.id, p.employee_id FROM payslips p "
						+ "WHERE p.employee_id IN (SELECT e.id FROM employees e WHERE e.company_id = ?)",
				PROBE_COMPANY_ID);

		System.out.println("=== EXPLAIN: payslips under P-1b, company " + PROBE_COMPANY_ID + " ===");
		plan.forEach(r -> System.out.println(
				"table=" + r.table() + " type=" + r.type() + " possible_keys=" + r.possibleKeys()
						+ " key=" + r.key() + " rows=" + r.rows() + " Extra=" + r.extra()));

		assertThat(plan).isNotEmpty();

		Row payslipsRow = plan.stream().filter(r -> "p".equals(r.table())).findFirst()
				.orElseThrow(() -> new AssertionError("no plan row for the outer `payslips` alias: " + plan));
		assertThat(payslipsRow.type())
				.describedAs("outer payslips scan must not be a full table scan against 30,000 rows "
						+ "for a ~3.3%%-selective company -- full plan: %s", plan)
				.isNotEqualTo("ALL");
		assertThat(payslipsRow.key())
				.describedAs("outer payslips scan must use an index on employee_id -- full plan: %s", plan)
				.isNotNull();
	}

	/**
	 * Confirms the seeded volume is what it claims to be -- if this ever
	 * reads a suspiciously small number, the EXPLAIN evidence above is
	 * against a fixture that regressed to "a handful of rows" and proves
	 * nothing.
	 */
	@Test
	void theSeededVolumeIsActuallyRealisticNotAHandful() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			ResultSet employeesCount = st.executeQuery(
					"SELECT COUNT(*) FROM employees WHERE id >= " + EMPLOYEE_ID_BASE);
			employeesCount.next();
			assertThat(employeesCount.getLong(1)).isEqualTo((long) COMPANIES * EMPLOYEES_PER_COMPANY);

			ResultSet attendanceCount = st.executeQuery(
					"SELECT COUNT(*) FROM attendance WHERE id >= " + ATTENDANCE_ID_BASE);
			attendanceCount.next();
			assertThat(attendanceCount.getLong(1))
					.isEqualTo((long) COMPANIES * EMPLOYEES_PER_COMPANY * ATTENDANCE_PER_EMPLOYEE);

			ResultSet payslipsCount = st.executeQuery(
					"SELECT COUNT(*) FROM payslips WHERE id >= " + PAYSLIP_ID_BASE);
			payslipsCount.next();
			assertThat(payslipsCount.getLong(1))
					.isEqualTo((long) COMPANIES * EMPLOYEES_PER_COMPANY * PAYSLIPS_PER_EMPLOYEE);
		}
	}

}
