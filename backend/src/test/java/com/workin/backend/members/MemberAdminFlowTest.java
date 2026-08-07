package com.workin.backend.members;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.workin.backend.tenancy.MembershipStatus;
import com.workin.backend.tenancy.TenantRole;

/**
 * F-24's deliverable (docs/migration/consolidated-task-matrix.md): the
 * privilege-escalation rules of docs/architecture/authorization-model.md
 * section 8, exercised through the real administration surface --
 * self-mutation, last-admin protection on both the role-removal and
 * disable paths, platform-namespace confinement as uniform 404s -- plus
 * end-to-end proof that override grants/revocations and membership
 * disabling bite on the target's very next request (F-19/F-20 through
 * the API instead of SQL), and section-9 audit attribution.
 */
class MemberAdminFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Members Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private Long adminMembershipId(Long companyId) {
		return jdbc().queryForObject(
				"SELECT id FROM tenant_memberships WHERE company_id = ? ORDER BY id LIMIT 1", Long.class, companyId);
	}

	private record MemberFixture(String accessToken, Long membershipId, Long companyId) {
	}

	private MemberFixture loginMember(Long companyId, TenantRole... roles) {
		JdbcTemplate jdbc = jdbc();
		String phone = uniquePhone();
		String password = "correct horse battery staple";
		Long identityId = jdbc.queryForObject(
				"INSERT INTO identities (phone, password_hash) VALUES (?, ?) RETURNING id",
				Long.class, phone, passwordEncoder.encode(password));
		Long membershipId = jdbc.queryForObject(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
				Long.class, identityId, companyId);
		for (TenantRole role : roles) {
			jdbc.update(
					"INSERT INTO membership_roles (membership_id, company_id, role) VALUES (?, ?, ?)",
					membershipId, companyId, role.name());
		}
		AuthResponse login = restTemplate.postForEntity(
				"/api/auth/login", new LoginRequest(phone, password), AuthResponse.class).getBody();
		return new MemberFixture(login.accessToken(), membershipId, companyId);
	}

	private void allowPermission(Long membershipId, Long companyId, String permissionKey) {
		jdbc().update(
				"INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect) "
						+ "SELECT ?, ?, p.id, 'ALLOW' FROM permissions p WHERE p.permission_key = ?",
				membershipId, companyId, permissionKey);
	}

	private HttpHeaders bearer(String accessToken) {
		HttpHeaders headers = new HttpHeaders();
		if (accessToken != null) {
			headers.setBearerAuth(accessToken);
		}
		return headers;
	}

	private ResponseEntity<String> assignRole(String accessToken, Long membershipId, TenantRole role) {
		return restTemplate.exchange(
				"/api/tenant/members/" + membershipId + "/roles", HttpMethod.POST,
				new HttpEntity<>(new AssignRoleRequest(role), bearer(accessToken)), String.class);
	}

	private ResponseEntity<String> removeRole(String accessToken, Long membershipId, TenantRole role) {
		return restTemplate.exchange(
				"/api/tenant/members/" + membershipId + "/roles/" + role.name(), HttpMethod.DELETE,
				new HttpEntity<>(bearer(accessToken)), String.class);
	}

	private ResponseEntity<String> grantOverride(String accessToken, Long membershipId, String key, OverrideEffect effect) {
		return restTemplate.exchange(
				"/api/tenant/members/" + membershipId + "/permission-overrides", HttpMethod.POST,
				new HttpEntity<>(new UpsertOverrideRequest(key, effect), bearer(accessToken)), String.class);
	}

	private ResponseEntity<String> revokeOverride(String accessToken, Long membershipId, String key) {
		return restTemplate.exchange(
				"/api/tenant/members/" + membershipId + "/permission-overrides/" + key, HttpMethod.DELETE,
				new HttpEntity<>(bearer(accessToken)), String.class);
	}

	private ResponseEntity<String> updateStatus(String accessToken, Long membershipId, MembershipStatus status) {
		return restTemplate.exchange(
				"/api/tenant/members/" + membershipId + "/status", HttpMethod.PUT,
				new HttpEntity<>(new UpdateStatusRequest(status), bearer(accessToken)), String.class);
	}

	private ResponseEntity<String> listPenalties(String accessToken) {
		return restTemplate.exchange(
				"/api/tenant/penalties", HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), String.class);
	}

	@Test
	void adminAssignsListsAndRemovesARole() {
		AuthResponse admin = registerCompanyAdmin();
		MemberFixture member = loginMember(admin.companyId(), TenantRole.EMPLOYEE);

		assertThat(assignRole(admin.accessToken(), member.membershipId(), TenantRole.HR).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		assertThat(assignRole(admin.accessToken(), member.membershipId(), TenantRole.HR).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);

		ResponseEntity<List<MemberView>> list = restTemplate.exchange(
				"/api/tenant/members", HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
		MemberView listed = list.getBody().stream()
				.filter(m -> m.membershipId().equals(member.membershipId())).findFirst().orElseThrow();
		assertThat(listed.roles()).containsExactlyInAnyOrder(TenantRole.EMPLOYEE, TenantRole.HR);
		assertThat(listed.status()).isEqualTo(MembershipStatus.ACTIVE);
		assertThat(listed.phone()).isNotBlank();

		assertThat(removeRole(admin.accessToken(), member.membershipId(), TenantRole.HR).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(removeRole(admin.accessToken(), member.membershipId(), TenantRole.HR).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void anOverrideGrantedThroughTheApiBitesOnTheTargetsNextRequestAndStopsAfterRevocation() {
		AuthResponse admin = registerCompanyAdmin();
		MemberFixture member = loginMember(admin.companyId(), TenantRole.EMPLOYEE);

		assertThat(listPenalties(member.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		assertThat(grantOverride(admin.accessToken(), member.membershipId(),
				PermissionKeys.PENALTIES_READ, OverrideEffect.ALLOW).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		assertThat(listPenalties(member.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(revokeOverride(admin.accessToken(), member.membershipId(), PermissionKeys.PENALTIES_READ)
				.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(listPenalties(member.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		ResponseEntity<MemberDetailView> detail = restTemplate.exchange(
				"/api/tenant/members/" + member.membershipId(), HttpMethod.GET,
				new HttpEntity<>(bearer(admin.accessToken())), MemberDetailView.class);
		assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(detail.getBody().overrides()).isEmpty();
	}

	@Test
	void ownMembershipCannotBeAdministered() {
		AuthResponse admin = registerCompanyAdmin();
		MemberFixture actor = loginMember(admin.companyId(), TenantRole.HR);
		allowPermission(actor.membershipId(), admin.companyId(), PermissionKeys.MEMBERS_MANAGE);

		ResponseEntity<String> selfRole = assignRole(actor.accessToken(), actor.membershipId(), TenantRole.COMPANY_ADMIN);
		ResponseEntity<String> selfOverride = grantOverride(actor.accessToken(), actor.membershipId(),
				PermissionKeys.PAYROLL_RUN, OverrideEffect.ALLOW);
		ResponseEntity<String> selfStatus = updateStatus(actor.accessToken(), actor.membershipId(), MembershipStatus.DISABLED);

		// Which 409 rule fired is pinned by scenario construction: the
		// actor holds members.manage and targets only their own
		// membership, so SelfMutation is the only conflict reachable
		// here (error messages are deliberately not exposed in response
		// bodies -- Spring's include-message default stays "never").
		assertThat(selfRole.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(selfOverride.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(selfStatus.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void theLastActiveCompanyAdminCannotBeRemovedOrDisabled() {
		AuthResponse admin = registerCompanyAdmin();
		Long adminMembership = adminMembershipId(admin.companyId());
		MemberFixture actor = loginMember(admin.companyId(), TenantRole.HR);
		allowPermission(actor.membershipId(), admin.companyId(), PermissionKeys.MEMBERS_MANAGE);

		ResponseEntity<String> removeLastAdminRole = removeRole(actor.accessToken(), adminMembership, TenantRole.COMPANY_ADMIN);
		ResponseEntity<String> disableLastAdmin = updateStatus(actor.accessToken(), adminMembership, MembershipStatus.DISABLED);
		// The actor targets someone else's membership with a role that is
		// assigned, so LastAdmin is the only conflict reachable here.
		assertThat(removeLastAdminRole.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(disableLastAdmin.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// With a second active COMPANY_ADMIN in place, the first can step down.
		MemberFixture successor = loginMember(admin.companyId(), TenantRole.EMPLOYEE);
		assertThat(assignRole(actor.accessToken(), successor.membershipId(), TenantRole.COMPANY_ADMIN).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		assertThat(removeRole(actor.accessToken(), adminMembership, TenantRole.COMPANY_ADMIN).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	void platformNamespaceAndUnknownKeysAreIndistinguishable404s() {
		AuthResponse admin = registerCompanyAdmin();
		MemberFixture member = loginMember(admin.companyId(), TenantRole.EMPLOYEE);

		ResponseEntity<String> platformKey = grantOverride(admin.accessToken(), member.membershipId(),
				PermissionKeys.PLATFORM_COMPANIES_READ, OverrideEffect.ALLOW);
		ResponseEntity<String> unknownKey = grantOverride(admin.accessToken(), member.membershipId(),
				"no.such.key", OverrideEffect.ALLOW);

		assertThat(platformKey.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(unknownKey.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void disablingAMembershipFailsItClosedOnItsNextRequestAndReactivationRestoresIt() {
		AuthResponse admin = registerCompanyAdmin();
		MemberFixture member = loginMember(admin.companyId(), TenantRole.EMPLOYEE);
		allowPermission(member.membershipId(), admin.companyId(), PermissionKeys.PENALTIES_READ);

		assertThat(listPenalties(member.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(updateStatus(admin.accessToken(), member.membershipId(), MembershipStatus.DISABLED).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(listPenalties(member.accessToken()).getStatusCode().is2xxSuccessful()).isFalse();

		assertThat(updateStatus(admin.accessToken(), member.membershipId(), MembershipStatus.ACTIVE).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(listPenalties(member.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void settingTheStatusAMembershipAlreadyHasIsANoOpWithoutAnAuditRow() {
		AuthResponse admin = registerCompanyAdmin();
		MemberFixture member = loginMember(admin.companyId(), TenantRole.EMPLOYEE);
		Long before = jdbc().queryForObject(
				"SELECT COUNT(*) FROM tenant_audit_events WHERE company_id = ?", Long.class, admin.companyId());

		assertThat(updateStatus(admin.accessToken(), member.membershipId(), MembershipStatus.ACTIVE).getStatusCode())
				.isEqualTo(HttpStatus.OK);

		Long after = jdbc().queryForObject(
				"SELECT COUNT(*) FROM tenant_audit_events WHERE company_id = ?", Long.class, admin.companyId());
		assertThat(after).isEqualTo(before);
	}

	@Test
	void crossTenantOperationsAreIndistinguishable404s() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		MemberFixture memberA = loginMember(companyA.companyId(), TenantRole.EMPLOYEE);

		assertThat(assignRole(companyB.accessToken(), memberA.membershipId(), TenantRole.HR).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(removeRole(companyB.accessToken(), memberA.membershipId(), TenantRole.EMPLOYEE).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(grantOverride(companyB.accessToken(), memberA.membershipId(),
				PermissionKeys.PENALTIES_READ, OverrideEffect.ALLOW).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(revokeOverride(companyB.accessToken(), memberA.membershipId(), PermissionKeys.PENALTIES_READ)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(updateStatus(companyB.accessToken(), memberA.membershipId(), MembershipStatus.DISABLED).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		ResponseEntity<String> crossGet = restTemplate.exchange(
				"/api/tenant/members/" + memberA.membershipId(), HttpMethod.GET,
				new HttpEntity<>(bearer(companyB.accessToken())), String.class);
		assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		ResponseEntity<List<MemberView>> listB = restTemplate.exchange(
				"/api/tenant/members", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(MemberView::membershipId).doesNotContain(memberA.membershipId());
	}

	@Test
	void readDoesNotImplyManage() {
		AuthResponse admin = registerCompanyAdmin();
		MemberFixture target = loginMember(admin.companyId(), TenantRole.EMPLOYEE);
		MemberFixture reader = loginMember(admin.companyId(), TenantRole.HR);
		allowPermission(reader.membershipId(), admin.companyId(), PermissionKeys.MEMBERS_READ);

		ResponseEntity<List<MemberView>> list = restTemplate.exchange(
				"/api/tenant/members", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(assignRole(reader.accessToken(), target.membershipId(), TenantRole.HR).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(grantOverride(reader.accessToken(), target.membershipId(),
				PermissionKeys.PENALTIES_READ, OverrideEffect.ALLOW).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(updateStatus(reader.accessToken(), target.membershipId(), MembershipStatus.DISABLED).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/members", HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	@Test
	void everySuccessfulMutationIsAuditedWithActorAndTarget() {
		AuthResponse admin = registerCompanyAdmin();
		Long adminMembership = adminMembershipId(admin.companyId());
		MemberFixture member = loginMember(admin.companyId(), TenantRole.EMPLOYEE);

		assignRole(admin.accessToken(), member.membershipId(), TenantRole.HR);
		removeRole(admin.accessToken(), member.membershipId(), TenantRole.HR);
		grantOverride(admin.accessToken(), member.membershipId(), PermissionKeys.PENALTIES_READ, OverrideEffect.ALLOW);
		revokeOverride(admin.accessToken(), member.membershipId(), PermissionKeys.PENALTIES_READ);
		updateStatus(admin.accessToken(), member.membershipId(), MembershipStatus.DISABLED);
		updateStatus(admin.accessToken(), member.membershipId(), MembershipStatus.ACTIVE);

		List<Map<String, Object>> events = jdbc().queryForList(
				"SELECT actor_membership_id, target_membership_id, action, permission_key "
						+ "FROM tenant_audit_events WHERE company_id = ? ORDER BY id",
				admin.companyId());
		assertThat(events).extracting(e -> e.get("action")).containsExactly(
				"ROLE_ASSIGNED", "ROLE_REMOVED", "OVERRIDE_GRANTED", "OVERRIDE_REVOKED",
				"MEMBERSHIP_DISABLED", "MEMBERSHIP_REACTIVATED");
		assertThat(events).allSatisfy(e -> {
			assertThat(e.get("actor_membership_id")).isEqualTo(adminMembership);
			assertThat(e.get("target_membership_id")).isEqualTo(member.membershipId());
		});
		assertThat(events.get(2).get("permission_key")).isEqualTo(PermissionKeys.PENALTIES_READ);
	}

	private static String uniquePhone() {
		return "+2018" + System.nanoTime() % 100_000_000L;
	}

}
