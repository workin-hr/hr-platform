package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link AdminNav} against {@code sidebar_build_menu()}.
 *
 * <p>The cases are the ones a static list got wrong: the comms group holding
 * different pages per audience, the three pages PHP's sidebar deliberately
 * does not render, and a group that opens for a page it does not list.
 */
class AdminNavTest {

	private static final DashboardSession ADMIN = DashboardSession.admin(0L);
	private static final DashboardSession OWNER = DashboardSession.company(7L);

	private static DashboardSession hrWith(String... permissions) {
		return DashboardSession.hr(70L, 7L, "hr", Set.of(permissions));
	}

	private static List<String> pagesOf(DashboardSession session, String groupIcon) {
		return AdminNav.groups(session).stream()
				.filter(group -> group.icon().equals(groupIcon))
				.findFirst().orElseThrow()
				.items().stream().map(AdminNav.Item::page).toList();
	}

	private static List<String> allPages(DashboardSession session) {
		return AdminNav.groups(session).stream()
				.flatMap(group -> group.items().stream())
				.map(AdminNav.Item::page).toList();
	}

	@Test
	void theAdministratorSeesTheFullMenuInPhpsOrder() {
		assertThat(pagesOf(ADMIN, "org"))
				.containsExactly("companies", "branches", "departments", "job_titles", "shifts");
		assertThat(pagesOf(ADMIN, "hr")).containsExactly(
				"employees", "requests", "leave_balances", "penalties",
				"administrative_decisions", "assets", "advances", "workforce_planning");
		assertThat(pagesOf(ADMIN, "payroll_group"))
				.containsExactly("salary_calculator", "attendance", "payroll");
		assertThat(pagesOf(ADMIN, "comms_group")).containsExactly(
				"notifications", "complaints", "banners", "faqs", "guide_videos",
				"phone_countries", "settings");
	}

	@Test
	void theCommsGroupHoldsDifferentPagesForAnOwner() {
		// Not a filtered version of the administrator's list: company_settings
		// is an entry the administrator never has.
		// banners is added unconditionally in PHP and then removed by the
		// canViewPage filter, because it is one of the nine administrator-only
		// content pages. The unconditional add is not the rule; the filter is.
		assertThat(pagesOf(OWNER, "comms_group"))
				.containsExactly("notifications", "complaints", "company_settings");
		assertThat(pagesOf(OWNER, "org"))
				.as("companies is the administrator's alone")
				.containsExactly("branches", "departments", "job_titles", "shifts");
	}

	@Test
	void theThreeRedirectAndProfilePagesAreNeverInTheSidebar() {
		// app_content and setting_templates are 302s into the settings page,
		// and change_password is reached from the profile page. A static list
		// carried all three as disabled rows the dashboard never shows.
		for (DashboardSession session : List.of(ADMIN, OWNER, hrWith(DashboardAccess.PERM_EMPLOYEES))) {
			assertThat(allPages(session))
					.doesNotContain("app_content", "setting_templates", "change_password", "profile");
		}
	}

	@Test
	void guideVideosIsInTheAdministratorsMenu() {
		// Absent from the static list, present in PHP -- and the reason five
		// of its strings were missing from the catalog too.
		assertThat(pagesOf(ADMIN, "comms_group")).contains("guide_videos");
		assertThat(pagesOf(OWNER, "comms_group")).doesNotContain("guide_videos");
	}

	@Test
	void anHrSeesOnlyWhatItsPermissionsAllow() {
		DashboardSession hr = hrWith(DashboardAccess.PERM_BRANCHES, DashboardAccess.PERM_PAYROLL);

		assertThat(pagesOf(hr, "org")).containsExactly("branches");
		assertThat(pagesOf(hr, "hr")).isEmpty();
		assertThat(pagesOf(hr, "payroll_group")).containsExactly("payroll");
		// notifications and complaints hang off can_employees, which this HR
		// lacks; banners is administrator-only; company_settings needs
		// can_company_settings. Nothing survives.
		assertThat(pagesOf(hr, "comms_group")).isEmpty();
	}

