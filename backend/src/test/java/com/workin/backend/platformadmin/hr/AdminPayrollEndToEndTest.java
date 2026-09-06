package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * What {@code /admin/payroll} does for the operator who is allowed to use it.
 *
 * <p>Access control is {@link AdminPayrollTenantIsolationTest}'s and the
 * calculation's numbers are {@link AdminPayrollCalculationParityTest}'s. A
 * failure here means the page's own behaviour changed, not that a guard or an
 * amount did.
 */
class AdminPayrollEndToEndTest extends AdminPayrollTestSupport {

	@Test
	void theBatchListRenders() {
		batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		assertThat(body(PATH)).contains("Alpha Co").contains("2026");
	}

	@Test
	void creatingARunWritesADraftWithItsFiscalBounds() {
		submit("action", "create_run", "company_id", String.valueOf(this.companyA),
				"month", "3", "year", "2026");

		Map<String, Object> row = this.jdbc.queryForMap(
				"SELECT company_id, month, year, period_from, period_to, status FROM payroll_batches");
		assertThat(row.get("company_id").toString()).isEqualTo(String.valueOf(this.companyA));
		assertThat(row.get("month").toString()).isEqualTo("3");
		assertThat(row.get("status")).as("PAY_DRAFT").isEqualTo("draft");
		assertThat(row.get("period_from").toString()).isEqualTo("2026-03-01");
		assertThat(row.get("period_to").toString()).isEqualTo("2026-03-31");
	}

	@Test
	void aSecondRunForTheSameMonthIsRefused() {
		batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");

		ResponseEntity<String> response = submit("action", "create_run",
				"company_id", String.valueOf(this.companyA), "month", "3", "year", "2026");

		assertThat(response.getHeaders().getLocation()).asString().contains("payroll_batch_exists");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM payroll_batches", Integer.class)).isEqualTo(1);
	}

	@Test
	void creatingARunForACompanyThatDoesNotExistWritesNothing() {
		// `if ($co)` -- PHP writes nothing and flashes nothing. Without the same
		// guard the insert would fail on payroll_batches' foreign key and the
		// operator would get a 500 where legacy gives them a quiet redirect.
		ResponseEntity<String> response = submit("action", "create_run",
				"company_id", "987654", "month", "3", "year", "2026");

		assertThat(response.getHeaders().getLocation()).asString().doesNotContain("error");
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM payroll_batches", Integer.class)).isZero();
	}

	@Test
	void theBatchDetailRendersItsPayslipsAndTotals() {
		long batchId = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		payslip(batchId, this.employeeA);

		String html = body(PATH + "?run_id=" + batchId);
		// The employee and the stored net, the latter from the totals strip --
		// the detail row shows the recomputed figure instead, which
		// AdminPayrollCalculationParityTest pins separately.
		assertThat(html).contains("Aya").contains("5180");
	}

	@Test
	void finalizingFlipsTheStatusAndChangesNothingElse() {
		long batchId = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		long payslipId = payslip(batchId, this.employeeA);
		Map<String, Object> batchBefore = batchRow(batchId);
		Map<String, Object> payslipBefore = payslipRow(payslipId);

		submit("action", "finalize", "id", String.valueOf(batchId));

		Map<String, Object> after = batchRow(batchId);
		assertThat(after.get("status")).isEqualTo("finalized");
		assertThat(after).as("the dashboard's finalize writes the status column and nothing else")
				.containsAllEntriesOf(withoutStatus(batchBefore));
		assertThat(payslipRow(payslipId)).as("no payslip is touched").isEqualTo(payslipBefore);
	}

	@Test
	void finalizingDoesNotApplyTheAdvanceAndPenaltySideEffectsTheApiApplies() {
		// The dashboard's finalize is `dbUpdate(['status' => PAY_FINALIZED])`
		// and nothing more; the API's finalize.php deducts approved advances
		// and marks penalties applied. Routing this page through
		// LegacyPayrollBatchService.finalize() would have started doing both.
		// The divergence between the two PHP paths is recorded as R-066 and
		// this fixture is what stops the port quietly resolving it.
		long batchId = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		payslip(batchId, this.employeeA);
		long advanceId = advance(this.employeeA, "1000", "1000");
		long penaltyId = penalty(this.employeeA, "2026-03-10");

		submit("action", "finalize", "id", String.valueOf(batchId));

		assertThat(this.jdbc.queryForObject(
				"SELECT remaining FROM advances WHERE id = " + advanceId, BigDecimal.class))
				.as("the advance is not deducted").isEqualByComparingTo("1000");
		assertThat(this.jdbc.queryForObject(
				"SELECT applied_to_payroll FROM penalties WHERE id = " + penaltyId, Integer.class))
				.as("the penalty is not marked applied").isZero();
	}

	@Test
	void reopeningPutsItBackToDraft() {
		long batchId = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "finalized");

