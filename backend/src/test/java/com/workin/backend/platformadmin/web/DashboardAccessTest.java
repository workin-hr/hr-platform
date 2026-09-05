package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link DashboardAccess} against {@code HrAccess}, page by page.
 *
 * <p>Pure logic, so this is a plain unit test with no context. The value is in
 * the cases where the three audiences disagree: a rule that is the same for
 * everyone proves nothing about a model whose entire job is telling them
 * apart.
 */
class DashboardAccessTest {

	private static final DashboardSession ADMIN = DashboardSession.admin(0L);
	private static final DashboardSession OWNER = DashboardSession.company(7L);

	private static DashboardSession hrWith(String... permissions) {
		return DashboardSession.hr(70L, 7L, "hr", Set.of(permissions));
	}

	private static final DashboardSession HR_NOTHING = hrWith();

	@Test
	void theAdministratorAndTheOwnerShortCircuitEveryFlag() {
		// hasFullAccess() is checked before the permission set is read, so an
		// unknown flag is granted too -- which is what makes it a
		// short-circuit rather than a lookup with wide defaults.
		for (DashboardSession session : List.of(ADMIN, OWNER)) {
			assertThat(DashboardAccess.hasFullAccess(session)).isTrue();
			assertThat(DashboardAccess.can(session, DashboardAccess.PERM_PAYROLL)).isTrue();
			assertThat(DashboardAccess.can(session, "can_a_flag_that_does_not_exist")).isTrue();
		}
		assertThat(DashboardAccess.hasFullAccess(HR_NOTHING)).isFalse();
	}

	@Test
	void anHrSessionIsGrantedExactlyWhatItsRowHolds() {
		DashboardSession hr = hrWith(DashboardAccess.PERM_BRANCHES, DashboardAccess.PERM_PAYROLL);

		assertThat(DashboardAccess.can(hr, DashboardAccess.PERM_BRANCHES)).isTrue();
		assertThat(DashboardAccess.can(hr, DashboardAccess.PERM_PAYROLL)).isTrue();
		assertThat(DashboardAccess.can(hr, DashboardAccess.PERM_EMPLOYEES)).isFalse();
		assertThat(DashboardAccess.can(hr, "can_a_flag_that_does_not_exist")).isFalse();
	}

	@Test
	void anHrWithNoRowAtAllCanSeeNoGatedPage() {
		// The join in doHrLogin() is a LEFT JOIN in effect: an employee with no
		// hr_permissions row logs in with an empty set rather than failing. The
		// dashboard must then be empty, not open.
		for (String page : List.of("employees", "branches", "payroll", "attendance",
				"requests", "leave_balances", "penalties", "assets", "advances",
				"workforce_planning", "salary_calculator", "company_settings",
				"administrative_decisions", "notifications", "complaints", "reports")) {
			assertThat(DashboardAccess.canViewPage(HR_NOTHING, page))
					.as("page %s", page).isFalse();
		}
		assertThat(DashboardAccess.canViewOrgNav(HR_NOTHING)).isFalse();
		assertThat(DashboardAccess.canViewHrNav(HR_NOTHING)).isFalse();
		assertThat(DashboardAccess.canViewPayrollNav(HR_NOTHING)).isFalse();
	}

	@Test
	void theNineContentPagesAreTheAdministratorsAlone() {
		for (String page : List.of("companies", "content", "app_content", "banners", "faqs",
				"guide_videos", "settings", "phone_countries", "setting_templates")) {
			assertThat(DashboardAccess.canViewPage(ADMIN, page)).as("admin, %s", page).isTrue();
			assertThat(DashboardAccess.canViewPage(OWNER, page)).as("owner, %s", page).isFalse();
			assertThat(DashboardAccess.canViewPage(hrWith(DashboardAccess.PERM_EMPLOYEES), page))
					.as("hr, %s", page).isFalse();
		}
	}

	@Test
	void profileAndChangePasswordAreTheOwnersAndHrsAlone() {
		// The mirror image: these two redirect an administrator away, because
		// the administrator is not a company and has no password on a company
		// row to change.
		for (String page : List.of("profile", "change_password")) {
			assertThat(DashboardAccess.canViewPage(ADMIN, page)).as("admin, %s", page).isFalse();
			assertThat(DashboardAccess.canViewPage(OWNER, page)).as("owner, %s", page).isTrue();
			assertThat(DashboardAccess.canViewPage(HR_NOTHING, page)).as("hr, %s", page).isTrue();
		}
	}

	@Test
	void threePagesAreGatedByAFlagThatIsNotNamedAfterThem() {
		// administrative_decisions, notifications and complaints all hang off
		// can_employees: an HR who may not see employees may not see the
		// records and messages about them either. Easy to port as three
		// separate flags that do not exist.
		DashboardSession withEmployees = hrWith(DashboardAccess.PERM_EMPLOYEES);
		DashboardSession withoutEmployees = hrWith(DashboardAccess.PERM_PAYROLL);
		for (String page : List.of("administrative_decisions", "notifications", "complaints")) {
			assertThat(DashboardAccess.canViewPage(withEmployees, page)).as("granted, %s", page).isTrue();
			assertThat(DashboardAccess.canViewPage(withoutEmployees, page)).as("denied, %s", page).isFalse();
		}
		// And reports hangs off can_dashboard, not a reports flag.
		assertThat(DashboardAccess.canViewPage(hrWith(DashboardAccess.PERM_DASHBOARD), "reports")).isTrue();
		assertThat(DashboardAccess.canViewPage(withEmployees, "reports")).isFalse();
	}

