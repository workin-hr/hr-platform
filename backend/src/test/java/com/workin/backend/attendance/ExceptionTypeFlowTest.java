package com.workin.backend.attendance;

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

/**
 * Minimal exception-types lookup (attendance's XOR dependency):
 * list/create only, update/delete deferred per the slice spec.
 */
class ExceptionTypeFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Exception Types Co", uniquePhone(), "correct horse battery staple"),
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

	@Test
	void adminCreatesAndListsExceptionTypes() {
		AuthResponse admin = registerCompanyAdmin();

		ResponseEntity<ExceptionTypeView> created = restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.POST,
				new HttpEntity<>(new CreateExceptionTypeRequest("Excused absence"), bearer(admin.accessToken())),
				ExceptionTypeView.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().name()).isEqualTo("Excused absence");

		ResponseEntity<List<ExceptionTypeView>> list = restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.GET, new HttpEntity<>(bearer(admin.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(list.getBody()).extracting(ExceptionTypeView::id).contains(created.getBody().id());
	}

	@Test
	void blankNameIsRejected() {
		AuthResponse admin = registerCompanyAdmin();

		ResponseEntity<String> blank = restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.POST,
				new HttpEntity<>(new CreateExceptionTypeRequest("  "), bearer(admin.accessToken())), String.class);
		assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void crossTenantRowsNeverAppearInLists() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();
		Long typeA = restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.POST,
				new HttpEntity<>(new CreateExceptionTypeRequest("Company A only"), bearer(companyA.accessToken())),
				ExceptionTypeView.class).getBody().id();

		ResponseEntity<List<ExceptionTypeView>> listB = restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.GET, new HttpEntity<>(bearer(companyB.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(listB.getBody()).extracting(ExceptionTypeView::id).doesNotContain(typeA);
	}

	@Test
	void readDoesNotImplyCorrect() {
		AuthResponse admin = registerCompanyAdmin();
		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.ATTENDANCE_READ);

		ResponseEntity<List<ExceptionTypeView>> list = restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.GET, new HttpEntity<>(bearer(reader.accessToken())),
				new ParameterizedTypeReference<>() {
				});
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> createDenied = restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.POST,
				new HttpEntity<>(new CreateExceptionTypeRequest("Denied"), bearer(reader.accessToken())), String.class);
		assertThat(createDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/exception-types", HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	private static String uniquePhone() {
		return "+2016" + System.nanoTime() % 100_000_000L;
	}

}
