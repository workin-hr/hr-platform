package com.workin.backend.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.tenancy.AuthorizationContext;
import com.workin.backend.tenancy.TenantContextService;

/**
 * F-17's core for what this slice ships: ADR-0010 Dimension 3's
 * precedence chain (explicit deny -> explicit allow -> role-granted ->
 * deny by default), asserted through the same entry point every request
 * uses (TenantContextService.establishContext), never through a
 * private evaluation shortcut. Fixtures are inserted through the
 * privileged DataSource, the same pattern as the rest of the suite.
 */
class PermissionEvaluationTest extends AbstractIntegrationTest {

	@Autowired
	private TenantContextService tenantContextService;

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	private record Fixture(Long identityId, Long membershipId, Long companyId) {
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(flywayDataSource);
	}

	private Fixture createMembershipWithRole(String role) {
		JdbcTemplate jdbc = jdbc();
		String phone = "+2033" + System.nanoTime() % 100_000_000L;
		Long companyId = jdbc.queryForObject(
				"INSERT INTO companies (name, phone) VALUES ('Perm Co', ?) RETURNING id", Long.class, phone);
		Long identityId = jdbc.queryForObject(
				"INSERT INTO identities (phone, password_hash) VALUES (?, 'x') RETURNING id", Long.class, phone);
		Long membershipId = jdbc.queryForObject(
				"INSERT INTO tenant_memberships (identity_id, company_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
				Long.class, identityId, companyId);
		jdbc.update(
				"INSERT INTO membership_roles (membership_id, company_id, role) VALUES (?, ?, ?)",
				membershipId, companyId, role);
		return new Fixture(identityId, membershipId, companyId);
	}

	private void insertOverride(Fixture fixture, String permissionKey, String effect) {
		jdbc().update(
				"INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect) "
						+ "SELECT ?, ?, p.id, ? FROM permissions p WHERE p.permission_key = ?",
				fixture.membershipId(), fixture.companyId(), effect, permissionKey);
	}

	private AuthorizationContext contextFor(Fixture fixture) {
		return tenantContextService.establishContext(
				fixture.identityId(), fixture.membershipId(), fixture.companyId());
	}

	@Test
	void companyAdminIsRoleGrantedEveryTenantPermissionButNoPlatformPermission() {
		Fixture admin = createMembershipWithRole("COMPANY_ADMIN");

		AuthorizationContext context = contextFor(admin);

		assertThat(context.hasPermission(PermissionKeys.EMPLOYEES_READ)).isTrue();
		assertThat(context.hasPermission(PermissionKeys.PAYROLL_RUN)).isTrue();
		assertThat(context.hasPermission(PermissionKeys.PLATFORM_COMPANIES_READ)).isFalse();
		assertThat(context.hasPermission(PermissionKeys.PLATFORM_COMPANIES_DELETE)).isFalse();
	}

	@Test
	void explicitDenyBeatsARoleGrant() {
		Fixture admin = createMembershipWithRole("COMPANY_ADMIN");
		insertOverride(admin, PermissionKeys.EMPLOYEES_READ, "DENY");

		AuthorizationContext context = contextFor(admin);

		assertThat(context.hasPermission(PermissionKeys.EMPLOYEES_READ)).isFalse();
		// The deny is per-permission, not a role wipe.
		assertThat(context.hasPermission(PermissionKeys.EMPLOYEES_MANAGE)).isTrue();
	}

	@Test
	void explicitAllowGrantsBeyondTheRoleBundle() {
		Fixture hr = createMembershipWithRole("HR");
		insertOverride(hr, PermissionKeys.EMPLOYEES_READ, "ALLOW");

		AuthorizationContext context = contextFor(hr);

		assertThat(context.hasPermission(PermissionKeys.EMPLOYEES_READ)).isTrue();
	}

	@Test
	void noRuleAnywhereMeansDenyByDefault() {
		// HR deliberately has an empty default bundle -- legacy HR
		// capability was entirely per-employee (the hr_permissions
		// matrix), migrated as ALLOW overrides, never a role default.
		Fixture hr = createMembershipWithRole("HR");

		AuthorizationContext context = contextFor(hr);

		assertThat(context.hasPermission(PermissionKeys.EMPLOYEES_READ)).isFalse();
		assertThat(context.permissions()).isEmpty();
	}

}
