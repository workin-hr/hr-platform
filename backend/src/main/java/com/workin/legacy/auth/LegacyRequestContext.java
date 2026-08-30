package com.workin.legacy.auth;

import com.workin.legacy.employees.LegacyEmployee;

/**
 * P-2: the per-request legacy auth context {@link LegacyRequestGuard#requireAuth}
 * returns -- the employee id and company id a legacy controller needs,
 * already validated by the time it holds one, plus the role claim so a
 * controller can make its own finer-grained decisions.
 *
 * <h2>{@code authType}</h2>
 * <p>{@code $auth[AuthKey::TYPE]}, one of {@code "company"} or
 * {@code "employee"}. It is not a synonym for "employeeId is zero" and must
 * not be inferred from one: {@code notification_inbox_filter()} branches on
 * <em>type</em> first and falls through to the employee inbox only when the
 * type is not {@code company}, and the whole {@code profile/} module keys its
 * behaviour off the same claim ({@code profile/change_password.php:32},
 * {@code delete_account.php:24}, {@code register_push_token.php:24},
 * {@code request_phone_change.php:17}).
 *
 * <p>PHP reads it as {@code (string) ($auth[AuthKey::TYPE] ?? '')}, so a token
 * that carries no type claim compares equal to {@code ""} and not to
 * {@code "company"}. This field carries the same normalisation: never null,
 * {@code ""} when the claim is absent.
 */
public record LegacyRequestContext(
		long employeeId, long companyId, LegacyEmployee.Role role, String authType) {

	public LegacyRequestContext {
		authType = authType == null ? "" : authType;
	}

	/** {@code $auth_type === AuthTypeEnum::COMPANY->value}. */
	public boolean isCompanyAuth() {
		return "company".equals(authType);
	}
}
