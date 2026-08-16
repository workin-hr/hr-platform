package com.workin.legacy.auth;

/**
 * Every outcome legacy's employee login can produce, with the exact
 * status and message key it produces them with.
 *
 * <p>Ported verbatim from
 * {@code hr-legacy/apis/api/auth/login_employee.php} @ {@code d113204}.
 * These are business behaviour, not implementation detail: the Flutter
 * clients branch on them, so Phase 1 reproduces them exactly (D-040's
 * contract parity, reaffirmed by D-042).
 *
 * <p>The one thing Phase 1 does <em>not</em> reproduce is the session
 * token itself — legacy's 10-year JWT with no revocation is a recorded
 * defect (`hr-legacy#7`), and D-042 keeps the short-lived access token
 * plus refresh rotation instead. Outcomes are parity; token lifetime is
 * the recorded exception.
 */
public enum LegacyLoginOutcome {

	/** Exactly one login-ready account, or exactly one pending one. */
	SUCCESS(200, "login_successful"),

	/** No employee row owns this phone at all. */
	USER_NOT_FOUND(401, "user_not_found"),

	/** Rows exist, none of their password hashes match. */
	INCORRECT_PASSWORD(401, "incorrect_password"),

	/**
	 * More than one account the caller could legitimately be — legacy
	 * refuses rather than choosing, and rather than offering a tenant
	 * picker. Removing this is the Phase 3 identity model, not Phase 1
	 * (D-042).
	 */
	MULTIPLE_ACCOUNTS_SAME_PHONE(409, "multiple_accounts_same_phone"),

	/** An accepted membership whose company is not active. */
	COMPANY_ACCOUNT_NOT_ACTIVE(403, "company_account_not_active"),

	/** An accepted membership whose employee row is deactivated. */
	EMPLOYEE_ACCOUNT_NOT_ACTIVE(403, "employee_account_not_active"),

	/** The catch-all legacy falls through to. */
	ACCOUNT_DEACTIVATED_ENTER_CODE(403, "account_deactivated_enter_code");

	private final int status;
	private final String messageKey;

	LegacyLoginOutcome(int status, String messageKey) {
		this.status = status;
		this.messageKey = messageKey;
	}

	public int status() {
		return status;
	}

	/** The legacy `LangKey` value, verbatim (`apis/lang/lang_key.php`). */
	public String messageKey() {
		return messageKey;
	}

	public boolean isSuccess() {
		return this == SUCCESS;
	}

}
