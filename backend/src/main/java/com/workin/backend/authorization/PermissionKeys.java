package com.workin.backend.authorization;

/**
 * Typed constants for every key in the canonical permission catalog
 * (V4's {@code permissions} table -- the single source of truth,
 * docs/architecture/authorization-model.md §3/§7). This class only
 * mirrors the catalog for compile-time-safe {@link RequiresPermission}
 * references; PermissionCatalogSyncTest enforces an exact
 * bidirectional match, so neither side can drift. Never introduce a
 * permission string anywhere else in the codebase.
 */
public final class PermissionKeys {

	public static final String REPORTS_READ = "reports.read";
	public static final String ACTIVITIES_READ = "activities.read";
	public static final String BRANCHES_READ = "branches.read";
	public static final String BRANCHES_MANAGE = "branches.manage";
	public static final String DEPARTMENTS_READ = "departments.read";
	public static final String DEPARTMENTS_MANAGE = "departments.manage";
	public static final String JOB_TITLES_READ = "job_titles.read";
	public static final String JOB_TITLES_MANAGE = "job_titles.manage";
	public static final String SHIFTS_READ = "shifts.read";
	public static final String SHIFTS_MANAGE = "shifts.manage";
	public static final String SCHEDULES_READ = "schedules.read";
	public static final String SCHEDULES_MANAGE = "schedules.manage";
	public static final String LEAVE_BALANCES_READ = "leave_balances.read";
	public static final String LEAVE_BALANCES_MANAGE = "leave_balances.manage";
	public static final String ASSETS_READ = "assets.read";
	public static final String ASSETS_MANAGE = "assets.manage";
	public static final String ADVANCES_READ = "advances.read";
	public static final String ADVANCES_MANAGE = "advances.manage";
	public static final String ADVANCES_APPROVE = "advances.approve";
	public static final String WORKFORCE_PLANNING_READ = "workforce_planning.read";
	public static final String WORKFORCE_PLANNING_MANAGE = "workforce_planning.manage";
	public static final String SALARY_CALCULATOR_READ = "salary_calculator.read";
	public static final String COMPANY_SETTINGS_READ = "company.settings.read";
	public static final String COMPANY_SETTINGS_MANAGE = "company.settings.manage";
	public static final String EMPLOYEES_READ = "employees.read";
	public static final String EMPLOYEES_MANAGE = "employees.manage";
	public static final String EMPLOYEES_DECISIONS_MANAGE = "employees.decisions.manage";
	public static final String NOTIFICATIONS_MANAGE = "notifications.manage";
	public static final String COMPLAINTS_MANAGE = "complaints.manage";
	public static final String ATTENDANCE_READ = "attendance.read";
	public static final String ATTENDANCE_CORRECT = "attendance.correct";
	public static final String REQUESTS_READ = "requests.read";
	public static final String REQUESTS_APPROVE = "requests.approve";
	public static final String REQUESTS_MANAGE = "requests.manage";
	public static final String PAYROLL_READ = "payroll.read";
	public static final String PAYROLL_RUN = "payroll.run";
	public static final String PENALTIES_READ = "penalties.read";
	public static final String PENALTIES_MANAGE = "penalties.manage";
	public static final String MEMBERS_READ = "members.read";
	public static final String MEMBERS_MANAGE = "members.manage";
	public static final String HOLIDAYS_READ = "holidays.read";
	public static final String HOLIDAYS_MANAGE = "holidays.manage";

	public static final String PLATFORM_COMPANIES_READ = "platform.companies.read";
	public static final String PLATFORM_COMPANIES_APPROVE = "platform.companies.approve";
	public static final String PLATFORM_COMPANIES_SUSPEND = "platform.companies.suspend";
	public static final String PLATFORM_COMPANIES_DELETE = "platform.companies.delete";

	private PermissionKeys() {
	}

}
