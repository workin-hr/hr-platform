package com.workin.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalTime;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.LoginRequest;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Organization-structure CRUD (branches, departments + junction,
 * job titles, shifts) + F-18 negatives. Delete semantics under test:
 * junction rows belong to the department aggregate (cleared with it);
 * a branch referenced from outside answers 409 via the real FK
 * constraint. qr_code/expires_at are view-only -- QR issuance is the
 * deferred slice's flow.
 */
class OrganizationStructureFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Org Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Org', 'Emp') RETURNING id",
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

	private BranchView createBranch(String accessToken, String name) {
		ResponseEntity<BranchView> response = restTemplate.exchange(
				"/api/tenant/branches", HttpMethod.POST,
				new HttpEntity<>(new UpsertBranchRequest(name, null, null, null, null, null), bearer(accessToken)),
				BranchView.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	private DepartmentView createDepartment(String accessToken, String name, Long managerId, List<Long> branchIds) {
		ResponseEntity<DepartmentView> response = restTemplate.exchange(
				"/api/tenant/departments", HttpMethod.POST,
				new HttpEntity<>(new UpsertDepartmentRequest(name, managerId, branchIds, null), bearer(accessToken)),
				DepartmentView.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	@Test
	void branchRoundTripWithDefaultsAndViewOnlyQrFields() {
		AuthResponse admin = registerCompanyAdmin();

		BranchView created = createBranch(admin.accessToken(), "HQ");
		assertThat(created.radiusMeters()).isEqualTo(200);
		assertThat(created.isActive()).isTrue();
		assertThat(created.qrCode()).isNull();
		assertThat(created.expiresAt()).isNull();

		// A request smuggling a qrCode field is simply ignored -- no
		// binding target exists.
		HttpHeaders json = bearer(admin.accessToken());
		json.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<BranchView> smuggled = restTemplate.exchange(
				"/api/tenant/branches/" + created.id(), HttpMethod.PUT,
				new HttpEntity<>("{\"name\":\"HQ2\",\"isActive\":false,\"qrCode\":\"sneaky\"}", json),
				BranchView.class);
		assertThat(smuggled.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(smuggled.getBody().name()).isEqualTo("HQ2");
		assertThat(smuggled.getBody().isActive()).isFalse();
		assertThat(smuggled.getBody().qrCode()).isNull();

		ResponseEntity<Void> deleted = restTemplate.exchange(
				"/api/tenant/branches/" + created.id(), HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), Void.class);
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	void departmentManagesItsBranchSetAndValidatesReferences() {
		AuthResponse admin = registerCompanyAdmin();
		Long managerId = createEmployee(admin.companyId());
		BranchView b1 = createBranch(admin.accessToken(), "B1");
		BranchView b2 = createBranch(admin.accessToken(), "B2");

		DepartmentView created = createDepartment(admin.accessToken(), "Ops", managerId, List.of(b1.id(), b2.id()));
		assertThat(created.managerId()).isEqualTo(managerId);
		assertThat(created.branchIds()).containsExactlyInAnyOrder(b1.id(), b2.id());

		ResponseEntity<DepartmentView> replaced = restTemplate.exchange(
				"/api/tenant/departments/" + created.id(), HttpMethod.PUT,
				new HttpEntity<>(new UpsertDepartmentRequest("Ops", null, List.of(b2.id()), null),
						bearer(admin.accessToken())),
				DepartmentView.class);
		assertThat(replaced.getBody().branchIds()).containsExactly(b2.id());
		assertThat(replaced.getBody().managerId()).isNull();

		ResponseEntity<DepartmentView> cleared = restTemplate.exchange(
				"/api/tenant/departments/" + created.id(), HttpMethod.PUT,
				new HttpEntity<>(new UpsertDepartmentRequest("Ops", null, List.of(), null),
						bearer(admin.accessToken())),
				DepartmentView.class);
		assertThat(cleared.getBody().branchIds()).isEmpty();

		ResponseEntity<String> foreignManager = restTemplate.exchange(
				"/api/tenant/departments", HttpMethod.POST,
				new HttpEntity<>(new UpsertDepartmentRequest("Bad", 999_999_999L, List.of(), null),
						bearer(admin.accessToken())),
				String.class);
		assertThat(foreignManager.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		AuthResponse companyB = registerCompanyAdmin();
		BranchView foreignBranch = createBranch(companyB.accessToken(), "Foreign");
		ResponseEntity<String> foreignBranchSet = restTemplate.exchange(
				"/api/tenant/departments", HttpMethod.POST,
				new HttpEntity<>(new UpsertDepartmentRequest("Bad", null, List.of(foreignBranch.id()), null),
						bearer(admin.accessToken())),
				String.class);
		assertThat(foreignBranchSet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void departmentDeleteClearsItsOwnJunctionRows() {
		AuthResponse admin = registerCompanyAdmin();
		BranchView branch = createBranch(admin.accessToken(), "B");
		DepartmentView department = createDepartment(admin.accessToken(), "D", null, List.of(branch.id()));

		ResponseEntity<Void> deleted = restTemplate.exchange(
				"/api/tenant/departments/" + department.id(), HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), Void.class);
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		Long junctionRows = jdbc().queryForObject(
				"SELECT COUNT(*) FROM department_branches WHERE department_id = ?", Long.class, department.id());
		assertThat(junctionRows).isZero();
	}

	@Test
	void aBranchReferencedFromOutsideItsAggregateAnswers409OnDelete() {
		AuthResponse admin = registerCompanyAdmin();
		BranchView branch = createBranch(admin.accessToken(), "Referenced");
		DepartmentView department = createDepartment(admin.accessToken(), "D", null, List.of(branch.id()));

		ResponseEntity<String> blocked = restTemplate.exchange(
				"/api/tenant/branches/" + branch.id(), HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), String.class);
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		restTemplate.exchange(
				"/api/tenant/departments/" + department.id(), HttpMethod.PUT,
				new HttpEntity<>(new UpsertDepartmentRequest("D", null, List.of(), null), bearer(admin.accessToken())),
				DepartmentView.class);
		ResponseEntity<Void> deleted = restTemplate.exchange(
				"/api/tenant/branches/" + branch.id(), HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), Void.class);
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	void jobTitleDefaultsValidationAndDepartmentReference() {
		AuthResponse admin = registerCompanyAdmin();
		DepartmentView department = createDepartment(admin.accessToken(), "Eng", null, List.of());

		ResponseEntity<JobTitleView> created = restTemplate.exchange(
				"/api/tenant/job-titles", HttpMethod.POST,
				new HttpEntity<>(new UpsertJobTitleRequest("Engineer", department.id(), null, null),
						bearer(admin.accessToken())),
				JobTitleView.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().workHours()).isEqualByComparingTo("8.00");
		assertThat(created.getBody().departmentId()).isEqualTo(department.id());

		ResponseEntity<String> zeroHours = restTemplate.exchange(
				"/api/tenant/job-titles", HttpMethod.POST,
				new HttpEntity<>(new UpsertJobTitleRequest("Bad", null, BigDecimal.ZERO, null),
						bearer(admin.accessToken())),
				String.class);
		assertThat(zeroHours.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		ResponseEntity<String> foreignDepartment = restTemplate.exchange(
				"/api/tenant/job-titles", HttpMethod.POST,
				new HttpEntity<>(new UpsertJobTitleRequest("Bad", 999_999_999L, null, null),
						bearer(admin.accessToken())),
				String.class);
		assertThat(foreignDepartment.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void shiftValidationAndRoundTrip() {
		AuthResponse admin = registerCompanyAdmin();

		ResponseEntity<ShiftView> created = restTemplate.exchange(
				"/api/tenant/shifts", HttpMethod.POST,
				new HttpEntity<>(new UpsertShiftRequest("Morning", LocalTime.of(9, 0), LocalTime.of(17, 0),
						"Fri,Sat", null), bearer(admin.accessToken())),
				ShiftView.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().daysOff()).isEqualTo("Fri,Sat");
		assertThat(created.getBody().startTime()).isEqualTo(LocalTime.of(9, 0));

		ResponseEntity<String> missingTime = restTemplate.exchange(
				"/api/tenant/shifts", HttpMethod.POST,
				new HttpEntity<>(new UpsertShiftRequest("Bad", null, LocalTime.of(17, 0), null, null),
						bearer(admin.accessToken())),
				String.class);
		assertThat(missingTime.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void crossTenantOperationsAreIndistinguishable404sPerSurface() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		BranchView branch = createBranch(companyA.accessToken(), "A branch");
		DepartmentView department = createDepartment(companyA.accessToken(), "A dept", null, List.of());
		Long jobTitleId = restTemplate.exchange(
				"/api/tenant/job-titles", HttpMethod.POST,
				new HttpEntity<>(new UpsertJobTitleRequest("A title", null, null, null), bearer(companyA.accessToken())),
				JobTitleView.class).getBody().id();
		Long shiftId = restTemplate.exchange(
				"/api/tenant/shifts", HttpMethod.POST,
				new HttpEntity<>(new UpsertShiftRequest("A shift", LocalTime.of(9, 0), LocalTime.of(17, 0), null, null),
						bearer(companyA.accessToken())),
				ShiftView.class).getBody().id();

		record Probe(String path, Object updateBody) {
		}
		List<Probe> probes = List.of(
				new Probe("/api/tenant/branches/" + branch.id(),
						new UpsertBranchRequest("X", null, null, null, null, null)),
				new Probe("/api/tenant/departments/" + department.id(),
						new UpsertDepartmentRequest("X", null, List.of(), null)),
				new Probe("/api/tenant/job-titles/" + jobTitleId,
						new UpsertJobTitleRequest("X", null, null, null)),
				new Probe("/api/tenant/shifts/" + shiftId,
						new UpsertShiftRequest("X", LocalTime.of(8, 0), LocalTime.of(16, 0), null, null)));
		for (Probe probe : probes) {
			assertThat(restTemplate.exchange(probe.path(), HttpMethod.GET,
					new HttpEntity<>(bearer(companyB.accessToken())), String.class).getStatusCode())
					.as("GET " + probe.path()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(restTemplate.exchange(probe.path(), HttpMethod.PUT,
					new HttpEntity<>(probe.updateBody(), bearer(companyB.accessToken())), String.class).getStatusCode())
					.as("PUT " + probe.path()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(restTemplate.exchange(probe.path(), HttpMethod.DELETE,
					new HttpEntity<>(bearer(companyB.accessToken())), String.class).getStatusCode())
					.as("DELETE " + probe.path()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		ResponseEntity<List<BranchView>> listB = restTemplate.exchange(
				"/api/tenant/branches", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(BranchView::id).doesNotContain(branch.id());
	}

	@Test
	void readDoesNotImplyManage() {
		AuthResponse admin = registerCompanyAdmin();
		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.BRANCHES_READ);

		ResponseEntity<List<BranchView>> list = restTemplate.exchange(
				"/api/tenant/branches", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> createDenied = restTemplate.exchange(
				"/api/tenant/branches", HttpMethod.POST,
				new HttpEntity<>(new UpsertBranchRequest("Denied", null, null, null, null, null),
						bearer(reader.accessToken())),
				String.class);
		assertThat(createDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		for (String path : List.of("/api/tenant/branches", "/api/tenant/departments",
				"/api/tenant/job-titles", "/api/tenant/shifts")) {
			ResponseEntity<String> response = restTemplate.exchange(
					path, HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
			assertThat(response.getStatusCode().is2xxSuccessful()).as(path).isFalse();
		}
	}

	private static String uniquePhone() {
		return "+2022" + System.nanoTime() % 100_000_000L;
	}

}
