package com.workin.backend.platformadmin.web;

import java.util.List;
import java.util.Map;

/**
 * {@code HrAccess} ({@code dashboard/includes/hr_access.php}): which dashboard
 * pages and sections a session may see.
 *
 * <p>One class, consulted by the sidebar and by every controller, because the
 * PHP it reproduces is one class consulted the same way. A rule per controller
 * would drift from the sidebar, and the direction of that drift is a link the
 * user can see and cannot follow -- or worse, a page the sidebar hides and the
 * URL still serves.
 *
 * <h2>The shape of the decision</h2>
 * <p>{@link #canViewPage} is authoritative and is what a controller should ask.
 * It reads {@link #NAV_PERMISSIONS}, and a page mapped to no permission falls
 * through to four special cases before defaulting to allow. The section
 * helpers exist because the sidebar groups need "may they see <em>any</em> of
 * this group", which is a different question from "may they open this page".
 *
 * <p>{@link #hasFullAccess} short-circuits everything for an administrator or
 * an owner. Only an HR or manager session ever reaches the permission set.
 */
public final class DashboardAccess {

	public static final String PERM_DASHBOARD = "can_dashboard";
	public static final String PERM_RECENT_ACTIVITIES = "can_recent_activities";
	public static final String PERM_BRANCHES = "can_branches";
	public static final String PERM_DEPARTMENTS = "can_departments";
	public static final String PERM_JOB_TITLES = "can_job_titles";
	public static final String PERM_SHIFTS = "can_shifts";
	public static final String PERM_EMPLOYEES = "can_employees";
	public static final String PERM_REQUESTS = "can_requests";
	public static final String PERM_LEAVE_BALANCES = "can_leave_balances";
	public static final String PERM_PENALTIES = "can_penalties";
	public static final String PERM_ASSETS = "can_assets";
	public static final String PERM_ADVANCES = "can_advances";
	public static final String PERM_WORKFORCE = "can_workforce_planning";
	public static final String PERM_SALARY_CALC = "can_salary_calculator";
	public static final String PERM_ATTENDANCE = "can_attendance";
	public static final String PERM_PAYROLL = "can_payroll";
	public static final String PERM_SETTINGS = "can_company_settings";

	/**
	 * {@code navPermissions()}: page to the permission that gates it, with
	 * {@code null} meaning "no flag of its own".
	 *
	 * <p>A {@code null} here does <b>not</b> mean "anyone": it means
	 * {@link #canViewPage} decides by another rule. Nine of them are
	 * administrator-only content pages and would be wide open if a null were
	 * read as permitted, which is why the map is private and the decision is
	 * one method.
	 *
	 * <p>Two entries are worth naming because they are not what the page name
	 * suggests. {@code administrative_decisions} is gated by
	 * {@code can_employees}, not a flag of its own, and so are
	 * {@code notifications} and {@code complaints} -- an HR who may not see
	 * employees may not see the messages about them either. {@code reports} is
	 * gated by {@code can_dashboard}.
	 */
	private static final Map<String, String> NAV_PERMISSIONS = java.util.Collections.unmodifiableMap(
			new java.util.LinkedHashMap<>() {{
				put("index", null);
				put("companies", null);
				put("employees", PERM_EMPLOYEES);
				put("leave_balances", PERM_LEAVE_BALANCES);
				put("assets", PERM_ASSETS);
				put("workforce_planning", PERM_WORKFORCE);
				put("branches", PERM_BRANCHES);
				put("departments", PERM_DEPARTMENTS);
				put("job_titles", PERM_JOB_TITLES);
				put("shifts", PERM_SHIFTS);
				put("attendance", PERM_ATTENDANCE);
				put("salary_calculator", PERM_SALARY_CALC);
				put("requests", PERM_REQUESTS);
				put("payroll", PERM_PAYROLL);
				put("penalties", PERM_PENALTIES);
				put("administrative_decisions", PERM_EMPLOYEES);
				put("advances", PERM_ADVANCES);
				put("reports", PERM_DASHBOARD);
				put("notifications", PERM_EMPLOYEES);
				put("complaints", PERM_EMPLOYEES);
				put("content", null);
				put("app_content", null);
				put("banners", null);
				put("faqs", null);
				put("guide_videos", null);
				put("settings", null);
				put("company_settings", PERM_SETTINGS);
				put("profile", null);
				put("change_password", null);
			}});

	/** The nine {@code null}-flagged pages that are administrator-only. */
	private static final java.util.Set<String> ADMIN_ONLY_PAGES = java.util.Set.of(
			"companies", "content", "app_content", "banners", "faqs", "guide_videos",
			"settings", "phone_countries", "setting_templates");

	private DashboardAccess() {
	}

	/** {@code hasFullAccess()}: the administrator and the company owner. */
	public static boolean hasFullAccess(DashboardSession session) {
		return session.isAdmin() || session.isCompany();
	}

