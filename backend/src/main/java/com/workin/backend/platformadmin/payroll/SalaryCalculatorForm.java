package com.workin.backend.platformadmin.payroll;

import com.workin.legacy.LegacyValues;

/**
 * The salary calculator's three inputs, as the page reads them.
 *
 * <p>Separate from the controller because these are value rules, not request
 * plumbing, and they are what the PHP-generated parity corpus indexes on: the
 * corpus holds raw strings a user could type, so the cast has to be reachable
 * from a test without an HTTP round trip. What stays in the controller is the
 * part that is genuinely about the request -- which of the query string and
 * the body wins, and whether {@code reset} is present.
 *
 * <p>The raw strings are kept beside the parsed numbers because the form
 * echoes them back verbatim: type "12,500" and the field still says "12,500"
 * on the way out, not "12500".
 */
public record SalaryCalculatorForm(
		String grossRaw, String siRaw, String otherRaw,
		double gross, Double siBase, double otherNonTaxable) {

	/**
	 * Parses the three trimmed fields.
	 *
	 * <p>Blank is not zero for the insurance base: it means "no declared base",
	 * which {@link EgyptSalaryCalculator#resolveSocialInsuranceBase} answers
	 * with the gross. Blank <em>is</em> zero for the other two.
	 *
	 * <p>{@code other_non_taxable} is floored at zero here, as the page floors
	 * it; the gross is not, which is why a negative gross produces no result at
	 * all rather than a clamped one.
	 */
	public static SalaryCalculatorForm of(String grossRaw, String siRaw, String otherRaw) {
		String gross = LegacyValues.phpTrim(grossRaw);
		String si = LegacyValues.phpTrim(siRaw);
		String other = LegacyValues.phpTrim(otherRaw);
		return new SalaryCalculatorForm(gross, si, other,
				gross.isEmpty() ? 0 : toFloat(gross),
				si.isEmpty() ? null : toFloat(si),
				other.isEmpty() ? 0 : Math.max(0, toFloat(other)));
	}

	/** The blank form, which is also what {@code ?reset=1} produces. */
	public static SalaryCalculatorForm empty() {
		return new SalaryCalculatorForm("", "", "", 0, null, 0);
	}

	/**
	 * PHP's {@code (float)} cast of the value with thousands separators
	 * stripped: "12,500" is 12500 and "7500abc" is 7500 -- a leading numeric
	 * prefix, or zero when there is not one. Binding this as a {@code double}
	 * request parameter would answer 400 to input the page reads as nothing.
	 */
	private static double toFloat(String raw) {
		return LegacyValues.toPhpDecimal(raw.replace(",", "")).doubleValue();
	}

	/**
	 * The estimate, or {@code null} when there is nothing to estimate. Legacy
	 * gates on {@code $gross > 0}, so both an empty form and a negative gross
	 * render the placeholder panel.
	 */
	public EgyptSalaryCalculator.Estimate estimate() {
		return this.gross > 0
				? EgyptSalaryCalculator.compute(this.gross, this.siBase, this.otherNonTaxable)
				: null;
	}

}
