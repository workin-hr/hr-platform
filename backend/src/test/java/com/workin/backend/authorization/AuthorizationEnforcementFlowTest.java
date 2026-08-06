package com.workin.backend.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.LoginRequest;
import com.workin.backend.identity.RegisterCompanyRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * End-to-end proof that @RequiresPermission is enforced by production
 * code (AuthorizationPolicyInterceptor + PermissionEvaluationService),
 * exercised through a probe controller registered only in this test's
 * context -- production ships no permission-gated endpoint yet, and
 * test classes are never component-scanned into the real application.
 * Covers F-19 (revocation effective on the very next request, same
 * token) and F-20's disabled-membership fail-closed case.
 */
class AuthorizationEnforcementFlowTest extends AbstractIntegrationTest {

	private static final String PROBE_PATH = "/test/authorization/employees-read-gated";

	@TestConfiguration
	static class ProbeControllerConfiguration {

		@RestController
		static class EmployeesReadGatedProbeController {

			@RequiresPermission(PermissionKeys.EMPLOYEES_READ)
			@GetMapping(PROBE_PATH)
			public String gated(HttpServletRequest request) {
				// The interceptor stashes the validated context for
				// handler reuse -- prove the stash is really there.
				Object context = request.getAttribute(
						com.workin.backend.tenancy.AuthorizationContext.class.getName());
				return context == null ? "missing-context" : "granted";
			}

		}

	}

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

	private ResponseEntity<String> callProbe(String accessToken) {
		HttpHeaders headers = new HttpHeaders();
		if (accessToken != null) {
			headers.setBearerAuth(accessToken);
		}
		return restTemplate.exchange(PROBE_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private AuthResponse registerCompanyAdmin() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("Enforcement Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private record HrFixture(String accessToken, Long membershipId, Long companyId) {
	}

	/** A second identity in an existing company, holding only the HR role. */
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

	@Test
	void companyAdminIsRoleGrantedThroughTheGate() {
		AuthResponse admin = registerCompanyAdmin();

		ResponseEntity<String> response = callProbe(admin.accessToken());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("granted");
	}

	@Test
	void hrWithoutAGrantIsDeniedByDefault() {
		AuthResponse admin = registerCompanyAdmin();
		HrFixture hr = loginHrMember(admin.companyId());

		assertThat(callProbe(hr.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void anAllowOverrideGrantsAndItsRevocationBitesOnTheVeryNextRequest() {
		AuthResponse admin = registerCompanyAdmin();
		HrFixture hr = loginHrMember(admin.companyId());

		jdbc().update(
				"INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect) "
						+ "SELECT ?, ?, p.id, 'ALLOW' FROM permissions p WHERE p.permission_key = ?",
				hr.membershipId(), hr.companyId(), PermissionKeys.EMPLOYEES_READ);
		assertThat(callProbe(hr.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

		// F-19: flip the grant to an explicit deny -- the SAME access
		// token must be rejected on the immediately following request,
		// with no re-login, refresh, or expiry involved.
		jdbc().update(
				"UPDATE membership_permission_overrides SET effect = 'DENY' WHERE membership_id = ?",
				hr.membershipId());
		assertThat(callProbe(hr.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aDisabledMembershipFailsClosedAtTheGate() {
		AuthResponse admin = registerCompanyAdmin();

		jdbc().update(
				"UPDATE tenant_memberships SET status = 'DISABLED' WHERE id = ?", admin.membershipId());

		// F-20 / ADR-0010 task 7's previously uncovered case: the gate
		// fails closed before evaluation, same token, next request.
		assertThat(callProbe(admin.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void noTokenNeverPassesTheGate() {
		ResponseEntity<String> response = callProbe(null);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	private static String uniquePhone() {
		return "+2044" + System.nanoTime() % 100_000_000L;
	}

}
