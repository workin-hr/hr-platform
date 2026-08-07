package com.workin.backend.requests;

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
 * Leave-balances surface + this module's F-18 negatives. The
 * (employee_id, year) uniqueness is a real DB constraint (V25) --
 * legacy only assumed it app-level. used_days is deliberately not
 * settable through this surface: it belongs to the request-approval
 * side effect (see RequestModuleFlowTest); remaining_days is the
 * transcribed DB-generated column.
 */
class LeaveBalanceFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Leave Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Leave', 'Emp') RETURNING id",
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

	private ResponseEntity<LeaveBalanceView> create(String accessToken, Long employeeId, int year, String totalDays) {
		return restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.POST,
				new HttpEntity<>(new CreateLeaveBalanceRequest(employeeId, (short) year, new BigDecimal(totalDays),
						null, null, null), bearer(accessToken)),
				LeaveBalanceView.class);
	}

	@Test
	void adminRoundTripCreateListGetUpdate() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		ResponseEntity<LeaveBalanceView> created = create(admin.accessToken(), employeeId, 2026, "21.0");
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().totalDays()).isEqualByComparingTo("21.0");
		assertThat(created.getBody().usedDays()).isEqualByComparingTo("0.0");
		assertThat(created.getBody().remainingDays()).isEqualByComparingTo("21.0");
		assertThat(created.getBody().periodFromMonth()).isEqualTo((short) 1);
		assertThat(created.getBody().periodToMonth()).isEqualTo((short) 12);
		Long id = created.getBody().id();

		ResponseEntity<List<LeaveBalanceView>> list = restTemplate.exchange(
				"/api/tenant/leave-balances?employeeId=" + employeeId, HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(LeaveBalanceView::id).contains(id);

		ResponseEntity<LeaveBalanceView> updated = restTemplate.exchange(
				"/api/tenant/leave-balances/" + id, HttpMethod.PUT,
				new HttpEntity<>(new UpdateLeaveBalanceRequest(new BigDecimal("30.0"), (short) 2, (short) 11,
						new BigDecimal("2.50")), bearer(admin.accessToken())),
				LeaveBalanceView.class);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		// remaining_days is DB-generated: total change recomputes it.
		assertThat(updated.getBody().remainingDays()).isEqualByComparingTo("30.0");
		assertThat(updated.getBody().monthlyCapDays()).isEqualByComparingTo("2.50");
	}

	@Test
	void duplicateEmployeeYearIsARealConstraintNotAnAppCheck() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		assertThat(create(admin.accessToken(), employeeId, 2026, "21.0").getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		ResponseEntity<String> duplicate = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.POST,
				new HttpEntity<>(new CreateLeaveBalanceRequest(employeeId, (short) 2026, new BigDecimal("25.0"),
						null, null, null), bearer(admin.accessToken())),
				String.class);
		assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// A different year for the same employee is fine.
		assertThat(create(admin.accessToken(), employeeId, 2027, "21.0").getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void negativeTotalDaysIsRejected() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		ResponseEntity<String> negative = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.POST,
				new HttpEntity<>(new CreateLeaveBalanceRequest(employeeId, (short) 2026, new BigDecimal("-1.0"),
						null, null, null), bearer(admin.accessToken())),
				String.class);
		assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void createWithAForeignOrNonexistentEmployeeIsAnIndistinguishable404() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());

		ResponseEntity<String> foreign = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.POST,
				new HttpEntity<>(new CreateLeaveBalanceRequest(employeeA, (short) 2026, new BigDecimal("21.0"),
						null, null, null), bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> nonexistent = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.POST,
				new HttpEntity<>(new CreateLeaveBalanceRequest(999_999_999L, (short) 2026, new BigDecimal("21.0"),
						null, null, null), bearer(companyB.accessToken())),
				String.class);
		assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(nonexistent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long employeeA = createEmployee(companyA.companyId());
		Long id = create(companyA.accessToken(), employeeA, 2026, "21.0").getBody().id();

		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/leave-balances/" + id, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossUpdate = restTemplate.exchange(
				"/api/tenant/leave-balances/" + id, HttpMethod.PUT,
				new HttpEntity<>(new UpdateLeaveBalanceRequest(new BigDecimal("99.0"), null, null, null),
						bearer(companyB.accessToken())),
				String.class);
		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<LeaveBalanceView>> listB = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(LeaveBalanceView::id).doesNotContain(id);
	}

	@Test
	void readDoesNotImplyManage() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.LEAVE_BALANCES_READ);

		ResponseEntity<List<LeaveBalanceView>> list = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> createDenied = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.POST,
				new HttpEntity<>(new CreateLeaveBalanceRequest(employeeId, (short) 2026, new BigDecimal("21.0"),
						null, null, null), bearer(reader.accessToken())),
				String.class);
		assertThat(createDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/leave-balances", HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	private static String uniquePhone() {
		return "+2019" + System.nanoTime() % 100_000_000L;
	}

}
