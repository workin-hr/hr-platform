package com.workin.backend.platformadmin.home;

import java.math.BigDecimal;

import com.workin.legacy.PhpMath;

/**
 * {@code home_get_summary_stats()}: the numbers across the top of the
 * dashboard's home page.
 *
 * <p>Every one is a count or a sum over the company the session is acting on --
 * for the platform administrator that is the company filter, and {@code 0}
 * there means every company at once, which is the whole reason this page is
 * worth having on this surface.
 *
 * @param companiesTotal    every company row, administrator only
 * @param companiesActive   {@code status='active'}
 * @param companiesPending  {@code status='pending'}, the queue somebody has to work
 * @param employeesTotal    active employees
 * @param branchesTotal     branches
 * @param checkedInToday    distinct employees with a punch dated today
 * @param pendingRequests   leave and other requests awaiting a decision
 * @param openComplaints    {@code source='company_support'} for an administrator,
 *                          which is the queue addressed to the platform rather
 *                          than to a company's own HR
 * @param pendingAdvances   salary advances awaiting a decision
 * @param penaltiesTotal    every penalty row
 * @param penaltiesUnapplied those payroll has not yet deducted
 * @param grossSalaries     the latest contract per employee, summed
 * @param basicSalaries     the same contracts' basic component
 * @param payrollDraft      batches not yet finalized
 * @param monthlyNet        payslip net for the current calendar month
 * @param resignations      employees deactivated this year
 */
public record HomeSummary(
		long companiesTotal, long companiesActive, long companiesPending,
		long employeesTotal, long branchesTotal, long checkedInToday,
		long pendingRequests, long openComplaints, long pendingAdvances,
		long penaltiesTotal, long penaltiesUnapplied,
		BigDecimal grossSalaries, BigDecimal basicSalaries,
		long payrollDraft, BigDecimal monthlyNet, long resignations) {

	/**
	 * The number half of {@code home_format_money()}, {@code number_format($n, 0)}. Legacy appends
	 * {@code ' ' . __('currency_egp')}, and the template does that, since the word is the page's language.
	 */
	public String gross() {
		return money(this.grossSalaries);
	}

	public String basic() {
		return money(this.basicSalaries);
	}

	public String net() {
		return money(this.monthlyNet);
	}

	/**
	 * Today's attendance as a share of the active headcount, to one decimal
	 * place (D-290), or {@code null} when there is nobody to divide by.
	 *
	 * <p>Capped at 100: {@code checkedInToday} counts anyone who punched today,
	 * as legacy's card does, so a person deactivated after punching is in the
	 * numerator and not the denominator. Over 100% would be that edge case
	 * reading as a bug on the page's most-looked-at figure.
	 */
	public String attendanceRate() {
		if (this.employeesTotal <= 0) {
			return null;
		}
		double rate = Math.min(100d, this.checkedInToday * 100d / this.employeesTotal);
		return String.format(java.util.Locale.ROOT, "%.1f", rate);
	}

	private static String money(BigDecimal amount) {
		return PhpMath.numberFormat(amount == null ? 0d : amount.doubleValue());
	}

}
