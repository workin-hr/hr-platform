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
 * {@code LegacyRequestContext#companyId()}.
 *
 * <h2>Nineteen entries are unauthenticated in legacy itself</h2>
 * <p>{@code auth/login_employee.php}, {@code configs/get.php},
 * {@code phone_countries/list.php}, {@code app_content/one.php},
 * {@code setting_allowed_values/list.php}, {@code complaints/create.php} and
 * Wave 13.1a's four OTP routes -- {@code auth/verify_otp.php},
 * {@code auth/resend_otp.php}, {@code auth/forgot_password.php} and
 * {@code auth/reset_password.php} -- and Wave 13.1b's nine account-lifecycle
 * routes are listed for a different reason from every other entry: their PHP calls no {@code requireAuth()} at all, so there
 * is no guard order for the controller to reproduce -- the endpoint is public
 * in legacy and must stay public here (D-111). Each performs its own PHP
 * method validation first.
 *
 * <p><b>The four OTP routes are the sharpest edge on this list.</b> Two of
 * them ({@code resend_otp}, {@code forgot_password}) cause an outbound
 * WhatsApp message to a caller-chosen number, and {@code reset_password}
 * changes a password given only a phone and a four-digit code. What stands
 * between them and abuse is the OTP rate limiter -- whose per-IP cap is
 * currently a platform-wide cap (R-014) -- and the fact that the code is no
 * longer returned in the response (PMR-05, {@code hr-legacy#4}). They are
 * listed because legacy requires no authentication, not because exposing them
 * is comfortable.
 *
 * <p><b>{@code auth/complete_company_registration.php} is sharper still.</b>
 * It is unauthenticated, takes {@code company_id} straight from
 * {@code $_POST}, and returns a <b>company-admin token for that id</b> -- so
 * naming a company that is mid-onboarding is enough to be handed a session for
 * it. That is R-016, ported in parity form under D-058 and recorded in the risk
 * register, the threat model and the endpoint inventory. It is on this list
 * because legacy requires no authentication for it; nothing about that is
 * an endorsement.
 *
 * <p>Five of the original six are safe to expose for the same kind of reason,
 * which is about the <em>data</em> rather than the routing: a login handler,
 * global operational configuration, the dial-code reference list, pre-login
 * marketing/legal copy, and the platform's allowed-value catalogue. None reads
 * company or personal data. {@code auth/get_company_registration_options.php}
 * joins them on the same argument -- three lookup tables with no
 * {@code company_id} and no personal data, which a client must render the
 * registration form from before any account exists to authenticate with.
 *
 * <p><b>{@code complaints/create.php} is the exception, and that argument does
 * not cover it.</b> It is the only public entry that <em>writes</em>: it
 * persists a caller-supplied name, phone and message from an anonymous source.
 * Rate limiting, spam and PII retention are live questions for it that do not
 * arise for the other five, and they are recorded in D-132 rather than
 * answered. It is listed because legacy requires no authentication, not because
 * exposing a write is comfortable.
 *
 * <p>{@code setting_allowed_values/list.php} is the odd one and the asymmetry
 * is legacy's: the values catalogue is world-readable while
 * {@code setting_definitions/list.php}, which names those same values, needs
 * COMPANY_ADMIN or HR. Both tables are platform configuration with no
 * {@code company_id}, so nothing tenant-scoped leaks either way -- but the
 * inconsistency is preserved rather than harmonised (D-058).
 *
 * <p><b>Read the two categories separately when adding an entry.</b> Everything
 * else on this list is permitted because its controller enforces authentication
 * itself; these two are permitted because legacy enforces none. A new route
 * belongs in the first category unless its PHP genuinely has no
 * {@code requireAuth()} call, and assuming the second because a controller
 * happens to compile without one is how a real hole would get added. What makes
 * {@code configs/get.php} safe to expose is that {@code configs} is a global
 * operational table with no {@code company_id} column and no personal data --
 * not that it appears in this array.
 *
 * <p>{@code LegacyEmployeeReadEndToEndTest#noMappedPhpRouteAnswersAnUnauthenticatedRequest}
 * holds the line: every mapped route must answer an unauthenticated GET with
 * 401 or 405 unless it is on that test's own closed list of public routes.
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
		"/apis/api/auth/verify_otp.php",
		"/apis/api/auth/resend_otp.php",
		"/apis/api/auth/forgot_password.php",
		"/apis/api/auth/reset_password.php",
		"/apis/api/auth/get_company_registration_options.php",
		"/apis/api/auth/lookup_company.php",
		"/apis/api/auth/check_status.php",
		"/apis/api/auth/register_company.php",
		"/apis/api/auth/complete_company_registration.php",
		"/apis/api/auth/register_employee.php",
		"/apis/api/auth/join_company.php",
		"/apis/api/auth/login_company.php",
		"/apis/api/auth/login_desktop.php",
		"/apis/api/configs/get.php",
		// Routes hr-legacy grew after the first sweep. Listed literally, not
		// as a prefix: these two modules have one endpoint each, and a
		// wildcard would pre-authorise routes nobody has written yet.
		"/apis/api/guide_videos/list.php",
		"/apis/api/time/now.php",
		"/apis/api/phone_countries/list.php",
		"/apis/api/app_content/one.php",
		"/apis/api/banners/list.php",
		"/apis/api/faqs/list.php",
		"/apis/api/dashboard/stats.php",
		"/apis/api/company_settings/**",
		"/apis/api/setting_definitions/list.php",
		"/apis/api/setting_allowed_values/list.php",
		"/apis/api/assets/**",
		"/apis/api/administrative_decisions/**",
		"/apis/api/workforce_planning/**",
		"/apis/api/employee_docs/**",
		"/apis/api/complaints/**",
		"/apis/api/company_join_requests/**",
		"/apis/api/notifications/**",
		"/apis/api/profile/employee.php",
		"/apis/api/profile/company.php",
		"/apis/api/profile/change_password.php",
		"/apis/api/profile/logout.php",
		"/apis/api/profile/register_push_token.php",
		"/apis/api/profile/request_phone_change.php",
		"/apis/api/profile/confirm_phone_change.php",
		"/apis/api/profile/delete_account_preview.php",
		"/apis/api/profile/delete_account.php",
	};

	private LegacyPhpRoutes() {
	}

}
