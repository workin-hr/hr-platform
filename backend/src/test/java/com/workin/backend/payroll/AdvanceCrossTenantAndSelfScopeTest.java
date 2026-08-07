package com.workin.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

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
import com.workin.backend.payroll.AdvanceController.AdvanceResponse;
import com.workin.backend.payroll.AdvanceController.CreateAdvanceRequest;
import com.workin.backend.payroll.AdvanceController.PayAdvanceRequest;
import com.workin.backend.payroll.AdvanceController.RejectAdvanceRequest;

/**
 * Direct regression test for hr-legacy#5: legacy's advances
 * approve/reject/pay/create/delete were missing company_id checks
 * entirely (docs/security/threat-model.md). Every one of those 5
 * methods is exercised here cross-tenant and must return 404, not a
 * real mutation and not a distinguishable 403 (see
 * docs/architecture/authorization-model.md §8). Also covers
 * EMPLOYEE-role self-scoping, the other half of this module's
 * authorization surface.
 */
class AdvanceCrossTenantAndSelfScopeTest extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	@Test
	void approveRejectPayDeleteAcrossTenantsAllReturnNotFound() {
		AuthResponse companyA = register("Company A");
		AuthResponse companyB = register("Company B");
		EmployeeFixture employeeInA = createEmployeeInCompany(companyA.companyId());

		Long advanceId = createAdvanceAsAdmin(companyA, employeeInA.employeeId(), BigDecimal.valueOf(500));

		HttpHeaders bHeaders = bearer(companyB.accessToken());

		assertThat(restTemplate.exchange(
				"/api/payroll/advances/" + advanceId + "/approve", HttpMethod.PUT, new HttpEntity<>(bHeaders), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(restTemplate.exchange(
				"/api/payroll/advances/" + advanceId + "/reject", HttpMethod.PUT,
				new HttpEntity<>(new RejectAdvanceRequest("no"), bHeaders), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(restTemplate.exchange(
				"/api/payroll/advances/" + advanceId + "/pay", HttpMethod.PUT,
				new HttpEntity<>(new PayAdvanceRequest(BigDecimal.TEN), bHeaders), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(restTemplate.exchange(
				"/api/payroll/advances/" + advanceId, HttpMethod.DELETE, new HttpEntity<>(bHeaders), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// The advance must be untouched by any of the above.
		ResponseEntity<AdvanceResponse> stillPending = restTemplate.exchange(
				"/api/payroll/advances/" + advanceId, HttpMethod.GET,
				new HttpEntity<>(bearer(companyA.accessToken())), AdvanceResponse.class);
		assertThat(stillPending.getBody().status()).isEqualTo(AdvanceStatus.PENDING);
		assertThat(stillPending.getBody().remaining()).isEqualByComparingTo("500");
	}

	@Test
	void createWithAForeignCompanysEmployeeIdIsRejected() {
		AuthResponse companyA = register("Company A");
		AuthResponse companyB = register("Company B");
		EmployeeFixture employeeInA = createEmployeeInCompany(companyA.companyId());

		CreateAdvanceRequest request = new CreateAdvanceRequest(
				employeeInA.employeeId(), BigDecimal.valueOf(100), "test", null, 1, null, null, null, null);
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/payroll/advances", HttpMethod.POST,
				new HttpEntity<>(request, bearer(companyB.accessToken())), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void employeeSeesOnlyOwnAdvancesAndCannotApprove() {
		AuthResponse companyA = register("Company A");
		EmployeeFixture employeeInA = createEmployeeInCompany(companyA.companyId());
		Long advanceId = createAdvanceAsAdmin(companyA, employeeInA.employeeId(), BigDecimal.valueOf(300));

		HttpHeaders employeeHeaders = bearer(employeeInA.accessToken());

		ResponseEntity<AdvanceResponse[]> listResponse = restTemplate.exchange(
				"/api/payroll/advances", HttpMethod.GET, new HttpEntity<>(employeeHeaders), AdvanceResponse[].class);
		assertThat(listResponse.getBody()).hasSize(1);
		assertThat(listResponse.getBody()[0].id()).isEqualTo(advanceId);

		ResponseEntity<String> approveAttempt = restTemplate.exchange(
				"/api/payroll/advances/" + advanceId + "/approve", HttpMethod.PUT,
				new HttpEntity<>(employeeHeaders), String.class);
		assertThat(approveAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	private Long createAdvanceAsAdmin(AuthResponse admin, Long employeeId, BigDecimal amount) {
		CreateAdvanceRequest request = new CreateAdvanceRequest(
				employeeId, amount, "test advance", null, 1, null, null, null, null);
		ResponseEntity<AdvanceResponse> response = restTemplate.exchange(
				"/api/payroll/advances", HttpMethod.POST,
				new HttpEntity<>(request, bearer(admin.accessToken())), AdvanceResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody().id();
	}

	private HttpHeaders bearer(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return headers;
	}

	private AuthResponse register(String name) {
		String phone = "+2011" + System.nanoTime() % 100_000_000L;
		ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
				"/api/auth/register", new RegisterCompanyRequest(name, phone, "correct horse battery staple"), AuthResponse.class);
		return response.getBody();
	}

	/**
	 * Builds a real EMPLOYEE-role membership in {@code targetCompanyId},
	 * plus its matching employees row (V15's identity_id link), by
	 * registering a throwaway shell company and reassigning its
	 * membership via JDBC -- login only supports a single active
	 * membership per identity today (see LoginService), so the shell
	 * membership is deleted, not just left in place.
	 */
	private EmployeeFixture createEmployeeInCompany(Long targetCompanyId) {
		String phone = "+2012" + System.nanoTime() % 100_000_000L;
		String password = "correct horse battery staple";
		ResponseEntity<AuthResponse> shellRegistration = restTemplate.postForEntity(
				"/api/auth/register", new RegisterCompanyRequest("Employee Shell", phone, password), AuthResponse.class);
		AuthResponse shell = shellRegistration.getBody();

		JdbcTemplate superuser = new JdbcTemplate(flywayDataSource);
		superuser.update("DELETE FROM membership_roles WHERE membership_id = ?", shell.membershipId());
		superuser.update("DELETE FROM tenant_memberships WHERE id = ?", shell.membershipId());

		Long identityId = superuser.queryForObject("SELECT id FROM identities WHERE phone = ?", Long.class, phone);
		Long newMembershipId = superuser.queryForObject(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
				Long.class, identityId, targetCompanyId);
		superuser.update(
				"INSERT INTO membership_roles (membership_id, company_id, role) VALUES (?, ?, 'EMPLOYEE')",
				newMembershipId, targetCompanyId);
		Long employeeId = superuser.queryForObject(
				"INSERT INTO employees (company_id, identity_id, first_name, last_name) VALUES (?, ?, 'Test', 'Employee') RETURNING id",
				Long.class, targetCompanyId, identityId);

		ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
				"/api/auth/login", new com.workin.backend.identity.LoginRequest(phone, password), AuthResponse.class);
		assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

		return new EmployeeFixture(employeeId, loginResponse.getBody().accessToken());
	}

	private record EmployeeFixture(Long employeeId, String accessToken) {
	}

}
