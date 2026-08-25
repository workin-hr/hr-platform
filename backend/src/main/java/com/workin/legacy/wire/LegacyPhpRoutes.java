package com.workin.legacy.wire;

/**
 * The explicit list of legacy PHP routes whose module reproduces PHP's own
 * guard order inside the controller, and which therefore pass Spring
 * Security's authorization decision unconditionally.
 *
 * <h2>Why this exists</h2>
 * <p>Legacy endpoints check the HTTP method <em>before</em> authenticating:
 *
 * <pre>
 * if ($_SERVER['REQUEST_METHOD'] !== HttpMethod::GET) { fail(INVALID_METHOD, 405); }
 * $auth = requireAuth([...]);          // 401 unauthorized_no_token
 * requireCompanyActive($company_id);   // 403 company_account_not_active
 * </pre>
 *
 * <p>So {@code POST /apis/api/employees/list.php} with no credentials is a 405
 * in legacy, not a 401. A chain that ends {@code .anyRequest().authenticated()}
 * inverts that: Spring rejects the request before any controller runs, which
 * both reorders the guards and renders the platform {@code {code, message}}
 * body instead of PHP's envelope -- bypassing D-074 exactly where an
 * unauthenticated client would notice.
 *
 * <h2>What this is not</h2>
 * <p>It is not "{@code /apis/**} is public". Only listed prefixes are permitted
 * at the authorization layer, and only because their controllers call
 * {@link com.workin.legacy.auth.LegacyRequestGuard#requireAuth} explicitly on
 * every protected path -- P-7 ({@code token_version}), P-8 (role) and P-9
 * (active company), plus the tenant re-derivation behind
 * {@code LegacyRequestContext#companyId()}. The public login path is the one
 * exception: its controller is intentionally unauthenticated and performs its
 * own PHP method/body validation before delegating to the login service.
 *
 * <p>Anything under {@code /apis/**} that is <em>not</em> listed here keeps
 * falling through to {@code .anyRequest().authenticated()}, so an unported
 * legacy path is a 401, not an accidental hole.
 */
public final class LegacyPhpRoutes {

	/** Extended only when the matching controller exists and owns PHP guard order. */
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
		"/apis/api/salary_contracts/**",
		"/apis/api/advances/**",
		"/apis/api/penalties/**",
		"/apis/api/company/**",
		"/apis/api/payroll_batches/**",
		"/apis/api/payslips/**",
		"/apis/api/attendance_exception_types/**",
		"/apis/api/branches/**",
		"/apis/api/departments/**",
		"/apis/api/job_titles/**",
		"/apis/api/auth/login_employee.php",
	};

	private LegacyPhpRoutes() {
	}

}
