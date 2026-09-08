package com.workin.legacy.payroll;

/**
 * Frozen {@code penalty_days_helper.php}: quarter-day through five-day whitelist.
 *
 * <p>Public because the JTE dashboard's penalties page applies the same rule.
 * A whitelist with two copies is a whitelist that drifts, and this one decides
 * what a payroll deduction may be -- so the copies would drift in money.
 */
public final class LegacyPenaltyDays {

	private static final double[] ALLOWED = {0.25, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0};

	private LegacyPenaltyDays() {
	}

	public static Double normalize(double value) {
		for (double allowed : ALLOWED) {
			if (Math.abs(value - allowed) < 0.0001) {
				return allowed;
			}
		}
		return null;
	}
}
