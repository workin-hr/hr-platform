package com.workin.legacy.auth;

import java.util.List;

import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code resolve_single_employee_auth_by_phone()}
 * ({@code helpers/functions.php:729-784}) -- how {@code forgot_password.php}
 * and {@code reset_password.php} pick <b>which</b> employee account a bare
 * phone number refers to, with no company id and no password.
 *
 * <h2>It is not {@link LegacyLoginResolver} with the password removed</h2>
 * <p>The two look alike and differ where it matters. {@code login_employee.php}
 * lets a <b>single pending</b> account log in; this one <b>rejects</b> any
 * pending account with {@code joined_company_wait_hr}. So an employee awaiting
 * HR approval can sign in but cannot reset their password. That asymmetry is
 * legacy's and is preserved (D-058) rather than harmonised.
 *
 * <p>The other difference is upstream: without a password there is no
 * {@code matched} filter, so every row owning the phone participates in the
 * decision. A phone used at three companies reaches
 * {@code multiple_accounts_same_phone} here even if the caller only knows the
 * password to one of them.
 *
 * <h2>The rejections run only when there is no ready account at all</h2>
 * <p>The whole rejection block sits inside {@code if ($login_ready === [])}.
 * So a phone owning <b>both</b> a login-ready account and a pending one is
 * <em>not</em> rejected: the ready account is selected and the pending row is
 * ignored. That is easy to state backwards, and an earlier draft of this
 * javadoc did state it backwards -- "a pending row reports pending regardless
 * of what the other rows say" is wrong, and
 * {@code aPhoneOwningBothAReadyAndAPendingAccountResolvesToTheReadyOne} exists
 * to keep it wrong in the tests too if anyone reintroduces it.
 *
 * <p>Within the block the order <em>is</em> observable: pending, then
 * company-not-active, then employee-not-active, then a catch-all. A row that is
 * both accepted-at-an-inactive-company and deactivated reports the
 * <em>company</em> reason.
 *
 * <p>PHP reads the status as {@code ($r[JOIN_REQUEST_STATUS] ?? 'accepted')},
 * defaulting a NULL column to accepted. {@code employees.join_request_status}
 * is {@code NOT NULL DEFAULT 'accepted'} in the frozen schema, so that
 * coalesce is unreachable and no null branch is reproduced here -- adding one
 * would be guessing at a state the schema forbids.
 */
public final class LegacyPhoneAuthResolver {

	private LegacyPhoneAuthResolver() {
	}

	/**
	 * @param newestFirst every row owning the phone, in legacy's
	 *        {@code ORDER BY e.id DESC} order
	 * @return the single login-ready row
	 * @throws LegacyApiException with legacy's status and key for every
	 *         rejection
	 */
	public static LegacyLoginCandidate resolve(List<LegacyLoginCandidate> newestFirst) {
		if (newestFirst.isEmpty()) {
			throw new LegacyApiException(404, "phone_not_found");
		}

		List<LegacyLoginCandidate> loginReady = newestFirst.stream()
				.filter(LegacyLoginCandidate::isLoginReady)
				.toList();

		if (loginReady.isEmpty()) {
			if (newestFirst.stream().anyMatch(LegacyLoginCandidate::isPending)) {
				throw new LegacyApiException(403, "joined_company_wait_hr");
			}
			if (newestFirst.stream().anyMatch(c -> c.isAccepted() && !c.isCompanyActive())) {
				throw new LegacyApiException(403, "company_account_not_active");
			}
			if (newestFirst.stream().anyMatch(c -> c.isAccepted() && !c.employeeActive())) {
				throw new LegacyApiException(403, "employee_account_not_active");
			}
			throw new LegacyApiException(403, "account_deactivated_enter_code");
		}

		if (loginReady.size() > 1) {
			throw new LegacyApiException(409, "multiple_accounts_same_phone");
		}
		return loginReady.get(0);
	}
}
