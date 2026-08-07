package com.workin.backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
 * F-25 branch/department manager scoping, proven end-to-end on
 * attendance (the hr-legacy#17/#18 over-reach surface). A pure MANAGER
 * is confined to employees in its assigned branches/departments; an
 * unscoped manager reaches nobody; company-wide roles (admin, and
 * MANAGER+HR) are unaffected; scope changes bite on the next request.
 */
class ManagerScopeFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Scope Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private HttpHeaders bearer(String accessToken) {
		HttpHeaders headers = new HttpHeaders();
		if (accessToken != null) {
			headers.setBearerAuth(accessToken);
		}
		return headers;
	}

	private Long createBranch(Long companyId, String name) {
		return jdbc().queryForObject(
				"INSERT INTO branches (company_id, name) VALUES (?, ?) RETURNING id", Long.class, companyId, name);
	}

	private Long createDepartment(Long companyId, String name) {
		return jdbc().queryForObject(
				"INSERT INTO departments (company_id, name) VALUES (?, ?) RETURNING id", Long.class, companyId, name);
	}

	private Long createEmployee(Long companyId, Long branchId, Long departmentId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name, branch_id, department_id) "
						+ "VALUES (?, 'S', 'E', ?, ?) RETURNING id",
				Long.class, companyId, branchId, departmentId);
	}

	private void seedAttendance(Long companyId, Long employeeId) {
		jdbc().update(
				"INSERT INTO attendance (employee_id, company_id, check_in, method) VALUES (?, ?, ?, 'app')",
				employeeId, companyId, java.sql.Timestamp.from(Instant.parse("2026-03-02T09:00:00Z")));
	}

	private record ManagerFixture(String accessToken, Long membershipId) {
	}

	/** A pure-MANAGER membership (MANAGER role only) with attendance.read allowed. */
	private ManagerFixture loginScopedManager(Long companyId, boolean alsoHr) {
		JdbcTemplate jdbc = jdbc();
		String phone = uniquePhone();
		String password = "correct horse battery staple";
		Long identityId = jdbc.queryForObject(
				"INSERT INTO identities (phone, password_hash) VALUES (?, ?) RETURNING id",
				Long.class, phone, passwordEncoder.encode(password));
		Long membershipId = jdbc.queryForObject(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
				Long.class, identityId, companyId);
		jdbc.update("INSERT INTO membership_roles (membership_id, company_id, role) VALUES (?, ?, 'MANAGER')",
				membershipId, companyId);
		if (alsoHr) {
			jdbc.update("INSERT INTO membership_roles (membership_id, company_id, role) VALUES (?, ?, 'HR')",
					membershipId, companyId);
		}
		jdbc.update(
				"INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect) "
						+ "SELECT ?, ?, p.id, 'ALLOW' FROM permissions p WHERE p.permission_key = ?",
				membershipId, companyId, PermissionKeys.ATTENDANCE_READ);
		AuthResponse login = restTemplate.postForEntity(
				"/api/auth/login", new LoginRequest(phone, password), AuthResponse.class).getBody();
		return new ManagerFixture(login.accessToken(), membershipId);
	}

	private void assignScope(AuthResponse admin, Long membershipId, String scopeType, Long scopeId) {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/members/" + membershipId + "/resource-scopes", HttpMethod.POST,
				new HttpEntity<>(Map.of("scopeType", scopeType, "scopeId", scopeId), bearer(admin.accessToken())),
				String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private List<Long> attendanceEmployeeIds(String accessToken) {
		ResponseEntity<List<AttendanceView>> list = restTemplate.exchange(
				"/api/tenant/attendance", HttpMethod.GET, new HttpEntity<>(bearer(accessToken)),
				new ParameterizedTypeReference<>() {
				});
		return list.getBody().stream().map(AttendanceView::employeeId).distinct().toList();
	}

	@Test
	void aBranchScopedManagerSeesOnlyInBranchAttendance() {
		AuthResponse admin = registerCompanyAdmin();
		Long branchA = createBranch(admin.companyId(), "A");
		Long branchB = createBranch(admin.companyId(), "B");
		Long empA = createEmployee(admin.companyId(), branchA, null);
		Long empB = createEmployee(admin.companyId(), branchB, null);
		seedAttendance(admin.companyId(), empA);
		seedAttendance(admin.companyId(), empB);

		ManagerFixture manager = loginScopedManager(admin.companyId(), false);
		// No scope rows yet -> deny by default.
		assertThat(attendanceEmployeeIds(manager.accessToken())).isEmpty();

		assignScope(admin, manager.membershipId(), "BRANCH", branchA);
		// Liveness: the branch-A employee is now visible on the next request.
		assertThat(attendanceEmployeeIds(manager.accessToken())).containsExactly(empA);

		// A branch-B row is a 404 for this manager; filtering by that
		// employee yields empty.
		Long branchBAttendanceId = jdbc().queryForObject(
				"SELECT id FROM attendance WHERE employee_id = ?", Long.class, empB);
		assertThat(restTemplate.exchange(
				"/api/tenant/attendance/" + branchBAttendanceId, HttpMethod.GET,
				new HttpEntity<>(bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		// Admin (company-wide) sees both regardless.
		assertThat(attendanceEmployeeIds(admin.accessToken())).containsExactlyInAnyOrder(empA, empB);
	}

	@Test
	void aDepartmentScopedManagerSeesOnlyInDepartment() {
		AuthResponse admin = registerCompanyAdmin();
		Long branch = createBranch(admin.companyId(), "B");
		Long dept = createDepartment(admin.companyId(), "D");
		Long empInDept = createEmployee(admin.companyId(), branch, dept);
		Long empNoDept = createEmployee(admin.companyId(), branch, null);
		seedAttendance(admin.companyId(), empInDept);
		seedAttendance(admin.companyId(), empNoDept);

		ManagerFixture manager = loginScopedManager(admin.companyId(), false);
		assignScope(admin, manager.membershipId(), "DEPARTMENT", dept);
		assertThat(attendanceEmployeeIds(manager.accessToken())).containsExactly(empInDept);
	}

	@Test
	void aManagerAlsoHoldingHrIsCompanyWide() {
		AuthResponse admin = registerCompanyAdmin();
		Long branchA = createBranch(admin.companyId(), "A");
		Long branchB = createBranch(admin.companyId(), "B");
		Long empA = createEmployee(admin.companyId(), branchA, null);
		Long empB = createEmployee(admin.companyId(), branchB, null);
		seedAttendance(admin.companyId(), empA);
		seedAttendance(admin.companyId(), empB);

		// MANAGER + HR: the company-wide role trumps, no scope needed.
		ManagerFixture managerHr = loginScopedManager(admin.companyId(), true);
		assertThat(attendanceEmployeeIds(managerHr.accessToken())).containsExactlyInAnyOrder(empA, empB);
	}

	private void allowManager(Long companyId, Long membershipId, String permissionKey) {
		jdbc().update(
				"INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect) "
						+ "SELECT ?, ?, p.id, 'ALLOW' FROM permissions p WHERE p.permission_key = ?",
				membershipId, companyId, permissionKey);
	}

	@Test
	void penaltiesRespectManagerScope() {
		AuthResponse admin = registerCompanyAdmin();
		Long branchA = createBranch(admin.companyId(), "A");
		Long branchB = createBranch(admin.companyId(), "B");
		Long empA = createEmployee(admin.companyId(), branchA, null);
		Long empB = createEmployee(admin.companyId(), branchB, null);
		Long penaltyA = jdbc().queryForObject(
				"INSERT INTO penalties (employee_id, company_id, penalty_type, penalty_days, penalty_date) "
						+ "VALUES (?, ?, 'late', 1.0, '2026-03-01') RETURNING id",
				Long.class, empA, admin.companyId());
		Long penaltyB = jdbc().queryForObject(
				"INSERT INTO penalties (employee_id, company_id, penalty_type, penalty_days, penalty_date) "
						+ "VALUES (?, ?, 'late', 1.0, '2026-03-01') RETURNING id",
				Long.class, empB, admin.companyId());

		ManagerFixture manager = loginScopedManager(admin.companyId(), false);
		allowManager(admin.companyId(), manager.membershipId(), PermissionKeys.PENALTIES_READ);
		allowManager(admin.companyId(), manager.membershipId(), PermissionKeys.PENALTIES_MANAGE);
		assignScope(admin, manager.membershipId(), "BRANCH", branchA);

		ResponseEntity<List<Map<String, Object>>> list = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.GET, new HttpEntity<>(bearer(manager.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(row -> ((Number) row.get("id")).longValue())
				.containsExactly(penaltyA);

		assertThat(restTemplate.exchange("/api/tenant/penalties/" + penaltyB, HttpMethod.GET,
				new HttpEntity<>(bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(restTemplate.exchange("/api/tenant/penalties/" + penaltyB, HttpMethod.DELETE,
				new HttpEntity<>(bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		// Create for an out-of-scope employee -> 404.
		assertThat(restTemplate.exchange("/api/tenant/penalties", HttpMethod.POST,
				new HttpEntity<>(Map.of("employeeId", empB, "penaltyType", "late", "penaltyDays", 1.0),
						bearer(manager.accessToken())),
				String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void requestsRespectManagerScopeIncludingApproval() {
		AuthResponse admin = registerCompanyAdmin();
		Long branchA = createBranch(admin.companyId(), "A");
		Long branchB = createBranch(admin.companyId(), "B");
		Long empA = createEmployee(admin.companyId(), branchA, null);
		Long empB = createEmployee(admin.companyId(), branchB, null);
		Long requestTypeId = jdbc().queryForObject(
				"INSERT INTO request_types (company_id, name) VALUES (?, 'Leave') RETURNING id",
				Long.class, admin.companyId());
		Long reqA = jdbc().queryForObject(
				"INSERT INTO requests (employee_id, company_id, request_type_id, from_date, to_date, status) "
						+ "VALUES (?, ?, ?, '2026-03-02', '2026-03-03', 'PENDING') RETURNING id",
				Long.class, empA, admin.companyId(), requestTypeId);
		Long reqB = jdbc().queryForObject(
				"INSERT INTO requests (employee_id, company_id, request_type_id, from_date, to_date, status) "
						+ "VALUES (?, ?, ?, '2026-03-02', '2026-03-03', 'PENDING') RETURNING id",
				Long.class, empB, admin.companyId(), requestTypeId);

		ManagerFixture manager = loginScopedManager(admin.companyId(), false);
		allowManager(admin.companyId(), manager.membershipId(), PermissionKeys.REQUESTS_READ);
		allowManager(admin.companyId(), manager.membershipId(), PermissionKeys.REQUESTS_APPROVE);
		assignScope(admin, manager.membershipId(), "BRANCH", branchA);

		ResponseEntity<List<Map<String, Object>>> list = restTemplate.exchange(
				"/api/tenant/requests", HttpMethod.GET, new HttpEntity<>(bearer(manager.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(row -> ((Number) row.get("id")).longValue())
				.containsExactly(reqA);

		// hr-legacy#18: the scoped manager cannot approve or reject an
		// out-of-branch employee's request -- it does not exist for them.
		assertThat(restTemplate.exchange("/api/tenant/requests/" + reqB + "/approve", HttpMethod.PUT,
				new HttpEntity<>(Map.of(), bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(restTemplate.exchange("/api/tenant/requests/" + reqB + "/reject", HttpMethod.PUT,
				new HttpEntity<>(Map.of("reply", "no"), bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(restTemplate.exchange("/api/tenant/requests/" + reqB, HttpMethod.GET,
				new HttpEntity<>(bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		// The in-branch request can be approved.
		assertThat(restTemplate.exchange("/api/tenant/requests/" + reqA + "/approve", HttpMethod.PUT,
				new HttpEntity<>(Map.of(), bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

	@Test
	void advancesRespectManagerScope() {
		AuthResponse admin = registerCompanyAdmin();
		Long branchA = createBranch(admin.companyId(), "A");
		Long branchB = createBranch(admin.companyId(), "B");
		Long empA = createEmployee(admin.companyId(), branchA, null);
		Long empB = createEmployee(admin.companyId(), branchB, null);
		Long advA = jdbc().queryForObject(
				"INSERT INTO advances (employee_id, company_id, amount, remaining, status, request_date) "
						+ "VALUES (?, ?, 100.0, 100.0, 'PENDING', '2026-03-01') RETURNING id",
				Long.class, empA, admin.companyId());
		Long advB = jdbc().queryForObject(
				"INSERT INTO advances (employee_id, company_id, amount, remaining, status, request_date) "
						+ "VALUES (?, ?, 100.0, 100.0, 'PENDING', '2026-03-01') RETURNING id",
				Long.class, empB, admin.companyId());

		ManagerFixture manager = loginScopedManager(admin.companyId(), false);
		allowManager(admin.companyId(), manager.membershipId(), PermissionKeys.ADVANCES_READ);
		allowManager(admin.companyId(), manager.membershipId(), PermissionKeys.ADVANCES_APPROVE);
		assignScope(admin, manager.membershipId(), "BRANCH", branchA);

		ResponseEntity<List<Map<String, Object>>> list = restTemplate.exchange(
				"/api/tenant/advances", HttpMethod.GET, new HttpEntity<>(bearer(manager.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(r -> ((Number) r.get("id")).longValue()).containsExactly(advA);
		assertThat(restTemplate.exchange("/api/tenant/advances/" + advB + "/approve", HttpMethod.POST,
				new HttpEntity<>(bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void salaryContractsRespectManagerScope() {
		AuthResponse admin = registerCompanyAdmin();
		Long branchA = createBranch(admin.companyId(), "A");
		Long branchB = createBranch(admin.companyId(), "B");
		Long empA = createEmployee(admin.companyId(), branchA, null);
		Long empB = createEmployee(admin.companyId(), branchB, null);
		Long contractB = jdbc().queryForObject(
				"INSERT INTO salary_contracts (employee_id, company_id, salary_mode, effective_from) "
						+ "VALUES (?, ?, 'MONTHLY', '2026-01-01') RETURNING id",
				Long.class, empB, admin.companyId());

		ManagerFixture manager = loginScopedManager(admin.companyId(), false);
		allowManager(admin.companyId(), manager.membershipId(), PermissionKeys.PAYROLL_READ);
		allowManager(admin.companyId(), manager.membershipId(), PermissionKeys.PAYROLL_RUN);
		assignScope(admin, manager.membershipId(), "BRANCH", branchA);

		// Listing a foreign (out-of-scope) employee's contracts -> empty.
		ResponseEntity<List<Map<String, Object>>> listB = restTemplate.exchange(
				"/api/tenant/salary-contracts?employeeId=" + empB, HttpMethod.GET,
				new HttpEntity<>(bearer(manager.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).isEmpty();
		// The out-of-scope contract's get -> 404.
		assertThat(restTemplate.exchange("/api/tenant/salary-contracts/" + contractB, HttpMethod.GET,
				new HttpEntity<>(bearer(manager.accessToken())), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		// Creating a contract for an out-of-scope employee -> 404.
		assertThat(restTemplate.exchange("/api/tenant/salary-contracts?employeeId=" + empB, HttpMethod.POST,
				new HttpEntity<>(Map.of("salaryMode", "MONTHLY", "effectiveFrom", "2026-02-01"),
						bearer(manager.accessToken())),
				String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(empA).isNotNull();
	}

	@Test
	void revokingAScopeHidesTheEmployeeAgainOnTheNextRequest() {
		AuthResponse admin = registerCompanyAdmin();
		Long branchA = createBranch(admin.companyId(), "A");
		Long empA = createEmployee(admin.companyId(), branchA, null);
		seedAttendance(admin.companyId(), empA);

		ManagerFixture manager = loginScopedManager(admin.companyId(), false);
		assignScope(admin, manager.membershipId(), "BRANCH", branchA);
		assertThat(attendanceEmployeeIds(manager.accessToken())).containsExactly(empA);

		restTemplate.exchange(
				"/api/tenant/members/" + manager.membershipId() + "/resource-scopes/BRANCH/" + branchA,
				HttpMethod.DELETE, new HttpEntity<>(bearer(admin.accessToken())), String.class);
		assertThat(attendanceEmployeeIds(manager.accessToken())).isEmpty();
	}

	private static String uniquePhone() {
		return "+2035" + System.nanoTime() % 100_000_000L;
	}

}