	@Test
	void anHrWithNothingGetsAnEmptyMenuNotAFullOne() {
		// The direction a permission model must fail in. Every group is empty,
		// so the sidebar renders no group headers at all.
		DashboardSession hr = hrWith();
		assertThat(allPages(hr)).isEmpty();
		assertThat(AdminNav.groups(hr).stream().filter(group -> !group.items().isEmpty()))
				.isEmpty();
	}

	@Test
	void theComplaintsLabelIsTheOwnersOwnWording() {
		AdminNav.Item ownerItem = AdminNav.groups(OWNER).stream()
				.flatMap(group -> group.items().stream())
				.filter(item -> item.page().equals("complaints")).findFirst().orElseThrow();
		AdminNav.Item adminItem = AdminNav.groups(ADMIN).stream()
				.flatMap(group -> group.items().stream())
				.filter(item -> item.page().equals("complaints")).findFirst().orElseThrow();

		assertThat(ownerItem.labelKey()).isEqualTo("nav_complaints");
		assertThat(adminItem.labelKey()).isEqualTo("nav_complaints_admin");
		// An HR session reads the administrator's wording, not the owner's.
		AdminNav.Item hrItem = AdminNav.groups(hrWith(DashboardAccess.PERM_EMPLOYEES)).stream()
				.flatMap(group -> group.items().stream())
				.filter(item -> item.page().equals("complaints")).findFirst().orElseThrow();
		assertThat(hrItem.labelKey()).isEqualTo("nav_complaints_admin");
	}

	@Test
	void aGroupOpensForAPageItDoesNotList() {
		// $commsPages has eleven entries and $commsChildren renders at most
		// seven. change_password is in the group and never in its menu, so
		// landing on it must still open the group the user came from.
		AdminNav.Group comms = AdminNav.groups(OWNER).stream()
				.filter(group -> group.icon().equals("comms_group")).findFirst().orElseThrow();

		assertThat(AdminNav.isOpen(comms, "change_password")).isTrue();
		assertThat(comms.items().stream().map(AdminNav.Item::page))
				.doesNotContain("change_password");
		assertThat(AdminNav.isOpen(comms, "branches")).isFalse();
	}

	@Test
	void eachGroupOpensOnlyForItsOwnPages() {
		List<AdminNav.Group> groups = AdminNav.groups(ADMIN);
		for (AdminNav.Group group : groups) {
			for (AdminNav.Item item : group.items()) {
				assertThat(AdminNav.isOpen(group, item.page()))
						.as("%s should open for %s", group.icon(), item.page()).isTrue();
				for (AdminNav.Group other : groups) {
					if (!other.icon().equals(group.icon())) {
						assertThat(AdminNav.isOpen(other, item.page()))
								.as("%s must not open for %s", other.icon(), item.page()).isFalse();
					}
				}
			}
		}
	}

	@Test
	void theHomeItemLinksToTheBareAdminPath() {
		assertThat(AdminNav.HOME.href()).isEqualTo("/admin");
		assertThat(new AdminNav.Item("branches", "branches", "branch").href())
				.isEqualTo("/admin/branches");
	}

	@Test
	void everyIconNamedInTheMenuIsOneAdminIconsKnows() {
		// A name AdminIcons does not have renders as nothing, which looks like
		// a styling bug rather than a typo.
		for (DashboardSession session : List.of(ADMIN, OWNER)) {
			for (AdminNav.Group group : AdminNav.groups(session)) {
				assertThat(AdminIcons.of(group.icon())).as("group %s", group.icon()).isNotEmpty();
				for (AdminNav.Item item : group.items()) {
					assertThat(AdminIcons.of(item.icon())).as("item %s", item.page()).isNotEmpty();
				}
			}
		}
		assertThat(AdminIcons.of(AdminNav.HOME.icon())).isNotEmpty();
	}

}
