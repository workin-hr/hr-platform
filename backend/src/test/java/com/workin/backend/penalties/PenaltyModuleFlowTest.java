package com.workin.backend.penalties;

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
 * Penalties flow + this module's F-18 negatives, plus the one real
 * business rule this slice carries: a penalty with
 * applied_to_payroll = true is locked from update and delete (V13's
 * documented immutability -- the flag is only ever set by the payroll
 * module's future finalize side effect, simulated here via SQL).
 */
class PenaltyModuleFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Penalties Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long createEmployee(Long companyId) {
		return jdbc().queryForObject(
				"INSERT INTO employees (company_id, first_name, last_name) VALUES (?, 'Pen', 'Emp') RETURNING id",
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

	private ResponseEntity<PenaltyView> create(String accessToken, Long employeeId, String days) {
		return restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.POST,
				new HttpEntity<>(new CreatePenaltyRequest(employeeId, "late_arrival", new BigDecimal(days),
						"penalty reason", null), bearer(accessToken)),
				PenaltyView.class);
	}

	@Test
	void adminRoundTripCreateListGetUpdateDelete() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		ResponseEntity<PenaltyView> created = create(admin.accessToken(), employeeId, "1.5");
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().penaltyDays()).isEqualByComparingTo(new BigDecimal("1.5"));
		assertThat(created.getBody().appliedToPayroll()).isFalse();
		assertThat(created.getBody().penaltyDate()).isNotNull();
		Long penaltyId = created.getBody().id();

		ResponseEntity<List<PenaltyView>> list = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getBody()).extracting(PenaltyView::id).contains(penaltyId);

		ResponseEntity<PenaltyView> updated = restTemplate.exchange(
				"/api/tenant/penalties/" + penaltyId, HttpMethod.PUT,
				new HttpEntity<>(new UpdatePenaltyRequest("unexcused_absence", new BigDecimal("2.0"),
						"updated reason", created.getBody().penaltyDate()), bearer(admin.accessToken())),
				PenaltyView.class);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updated.getBody().penaltyType()).isEqualTo("unexcused_absence");

		ResponseEntity<Void> deleted = restTemplate.exchange(
				"/api/tenant/penalties/" + penaltyId, HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), Void.class);
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<String> afterDelete = restTemplate.exchange(
				"/api/tenant/penalties/" + penaltyId, HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())), String.class);
		assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void zeroDaysIsLegalNegativeIsNot() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());

		assertThat(create(admin.accessToken(), employeeId, "0.0").getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> negative = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.POST,
				new HttpEntity<>(new CreatePenaltyRequest(employeeId, "late", new BigDecimal("-1.0"), null, null),
						bearer(admin.accessToken())),
				String.class);
		assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void aPayrollAppliedPenaltyIsLockedFromUpdateAndDelete() {
		AuthResponse admin = registerCompanyAdmin();
		Long penaltyId = create(admin.accessToken(), createEmployee(admin.companyId()), "1.0").getBody().id();
		// Stand-in for the payroll module's future finalize side effect.
		jdbc().update("UPDATE penalties SET applied_to_payroll = TRUE WHERE id = ?", penaltyId);

		ResponseEntity<String> updateLocked = restTemplate.exchange(
				"/api/tenant/penalties/" + penaltyId, HttpMethod.PUT,
				new HttpEntity<>(new UpdatePenaltyRequest("late", new BigDecimal("3.0"), null, null),
						bearer(admin.accessToken())),
				String.class);
		ResponseEntity<String> deleteLocked = restTemplate.exchange(
				"/api/tenant/penalties/" + penaltyId, HttpMethod.DELETE,
				new HttpEntity<>(bearer(admin.accessToken())), String.class);

		assertThat(updateLocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(deleteLocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void createWithAForeignOrNonexistentEmployeeIsAnIndistinguishable404() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long companyAEmployee = createEmployee(companyA.companyId());

		ResponseEntity<String> foreign = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.POST,
				new HttpEntity<>(new CreatePenaltyRequest(companyAEmployee, "late", new BigDecimal("1.0"), null, null),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> nonexistent = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.POST,
				new HttpEntity<>(new CreatePenaltyRequest(999_999_999L, "late", new BigDecimal("1.0"), null, null),
						bearer(companyB.accessToken())),
				String.class);
		assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(nonexistent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long penaltyId = create(companyA.accessToken(), createEmployee(companyA.companyId()), "1.0")
				.getBody().id();

		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/penalties/" + penaltyId, HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		ResponseEntity<String> crossUpdate = restTemplate.exchange(
				"/api/tenant/penalties/" + penaltyId, HttpMethod.PUT,
				new HttpEntity<>(new UpdatePenaltyRequest("late", new BigDecimal("9.0"), null, null),
						bearer(companyB.accessToken())),
				String.class);
		ResponseEntity<String> crossDelete = restTemplate.exchange(
				"/api/tenant/penalties/" + penaltyId, HttpMethod.DELETE,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);

		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(crossDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<PenaltyView>> listB = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(PenaltyView::id).doesNotContain(penaltyId);
	}

	@Test
	void readDoesNotImplyManage() {
		AuthResponse admin = registerCompanyAdmin();
		Long employeeId = createEmployee(admin.companyId());
		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.PENALTIES_READ);

		ResponseEntity<List<PenaltyView>> list = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> createDenied = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.POST,
				new HttpEntity<>(new CreatePenaltyRequest(employeeId, "late", new BigDecimal("1.0"), null, null),
						bearer(reader.accessToken())),
				String.class);
		assertThat(createDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	private static String uniquePhone() {
		return "+2000" + System.nanoTime() % 100_000_000L;
	}

}
