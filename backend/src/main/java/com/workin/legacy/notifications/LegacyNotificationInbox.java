package com.workin.legacy.notifications;

import java.util.List;

import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code notification_inbox_filter($auth)}
 * ({@code helpers/notifications.php:13-33}) -- the SQL fragment and binds that
 * scope every one of the six {@code notifications/*.php} endpoints to the
 * caller's own inbox.
 *
 * <h2>Two inboxes, and the order between them is the contract</h2>
 * <p>A {@code type=company} session reads the rows addressed to the
 * <em>company</em> ({@code recipient_kind = 'company'}), and an employee
 * session reads the rows addressed to <em>itself</em>
 * ({@code recipient_kind = 'employee'}). They never overlap: the kind is part
 * of both filters, so a company admin does not see the notifications sent to
 * their employees and an employee does not see the company's.
 *
 * <p>The branch is on the auth <em>type</em>, not on which id happens to be
 * non-zero, and the company branch additionally requires {@code company_id > 0}.
 * That ordering is why {@link LegacyRequestContext#authType()} exists as its own
 * field: without the type, a company session with no company id would match the
 * employee branch on its employee id, reading an employee's inbox from a company
 * token.
 *
 * <p><b>The second test is not reachable by a non-employee token, in either
 * system.</b> PHP's helper reads {@code $auth[AuthKey::EMPLOYEE_ID]} whatever
 * the type is, so a token typed neither {@code company} nor {@code employee} but
 * carrying an employee id would reach the employee branch there -- and would do
 * so having skipped {@code requireEmployeeSessionValid()}, which only runs for
 * {@code type === 'employee'}. No such token exists: {@code AuthTypeEnum} has
 * exactly two cases and all four issuers
 * ({@code login_company}, {@code login_desktop},
 * {@code complete_company_registration}, and the employee issuer in
 * {@code functions.php:556}) emit one of them. So the filter's shape is ported
 * faithfully and the unreachable arm stays unreachable on both sides; Java's
 * narrower context is not a divergence in behaviour, because no issued token can
 * tell the difference.
 *
 * <h2>This is the whole tenant boundary for the module</h2>
 * <p>There is no separate {@code company_id} check on the employee branch:
 * {@code to_employee_id} is the scope, and it is unique across companies, so
 * scoping to it scopes to the tenant. Every query in
 * {@link LegacyNotificationStore} takes this fragment; none of them is allowed
 * to reach the {@code notifications} table without it.
 */
public record LegacyNotificationInbox(String sql, List<Object> params) {

	private static final String COMPANY = "company";
	private static final String EMPLOYEE = "employee";

	/**
	 * @throws LegacyApiException 401 {@code unauthorized} when the session is
	 *         neither a company session with a company nor an employee session
	 *         -- {@code fail(LangKey::UNAUTHORIZED, 401)}, the last line of the
	 *         PHP helper
	 */
	public static LegacyNotificationInbox of(LegacyRequestContext context) {
		if (context.isCompanyAuth() && context.companyId() > 0) {
			return new LegacyNotificationInbox(
					"n.company_id = ? AND n.recipient_kind = ?",
					List.of(context.companyId(), COMPANY));
		}
		if (context.employeeId() > 0) {
			return new LegacyNotificationInbox(
					"n.to_employee_id = ? AND n.recipient_kind = ?",
					List.of(context.employeeId(), EMPLOYEE));
		}
		throw new LegacyApiException(401, "unauthorized");
	}
}
