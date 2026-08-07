package com.workin.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

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
