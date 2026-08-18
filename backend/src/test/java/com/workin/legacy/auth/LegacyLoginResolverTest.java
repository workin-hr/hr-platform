package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The legacy employee-login decision, ported branch for branch from
 * {@code hr-legacy/apis/api/auth/login_employee.php:50-119} @
 * {@code d113204}.
 *
 * <p>Every case here names the legacy line it comes from. The order of
 * the checks is itself part of the contract — legacy tests
 * company-not-active before employee-not-active, so a row that is both
 * reports the company reason, and a caller branching on that difference
 * would see a behaviour change if the order were "tidied".
 *
 * <p>Pure by construction: the resolver takes already-fetched rows and a
 * password matcher, so the whole decision is testable without a database
 * or a password hasher. That matters because this is the most
 * branch-dense piece of the port and the one where a plausible-looking
 * simplification silently changes who can log in.
 */
class LegacyLoginResolverTest {

	private static final String HASH = "$2y$correct";

	private static LegacyLoginCandidate candidate(
			long id, long companyId, String joinStatus, boolean employeeActive, String companyStatus) {
		return new LegacyLoginCandidate(
				id, companyId, "employee", joinStatus, employeeActive, companyStatus, HASH);
	}

	private static LegacyLoginResolution resolve(List<LegacyLoginCandidate> newestFirst) {
		return LegacyLoginResolver.resolve(newestFirst, HASH::equals);
	}

