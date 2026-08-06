package com.workin.backend.advances;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * F-18 negatives for the advances module, modeled on hr-legacy#5's
 * confirmed live cross-tenant IDOR (approve/reject/pay/delete/create
 * reachable across tenants): every cross-tenant operation here is an
 * indistinguishable 404, including create-with-foreign-employee. Pay
 * and delete deliberately do not exist in this surface yet -- pay is
 * the payroll module's finalize side effect; delete awaits the
 * lifecycle/retention discussion.
 */
class AdvanceModuleFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Advances Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Adv', 'Emp') RETURNING id",
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

	private ResponseEntity<AdvanceView> create(String accessToken, Long employeeId, String amount) {
		return restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.POST,
				new HttpEntity<>(new CreateAdvanceRequest(employeeId, new BigDecimal(amount), "advance reason"),
						bearer(accessToken)),
				AdvanceView.class);
	}

	private ResponseEntity<AdvanceView> approve(String accessToken, Long advanceId) {
		return restTemplate.exchange(
				"/api/tenant/advances/" + advanceId + "/approve", HttpMethod.POST,
				new HttpEntity<>(bearer(accessToken)), AdvanceView.class);
	}

	private ResponseEntity<AdvanceView> reject(String accessToken, Long advanceId, String reason) {
		return restTemplate.exchange(
				"/api/tenant/advances/" + advanceId + "/reject", HttpMethod.POST,
				new HttpEntity<>(new RejectAdvanceRequest(reason), bearer(accessToken)), AdvanceView.class);
	}

	@Test
	void adminRoundTripCreateListGetApprove() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		ResponseEntity<AdvanceView> created = create(admin.accessToken(), employeeId, "1500.00");
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().status()).isEqualTo("PENDING");
		assertThat(created.getBody().remaining()).isEqualByComparingTo(new BigDecimal("1500.00"));

		ResponseEntity<List<AdvanceView>> list = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(AdvanceView::id).contains(created.getBody().id());

		ResponseEntity<AdvanceView> approved = approve(admin.accessToken(), created.getBody().id());
		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(approved.getBody().status()).isEqualTo("APPROVED");
		// Approval never applies deductions -- that is payroll's job.
		assertThat(approved.getBody().remaining()).isEqualByComparingTo(new BigDecimal("1500.00"));
	}

	@Test
	void rejectRecordsTheReason() {
		AuthResponse admin = registerCompanyAdmin();
		Long advanceId = create(admin.accessToken(), createEmployee(admin.companyId()), "200.00").getBody().id();

		ResponseEntity<AdvanceView> rejected = reject(admin.accessToken(), advanceId, "budget freeze");

		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(rejected.getBody().status()).isEqualTo("REJECTED");
		assertThat(rejected.getBody().rejectionReason()).isEqualTo("budget freeze");
	}

	@Test
	void transitionsFromATerminalStateAreConflicts() {
		AuthResponse admin = registerCompanyAdmin();
		Long advanceId = create(admin.accessToken(), createEmployee(admin.companyId()), "300.00").getBody().id();
		approve(admin.accessToken(), advanceId);

		ResponseEntity<String> approveAgain = restTemplate.exchange(
				"/api/tenant/advances/" + advanceId + "/approve", HttpMethod.POST,
				new HttpEntity<>(bearer(admin.accessToken())), String.class);
		ResponseEntity<String> rejectAfterApprove = restTemplate.exchange(
				"/api/tenant/advances/" + advanceId + "/reject", HttpMethod.POST,
				new HttpEntity<>(new RejectAdvanceRequest("late"), bearer(admin.accessToken())), String.class);

		assertThat(approveAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(rejectAfterApprove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void nonPositiveAmountsAreRejected() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		ResponseEntity<String> negative = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.POST,
				new HttpEntity<>(new CreateAdvanceRequest(employeeId, new BigDecimal("-5.00"), null),
						bearer(admin.accessToken())),
				String.class);

		assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void createWithAForeignOrNonexistentEmployeeIsAnIndistinguishable404() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long companyAEmployee = createEmployee(companyA.companyId());

		// hr-legacy#5's create shape: B creating an advance against A's employee.
		ResponseEntity<String> foreign = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.POST,
				new HttpEntity<>(new CreateAdvanceRequest(companyAEmployee, new BigDecimal("100.00"), null),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> nonexistent = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.POST,
				new HttpEntity<>(new CreateAdvanceRequest(999_999_999L, new BigDecimal("100.00"), null),
						bearer(companyB.accessToken())),
				String.class);

		assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(nonexistent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long advanceId = create(companyA.accessToken(), createEmployee(companyA.companyId()), "400.00")
				.getBody().id();

		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/advances/" + advanceId, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossApprove = restTemplate.exchange(
				"/api/tenant/advances/" + advanceId + "/approve", HttpMethod.POST,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossReject = restTemplate.exchange(
				"/api/tenant/advances/" + advanceId + "/reject", HttpMethod.POST,
				new HttpEntity<>(new RejectAdvanceRequest("nope"), bearer(companyB.accessToken())), String.class);

		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossApprove.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossReject.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<AdvanceView>> listB = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(AdvanceView::id).doesNotContain(advanceId);
	}

	@Test
	void manageDoesNotImplyApproveAndReadDoesNotImplyManage() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		HrFixture manager = loginHrMember(admin.companyId());
		allowPermission(manager, PermissionKeys.ADVANCES_MANAGE);
		ResponseEntity<AdvanceView> created = create(manager.accessToken(), employeeId, "50.00");
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		ResponseEntity<String> approveDenied = restTemplate.exchange(
				"/api/tenant/advances/" + created.getBody().id() + "/approve", HttpMethod.POST,
				new HttpEntity<>(bearer(manager.accessToken())), String.class);
		assertThat(approveDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.ADVANCES_READ);
		ResponseEntity<List<AdvanceView>> list = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
		ResponseEntity<String> createDenied = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.POST,
				new HttpEntity<>(new CreateAdvanceRequest(employeeId, new BigDecimal("10.00"), null),
						bearer(reader.accessToken())),
				String.class);
		assertThat(createDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	private static String uniquePhone() {
		return "+2011" + System.nanoTime() % 100_000_000L;
	}

}
