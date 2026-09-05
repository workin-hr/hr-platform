package com.workin.backend.platformadmin.web;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code sidebar_build_menu()} ({@code dashboard/sidebar/nav_menu.php}): the
 * sidebar's entries for one session, same groups, same order, same label keys,
 * so the copied stylesheet lays it out the way the dashboard does (ADR-0016).
 *
 * <h2>Built per session, not listed once</h2>
 * <p>This used to be a static list with an {@code adminOnly} flag per item.
 * That was two things wrong at once. The flag was a second copy of a rule
 * {@link DashboardAccess} already owns, and a static list cannot express what
 * the PHP actually does: the comms group holds <em>different pages</em>
 * depending on who is looking -- an administrator gets FAQs, guide videos,
 * dial codes and settings; an owner or HR gets company settings instead.
 *
 * <p>The static list also drifted. It carried {@code app_content},
 * {@code setting_templates} and {@code change_password}, none of which PHP's
 * sidebar renders -- the first two are 302 redirects into the settings page
 * and the third is reached from the profile page -- and it was missing
 * {@code guide_videos}, which PHP does render.
 *
 * <h2>Two different filters, deliberately</h2>
 * <p>Whether an entry is <em>permitted</em> is {@link DashboardAccess}'s
 * answer and decides whether it is in the list at all. Whether it is
 * <em>built</em> is {@link AdminPageAvailability}'s, read from the handler
 * mapping, and decides whether it renders as a link or as a disabled row. A
 * page the viewer may not see is absent; a page nobody has ported yet is
 * visible and dead, because during a migration the gap is the most useful
 * thing on the screen.
 */
public final class AdminNav {

	/**
	 * @param page     the dashboard's page name, which is also the last URL
	 *                 segment: {@code branches} serves at {@code /admin/branches}
	 * @param labelKey a key in {@code i18n/admin-messages}
	 * @param icon     a name {@link AdminIcons} knows
	 */
	public record Item(String page, String labelKey, String icon) {

		public String href() {
			return "index".equals(page) ? "/admin" : "/admin/" + page;
		}
	}

	/** @param icon doubles as the {@code data-nav-group} value the sidebar script toggles on */
	public record Group(String icon, String labelKey, List<Item> items) {
	}

	public static final Item HOME = new Item("index", "nav_home", "home");

	/**
	 * The pages of each group, for the "is this group open" test.
	 *
	 * <p>These are PHP's {@code $orgPages} and friends, and they are
	 * <b>not</b> the same as the rendered children: {@code $commsPages} lists
	 * eleven pages while {@code $commsChildren} renders at most seven. A page
	 * that is in the group but not in its menu still opens the group when it is
	 * the current page -- {@code change_password}, reached from the profile
	 * page, is exactly that case.
	 */
	private static final List<String> ORG_PAGES =
			List.of("companies", "branches", "departments", "job_titles", "shifts");

	private static final List<String> HR_PAGES = List.of(
			"employees", "requests", "leave_balances", "penalties",
			"administrative_decisions", "assets", "advances", "workforce_planning");

	private static final List<String> PAYROLL_PAGES =
			List.of("salary_calculator", "attendance", "payroll");

	private static final List<String> COMMS_PAGES = List.of(
			"notifications", "complaints", "app_content", "banners", "faqs", "guide_videos",
			"phone_countries", "setting_templates", "settings", "company_settings",
			"change_password");

	private AdminNav() {
	}

	/** The four groups as this session sees them, empty groups included. */
	public static List<Group> groups(DashboardSession session) {
		return List.of(
				new Group("org", "nav_branches_shifts", orgChildren(session)),
				new Group("hr", "nav_hr_affairs", hrChildren(session)),
				new Group("payroll_group", "nav_salaries_wages", payrollChildren(session)),
				new Group("comms_group", "nav_comms_content", commsChildren(session)));
	}

