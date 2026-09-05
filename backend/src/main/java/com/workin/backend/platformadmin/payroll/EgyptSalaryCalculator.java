package com.workin.backend.platformadmin.payroll;

import com.workin.legacy.PhpMath;

/**
 * {@code EgyptMonthlySalaryCalculator}
 * ({@code dashboard/pages/salary_calculator/egypt_salary_calculator.php}).
 *
 * <p>An estimate of one month's Egyptian net pay, and the only page on this
 * surface that touches no table at all: three numbers in, ten out, nothing
 * read and nothing written. The legacy file says so in its own header --
 * "للعرض فقط وليس استشارة ضريبية", for display only and not tax advice -- and
 * the page repeats it to the user in two notes above the form.
 *
 * <p><b>Every rate and bracket is copied, not recomputed.</b> They are the
 * legacy file's constants, and if Egyptian law moves, this class is wrong in
 * exactly the way the PHP is wrong until someone changes both. That is the
 * intended relationship during the cutover: the port's contract is the frozen
 * page, not the tax code.
 *
 * <p>Arithmetic is {@code double} throughout because the source is, and
 * rounding goes through {@link PhpMath} because PHP's {@code round()} is not
 * any of Java's. Doing this in {@code BigDecimal} would be more accurate and
 * would disagree with the page it replaces.
 */
public final class EgyptSalaryCalculator {

	public static final double EMPLOYEE_SI_RATE = 0.11;

	public static final double EMPLOYER_SI_RATE = 0.1875;

	public static final double MARTYRS_RATE = 0.0005;

	public static final double ANNUAL_PERSONAL_EXEMPTION = 20000;

	public static final double SI_BASE_MIN_MONTHLY = 2700;

	public static final double SI_BASE_MAX_MONTHLY = 16700;

	/**
	 * {@code ANNUAL_TAX_SLICES}: slice <em>widths</em> and their rates, not
	 * thresholds. Each row is consumed in turn, so the boundaries are the
	 * running totals -- 40,000 then 55,000 then 70,000 and so on -- and the
	 * last row is unbounded.
	 */
	private static final double[][] ANNUAL_TAX_SLICES = {
		{ 40000, 0 },
		{ 15000, 0.10 },
		{ 15000, 0.15 },
		{ 130000, 0.20 },
		{ 200000, 0.225 },
		{ 800000, 0.25 },
		{ Double.MAX_VALUE, 0.275 },
	};

	private EgyptSalaryCalculator() {
	}

	/**
	 * One month's estimate. Field for field the PHP's returned array, including
	 * the two the page computes and never prints.
	 *
	 * @param otherNonTaxableMonthly computed and not rendered
	 * @param totalDeductions computed and not rendered -- the page's
	 *     "total deductions" line is a section heading with an empty value
	 *     beside it, and the number that would fill it is this one
	 */
	public record Estimate(
			double grossMonthly,
			double socialInsuranceBase,
			double employeeSi,
			double martyrsFund,
			double incomeTax,
			double netMonthly,
			double employerSi,
			double otherNonTaxableMonthly,
			double totalDeductions,
			double netYearly) {

		public String grossMonthlyLabel() {
			return PhpMath.numberFormat(this.grossMonthly);
		}

		public String socialInsuranceBaseLabel() {
			return PhpMath.numberFormat(this.socialInsuranceBase);
		}

		public String employeeSiLabel() {
			return PhpMath.numberFormat(this.employeeSi);
		}

		public String martyrsFundLabel() {
			return PhpMath.numberFormat(this.martyrsFund);
		}

		public String incomeTaxLabel() {
			return PhpMath.numberFormat(this.incomeTax);
		}

		public String netMonthlyLabel() {
			return PhpMath.numberFormat(this.netMonthly);
		}

		public String employerSiLabel() {
			return PhpMath.numberFormat(this.employerSi);
		}

		public String netYearlyLabel() {
			return PhpMath.numberFormat(this.netYearly);
		}
	}

	/** {@code roundMoney()}: two decimal places, PHP's rounding. */
	public static double roundMoney(double value) {
		return PhpMath.round(value * 100) / 100;
	}

	/**
	 * {@code resolveSocialInsuranceBase()}.
	 *
	 * <p>A blank or non-positive declared base falls back to the gross, and the
	 * result is then clamped into the statutory band. The clamp is what makes a
	 * very small gross produce a <em>negative</em> net: the floor of 2,700
	 * charges 297 in employee contributions against a gross that may be less
	 * than that. The page prints the loss rather than hiding it, and so does
	 * this.
	 */
	public static double resolveSocialInsuranceBase(
			double grossMonthly, Double insuranceBaseMonthly) {
		double raw = (insuranceBaseMonthly == null || insuranceBaseMonthly <= 0)
				? grossMonthly : insuranceBaseMonthly;
		return Math.max(SI_BASE_MIN_MONTHLY, Math.min(SI_BASE_MAX_MONTHLY, raw));
	}

	private static double annualIncomeTax(double taxableAnnual) {
		if (taxableAnnual <= 0) {
			return 0;
		}
		double remaining = taxableAnnual;
		double tax = 0;
		for (double[] slice : ANNUAL_TAX_SLICES) {
			if (remaining <= 0) {
				break;
			}
			double portion = Math.min(slice[0], remaining);
			tax += portion * slice[1];
			remaining -= portion;
		}
		return tax;
	}

	/**
	 * {@code compute()}.
	 *
	 * <p>Tax is assessed annually and divided back down, not charged monthly,
	 * so the exemption and the brackets apply once across twelve months. The
	 * employee's own insurance and the martyrs' levy are deducted before tax is
	 * assessed; the employer's share is reported beside the result and is not
	 * part of the net.
	 */
	public static Estimate compute(
			double grossMonthly, Double insuranceBaseMonthly, double otherNonTaxableMonthly) {
		double gross = Math.max(0, grossMonthly);
		double otherNonTaxable = Math.max(0, otherNonTaxableMonthly);
		double base = resolveSocialInsuranceBase(gross, insuranceBaseMonthly);

		double employeeSi = roundMoney(base * EMPLOYEE_SI_RATE);
		double martyrs = roundMoney(gross * MARTYRS_RATE);
		// Kept as the source's separate annual terms rather than folded into
		// one expression: floating-point subtraction is order-sensitive, and
		// the whole point of this class is to land on the same double the page
		// does.
		double annualGross = gross * 12;
		double annualEmployeeSi = employeeSi * 12;
		double annualMartyrs = martyrs * 12;
		double annualOtherNonTaxable = otherNonTaxable * 12;
		double taxableAnnual = Math.max(0, annualGross - annualEmployeeSi - annualMartyrs
				- annualOtherNonTaxable - ANNUAL_PERSONAL_EXEMPTION);
		double monthlyTax = roundMoney(annualIncomeTax(taxableAnnual) / 12);
		double net = roundMoney(gross - employeeSi - monthlyTax - martyrs);

		return new Estimate(
				gross,
				base,
				employeeSi,
				martyrs,
				monthlyTax,
				net,
				roundMoney(base * EMPLOYER_SI_RATE),
				otherNonTaxable,
				roundMoney(employeeSi + monthlyTax + martyrs),
				roundMoney(net * 12));
	}

}
