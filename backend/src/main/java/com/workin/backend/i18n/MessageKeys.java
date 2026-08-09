package com.workin.backend.i18n;

/**
 * Typed constants for every key in the i18n catalogs
 * (src/main/resources/i18n/messages*.properties).
 * MessageCatalogSyncTest enforces that each constant resolves in the
 * English base and that every translation has full key parity. Never
 * introduce a message string anywhere else.
 */
public final class MessageKeys {

	public static final String ERROR_NOT_FOUND = "error.not_found";
	public static final String ERROR_FORBIDDEN = "error.forbidden";
	public static final String ERROR_UNAUTHORIZED = "error.unauthorized";
	public static final String ERROR_CONFLICT = "error.conflict";
	public static final String ERROR_BAD_REQUEST = "error.bad_request";
	public static final String ERROR_VALIDATION = "error.validation";

	public static final String COMPANY_SETTINGS_CONCURRENT_WRITE = "company_settings.concurrent_write";
	public static final String EMPLOYEES_PHONE_IN_USE = "employees.phone_in_use";
	public static final String AUTH_INVALID_REFRESH_TOKEN = "auth.invalid_refresh_token";
	public static final String AUTH_INVALID_CREDENTIALS = "auth.invalid_credentials";
	public static final String AUTH_NO_ACTIVE_MEMBERSHIP = "auth.no_active_membership";
	public static final String AUTH_PHONE_ALREADY_REGISTERED = "auth.phone_already_registered";
	public static final String MEMBERS_ALREADY_PRESENT = "members.already_present";
	public static final String MEMBERS_OWN_MEMBERSHIP = "members.own_membership";
	public static final String MEMBERS_LAST_ADMIN = "members.last_admin";
	public static final String ORGANIZATION_BRANCH_REFERENCED = "organization.branch_referenced";
	public static final String ORGANIZATION_DEPARTMENT_REFERENCED = "organization.department_referenced";
	public static final String ORGANIZATION_JOB_TITLE_REFERENCED = "organization.job_title_referenced";
	public static final String ORGANIZATION_SHIFT_REFERENCED = "organization.shift_referenced";
	public static final String PAYROLL_BATCH_EXISTS = "payroll.batch_exists";
	public static final String PAYROLL_DAILY_WAGE_REQUIRED = "payroll.daily_wage_required";
	public static final String REQUESTS_INVALID_DATE_RANGE = "requests.invalid_date_range";
	public static final String REQUESTS_INSUFFICIENT_BALANCE = "requests.insufficient_balance";
	public static final String SCHEDULE_INVALID_RANGE = "schedule.invalid_range";
	public static final String SCHEDULE_RANGE_EXCEEDS_MAX = "schedule.range_exceeds_max";
	public static final String SCHEDULE_CONCURRENT_WRITE = "schedule.concurrent_write";
	public static final String AUTH_MULTIPLE_MEMBERSHIPS = "auth.multiple_memberships";
	public static final String SCHEDULE_NO_ASSIGNMENT = "schedule.no_assignment";
	public static final String SCHEDULE_WEEKLY_REST = "schedule.weekly_rest";
	public static final String REQUESTS_LEAVE_BALANCE_EXISTS = "requests.leave_balance_exists";
	public static final String ATTENDANCE_EXCEPTION_SHAPE_REQUIRES_DATE = "attendance.exception_shape_requires_date";
	public static final String ATTENDANCE_EXCEPTION_SHAPE_FORBIDS_PUNCH = "attendance.exception_shape_forbids_punch";
	public static final String ATTENDANCE_PUNCH_SHAPE_REQUIRES_CHECKIN = "attendance.punch_shape_requires_checkin";
	public static final String ATTENDANCE_PUNCH_SHAPE_FORBIDS_DATE = "attendance.punch_shape_forbids_date";

	private MessageKeys() {
	}


	public static final String HOLIDAYS_NAME_REQUIRED = "holidays.name_required";
	public static final String HOLIDAYS_DATES_REQUIRED = "holidays.dates_required";
	public static final String HOLIDAYS_DATE_ALREADY_TAKEN = "holidays.date_already_taken";
}