	@Test
	void aGroupOpensWhenAnyOneOfItsPagesDoes() {
		assertThat(DashboardAccess.canViewOrgNav(hrWith(DashboardAccess.PERM_SHIFTS))).isTrue();
		assertThat(DashboardAccess.canViewOrgNav(hrWith(DashboardAccess.PERM_PAYROLL))).isFalse();

		assertThat(DashboardAccess.canViewHrNav(hrWith(DashboardAccess.PERM_ADVANCES))).isTrue();
		assertThat(DashboardAccess.canViewHrNav(hrWith(DashboardAccess.PERM_SHIFTS))).isFalse();

		assertThat(DashboardAccess.canViewPayrollNav(hrWith(DashboardAccess.PERM_ATTENDANCE))).isTrue();
		assertThat(DashboardAccess.canViewPayrollNav(hrWith(DashboardAccess.PERM_ASSETS))).isFalse();
	}

	@Test
	void anUnknownSectionIsDeniedRatherThanDefaulted() {
		// The section helpers end in `default => false`, unlike canViewPage.
		DashboardSession hr = hrWith(DashboardAccess.PERM_EMPLOYEES, DashboardAccess.PERM_PAYROLL);
		assertThat(DashboardAccess.canViewOrgSection(hr, "something_else")).isFalse();
		assertThat(DashboardAccess.canViewHrSection(hr, "something_else")).isFalse();
		assertThat(DashboardAccess.canViewPayrollSection(hr, "something_else")).isFalse();
		// But full access wins even over an unknown section name.
		assertThat(DashboardAccess.canViewOrgSection(ADMIN, "something_else")).isTrue();
	}

	@Test
	void theCompanySettingsSpecialCaseIsUnreachable() {
		// company_settings is in NAV_PERMISSIONS with a real flag, so
		// canViewPage answers can(PERM_SETTINGS) and the isScopedToOneCompany()
		// branch below it never runs. The visible consequence is that an
		// administrator CAN view it -- which the dead branch reads as
		// forbidding. If this ever fails, the branch has come alive and its
		// answer for an administrator has flipped.
		assertThat(DashboardAccess.canViewPage(ADMIN, "company_settings"))
				.as("the live rule is can(PERM_SETTINGS), which full access satisfies")
				.isTrue();
		assertThat(DashboardAccess.canViewPage(hrWith(DashboardAccess.PERM_SETTINGS), "company_settings"))
				.isTrue();
		assertThat(DashboardAccess.canViewPage(HR_NOTHING, "company_settings")).isFalse();
	}

	@Test
	void aPageWithNoRuleAtAllIsAllowed() {
		// The default is allow, and index is the page that relies on it.
		assertThat(DashboardAccess.canViewPage(HR_NOTHING, "index")).isTrue();
		assertThat(DashboardAccess.canViewPage(HR_NOTHING, "a_page_nobody_has_built")).isTrue();
	}

	@Test
	void theGrantedSetReadsAPermissionsRowThePhpWay() {
		// `!empty($perms[$flag])` over a tinyint(1): 1 grants, 0 does not, and
		// NULL -- an INSERT that never named the column -- does not either.
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", 5);
		row.put("employee_id", 70);
		row.put("can_branches", 1);
		row.put("can_departments", 0);
		row.put("can_shifts", null);
		row.put("can_payroll", true);
		row.put("can_assets", "1");
		row.put("can_advances", "0");
		row.put("updated_at", "2026-09-05 10:00:00");

		assertThat(DashboardSession.grantedFrom(row))
				.containsExactlyInAnyOrder("can_branches", "can_payroll", "can_assets");
	}

	@Test
	void nonPermissionColumnsNeverBecomeGrants() {
		// PHP unsets id, employee_id and updated_at before storing the row. The
		// prefix filter does the same job and also survives a column being
		// added to the table -- `id` is truthy, and an id that became a
		// permission name would be a grant nobody wrote.
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", 5);
		row.put("employee_id", 70);
		row.put("updated_at", "2026-09-05 10:00:00");
		assertThat(DashboardSession.grantedFrom(row)).isEmpty();
		assertThat(DashboardSession.grantedFrom(null)).isEmpty();
	}

	@Test
	void anAudienceCannotBeTwoThingsAtOnce() {
		// The whole reason this is an enum: PHP's three session flags are
		// independent booleans, and every login path has to remember to unset
		// the other two.
		assertThat(ADMIN.isAdmin()).isTrue();
		assertThat(ADMIN.isCompany()).isFalse();
		assertThat(ADMIN.isHr()).isFalse();
		assertThat(ADMIN.isScopedToOneCompany()).as("an admin is not bound to one company").isFalse();

		assertThat(OWNER.isScopedToOneCompany()).isTrue();
		assertThat(HR_NOTHING.isScopedToOneCompany()).isTrue();
	}

}