	/**
	 * {@code can($flag)}.
	 *
	 * <p>The last line is the one to read twice. A session that is neither
	 * full-access nor HR falls back to {@code isAdmin()}, which is already
	 * false by then -- so it denies. Reproduced rather than simplified to
	 * {@code false} because the shapes are the same and the PHP is what the
	 * next reader will compare against.
	 */
	public static boolean can(DashboardSession session, String flag) {
		if (hasFullAccess(session)) {
			return true;
		}
		if (!session.isHr()) {
			return session.isAdmin();
		}
		return session.permissions().contains(flag);
	}

	/** {@code canViewOrgSection()}. */
	public static boolean canViewOrgSection(DashboardSession session, String section) {
		if (hasFullAccess(session)) {
			return true;
		}
		return switch (section) {
			case "branches" -> can(session, PERM_BRANCHES);
			case "departments" -> can(session, PERM_DEPARTMENTS);
			case "job_titles" -> can(session, PERM_JOB_TITLES);
			case "shifts" -> can(session, PERM_SHIFTS);
			default -> false;
		};
	}

	/** {@code canViewHrSection()}. */
	public static boolean canViewHrSection(DashboardSession session, String section) {
		if (hasFullAccess(session)) {
			return true;
		}
		return switch (section) {
			case "employees" -> can(session, PERM_EMPLOYEES);
			case "requests" -> can(session, PERM_REQUESTS);
			case "leave_balances" -> can(session, PERM_LEAVE_BALANCES);
			case "penalties" -> can(session, PERM_PENALTIES);
			case "administrative_decisions" -> can(session, PERM_EMPLOYEES);
			case "assets" -> can(session, PERM_ASSETS);
			case "advances" -> can(session, PERM_ADVANCES);
			case "workforce_planning" -> can(session, PERM_WORKFORCE);
			default -> false;
		};
	}

	/** {@code canViewPayrollSection()}. */
	public static boolean canViewPayrollSection(DashboardSession session, String section) {
		if (hasFullAccess(session)) {
			return true;
		}
		return switch (section) {
			case "salary_calculator" -> can(session, PERM_SALARY_CALC);
			case "attendance" -> can(session, PERM_ATTENDANCE);
			case "payroll" -> can(session, PERM_PAYROLL);
			default -> false;
		};
	}

	private static final List<String> ORG_SECTIONS =
			List.of("branches", "departments", "job_titles", "shifts");

	private static final List<String> HR_SECTIONS = List.of(
			"employees", "requests", "leave_balances", "penalties",
			"administrative_decisions", "assets", "advances", "workforce_planning");

	private static final List<String> PAYROLL_SECTIONS =
			List.of("salary_calculator", "attendance", "payroll");

	/** {@code canViewOrgNav()}: the group header shows when any of its pages does. */
	public static boolean canViewOrgNav(DashboardSession session) {
		return ORG_SECTIONS.stream().anyMatch(section -> canViewOrgSection(session, section));
	}

	/** {@code canViewHrNav()}. */
	public static boolean canViewHrNav(DashboardSession session) {
		return HR_SECTIONS.stream().anyMatch(section -> canViewHrSection(session, section));
	}

	/** {@code canViewPayrollNav()}. */
	public static boolean canViewPayrollNav(DashboardSession session) {
		return PAYROLL_SECTIONS.stream().anyMatch(section -> canViewPayrollSection(session, section));
	}

	/**
	 * {@code canViewPage()}: the authoritative gate, and what a controller asks.
	 *
	 * <p>The default is <b>allow</b>, which is not the insecure-default it
	 * looks like: every page that carries data is either in
	 * {@link #NAV_PERMISSIONS} with a flag or in one of the three special cases
	 * below. The default catches pages with nothing to protect -- and a page
	 * this method has never heard of does not exist to be served, because
	 * {@link AdminPageAvailability} decides that separately from the handler
	 * mapping.
	 */
	public static boolean canViewPage(DashboardSession session, String page) {
		String permission = NAV_PERMISSIONS.get(page);
		if (permission == null) {
			if (ADMIN_ONLY_PAGES.contains(page)) {
				return session.isAdmin();
			}
			// Unreachable, and so is PHP's: `company_settings` is in
			// NAV_PERMISSIONS with a non-null flag, so the lookup above never
			// yields null for it and the branch below never runs. The live
			// answer is `can(PERM_SETTINGS)`, which is true for an
			// administrator -- not the company/HR-only rule this line reads
			// as. Kept because it is in the source being reproduced and
			// removing it would make a later edit to NAV_PERMISSIONS silently
			// change behaviour; pinned as dead by
			// DashboardAccessTest#theCompanySettingsSpecialCaseIsUnreachable.
			if ("company_settings".equals(page)) {
				return session.isScopedToOneCompany() && can(session, PERM_SETTINGS);
			}
			if ("profile".equals(page) || "change_password".equals(page)) {
				return session.isScopedToOneCompany();
			}
			return true;
		}
		return can(session, permission);
	}

}
