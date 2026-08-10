package com.workin.backend.companysettings;

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
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.authorization.PermissionKeys;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.LoginRequest;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Typed company-settings surface (V27, the owner-confirmed EAV
 * collapse). Null is a real value everywhere: "unset -- consumers
 * apply the legacy fallback". No path ids exist on this surface, so
 * the cross-tenant probe class is structurally absent -- isolation is
 * asserted through both companies' own GET/PUT round trips instead.
 */
class CompanySettingsFlowTest extends AbstractIntegrationTest {

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
				new RegisterCompanyRequest("Settings Co", uniquePhone(), "correct horse battery staple"),
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

	private ResponseEntity<CompanySettingsView> getSettings(String accessToken) {
		return restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.GET,
				new HttpEntity<>(bearer(accessToken)), CompanySettingsView.class);
	}

	private ResponseEntity<CompanySettingsView> putSettings(String accessToken, UpdateCompanySettingsRequest body) {
		return restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(body, bearer(accessToken)), CompanySettingsView.class);
	}

	@Test
	void getWithNoRowIsAllNullAndPutUpsertsExactlyOneRow() {
		AuthResponse admin = registerCompanyAdmin();

		ResponseEntity<CompanySettingsView> empty = getSettings(admin.accessToken());
		assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(empty.getBody().monthStartDay()).isNull();
		assertThat(empty.getBody().monthEndDay()).isNull();
		assertThat(empty.getBody().weeklyOffDays()).isNull();
		assertThat(empty.getBody().overtimeRate()).isNull();
		assertThat(empty.getBody().monthlyLeaveAccrual()).isNull();
		assertThat(empty.getBody().payOvertime()).isNull();

		ResponseEntity<CompanySettingsView> updated = putSettings(admin.accessToken(),
				new UpdateCompanySettingsRequest((short) 26, (short) 25, "Fri,Sat",
						new BigDecimal("1.50"), new BigDecimal("15.5"), false));
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updated.getBody().monthStartDay()).isEqualTo((short) 26);
		assertThat(updated.getBody().monthEndDay()).isEqualTo((short) 25);
		assertThat(updated.getBody().weeklyOffDays()).isEqualTo("Fri,Sat");
		assertThat(updated.getBody().payOvertime()).isFalse();
		assertThat(getSettings(admin.accessToken()).getBody().monthlyLeaveAccrual())
				.isEqualByComparingTo("15.5");

		// Second PUT updates the same row -- start unset again.
		ResponseEntity<CompanySettingsView> second = putSettings(admin.accessToken(),
				new UpdateCompanySettingsRequest(null, (short) 25, "Fri,Sat",
						new BigDecimal("1.50"), new BigDecimal("15.5"), null));
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody().monthStartDay()).isNull();

		Long rowCount = jdbc().queryForObject(
				"SELECT COUNT(*) FROM company_settings WHERE company_id = ?", Long.class, admin.companyId());
		assertThat(rowCount).isEqualTo(1L);
		Object storedStart = jdbc().queryForObject(
				"SELECT month_start_day FROM company_settings WHERE company_id = ?", Object.class, admin.companyId());
		assertThat(storedStart).isNull();
	}

	@Test
	void outOfRangeValuesAreRejected() {
		AuthResponse admin = registerCompanyAdmin();

		ResponseEntity<String> dayZero = restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(new UpdateCompanySettingsRequest((short) 0, null, null, null, null, null),
						bearer(admin.accessToken())),
				String.class);
		ResponseEntity<String> dayThirtyTwo = restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(new UpdateCompanySettingsRequest(null, (short) 32, null, null, null, null),
						bearer(admin.accessToken())),
				String.class);
		ResponseEntity<String> negativeAccrual = restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(new UpdateCompanySettingsRequest(null, null, null, null, new BigDecimal("-1.0"), null),
						bearer(admin.accessToken())),
				String.class);

		assertThat(dayZero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(dayThirtyTwo.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(negativeAccrual.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void companiesNeverSeeEachOthersSettings() {
		AuthResponse companyA = registerCompanyAdmin();
		AuthResponse companyB = registerCompanyAdmin();

		putSettings(companyA.accessToken(),
				new UpdateCompanySettingsRequest((short) 26, (short) 25, null, null, null, null));
		putSettings(companyB.accessToken(),
				new UpdateCompanySettingsRequest((short) 1, null, "Sun", null, new BigDecimal("30.0"), null));

		CompanySettingsView viewA = getSettings(companyA.accessToken()).getBody();
		CompanySettingsView viewB = getSettings(companyB.accessToken()).getBody();
		assertThat(viewA.monthStartDay()).isEqualTo((short) 26);
		assertThat(viewA.weeklyOffDays()).isNull();
		assertThat(viewB.monthStartDay()).isEqualTo((short) 1);
		assertThat(viewB.weeklyOffDays()).isEqualTo("Sun");
	}

	@Test
	void readDoesNotImplyManage() {
		AuthResponse admin = registerCompanyAdmin();
		HrFixture reader = loginHrMember(admin.companyId());
		allowPermission(reader, PermissionKeys.COMPANY_SETTINGS_READ);

		assertThat(getSettings(reader.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> putDenied = restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.PUT,
				new HttpEntity<>(new UpdateCompanySettingsRequest((short) 1, null, null, null, null, null),
						bearer(reader.accessToken())),
				String.class);
		assertThat(putDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unauthenticatedAccessNeverSucceeds() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/api/tenant/company-settings", HttpMethod.GET, new HttpEntity<>(bearer(null)), String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
	}

	private static String uniquePhone() {
		return "+2021" + System.nanoTime() % 100_000_000L;
	}

}
