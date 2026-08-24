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
 * every path -- P-7 ({@code token_version}), P-8 (role) and P-9 (active
 * company), plus the tenant re-derivation behind
 * {@code LegacyRequestContext#companyId()}. Authentication still happens; it
 * happens where PHP does it. {@code JwtAuthenticationFilter} and
 * {@code TenantScopeFilter} run unchanged for these requests, so a valid token
 * still establishes a principal and a re-derived tenant scope, and an invalid
 * one still leaves the context empty -- which is what makes the guard's
 * {@code unauthorized_no_token} correct rather than accidental.
 *
 * <p>A legacy route added without its guard calls would be an unauthenticated
 * endpoint. {@code LegacyEmployeeReadEndToEndTest.noMappedPhpRouteAnswersAnUnauthenticatedRequest}
 * makes that a test failure rather than a review question: it enumerates the
 * live {@code RequestMappingHandlerMapping} and asserts that every mapped
 * {@code /apis/**} route answers an unauthenticated GET with one of PHP's own
 * denials -- {@code 401 unauthorized_no_token} from {@code requireAuth}, or
 * {@code 405 invalid_method} where the route's PHP verb is not GET and the
 * method guard runs first -- never a 200, and always in the PHP envelope. Not
 * every route answers 401: a POST-only or DELETE-only route legitimately
 * denies with 405, because that is the order legacy checks in.
 *
 * <p>Anything under {@code /apis/**} that is <em>not</em> listed here keeps
 * falling through to {@code .anyRequest().authenticated()}, so an unported
 * legacy path is a 401, not an accidental hole.
 */
public final class LegacyPhpRoutes {

	/**
	 * Extended one wave at a time, never pre-emptively: a prefix or exact route
	 * belongs here only once its controller carries the legacy guard calls.
	 * Requests stay exact rather than wildcarded so a future unmapped action
	 * cannot accidentally pass Spring's authorization decision into a 404.
	 */
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
	};

	private LegacyPhpRoutes() {
	}

}