	/** {@code $orgChildren}: companies first and only for an administrator. */
	private static List<Item> orgChildren(DashboardSession session) {
		List<Item> items = new ArrayList<>();
		if (session.isAdmin()) {
			items.add(new Item("companies", "nav_companies", "companies"));
		}
		addIf(items, session, DashboardAccess::canViewOrgSection, List.of(
				new Item("branches", "branches", "branch"),
				new Item("departments", "departments", "department"),
				new Item("job_titles", "job_titles", "job"),
				new Item("shifts", "shifts", "shift")));
		return List.copyOf(items);
	}

	/** {@code $hrChildren}. */
	private static List<Item> hrChildren(DashboardSession session) {
		List<Item> items = new ArrayList<>();
		addIf(items, session, DashboardAccess::canViewHrSection, List.of(
				new Item("employees", "nav_employees", "employees"),
				new Item("requests", "nav_employee_requests", "requests"),
				new Item("leave_balances", "nav_leave_balances", "leave"),
				new Item("penalties", "nav_penalties", "penalties"),
				new Item("administrative_decisions", "nav_administrative_decisions", "requests"),
				new Item("assets", "nav_assets", "asset"),
				new Item("advances", "nav_advances", "advances"),
				new Item("workforce_planning", "nav_workforce_planning", "workforce")));
		return List.copyOf(items);
	}

	/** {@code $payrollChildren}. */
	private static List<Item> payrollChildren(DashboardSession session) {
		List<Item> items = new ArrayList<>();
		addIf(items, session, DashboardAccess::canViewPayrollSection, List.of(
				new Item("salary_calculator", "nav_salary_calculator", "calculator"),
				new Item("attendance", "nav_fingerprints", "attendance"),
				new Item("payroll", "nav_payroll", "payroll")));
		return List.copyOf(items);
	}

	/**
	 * {@code $commsChildren}: three entries everyone gets, then four for an
	 * administrator or one for a scoped session, then filtered by
	 * {@code canViewPage}.
	 *
	 * <p>The complaints label is the owner's own: {@code nav_complaints}
	 * ("Employee Complaints") for a company owner, {@code nav_complaints_admin}
	 * ("Complaints & Suggestions") for everyone else -- including an HR
	 * session, which reads the administrator's wording.
	 */
	private static List<Item> commsChildren(DashboardSession session) {
		List<Item> items = new ArrayList<>();
		items.add(new Item("notifications", "nav_notifications", "notifications"));
		items.add(new Item("complaints",
				session.isCompany() ? "nav_complaints" : "nav_complaints_admin", "complaints"));
		items.add(new Item("banners", "nav_banners", "banners"));
		if (session.isAdmin()) {
			items.add(new Item("faqs", "nav_faqs", "faqs"));
			items.add(new Item("guide_videos", "nav_guide_videos", "guide_videos"));
			items.add(new Item("phone_countries", "nav_phone_countries", "countries"));
			items.add(new Item("settings", "nav_settings", "settings"));
		} else if (session.isScopedToOneCompany()) {
			items.add(new Item("company_settings", "nav_company_settings", "settings"));
		}
		items.removeIf(item -> !DashboardAccess.canViewPage(session, item.page()));
		return List.copyOf(items);
	}

	private interface SectionCheck {
		boolean test(DashboardSession session, String section);
	}

	/** The section name is the page name for every one of these. */
	private static void addIf(
			List<Item> into, DashboardSession session, SectionCheck check, List<Item> candidates) {
		for (Item item : candidates) {
			if (check.test(session, item.page())) {
				into.add(item);
			}
		}
	}

	/** True when {@code page} is the one being rendered, for the {@code active} class. */
	public static boolean isCurrent(Item item, String currentPage) {
		return item.page().equals(currentPage);
	}

	/**
	 * {@code $orgNavOpen} and friends: a group opens when the current page
	 * belongs to it, whether or not that page is one of its rendered children.
	 */
	public static boolean isOpen(Group group, String currentPage) {
		return switch (group.icon()) {
			case "org" -> ORG_PAGES.contains(currentPage);
			case "hr" -> HR_PAGES.contains(currentPage);
			case "payroll_group" -> PAYROLL_PAGES.contains(currentPage);
			case "comms_group" -> COMMS_PAGES.contains(currentPage);
			default -> false;
		};
	}

}
