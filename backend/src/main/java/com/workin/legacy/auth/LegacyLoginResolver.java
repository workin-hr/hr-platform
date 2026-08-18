package com.workin.legacy.auth;

import java.util.List;
import java.util.function.Predicate;

/**
 * Legacy's employee-login decision, ported branch for branch from
 * {@code hr-legacy/apis/api/auth/login_employee.php:50-119} @
 * {@code d113204}.
 *
 * <p>Pure: it takes rows somebody else fetched and a password matcher
 * somebody else supplies, so the decision can be exercised exhaustively
 * without a database or a hasher. That is deliberate — this is the most
 * branch-dense piece of the Phase 1 port, and several of its branches
 * are ones a reasonable person would simplify away.
 *
 * <p><b>The order of the checks is part of the contract.</b> Legacy
 * tests company-not-active before employee-not-active, so a row that is
 * both reports the company reason. Legacy also reaches the pending
 * branch only when no login-ready account exists, and a single pending
 * account <em>succeeds</em> rather than failing. Both are pinned by
 * tests, because both are invisible to a casual reading.
 *
 * <p>What this deliberately does not decide is the session token.
 * D-042 keeps the short-lived access token plus refresh rotation rather
 * than legacy's 10-year JWT: outcomes are parity, token lifetime is the
 * recorded exception.
 */
public final class LegacyLoginResolver {

	private LegacyLoginResolver() {
	}

	/**
	 * @param newestFirst every employee row owning the phone, in
	 *        legacy's own {@code ORDER BY e.id DESC} order. The ordering
	 *        is honoured, not re-derived — legacy takes
	 *        {@code $login_ready[0]}, and re-sorting here would move the
	 *        decision away from the query that produced it.
	 * @param passwordMatchesHash applied only to hashes legacy would
	 *        have attempted, i.e. neither null nor empty
	 */
	public static LegacyLoginResolution resolve(
			List<LegacyLoginCandidate> newestFirst, Predicate<String> passwordMatchesHash) {

		// line 50: no row owns this phone
		if (newestFirst.isEmpty()) {
			return LegacyLoginResolution.rejected(LegacyLoginOutcome.USER_NOT_FOUND);
		}

		// lines 54-63: rows exist; keep the ones whose password verifies.
		// The usable-hash guard is legacy's own -- an employee with no
		// password must not be loggable into by supplying an empty one.
		List<LegacyLoginCandidate> matched = newestFirst.stream()
				.filter(LegacyLoginCandidate::hasUsableHash)
				.filter(candidate -> passwordMatchesHash.test(candidate.passwordHash()))
				.toList();
		if (matched.isEmpty()) {
			return LegacyLoginResolution.rejected(LegacyLoginOutcome.INCORRECT_PASSWORD);
		}

		// lines 68-70
		List<LegacyLoginCandidate> loginReady = matched.stream()
				.filter(LegacyLoginCandidate::isLoginReady)
				.toList();

		// lines 72-104: the no-ready-account branch, in legacy's order
		if (loginReady.isEmpty()) {
			List<LegacyLoginCandidate> pending = matched.stream()
					.filter(LegacyLoginCandidate::isPending)
					.toList();

			// lines 76-88: a single pending account SUCCEEDS. Not an
			// error path -- treating pending as a rejection would lock
			// out every employee awaiting approval.
			if (pending.size() == 1) {
				return LegacyLoginResolution.success(pending.get(0));
			}
			// line 90
			if (pending.size() > 1) {
				return LegacyLoginResolution.rejected(
						LegacyLoginOutcome.MULTIPLE_ACCOUNTS_SAME_PHONE);
			}
			// lines 93-96, checked before the employee reason below
			if (matched.stream().anyMatch(c -> c.isAccepted() && !c.isCompanyActive())) {
				return LegacyLoginResolution.rejected(
						LegacyLoginOutcome.COMPANY_ACCOUNT_NOT_ACTIVE);
			}
			// lines 98-101
			if (matched.stream().anyMatch(c -> c.isAccepted() && !c.employeeActive())) {
				return LegacyLoginResolution.rejected(
						LegacyLoginOutcome.EMPLOYEE_ACCOUNT_NOT_ACTIVE);
			}
			// line 103
			return LegacyLoginResolution.rejected(
					LegacyLoginOutcome.ACCOUNT_DEACTIVATED_ENTER_CODE);
		}

		// line 106: legacy refuses to choose between accounts, and does
		// not offer a tenant picker. Removing this is the Phase 3
		// identity model, not Phase 1 (D-042).
		if (loginReady.size() > 1) {
			return LegacyLoginResolution.rejected(
					LegacyLoginOutcome.MULTIPLE_ACCOUNTS_SAME_PHONE);
		}

		// line 110
		return LegacyLoginResolution.success(loginReady.get(0));
	}

}
