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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.LoginRequest;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * F-18 negatives for the payroll group's three surfaces
 * (salary-contracts, payroll-batches, payslips), modeled on
 * hr-legacy#5/#6's operation shapes and copying the
 * EmployeeModuleFlowTest/AdvanceModuleFlowTest/PenaltyModuleFlowTest
 * template: every cross-tenant read and mutation is a 404
 * indistinguishable from nonexistence, cross-tenant rows never appear
 * in lists, and -- specific to this module's batch state machine --
 * a foreign batch's DRAFT/FINALIZED state never leaks through a 409
 * where an in-tenant caller would receive one.
 */
class PayrollModuleFlowTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(flywayDataSource);
	}

	private AuthResponse registerCompanyAdmin() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Payroll Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Pay', 'Emp') RETURNING id",
				Long.class, companyId);
	}

	private record HrFixture(String accessToken, Long membershipId, Long companyId) {
	}

	private HrFixture loginHrMember(Long companyId) {
		JdbcTemplate jdbc = jdbc();
		String phone = uniquePhone();
		String password = "correct horse battery staple";
		Long identityId = jdbc.queryForObject(
				"INSERT INTO identities (phone, password_hash) VALUES (?, ?) RETURNING id",
				Long.class, phone, passwordEncoder.encode(password));
		Long membershipId = jdbc.queryForObject(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
				Long.class, identityId, companyId);
		jdbc.update(
				"INSERT INTO membership_roles (membership_id, company_id, role) VALUES (?, ?, 'HR')",
				membershipId, companyId);
		AuthResponse login = restTemplate.postForEntity(
				"/api/auth/login", new LoginRequest(phone, password), AuthResponse.class).getBody();
		return new HrFixture(login.accessToken(), membershipId, companyId);
	}

	private void allowPermission(HrFixture hr, String permissionKey) {
		jdbc().update(
				"INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect) "
						+ "SELECT ?, ?, p.id, 'ALLOW' FROM permissions p WHERE p.permission_key = ?",
				hr.membershipId(), hr.companyId(), permissionKey);
	}

	private HttpHeaders bearer(String accessToken) {
		HttpHeaders headers = new HttpHeaders();
		if (accessToken != null) {
			headers.setBearerAuth(accessToken);
		}
		return headers;
	}

	private Long createMonthlyContract(String accessToken, Long employeeId) {
		ResponseEntity<SalaryContractView> response = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + employeeId, HttpMethod.POST,
				new HttpEntity<>(monthlyContractRequest(), bearer(accessToken)), SalaryContractView.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody().id();
	}

	private static UpsertSalaryContractRequest monthlyContractRequest() {
		return new UpsertSalaryContractRequest(
				SalaryMode.MONTHLY, BigDecimal.valueOf(3000), null, null, null, null, null, null,
				null, null, null, null, null, LocalDate.of(2026, 1, 1));
	}

	private Long createBatch(String accessToken, int month) {
		ResponseEntity<PayrollBatchView> response = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) month, (short) 2026), bearer(accessToken)),
				PayrollBatchView.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody().id();
	}

	private Long createPayslip(String accessToken, Long batchId, Long employeeId) {
		ResponseEntity<PayslipView> response = restTemplate.exchange(
				"/api/tenant/payslips", HttpMethod.POST,
				new HttpEntity<>(new CreatePayslipRequest(batchId, employeeId, 30, 0, 0, BigDecimal.ZERO),
						bearer(accessToken)),
				PayslipView.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody().id();
	}

	@Test
	void crossTenantSalaryContractOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());
		Long contractId = createMonthlyContract(companyA.accessToken(), employeeA);

		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/salary-contracts/" + contractId, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossUpdate = restTemplate.exchange(
				"/api/tenant/salary-contracts/" + contractId, HttpMethod.PUT,
				new HttpEntity<>(monthlyContractRequest(), bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossDelete = restTemplate.exchange(
				"/api/tenant/salary-contracts/" + contractId, HttpMethod.DELETE,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);

		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Listing by a foreign employee id yields an empty list, not that
		// employee's contract history.
		ResponseEntity<List<SalaryContractView>> crossList = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + employeeA, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(crossList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(crossList.getBody()).isEmpty();
	}

	@Test
	void createContractForAForeignOrNonexistentEmployeeIsAnIndistinguishable404() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());

		ResponseEntity<String> foreign = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + employeeA, HttpMethod.POST,
				new HttpEntity<>(monthlyContractRequest(), bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> nonexistent = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=999999999", HttpMethod.POST,
				new HttpEntity<>(monthlyContractRequest(), bearer(companyB.accessToken())), String.class);

		assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(nonexistent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantBatchOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long batchId = createBatch(companyA.accessToken(), 4);

		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossPeriodUpdate = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId, HttpMethod.PUT,
				new HttpEntity<>(new UpdateBatchPeriodRequest(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> crossCalculate = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossFinalize = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/finalize", HttpMethod.PUT,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossReopen = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/reopen", HttpMethod.PUT,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossDelete = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId, HttpMethod.DELETE,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);

		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossPeriodUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossCalculate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossFinalize.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossReopen.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<PayrollBatchView>> listB = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(PayrollBatchView::id).doesNotContain(batchId);
	}

	@Test
	void aForeignBatchsStateNeverLeaksThroughAConflictResponse() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long batchId = createBatch(companyA.accessToken(), 5);
		ResponseEntity<PayrollBatchView> finalized = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/finalize", HttpMethod.PUT,
				new HttpEntity<>(bearer(companyA.accessToken())), PayrollBatchView.class);
		assertThat(finalized.getBody().status()).isEqualTo(BatchStatus.FINALIZED);

		// In-tenant, these are state conflicts (409) -- the batch exists
		// but is FINALIZED.
		ResponseEntity<String> inTenantCalculate = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(bearer(companyA.accessToken())), String.class);
		ResponseEntity<String> inTenantDelete = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId, HttpMethod.DELETE,
				new HttpEntity<>(bearer(companyA.accessToken())), String.class);
		assertThat(inTenantCalculate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(inTenantDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// Cross-tenant, the same operations on the same batch must be
		// 404s -- a 409 here would tell company B that company A has a
		// finalized batch with this id (existence + state leak).
		ResponseEntity<String> crossCalculate = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossDelete = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId, HttpMethod.DELETE,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		assertThat(crossCalculate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantPayslipOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());
		createMonthlyContract(companyA.accessToken(), employeeA);
		Long batchA = createBatch(companyA.accessToken(), 1);
		Long payslipId = createPayslip(companyA.accessToken(), batchA, employeeA);

		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/payslips/" + payslipId, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossUpdate = restTemplate.exchange(
				"/api/tenant/payslips/" + payslipId, HttpMethod.PUT,
				new HttpEntity<>(new UpdatePayslipRequest(25, 5, 0, BigDecimal.ZERO), bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> crossDelete = restTemplate.exchange(
				"/api/tenant/payslips/" + payslipId, HttpMethod.DELETE,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);

		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<PayslipView>> listB = restTemplate.exchange(
				"/api/tenant/payslips", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(PayslipView::id).doesNotContain(payslipId);
	}

	@Test
	void createPayslipWithAForeignBatchOrForeignEmployeeIsAnIndistinguishable404() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());
		createMonthlyContract(companyA.accessToken(), employeeA);
		Long batchA = createBatch(companyA.accessToken(), 2);
		Long employeeB = createEmployee(companyB.companyId());
		createMonthlyContract(companyB.accessToken(), employeeB);
		Long batchB = createBatch(companyB.accessToken(), 2);

		ResponseEntity<String> foreignBatch = restTemplate.exchange(
				"/api/tenant/payslips", HttpMethod.POST,
				new HttpEntity<>(new CreatePayslipRequest(batchA, employeeB, 30, 0, 0, BigDecimal.ZERO),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> foreignEmployee = restTemplate.exchange(
				"/api/tenant/payslips", HttpMethod.POST,
				new HttpEntity<>(new CreatePayslipRequest(batchB, employeeA, 30, 0, 0, BigDecimal.ZERO),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> nonexistentBatch = restTemplate.exchange(
				"/api/tenant/payslips", HttpMethod.POST,
				new HttpEntity<>(new CreatePayslipRequest(999_999_999L, employeeB, 30, 0, 0, BigDecimal.ZERO),
						bearer(companyB.accessToken())),
				String.class);

		assertThat(foreignBatch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(foreignEmployee.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(nonexistentBatch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void readDoesNotImplyRun() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		createMonthlyContract(admin.accessToken(), employeeId);
		Long batchId = createBatch(admin.accessToken(), 3);
		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.PAYROLL_READ);

		ResponseEntity<List<PayrollBatchView>> batchList = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		ResponseEntity<List<PayslipView>> payslipList = restTemplate.exchange(
				"/api/tenant/payslips", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		ResponseEntity<List<SalaryContractView>> contractList = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + employeeId, HttpMethod.GET,
				new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(batchList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(payslipList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(contractList.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> createBatchDenied = restTemplate.exchange(
				"/api/tenant/payroll-batches", HttpMethod.POST,
				new HttpEntity<>(new CreateBatchRequest((short) 6, (short) 2026), bearer(reader.accessToken())),
				String.class);
		ResponseEntity<String> calculateDenied = restTemplate.exchange(
				"/api/tenant/payroll-batches/" + batchId + "/calculate", HttpMethod.POST,
				new HttpEntity<>(bearer(reader.accessToken())), String.class);
		ResponseEntity<String> createContractDenied = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + employeeId, HttpMethod.POST,
				new HttpEntity<>(monthlyContractRequest(), bearer(reader.accessToken())), String.class);
		ResponseEntity<String> createPayslipDenied = restTemplate.exchange(
				"/api/tenant/payslips", HttpMethod.POST,
				new HttpEntity<>(new CreatePayslipRequest(batchId, employeeId, 30, 0, 0, BigDecimal.ZERO),
						bearer(reader.accessToken())),
				String.class);

		assertThat(createBatchDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(calculateDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(createContractDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(createPayslipDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		for (String path : List.of(
				"/api/tenant/payroll-batches", "/api/tenant/payslips", "/api/tenant/salary-contracts?employeeId=1")) {
			ResponseEntity<String> response = restTemplate.exchange(
					path, HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
			assertThat(response.getStatusCode().is2xxSuccessful()).as(path).isFalse();
		}
	}

	private static String uniquePhone() {
		return "+2015" + System.nanoTime() % 100_000_000L;
	}

}
