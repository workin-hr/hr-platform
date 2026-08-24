package com.workin.legacy.wire;

/**
 * The explicit list of legacy PHP routes whose module reproduces PHP's own
 * guard order inside the controller, and which therefore pass Spring
 * Security's authorization decision unconditionally.
 *
 * <p>Controllers still own method/auth/company ordering. Anything under
 * {@code /apis/**} that is not listed here falls through to authenticated
 * Spring Security handling rather than becoming an accidental public route.
 */
public final class LegacyPhpRoutes {

	public static final String[] CONTROLLER_GUARDED = {
		"/apis/api/employees/**",
		"/apis/api/hr_employees/**",
		"/apis/api/shifts/**",
		"/apis/api/request_types/**",
		"/apis/api/company_official_holidays/**",
		"/apis/api/attendance/**",
		"/apis/api/schedules/**",
		"/apis/api/requests/list.php",
		"/apis/api/requests/one.php",
		"/apis/api/requests/create.php",
		"/apis/api/requests/update.php",
		"/apis/api/requests/delete.php",
		"/apis/api/requests/approve.php",
		"/apis/api/requests/reject.php",
		"/apis/api/leave_balances/**",
	};

	private LegacyPhpRoutes() {
	}
}
