package com.workin.backend.employees;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.workin.backend.organization.BranchView;
import com.workin.backend.organization.DepartmentView;
import com.workin.backend.organization.JobTitleView;
import com.workin.backend.organization.UpsertBranchRequest;
import com.workin.backend.organization.UpsertDepartmentRequest;
import com.workin.backend.organization.UpsertJobTitleRequest;

/**
 * The employees module's flow tests double as this module's F-18
 * negatives (docs/migration/consolidated-task-matrix.md), modeled on
 * hr-legacy#2 (cross-tenant read of another company's employees) and
 * hr-legacy#3 (cross-tenant mutation via employee edit): every
 * cross-tenant access is an indistinguishable 404, and the admin
 * surface has no credential fields at all.
 */
class EmployeeModuleFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Employees Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
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

	private ResponseEntity<EmployeeView> create(String accessToken, CreateEmployeeRequest request) {
		return restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.POST, new HttpEntity<>(request, bearer(accessToken)),
				EmployeeView.class);
	}

	private record OrgFixture(Long branchId, Long departmentId, Long jobTitleId) {
	}

	private OrgFixture createOrgStructure(String accessToken) {
		Long branchId = restTemplate.exchange(
				"/api/tenant/branches", HttpMethod.POST,
				new HttpEntity<>(new UpsertBranchRequest("HQ", null, null, null, null, null), bearer(accessToken)),
				BranchView.class).getBody().id();
		Long departmentId = restTemplate.exchange(
				"/api/tenant/departments", HttpMethod.POST,
				new HttpEntity<>(new UpsertDepartmentRequest("Ops", null, List.of(branchId), null), bearer(accessToken)),
				DepartmentView.class).getBody().id();
		Long jobTitleId = restTemplate.exchange(
				"/api/tenant/job-titles", HttpMethod.POST,
				new HttpEntity<>(new UpsertJobTitleRequest("Engineer", departmentId, null, null), bearer(accessToken)),
				JobTitleView.class).getBody().id();
		return new OrgFixture(branchId, departmentId, jobTitleId);
	}

	@Test
	void organizationAttributionRoundTripsAndValidatesReferences() {
		AuthResponse admin = registerCompanyAdmin();
		OrgFixture org = createOrgStructure(admin.accessToken());

		ResponseEntity<EmployeeView> created = create(admin.accessToken(), new CreateEmployeeRequest(
				"Placed", "Emp", uniquePhone(), org.branchId(), org.departmentId(), org.jobTitleId()));
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().branchId()).isEqualTo(org.branchId());
		assertThat(created.getBody().departmentId()).isEqualTo(org.departmentId());
		assertThat(created.getBody().jobTitleId()).isEqualTo(org.jobTitleId());
		Long employeeId = created.getBody().id();

		// Nulls clear the attribution.
		ResponseEntity<EmployeeView> cleared = restTemplate.exchange(
				"/api/tenant/employees/" + employeeId, HttpMethod.PUT,
				new HttpEntity<>(new UpdateEmployeeRequest("Placed", "Emp", null, null, null),
						bearer(admin.accessToken())),
				EmployeeView.class);
		assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(cleared.getBody().branchId()).isNull();
		assertThat(cleared.getBody().departmentId()).isNull();
		assertThat(cleared.getBody().jobTitleId()).isNull();

		// Foreign (company B's) and nonexistent references: the same 404
		// for each of the three ids.
		AuthResponse companyB = registerCompanyAdmin();
		OrgFixture foreign = createOrgStructure(companyB.accessToken());
		List<UpdateEmployeeRequest> badUpdates = List.of(
				new UpdateEmployeeRequest("P", "E", foreign.branchId(), null, null),
				new UpdateEmployeeRequest("P", "E", null, foreign.departmentId(), null),
				new UpdateEmployeeRequest("P", "E", null, null, foreign.jobTitleId()),
				new UpdateEmployeeRequest("P", "E", 999_999_999L, null, null),
				new UpdateEmployeeRequest("P", "E", null, 999_999_999L, null),
				new UpdateEmployeeRequest("P", "E", null, null, 999_999_999L));
		for (UpdateEmployeeRequest bad : badUpdates) {
			ResponseEntity<String> response = restTemplate.exchange(
					"/api/tenant/employees/" + employeeId, HttpMethod.PUT,
					new HttpEntity<>(bad, bearer(admin.accessToken())), String.class);
			assertThat(response.getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.NOT_FOUND);
		}
	}

	private ResponseEntity<List<EmployeeView>> list(String accessToken) {
		return restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.GET, new HttpEntity<>(bearer(accessToken)),
				new ParameterizedTypeReference<>() {
				});
	}

	@Test
	void adminRoundTripCreateListGetUpdate() {
		AuthResponse admin = registerCompanyAdmin();

		ResponseEntity<EmployeeView> created = create(
				admin.accessToken(), new CreateEmployeeRequest("Nour", "Hassan", uniquePhone(), null, null, null));
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().firstName()).isEqualTo("Nour");
		assertThat(created.getBody().role()).isEqualTo("EMPLOYEE");
		assertThat(created.getBody().active()).isTrue();

		assertThat(list(admin.accessToken()).getBody())
				.extracting(EmployeeView::id)
				.contains(created.getBody().id());

		ResponseEntity<EmployeeView> fetched = restTemplate.exchange(
				"/api/tenant/employees/" + created.getBody().id(), HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())), EmployeeView.class);
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<EmployeeView> updated = restTemplate.exchange(
				"/api/tenant/employees/" + created.getBody().id(), HttpMethod.PUT,
				new HttpEntity<>(new UpdateEmployeeRequest("Noura", "Hassan", null, null, null), bearer(admin.accessToken())),
				EmployeeView.class);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updated.getBody().firstName()).isEqualTo("Noura");
	}

	@Test
	void theAdminSurfaceNeverExposesCredentialFields() {
		AuthResponse admin = registerCompanyAdmin();
		create(admin.accessToken(), new CreateEmployeeRequest("Sami", "Adel", uniquePhone(), null, null, null));

		ResponseEntity<String> raw = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())),
				String.class);

		// hr-legacy#3's root fix is structural: the entity never maps
		// password_hash and no DTO carries credentials -- the JSON can't
		// contain a password under any spelling.
		assertThat(raw.getBody().toLowerCase()).doesNotContain("password");
	}

	@Test
	void duplicatePhoneIsACleanConflict() {
		AuthResponse admin = registerCompanyAdmin();
		String phone = uniquePhone();
		create(admin.accessToken(), new CreateEmployeeRequest("First", "User", phone, null, null, null));

		ResponseEntity<String> second = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.POST,
				new HttpEntity<>(new CreateEmployeeRequest("Second", "User", phone, null, null, null), bearer(admin.accessToken())),
				String.class);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void hrWithoutGrantsIsDeniedOnBothReadAndManage() {
		AuthResponse admin = registerCompanyAdmin();
		HrFixture hr = loginHrMember(admin.companyId());

		assertThat(list(hr.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		ResponseEntity<String> createAttempt = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.POST,
				new HttpEntity<>(new CreateEmployeeRequest("Nope", "Nope", null, null, null, null), bearer(hr.accessToken())),
				String.class);
		assertThat(createAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void readGrantDoesNotImplyManage() {
		AuthResponse admin = registerCompanyAdmin();
		HrFixture hr = loginHrMember(admin.companyId());
		allowPermission(hr, PermissionKeys.EMPLOYEES_READ);

		assertThat(list(hr.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> createAttempt = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.POST,
				new HttpEntity<>(new CreateEmployeeRequest("Still", "No", null, null, null, null), bearer(hr.accessToken())),
				String.class);
		assertThat(createAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void crossTenantAccessIsAnIndistinguishable404() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long companyAEmployeeId = create(
				companyA.accessToken(), new CreateEmployeeRequest("Alia", "Amr", uniquePhone(), null, null, null)).getBody().id();

		// hr-legacy#2's read shape: B reading A's employee.
		ResponseEntity<String> crossRead = restTemplate.exchange(
				"/api/tenant/employees/" + companyAEmployeeId, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		assertThat(crossRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// hr-legacy#3's mutation shape: B editing A's employee.
		ResponseEntity<String> crossUpdate = restTemplate.exchange(
				"/api/tenant/employees/" + companyAEmployeeId, HttpMethod.PUT,
				new HttpEntity<>(new UpdateEmployeeRequest("Taken", "Over", null, null, null), bearer(companyB.accessToken())),
				String.class);
		assertThat(crossUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// And B's list never contains A's rows.
		assertThat(list(companyB.accessToken()).getBody())
				.extracting(EmployeeView::id)
				.doesNotContain(companyAEmployeeId);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/employees", HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	private static String uniquePhone() {
		return "+2022" + System.nanoTime() % 100_000_000L;
	}

}
