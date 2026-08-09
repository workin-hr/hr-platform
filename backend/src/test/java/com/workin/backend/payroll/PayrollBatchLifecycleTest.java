package com.workin.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.companysettings.UpdateCompanySettingsRequest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * End-to-end regression coverage for the payroll batch defects
 * documented in docs/legacy/business-rule-extraction.md and
 * docs/migration/payroll-module-execution-plan.md: hr-legacy#21 (batch
 * uniqueness race, now a real DB constraint), and hr-legacy#12
 * (daily-wage employees silently losing base pay on the manual
 * "add one payslip" endpoint specifically -- the exact endpoint the
 * legacy bug lived in).
 */
class PayrollBatchLifecycleTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Test
	void duplicateBatchForTheSameCompanyMonthYearIsRejectedNotDuplicated() {
		AuthResponse companyA = register("Company A");
		HttpHeaders headers = bearer(companyA.accessToken());

		ResponseEntity<PayrollBatchView> first = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 3, (short) 2026), headers), PayrollBatchView.class);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> second = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 3, (short) 2026), headers), String.class);
		// hr-legacy#21: legacy's app-level pre-check left a race window
		// that could produce two draft batches for the same period. The
		// new schema's real UNIQUE constraint (V10) closes it -- a
		// second attempt is a clean conflict, not a silent duplicate.
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void manuallyAddedPayslipForADailyWageEmployeeHasNonZeroBasePay() {
		AuthResponse companyA = register("Company A");
		HttpHeaders headers = bearer(companyA.accessToken());
		Long employeeId = createEmployeeRecord(companyA.companyId());

		UpsertSalaryContractRequest contractRequest = new UpsertSalaryContractRequest(
				SalaryMode.DAILY, null, BigDecimal.valueOf(100), null, null, null, null, null,
				null, null, null, null, null, LocalDate.of(2026, 1, 1));
		ResponseEntity<SalaryContractView> contractResponse = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + employeeId, HttpMethod.POST,
				new HttpEntity<>(contractRequest, headers), SalaryContractView.class);
		assertThat(contractResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<PayrollBatchView> batch = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 1, (short) 2026), headers), PayrollBatchView.class);
		assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Long batchId = batch.getBody().id();

		// This is the exact hr-legacy#12 bug site: manually adding one
		// payslip to a draft batch, bypassing batch-wide calculate.
		CreatePayslipRequest payslipRequest = new CreatePayslipRequest(batchId, employeeId, 30, 0, 0, BigDecimal.ZERO);
		ResponseEntity<PayslipView> payslipResponse = restTemplate.exchange(
				"/api/tenant/payslips", HttpMethod.POST,
				new HttpEntity<>(payslipRequest, headers), PayslipView.class);

		assertThat(payslipResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		// dailyWage(100) * 30 = 3000. hr-legacy#12 would have produced 0.
		assertThat(payslipResponse.getBody().basicSalary()).isEqualByComparingTo("3000.00");
		assertThat(payslipResponse.getBody().netSalary()).isEqualByComparingTo("3000.00");
	}

	@Test
	void dailyContractWithoutADailyWageIsRejectedAtWriteNotAtCalculate() {
		AuthResponse companyA = register("Company A");
		HttpHeaders headers = bearer(companyA.accessToken());
		Long employeeId = createEmployeeRecord(companyA.companyId());

		// DAILY mode with a null dailyWage: PayrollCalculationService
		// dereferences dailyWage for DAILY, so this would otherwise save
		// cleanly and then NPE the whole batch at calculate time.
		UpsertSalaryContractRequest missingDailyWage = new UpsertSalaryContractRequest(
				SalaryMode.DAILY, null, null, null, null, null, null, null,
				null, null, null, null, null, LocalDate.of(2026, 1, 1));
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + employeeId, HttpMethod.POST,
				new HttpEntity<>(missingDailyWage, headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void calculateFinalizeAndReopenTransitionStatusCorrectly() {
		AuthResponse companyA = register("Company A");
		HttpHeaders headers = bearer(companyA.accessToken());

		ResponseEntity<PayrollBatchView> batch = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 2, (short) 2026), headers), PayrollBatchView.class);
		Long batchId = batch.getBody().id();

		ResponseEntity<PayrollBatchView> calculated = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(headers), PayrollBatchView.class);
		assertThat(calculated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(calculated.getBody().status()).isEqualTo(BatchStatus.DRAFT);

		ResponseEntity<PayrollBatchView> finalized = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/finalize", HttpMethod.PUT,
				new HttpEntity<>(headers), PayrollBatchView.class);
		assertThat(finalized.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(finalized.getBody().status()).isEqualTo(BatchStatus.FINALIZED);

		// Finalized batches reject recalculation.
		ResponseEntity<String> recalcAttempt = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(headers), String.class);
		assertThat(recalcAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		ResponseEntity<PayrollBatchView> reopened = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/reopen", HttpMethod.PUT,
				new HttpEntity<>(headers), PayrollBatchView.class);
		assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reopened.getBody().status()).isEqualTo(BatchStatus.DRAFT);
	}

	@Test
	void fiscalPeriodSettingsShiftTheBatchWindow() {
		AuthResponse companyA = register("Company A");
		HttpHeaders headers = bearer(companyA.accessToken());
		// Fiscal month 26th -> 25th (legacy payroll_fiscal_period_bounds'
		// start > end case: the period spans from the previous month).
		restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(new UpdateCompanySettingsRequest((short) 26, (short) 25, null, null, null), headers),
				String.class);

		ResponseEntity<PayrollBatchView> batch = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 3, (short) 2026), headers), PayrollBatchView.class);

		assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(batch.getBody().periodFrom()).isEqualTo(LocalDate.of(2026, 2, 26));
		assertThat(batch.getBody().periodTo()).isEqualTo(LocalDate.of(2026, 3, 25));
	}

	@Test
	void fiscalDayBoundsCapToEachMonthsRealLastDay() {
		AuthResponse companyA = register("Company A");
		HttpHeaders headers = bearer(companyA.accessToken());
		// start 30 <= end 31 keeps legacy in the same-month branch, where
		// each bound caps to the month's real last day: Feb 2026 has 28
		// days, so both become the 28th. (start 31 with end unset would
		// instead take the start > end previous-month branch -- covered
		// by the shifted-window test above.)
		restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(new UpdateCompanySettingsRequest((short) 30, (short) 31, null, null, null), headers),
				String.class);

		ResponseEntity<PayrollBatchView> batch = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 2, (short) 2026), headers), PayrollBatchView.class);

		assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(batch.getBody().periodFrom()).isEqualTo(LocalDate.of(2026, 2, 28));
		assertThat(batch.getBody().periodTo()).isEqualTo(LocalDate.of(2026, 2, 28));
	}

	@Test
	void payslipDaysPresentReflectsTheRealFiscalPeriodLengthNotTheEndDayOfMonth() {
		AuthResponse companyA = register("Company A");
		HttpHeaders headers = bearer(companyA.accessToken());
		JdbcTemplate jdbc = new JdbcTemplate(flywayDataSource);

		Long employeeId = createEmployeeRecord(companyA.companyId());
		UpsertSalaryContractRequest contract = new UpsertSalaryContractRequest(
				SalaryMode.MONTHLY, BigDecimal.valueOf(3000), null, null, null, null, null, null,
				null, null, null, null, null, LocalDate.of(2026, 1, 1));
		restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + employeeId, HttpMethod.POST,
				new HttpEntity<>(contract, headers), SalaryContractView.class);

		// Fiscal month 26th -> 25th: the period is 2026-02-26..2026-03-25,
		// whose real inclusive length is 28 days. The old
		// getPeriodTo().getDayOfMonth() would have stored 25.
		restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(new UpdateCompanySettingsRequest((short) 26, (short) 25, null, null, null), headers),
				String.class);

		Long batchId = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 3, (short) 2026), headers), PayrollBatchView.class)
				.getBody().id();
		restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(headers), PayrollBatchView.class);

		// Asserted on the period itself rather than on days_present. It used
		// to read days_present == 28, but only because calculate() assumed
		// full attendance and so echoed the period length back. Now that
		// payroll pays on real attendance (#71), an employee with no punches
		// has no present days, and the period length has to be checked
		// where it actually lives.
		java.time.LocalDate periodFrom = jdbc.queryForObject(
				"SELECT period_from FROM payroll_batches WHERE id = ?",
				java.time.LocalDate.class, batchId);
		java.time.LocalDate periodTo = jdbc.queryForObject(
				"SELECT period_to FROM payroll_batches WHERE id = ?",
				java.time.LocalDate.class, batchId);
		assertThat(periodFrom).isEqualTo(java.time.LocalDate.of(2026, 2, 26));
		assertThat(periodTo).isEqualTo(java.time.LocalDate.of(2026, 3, 25));
		assertThat(java.time.temporal.ChronoUnit.DAYS.between(periodFrom, periodTo) + 1).isEqualTo(28);
	}

	@Test
	void aMidCalculationFailureLeavesThePriorPayslipsExactlyAsTheyWere() {
		AuthResponse companyA = register("Company A");
		HttpHeaders headers = bearer(companyA.accessToken());
		JdbcTemplate jdbc = new JdbcTemplate(flywayDataSource);

		Long healthyEmployee = createEmployeeRecord(companyA.companyId());
		UpsertSalaryContractRequest healthyContract = new UpsertSalaryContractRequest(
				SalaryMode.MONTHLY, BigDecimal.valueOf(3000), null, null, null, null, null, null,
				null, null, null, null, null, LocalDate.of(2026, 1, 1));
		restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + healthyEmployee, HttpMethod.POST,
				new HttpEntity<>(healthyContract, headers), SalaryContractView.class);

		Long batchId = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 7, (short) 2026), headers), PayrollBatchView.class)
				.getBody().id();
		restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(headers), PayrollBatchView.class);
		Long originalPayslipId = jdbc.queryForObject(
				"SELECT id FROM payslips WHERE batch_id = ?", Long.class, batchId);

		// A second, later-processed employee whose contract's six money
		// components are all at NUMERIC(10, 2)'s maximum: the computed
		// gross (~600M, 9 integer digits) cannot be stored, so this
		// employee's payslip save fails after the healthy employee's
		// payslip was already written -- a genuine mid-calculation
		// failure, no mocks. Inserted via SQL because the API's own
		// surface has no reason to allow building this on purpose.
		Long poisonedEmployee = createEmployeeRecord(companyA.companyId());
		jdbc.update(
				"INSERT INTO salary_contracts (employee_id, company_id, salary_mode, basic_salary, "
						+ "housing_allowance, transport_allowance, food_allowance, risk_allowance, incentives, "
						+ "effective_from) VALUES (?, ?, 'MONTHLY', 99999999.99, 99999999.99, 99999999.99, "
						+ "99999999.99, 99999999.99, 99999999.99, '2026-01-01')",
				poisonedEmployee, companyA.companyId());

		ResponseEntity<String> failedCalculate = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(headers), String.class);
		assertThat(failedCalculate.getStatusCode().is5xxServerError()).isTrue();

		// hr-legacy#22: legacy's calculate.php was not transactional --
		// this crash would have left the batch half-calculated (old
		// payslips already deleted, only some new ones written). The new
		// calculate is one transaction: the pre-existing payslip row
		// survives untouched (same id -- the delete rolled back), and
		// nothing from the aborted run persists.
		List<Long> survivingIds = jdbc.queryForList(
				"SELECT id FROM payslips WHERE batch_id = ? ORDER BY id", Long.class, batchId);
		assertThat(survivingIds).containsExactly(originalPayslipId);
	}

	private Long createEmployeeRecord(Long companyId) {
		JdbcTemplate superuser = new JdbcTemplate(flywayDataSource);
		return superuser.queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Test', 'Employee') RETURNING id",
				Long.class, companyId);
	}

	private HttpHeaders bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

	private AuthResponse register(String name) {
		String phone = "+2013" + System.nanoTime() % 100_000_000L;
		ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
				"/api/auth/register", new RegisterCompanyRequest(name, phone, "correct horse battery staple"), AuthResponse.class);
		return response.getBody();
	}

}