		submit("action", "reopen", "id", String.valueOf(batchId));

		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM payroll_batches WHERE id = " + batchId, String.class))
				.isEqualTo("draft");
	}

	@Test
	void deletingARunTakesItsPayslipsWithIt() {
		long batchId = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		payslip(batchId, this.employeeA);
		long keptBatch = batch(this.companyA, 4, 2026, "2026-04-01", "2026-04-30", "draft");
		long keptPayslip = payslip(keptBatch, this.employeeA);

		submit("action", "delete_run", "id", String.valueOf(batchId));

		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM payroll_batches WHERE id = " + batchId, Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM payslips WHERE batch_id = " + batchId, Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM payslips WHERE id = " + keptPayslip, Integer.class))
				.as("the other batch is untouched").isEqualTo(1);
	}

	/**
	 * The one that matters most on this page.
	 *
	 * <p>{@code edit_detail} posts twelve fields into a twenty-five column
	 * table. This asserts all twenty-five: the twelve hold exactly what was
	 * posted, and the thirteen the form does not carry still hold what the
	 * calculation put there. The second half is the point — it pins the partial
	 * write, so a future change that starts recomputing {@code gross_salary},
	 * {@code total_entitlements} or {@code total_deductions} on edit fails here
	 * instead of silently diverging from PHP.
	 */
	@Test
	void editingAPayslipWritesTwelveFieldsExactlyAndLeavesTheOtherThirteenAlone() {
		long batchId = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		long payslipId = payslip(batchId, this.employeeA);
		Map<String, Object> before = payslipRow(payslipId);

		// Representative non-default values, all different from the fixture's
		// and from each other, so a field written from the wrong parameter is
		// visible rather than accidentally right.
		// days_* are tinyint and overtime_hours is decimal(5,1), so the values
		// are chosen to survive their own column types exactly -- a fractional
		// day would be rounded by MariaDB and the assertion would be testing
		// the column, not the write.
		Map<String, String> posted = new java.util.LinkedHashMap<>();
		posted.put("days_present", "18");
		posted.put("days_absent", "3");
		posted.put("days_leave", "2");
		posted.put("overtime_hours", "9.5");
		posted.put("basic_salary", "7431.75");
		posted.put("allowances", "812.40");
		posted.put("overtime_pay", "266.35");
		posted.put("penalties_total", "133.20");
		posted.put("advance_deduction", "455.60");
		posted.put("advances_deduction", "312.10");
		posted.put("other_deductions", "77.90");
		posted.put("net_salary", "7531.70");

		String[] fields = new String[4 + posted.size() * 2];
		fields[0] = "action";
		fields[1] = "edit_detail";
		fields[2] = "id";
		fields[3] = String.valueOf(payslipId);
		int index = 4;
		for (Map.Entry<String, String> entry : posted.entrySet()) {
			fields[index++] = entry.getKey();
			fields[index++] = entry.getValue();
		}
		ResponseEntity<String> response = submit(fields);
		assertThat(response.getHeaders().getLocation())
				.as("edit_detail redirects back to its batch, not to the list")
				.asString().contains("run_id=" + batchId);

		Map<String, Object> after = payslipRow(payslipId);
		for (Map.Entry<String, String> entry : posted.entrySet()) {
			// Read back through the column's own JDBC type -- tinyint comes back
			// an Integer, decimal a BigDecimal -- and compare numerically.
			assertThat(new BigDecimal(String.valueOf(after.get(entry.getKey()))))
					.as("%s was posted and must be stored exactly", entry.getKey())
					.isEqualByComparingTo(new BigDecimal(entry.getValue()));
		}
		for (String column : PAYSLIP_COLUMNS) {
			if (WRITABLE_COLUMNS.contains(column)) {
				continue;
			}
			assertThat(after.get(column))
					.as("%s is not on the form and must survive the edit untouched", column)
					.isEqualTo(before.get(column));
		}
		assertThat(after.get("net_salary")).as("the edited net no longer reconciles with the"
				+ " untouched totals -- legacy's behaviour, asserted so it is not 'fixed' by accident")
				.isNotEqualTo(after.get("total_entitlements"));
	}

	private static Map<String, Object> withoutStatus(Map<String, Object> row) {
		Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
		copy.remove("status");
		return copy;
	}

	private long advance(long employeeId, String amount, String remaining) {
		this.jdbc.update("INSERT INTO advances (employee_id, amount, remaining, status,"
				+ " request_date, created_at) VALUES (?, ?, ?, 'approved', '2026-03-01', NOW())",
				employeeId, new BigDecimal(amount), new BigDecimal(remaining));
		return this.jdbc.queryForObject("SELECT MAX(id) FROM advances", Long.class);
	}

	private long penalty(long employeeId, String date) {
		this.jdbc.update("INSERT INTO penalties (employee_id, penalty_type, penalty_days,"
				+ " penalty_date, applied_to_payroll, created_at)"
				+ " VALUES (?, 'late', 1.0, ?, 0, NOW())", employeeId, date);
		return this.jdbc.queryForObject("SELECT MAX(id) FROM penalties", Long.class);
	}

}
