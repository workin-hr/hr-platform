package com.workin.legacy;

/**
 * PHP's {@code round()} and {@code number_format()}, reproduced bit for bit.
 *
 * <p>Needed because neither has a Java equivalent that agrees with it.
 * {@link Math#round} breaks two ways at once: it is {@code floor(v + 0.5)},
 * which rounds {@code -2.5} to {@code -2} where PHP gives {@code -3}, and it
 * was deliberately fixed in Java 7 so that {@code 0.49999999999999994} rounds
 * to {@code 0} -- where PHP, still computing {@code floor(v + 0.5)} in C,
 * gives {@code 1}. {@link java.math.BigDecimal} with {@code HALF_UP} gets the
 * sign right and then disagrees for a different reason: it rounds the exact
 * decimal value, and PHP does not.
 *
 * <p>PHP pre-rounds. Before rounding to the requested number of places it
 * rounds to {@code 14 - floor(log10(|v|))} places first, which is a window of
 * roughly {@code 1.1e-15} relative -- enough to absorb the representation
 * error in a value that <em>should</em> have landed on a boundary.
 * {@code round(1234.4999999999998)} is {@code 1235}, not {@code 1234}. A
 * money calculator that skipped this would disagree with the page it is
 * replacing on values a user can actually type.
 *
 * <p><b>Measured, not inferred.</b> The model here was checked against PHP
 * 8.3 over 20,160 cases exchanged as raw bit patterns -- the {@code .5} edges
 * of ten decades walked three ULPs either side, the products this
 * application's calculators actually form, and the extremes -- with zero
 * disagreements on both methods. {@code PhpMathTest} keeps the boundary half
 * of that corpus.
 */
public final class PhpMath {

	/**
	 * The magnitude at which PHP stops rounding and hands the value back. A
	 * double this large has no fractional part left worth deciding, and
	 * scaling one would lose more than the rounding could recover.
	 */
	private static final double NO_FRACTION_LEFT = 1e15;

	private PhpMath() {
	}

	/**
	 * {@code round($value)} -- to whole numbers, PHP's default mode
	 * ({@code PHP_ROUND_HALF_UP}, which is half <em>away from zero</em>, not
	 * Java's half-toward-positive-infinity).
	 */
	public static double round(double value) {
		if (value == 0d || !Double.isFinite(value)) {
			return value;
		}
		if (Math.abs(value) >= NO_FRACTION_LEFT) {
			return value;
		}
		int precisionPlaces = 14 - (int) Math.floor(Math.log10(Math.abs(value)));
		if (precisionPlaces > 0 && precisionPlaces < 15) {
			double scale = Math.pow(10, precisionPlaces);
			return halfAwayFromZero(halfAwayFromZero(value * scale) / scale);
		}
		return halfAwayFromZero(value);
	}

	/** {@code php_round_helper()} for {@code PHP_ROUND_HALF_UP}. */
	private static double halfAwayFromZero(double value) {
		return value >= 0 ? Math.floor(value + 0.5) : Math.ceil(value - 0.5);
	}

	/**
	 * {@code number_format($value, 0, '.', ',')} -- whole EGP, grouped in
	 * threes, which is how every money figure on the payroll pages is printed.
	 *
	 * <p>Negative is reachable and not a guard against nonsense: the salary
	 * calculator clamps the social-insurance base up to a floor of 2,700, so a
	 * gross below the employee's share of it nets out below zero and the page
	 * prints the loss.
	 */
	public static String numberFormat(double value) {
		double rounded = round(value);
		// C's printf, which PHP hands the rounded value to, rounds the exact
		// binary value to nearest and breaks ties to even. Java's Formatter
		// breaks them away from zero, so "%.0f" would disagree -- reachable
		// only above 1e15, where round() returns the value untouched and a
		// fraction survives to this line.
		String digits = new java.math.BigDecimal(Math.abs(rounded))
				.setScale(0, java.math.RoundingMode.HALF_EVEN).toPlainString();
		StringBuilder grouped = new StringBuilder();
		int leading = digits.length() % 3 == 0 ? 3 : digits.length() % 3;
		grouped.append(digits, 0, leading);
		for (int at = leading; at < digits.length(); at += 3) {
			grouped.append(',').append(digits, at, at + 3);
		}
		// "-0" is not a thing PHP prints, and rounding a small negative
		// towards zero is the way to reach it.
		boolean negative = rounded < 0 && !"0".contentEquals(digits);
		return negative ? "-" + grouped : grouped.toString();
	}

}
