package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * The two aggregate expressions in {@code payroll_batches/stats.php}, against
 * the real schema.
 *
 * <p>Both of these shipped wrong and the suite stayed green, because every
 * existing fixture happens to sit where the correct and incorrect expressions
 * agree: no payslip stored a legitimate zero total, and no payslip carried an
 * allowance outside the housing column. This test seeds exactly those two
 * shapes.
 */
class LegacyPayrollBatchStatsParityTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 24801L;
	private static final long BRANCH = 24811L;
	private static final long ABSENT_EMPLOYEE = 248011L;
	private static final long PAID_EMPLOYEE = 248012L;
	private static final long BATCH = 248021L;

	private static LegacyPayrollBatchStore store;

	@BeforeAll
	static void seed() throws Exception {
		seedAsLegacyWould(
				"DELETE FROM payslips WHERE batch_id = " + BATCH,
				"DELETE FROM payroll_batches WHERE id = " + BATCH,
				"DELETE FROM employees WHERE id IN (" + ABSENT_EMPLOYEE + ", " + PAID_EMPLOYEE + ")",
				"DELETE FROM branches WHERE id = " + BRANCH,
				"DELETE FROM companies WHERE id = " + COMPANY,
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES ("
						+ COMPANY + ", 'Stats Co', '+201000024801', 'active', '2025-01-01 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
						+ BRANCH + ", " + COMPANY + ", 'HQ', 1, '2025-01-01 09:00:00')",
				employee(ABSENT_EMPLOYEE, "8001", "Absent"),
				employee(PAID_EMPLOYEE, "8002", "Paid"),
				"INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to, status)"
						+ " VALUES (" + BATCH + ", " + COMPANY + ", 6, 2025, '2025-06-01', '2025-06-30', 'draft')",

				// Absent the whole period. payroll_compute_employee_payslip()
				// stores total_entitlements = 0 because salary_by_attendance is
				// 0, while basic_salary and the allowances stay populated --
				// the exact row NULLIF(col, 0) mistook for "unset".
				payslip(ABSENT_EMPLOYEE, "0.00", "10000.00", "1000.00", "500.00", "300.00", "100.00", "200.00",
						"500.00"),

				// Ordinary paid row, allowances only in the housing column, so
				// the two allowance expressions agree on this one.
				payslip(PAID_EMPLOYEE, "3000.00", "3000.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00"));

		store = new LegacyPayrollBatchStore(new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()));
	}

	/**
	 * A stored zero is a real figure, not a missing one.
	 *
	 * <p>{@code total_entitlements} is {@code NOT NULL DEFAULT 0.00}, so PHP's
	 * {@code COALESCE(col, sum)} always returns the stored column and its sum
	 * branch is unreachable. The port had {@code COALESCE(NULLIF(col, 0), sum)},
	 * which turned every legitimately-zero payslip into the employee's full
	 * gross -- reporting an absent employee as fully paid.
	 */
	@Test
	void anAbsentEmployeesStoredZeroIsNotReplacedByTheirGross() {
		Map<String, Object> stats = store.statsForBatch(BATCH);

		assertThat(new BigDecimal(String.valueOf(stats.get("total_entitlements"))))
				.as("3000 from the paid row and 0 from the absent one -- not 15100")
				.isEqualByComparingTo("3000.00");
		assertThat(new BigDecimal(String.valueOf(stats.get("total_net_salary"))))
				.as("net follows entitlements")
				.isEqualByComparingTo("3000.00");
	}

	/**
	 * The deductions half of the same rule, which the entitlements case does not
	 * reach.
	 *
	 * <p>Both stored totals are 0 here, so with every deduction component also 0
	 * the two expressions agree and the old `NULLIF` passes — verified by
	 * reinstating it. The absent employee therefore carries
	 * {@code other_deductions = 500} beside a stored {@code total_deductions = 0},
	 * which is D-190's real scenario: {@code edit_detail} writes
	 * {@code other_deductions} and never {@code total_deductions}.
	 *
	 * <p>PHP reports the stored 0. The old expression read that zero as unset and
	 * fell through to the component sum, inventing a 500 deduction nobody
	 * recorded.
	 */
	@Test
	void aStoredZeroDeductionIsNotReplacedByTheSumOfItsComponents() {
		Map<String, Object> stats = store.statsForBatch(BATCH);

		assertThat(new BigDecimal(String.valueOf(stats.get("total_deductions"))))
				.as("both rows store 0, despite one carrying a 500 component -- not 500")
				.isEqualByComparingTo("0.00");
		assertThat(new BigDecimal(String.valueOf(stats.get("total_other_deductions"))))
				.as("the component itself is still reported, so the 500 is not simply absent")
				.isEqualByComparingTo("500.00");
	}

	/**
	 * {@code sql_payslip_total_allowances()} sums all five allowance columns.
	 * The port summed only {@code allowances}, the housing one, so every other
	 * allowance an employee receives was missing from the batch total.
	 */
	@Test
	void everyAllowanceColumnCountsTowardTheBatchTotal() {
		Map<String, Object> stats = store.statsForBatch(BATCH);

		assertThat(new BigDecimal(String.valueOf(stats.get("total_allowances"))))
				.as("transport 500 + food 300 + risk 100 + incentives 200 + housing 1000 -- not 1000")
				.isEqualByComparingTo("2100.00");
	}

	private static String employee(long id, String code, String firstName) {
		return "INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
				+ " phone, role, is_active, join_request_status, created_at) VALUES ("
				+ id + ", " + COMPANY + ", " + BRANCH + ", '" + code + "', '" + firstName + "', 'Person',"
				+ " '+2011000" + id + "', 'employee', 1, 'accepted', '2025-01-01 09:00:00')";
	}

	/**
	 * {@code total_deductions} is always the stored 0 -- that is the value under
	 * test. {@code otherDeductions} is a <em>component</em>, and giving one row a
	 * non-zero component beside that stored zero is what separates the two
	 * expressions: the correct one reports the stored 0, the old one treats it as
	 * unset and falls through to the component sum.
	 */
	private static String payslip(long employeeId, String totalEntitlements, String basic,
			String housing, String transport, String food, String risk, String incentives,
			String otherDeductions) {
		return "INSERT INTO payslips (batch_id, employee_id, basic_salary, allowances,"
				+ " transport_allowance, food_allowance, risk_allowance, incentives,"
				+ " other_deductions, total_entitlements, total_deductions) VALUES ("
				+ BATCH + ", " + employeeId + ", " + basic + ", " + housing + ", " + transport + ", "
				+ food + ", " + risk + ", " + incentives + ", " + otherDeductions + ", "
				+ totalEntitlements + ", 0.00)";
	}
}
