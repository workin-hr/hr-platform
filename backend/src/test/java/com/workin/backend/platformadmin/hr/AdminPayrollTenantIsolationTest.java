package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * R-064: the payroll page's five id-driven actions, refused across companies.
 *
 * <p>This is the port's one deliberate divergence from its source on this page,
 * and it is authorization only. In PHP every action below succeeds against
 * another company's id — {@code dbUpdate}, {@code dbDelete} and {@code dbFind}
 * are each a plain {@code WHERE id = ?}. {@code 505004f} closed the identical
 * hole on the attendance page and did not reach this one.
 *
 * <p>Every refusal is asserted twice: the request is rejected, <b>and</b> the
 * victim's row is unchanged column for column afterwards. A guard that refuses
 * the response while half-applying the write would pass the first assertion and
 * fail the second, which is the failure worth catching.
 *
 * <p>Kept apart from {@link AdminPayrollCalculationParityTest} on purpose: when
 * this class goes red, access control broke; when that one does, payroll
 * arithmetic did.
 */
class AdminPayrollTenantIsolationTest extends AdminPayrollTestSupport {

	@Test
	void calculatingAnotherCompanysBatchIsRefused() {
		long victim = batch(this.companyB, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		Map<String, Object> before = batchRow(victim);

		ResponseEntity<String> response = submitFilteredTo(this.companyA,
				"action", "calculate", "id", String.valueOf(victim));

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(batchRow(victim)).isEqualTo(before);
		assertThat(this.jdbc.queryForObject(
				"SELECT COUNT(*) FROM payslips WHERE batch_id = " + victim, Integer.class))
				.as("no payslip was written into another company's batch").isZero();
	}

	@Test
	void finalizingAnotherCompanysBatchIsRefused() {
		long victim = batch(this.companyB, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		Map<String, Object> before = batchRow(victim);

		ResponseEntity<String> response = submitFilteredTo(this.companyA,
				"action", "finalize", "id", String.valueOf(victim));

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(batchRow(victim))
				.as("every column of the foreign batch survives the refused finalize")
				.isEqualTo(before);
	}

	@Test
	void reopeningAnotherCompanysBatchIsRefused() {
		long victim = batch(this.companyB, 3, 2026, "2026-03-01", "2026-03-31", "finalized");
		Map<String, Object> before = batchRow(victim);

		ResponseEntity<String> response = submitFilteredTo(this.companyA,
				"action", "reopen", "id", String.valueOf(victim));

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(batchRow(victim)).isEqualTo(before);
	}

	@Test
	void deletingAnotherCompanysBatchIsRefused() {
		long victim = batch(this.companyB, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		long victimPayslip = payslip(victim, this.employeeB);
		Map<String, Object> batchBefore = batchRow(victim);
		Map<String, Object> payslipBefore = payslipRow(victimPayslip);

		ResponseEntity<String> response = submitFilteredTo(this.companyA,
				"action", "delete_run", "id", String.valueOf(victim));

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(batchRow(victim)).as("the batch is still there, unchanged").isEqualTo(batchBefore);
		assertThat(payslipRow(victimPayslip))
				.as("and so is the payslip the cascade would have taken with it")
				.isEqualTo(payslipBefore);
	}

	@Test
	void editingAnotherCompanysPayslipIsRefused() {
		// The worst of the five: this one rewrites what somebody is paid.
		long victim = batch(this.companyB, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		long victimPayslip = payslip(victim, this.employeeB);
		Map<String, Object> before = payslipRow(victimPayslip);

		ResponseEntity<String> response = submitFilteredTo(this.companyA,
				"action", "edit_detail", "id", String.valueOf(victimPayslip),
				"days_present", "0", "days_absent", "30", "days_leave", "0",
				"overtime_hours", "0", "basic_salary", "1", "allowances", "0",
				"overtime_pay", "0", "penalties_total", "9999", "advance_deduction", "0",
				"advances_deduction", "0", "other_deductions", "0", "net_salary", "1");

		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
		assertThat(payslipRow(victimPayslip))
				.as("all twenty-five columns of the foreign payslip are untouched")
				.isEqualTo(before);
	}

	@Test
	void anotherCompanysPayslipDoesNotRenderIntoTheEditForm() {
		// Refusing the write while still rendering the victim's salary into the
		// form would close nothing worth closing.
		long victim = batch(this.companyB, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		long victimPayslip = payslip(victim, this.employeeB);

		get(PATH + "?company_id=" + this.companyA, this.cookie);
		String html = body(PATH + "?company_id=" + this.companyA
				+ "&action=edit_detail&id=" + victimPayslip);

		assertThat(html).doesNotContain("Basma");
	}

	@Test
	void anUnknownBatchIsRefusedRatherThanSilentlyDoingNothing() {
		ResponseEntity<String> response = submit("action", "finalize", "id", "987654");
		assertThat(response.getHeaders().getLocation()).asString().contains("error=error_db");
	}

	/**
	 * R-044's deliberate reach, unchanged.
	 *
	 * <p>An unfiltered platform administrator is meant to act across companies;
	 * the guard only binds a session that has declared a company, whether by
	 * being scoped to one or by filtering to one. Without this, the divergence
	 * above would have quietly become a second, unrequested policy change.
	 */
	@Test
	void anUnfilteredAdministratorStillReachesEveryCompany() {
		long alpha = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");
		long beta = batch(this.companyB, 3, 2026, "2026-03-01", "2026-03-31", "draft");

		submit("action", "finalize", "id", String.valueOf(alpha));
		submit("action", "finalize", "id", String.valueOf(beta));

		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM payroll_batches WHERE id = " + alpha, String.class))
				.isEqualTo("finalized");
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM payroll_batches WHERE id = " + beta, String.class))
				.as("R-044: an administrator with no filter is not confined")
				.isEqualTo("finalized");
	}

	/**
	 * The control for every refusal above.
	 *
	 * <p>Same filter, same action, same shape — the row's owner is the only
	 * variable. Without it a refusal could be coming from the filter itself
	 * rather than from the ownership check.
	 */
	@Test
	void theSameRequestUnderTheSameFilterSucceedsOnTheSessionsOwnBatch() {
		long own = batch(this.companyA, 3, 2026, "2026-03-01", "2026-03-31", "draft");

		ResponseEntity<String> response = submitFilteredTo(this.companyA,
				"action", "finalize", "id", String.valueOf(own));

		assertThat(response.getHeaders().getLocation()).asString().doesNotContain("error");
		assertThat(this.jdbc.queryForObject(
				"SELECT status FROM payroll_batches WHERE id = " + own, String.class))
				.isEqualTo("finalized");
	}

}