	/** `if (!$rows) fail(USER_NOT_FOUND, 401)` — line 50. */
	@Test
	void noRowForThePhoneIsUserNotFound() {
		LegacyLoginResolution result = resolve(List.of());

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.USER_NOT_FOUND);
		assertThat(result.outcome().status()).isEqualTo(401);
		assertThat(result.authenticated()).isNull();
	}

	/** `if ($matched === []) fail(INCORRECT_PASSWORD, 401)` — line 62. */
	@Test
	void rowsThatExistButDoNotMatchThePasswordAreIncorrectPassword() {
		LegacyLoginResolution result = LegacyLoginResolver.resolve(
				List.of(candidate(11, 1, "accepted", true, "active")),
				hash -> false);

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.INCORRECT_PASSWORD);
		assertThat(result.outcome().status()).isEqualTo(401);
	}

	/**
	 * A null or empty stored hash never matches — legacy requires
	 * `$hash !== null && $hash !== ''` before `password_verify` (line
	 * 56). Employees created without a password must not be loggable
	 * into by supplying an empty one.
	 */
	@Test
	void anEmptyOrMissingStoredHashCanNeverMatch() {
		LegacyLoginCandidate noHash = new LegacyLoginCandidate(
				11, 1, "employee", "accepted", true, "active", null);
		LegacyLoginCandidate blankHash = new LegacyLoginCandidate(
				12, 1, "employee", "accepted", true, "active", "");

		LegacyLoginResolution result = LegacyLoginResolver.resolve(
				List.of(noHash, blankHash), hash -> true);

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.INCORRECT_PASSWORD);
	}

	/** The ordinary path — line 111 onward. */
	@Test
	void exactlyOneLoginReadyAccountAuthenticates() {
		LegacyLoginResolution result = resolve(List.of(candidate(11, 1, "accepted", true, "active")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.SUCCESS);
		assertThat(result.authenticated().employeeId()).isEqualTo(11);
		assertThat(result.authenticated().companyId()).isEqualTo(1);
	}

	/** `if (count($login_ready) > 1) fail(..., 409)` — line 106. */
	@Test
	void twoLoginReadyAccountsAreRefusedRatherThanChosenBetween() {
		LegacyLoginResolution result = resolve(List.of(
				candidate(11, 1, "accepted", true, "active"),
				candidate(12, 2, "accepted", true, "active")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.MULTIPLE_ACCOUNTS_SAME_PHONE);
		assertThat(result.outcome().status()).isEqualTo(409);
		assertThat(result.authenticated()).isNull();
	}

	/**
	 * The branch most likely to be missed: with no login-ready account,
	 * a <em>single</em> pending one logs in successfully (line 76-88).
	 * It is not an error path — treating "pending" as a rejection would
	 * lock out every employee awaiting approval.
	 */
	@Test
	void asinglePendingAccountLogsInSuccessfully() {
		LegacyLoginResolution result = resolve(List.of(candidate(11, 1, "pending", true, "active")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.SUCCESS);
		assertThat(result.authenticated().employeeId()).isEqualTo(11);
	}

	/** `if (count($pending) > 1) fail(..., 409)` — line 90. */
	@Test
	void twoPendingAccountsAreAlsoAMultipleAccountsConflict() {
		LegacyLoginResolution result = resolve(List.of(
				candidate(11, 1, "pending", true, "active"),
				candidate(12, 2, "pending", true, "active")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.MULTIPLE_ACCOUNTS_SAME_PHONE);
	}

	/** Line 93-96, checked before the employee-active reason. */
	@Test
	void anAcceptedAccountOnAnInactiveCompanyReportsTheCompanyReason() {
		LegacyLoginResolution result = resolve(List.of(candidate(11, 1, "accepted", true, "suspended")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.COMPANY_ACCOUNT_NOT_ACTIVE);
		assertThat(result.outcome().status()).isEqualTo(403);
	}

	/** Line 98-101. */
	@Test
	void anAcceptedAccountOnAnActiveCompanyButDeactivatedReportsTheEmployeeReason() {
		LegacyLoginResolution result = resolve(List.of(candidate(11, 1, "accepted", false, "active")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.EMPLOYEE_ACCOUNT_NOT_ACTIVE);
	}

	/**
	 * Order matters and is asserted, not assumed. A row that is both
	 * company-inactive and employee-inactive reports the <em>company</em>
	 * reason, because legacy runs that loop first. Reordering the checks
	 * would be invisible to every other test here.
	 */
	@Test
	void whenBothReasonsApplyTheCompanyReasonWinsBecauseLegacyChecksItFirst() {
		LegacyLoginResolution result = resolve(List.of(candidate(11, 1, "accepted", false, "suspended")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.COMPANY_ACCOUNT_NOT_ACTIVE);
	}

	/** The fall-through, line 103. */
	@Test
	void arejectedAccountFallsThroughToTheDeactivatedCatchAll() {
		LegacyLoginResolution result = resolve(List.of(candidate(11, 1, "rejected", true, "active")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.ACCOUNT_DEACTIVATED_ENTER_CODE);
		assertThat(result.outcome().status()).isEqualTo(403);
	}

	/**
	 * Legacy orders the query {@code ORDER BY e.id DESC} and takes
	 * {@code $login_ready[0]} (lines 46, 110). With one login-ready row
	 * the order is irrelevant; this pins that the resolver honours the
	 * caller's ordering rather than re-sorting, so the day a second
	 * ordering-sensitive branch appears it is already correct.
	 */
	@Test
	void theResolverHonoursTheCallersNewestFirstOrdering() {
		LegacyLoginResolution result = resolve(List.of(
				candidate(12, 2, "accepted", true, "active"),
				candidate(11, 1, "rejected", true, "active")));

		assertThat(result.authenticated().employeeId()).isEqualTo(12);
	}

	/**
	 * A login-ready account wins over a pending one — the pending branch
	 * is only reached when {@code $login_ready === []} (line 72).
	 */
	@Test
	void apendingAccountDoesNotBlockAReadyOne() {
		LegacyLoginResolution result = resolve(List.of(
				candidate(12, 2, "pending", true, "active"),
				candidate(11, 1, "accepted", true, "active")));

		assertThat(result.outcome()).isEqualTo(LegacyLoginOutcome.SUCCESS);
		assertThat(result.authenticated().employeeId()).isEqualTo(11);
	}

}
