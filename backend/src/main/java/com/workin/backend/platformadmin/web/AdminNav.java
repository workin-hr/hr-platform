package com.workin.backend.platformadmin.web;

import java.util.List;

/**
 * The sidebar's structure, mirroring {@code sidebar_build_menu()} in
 * {@code hr-legacy/dashboard/sidebar/nav_menu.php} -- same groups, same
 * order, same label keys, so the copied stylesheet lays it out the way the
 * dashboard does (ADR-0016).
 *
 * <p>Every page the PHP dashboard has is listed, including the ones this
 * application has not built yet. Those render as disabled items rather than
 * being hidden, because a sidebar that silently omits half the product looks
 * finished when it is not -- during a migration the gap is the most important
 * thing on the screen.
 *
 * <p>Which of them are live is <b>not</b> declared here. {@link
 * AdminPageAvailability} reads it from the handler mapping, so this list
 * cannot claim a page whose controller was never written, was removed, or is
 * scoped to a profile this deployment is not running.
 */
public final class AdminNav {

	/**
	 * @param page        the dashboard's page name, which is also the last URL
	 *                    segment: {@code branches} serves at {@code /admin/branches}
	 * @param labelKey    a key in {@code i18n/admin-messages}
	 * @param icon        a name {@link AdminIcons} knows
	 * @param adminOnly   hidden from company and HR sessions
	 */
	public record Item(String page, String labelKey, String icon, boolean adminOnly) {

		static Item of(String page, String labelKey, String icon) {
			return new Item(page, labelKey, icon, false);
		}

		public String href() {
			return "index".equals(page) ? "/admin" : "/admin/" + page;
		}
	}

	/** @param icon doubles as the {@code data-nav-group} value the sidebar script toggles on */
	public record Group(String icon, String labelKey, List<Item> items) {
	}

	public static final Item HOME = Item.of("index", "nav_home", "home");

	private static final Group ORG = new Group("org", "nav_branches_shifts", List.of(
			new Item("companies", "nav_companies", "companies", true),
			Item.of("branches", "branches", "branch"),
			Item.of("departments", "departments", "department"),
			Item.of("job_titles", "job_titles", "job"),
			Item.of("shifts", "shifts", "shift")));

	private static final Group HR = new Group("hr", "nav_hr_affairs", List.of(
			Item.of("employees", "nav_employees", "employees"),
			Item.of("requests", "nav_employee_requests", "requests"),
			Item.of("leave_balances", "nav_leave_balances", "leave"),
			Item.of("penalties", "nav_penalties", "penalties"),
			Item.of("administrative_decisions", "nav_administrative_decisions", "requests"),
			Item.of("assets", "nav_assets", "asset"),
			Item.of("advances", "nav_advances", "advances"),
			Item.of("workforce_planning", "nav_workforce_planning", "workforce")));

	private static final Group PAYROLL = new Group("payroll_group", "nav_salaries_wages", List.of(
			Item.of("salary_calculator", "nav_salary_calculator", "calculator"),
			Item.of("attendance", "nav_attendance", "attendance"),
			Item.of("payroll", "nav_payroll", "payroll")));

	private static final Group COMMS = new Group("comms_group", "nav_comms_content", List.of(
			Item.of("notifications", "nav_notifications", "notifications"),
			Item.of("complaints", "nav_complaints", "complaints"),
			Item.of("app_content", "nav_app_content", "app_content"),
			Item.of("banners", "nav_banners", "banners"),
			Item.of("faqs", "nav_faqs", "faqs"),
			Item.of("phone_countries", "nav_phone_countries", "countries"),
			Item.of("setting_templates", "nav_setting_templates", "settings"),
			Item.of("settings", "nav_settings", "settings"),
			Item.of("company_settings", "nav_company_settings", "settings"),
			Item.of("change_password", "change_password", "profile")));

	public static final List<Group> GROUPS = List.of(ORG, HR, PAYROLL, COMMS);

	private AdminNav() {
	}

	/** Every item across every group, plus {@link #HOME}. */
	public static List<Item> allItems() {
		return java.util.stream.Stream.concat(
						java.util.stream.Stream.of(HOME),
						GROUPS.stream().flatMap(group -> group.items().stream()))
				.toList();
	}

	/** True when {@code page} is the one being rendered, for the {@code active} class. */
	public static boolean isCurrent(Item item, String currentPage) {
		return item.page().equals(currentPage);
	}

	/** A group opens when it holds the current page, exactly as {@code $orgNavOpen} does. */
	public static boolean isOpen(Group group, String currentPage) {
		return group.items().stream().anyMatch(item -> item.page().equals(currentPage));
	}

}
