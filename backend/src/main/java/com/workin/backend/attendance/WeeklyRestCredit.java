package com.workin.backend.attendance;

/**
 * Whether a weekly-rest day is paid — legacy's
 * {@code WEEKLY_REST_CREDIT_EARNED / _VOID / _PENDING}
 * ({@code weekly_rest_credit_helper.php:19-21}).
 *
 * <p>The rest day is earned by <em>attending the week before it</em>:
 * cover at least three of the preceding scheduled workdays and the rest
 * day is paid; fall short and it is not, and payroll counts it as an
 * absence.
 */
public enum WeeklyRestCredit {

	/** The preceding week was covered, or there was no workday to cover. */
	EARNED,

	/**
	 * Reached, and short of the three-day threshold. Payroll adds these
	 * to absences.
	 */
	VOID,

	/**
	 * The rest day is still in the future relative to {@code asOf}, so
	 * nothing is decided yet.
	 *
	 * <p>Narrower than legacy's header comment claims. That comment
	 * describes pending as "the week is not finished and coverage is
	 * still short", but three branches that would produce it are
	 * unreachable: every examined workday precedes the rest day, and the
	 * function already returned if the rest day is in the future. So a
	 * reached rest day is decided immediately — mid-week and short means
	 * VOID, not PENDING.
	 */
	PENDING

}
