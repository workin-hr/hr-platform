package com.workin.legacy.payroll;

import java.time.LocalDate;

/**
 * The two pure period predicates every attendance-derived calculation starts
 * from: {@code payroll_calculation_as_of_date()} and
 * {@code payroll_period_in_progress()} ({@code payroll_calculation.php:219-229}).
 *
 * <p>Both are three lines of date arithmetic with no database and no settings,
 * which is why they were originally inlined at each call site. They now have
 * four consumers -- payslip enrichment, payslip display, batch calculation and
 * the Wave 12.6.6 overall attendance report -- and the report's contract depends
 * on applying exactly the same clamp the payroll engine does, so they get a
 * named home rather than a fourth copy.
 *
 * <p>Both compare dates as {@code Y-m-d} strings, not as {@link LocalDate},
 * because PHP does: {@code $today <= $period_to} is a string comparison there.
 * For well-formed {@code Y-m-d} the two orderings agree, and keeping the string
 * form means a malformed bound behaves the way legacy's does rather than
 * throwing where legacy would not.
 */
public final class LegacyPayrollPeriod {

	private LegacyPayrollPeriod() {
	}

	/**
	 * {@code payroll_calculation_as_of_date()}: today while the period is still
	 * open, the period end once it has closed. Never later than {@code periodTo}.
	 */
	public static String asOfDate(String today, String periodTo) {
		return today.compareTo(periodTo) <= 0 ? today : periodTo;
	}

	/**
	 * {@code payroll_period_in_progress()}: strictly {@code asOf < periodTo}.
	 * A period whose last day is today is <b>not</b> in progress.
	 */
	public static boolean inProgress(String periodTo, String asOf) {
		return asOf.compareTo(periodTo) < 0;
	}

	/**
	 * The end of the window attendance maths may look at: {@code asOf} while the
	 * period is open, {@code periodTo} once it has closed.
	 *
	 * <p>Not a legacy function of its own -- it is the
	 * {@code payroll_period_in_progress($period_to, $as_of) ? $as_of : $period_to}
	 * expression that follows every one of the call sites above, named here so
	 * the clamp cannot drift between them.
	 */
	public static String rangeEnd(String periodTo, String asOf) {
		return inProgress(periodTo, asOf) ? asOf : periodTo;
	}
}
