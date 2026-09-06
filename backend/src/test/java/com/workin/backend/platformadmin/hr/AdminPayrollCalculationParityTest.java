package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.workin.legacy.payroll.LegacyPayrollBatchService;
import com.workin.legacy.payroll.LegacyPayslipService;

/**
 * The page's {@code calculate} produces the same numbers as the calculation
 * path that already existed.
 *
 * <p>{@code payroll_calculate_batch()} is literally the same PHP function the
 * dashboard and {@code calculate.php} both call, so the port routes the page
 * through {@link LegacyPayrollBatchService#calculate} rather than growing a
 * second implementation. This is what proves that stayed true: one batch, one
 * set of inputs, calculated both ways, compared column for column across every
 * payslip.
 *
 * <p>Deliberately separate from {@link AdminPayrollTenantIsolationTest}. If
 * this class fails, payroll arithmetic changed; if that one fails, access
 * control did. Folding them together would make a red build ambiguous about
 * which of the two happened.
 */
class AdminPayrollCalculationParityTest extends AdminPayrollTestSupport {

	@Autowired
	private LegacyPayrollBatchService batchService;

	@Autowired
	private LegacyPayslipService payslipService;

	@Autowired
	private PayrollStore payrollStore;

	@Test
	void thePagesCalculateMatchesTheServiceColumnForColumn() {
		long batchId = seedCalculableBatch();

		// The existing path, first.
		int serviceCount = inRequestScope(() ->
				this.batchService.calculate(this.companyA, batchId, "raha").calculatedCount());
		List<Map<String, Object>> viaService = payslipsOf(batchId);
		assertThat(viaService).as("the fixture must actually produce payslips").isNotEmpty();

		// Same batch, same inputs, through the page.
		this.jdbc.update("DELETE FROM payslips WHERE batch_id = ?", batchId);
		submit("action", "calculate", "id", String.valueOf(batchId));
		List<Map<String, Object>> viaPage = payslipsOf(batchId);

		assertThat(viaPage).hasSize(serviceCount).hasSameSizeAs(viaService);
		for (int index = 0; index < viaService.size(); index++) {
			Map<String, Object> expected = viaService.get(index);
			Map<String, Object> actual = viaPage.get(index);
			for (String column : PAYSLIP_COLUMNS) {
				if ("id".equals(column)) {
					continue;
				}
				assertThat(actual.get(column))
						.as("payslip %d, column %s", index, column)
						.isEqualTo(expected.get(column));
			}
		}
	}

	@Test
	void thePagesCalculateRewritesTheBatchPeriodTheSameWay() {
		long batchId = seedCalculableBatch();

		inRequestScope(() -> this.batchService.calculate(this.companyA, batchId, "raha"));
		Map<String, Object> viaService = batchRow(batchId);

		this.jdbc.update("DELETE FROM payslips WHERE batch_id = ?", batchId);
		submit("action", "calculate", "id", String.valueOf(batchId));

		assertThat(batchRow(batchId))
				.as("period_from, period_to, month, year and status all match")
				.isEqualTo(viaService);
	}

	/**
	 * A finalized batch is skipped in silence.
	 *
	 * <p>{@code if ($run && $run['status'] !== PAY_FINALIZED)} — PHP falls
	 * through to the redirect with no flash. The service throws
	 * {@code batch_already_finalized} for the same case, so the port catches
	 * exactly that key and does nothing rather than surfacing an error the page
	 * has never shown.
	 */
	@Test
	void calculatingAFinalizedBatchDoesNothingAndSaysNothing() {
		long batchId = seedCalculableBatch();
		this.jdbc.update("UPDATE payroll_batches SET status = 'finalized' WHERE id = ?", batchId);

		var response = submit("action", "calculate", "id", String.valueOf(batchId));

		assertThat(response.getHeaders().getLocation())
				.as("no error, because PHP shows none either").asString().doesNotContain("error");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM payslips WHERE batch_id = " + batchId, Integer.class))
				.as("and nothing was calculated").isZero();
	}

	/**
	 * The detail table and the totals strip disagree, and must.
	 *
	 * <p>{@code payroll_enrich_payslip_row()} recomputes {@code net_salary},
	 * {@code total_entitlements}, {@code total_deductions}, {@code penalties_total}
	 * and the three day counts from live attendance and contract data, so the
	 * table shows those rather than what is stored. {@code payroll_batch_payslip_totals()}
	 * sums the stored columns and shows those. An {@code edit_detail} write
	 * therefore moves the strip and not the row.
	 *
	 * <p>Pinned because the obvious "cleanup" is to render the stored columns in
	 * both places, which would quietly change what the page reports.
	 */
	@Test
	void theTableIsRecomputedWhileTheStripSumsWhatIsStored() {
		salaryContract(this.employeeA, "6000.00", "2026-01-01");
		long batchId = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		long payslipId = payslip(batchId, this.employeeA);
		// A stored net that no recomputation of this fixture could arrive at.
		this.jdbc.update("UPDATE payslips SET net_salary = 12345.67 WHERE id = ?", payslipId);

		PayrollRecord.BatchTotals strip = this.payrollStore.batchTotals(batchId);
		assertThat(strip.netSalary())
				.as("the strip sums the stored column")
				.isEqualByComparingTo("12345.67");

		Map<String, Object> enriched = inRequestScope(() -> this.payslipService.enrichRows(
				this.payrollStore.paginatePayslips(batchId, "2026-03-31", 1, 10).data(),
				this.companyA, "present", "raha", "holiday").get(0));
		assertThat(new java.math.BigDecimal(String.valueOf(enriched.get("net_salary"))))
				.as("the row the table renders is recomputed, not the stored 12345.67")
				.isNotEqualByComparingTo("12345.67");
	}

	/**
	 * One employee with a contract and a month of attendance — enough for the
	 * calculation to produce a payslip with non-zero amounts, which is what
	 * makes a column-for-column comparison meaningful.
	 */
	private long seedCalculableBatch() {
		salaryContract(this.employeeA, "6000.00", "2026-01-01");
		for (int day = 2; day <= 20; day++) {
			String date = String.format("2026-03-%02d", day);
			this.jdbc.update("INSERT INTO attendance (employee_id, check_in, check_out, method)"
					+ " VALUES (?, ?, ?, 'app')",
					this.employeeA, date + " 09:00:00", date + " 17:00:00");
		}
		return batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
	}

	private List<Map<String, Object>> payslipsOf(long batchId) {
		return this.jdbc.queryForList(
				"SELECT * FROM payslips WHERE batch_id = ? ORDER BY employee_id", batchId);
	}

}
