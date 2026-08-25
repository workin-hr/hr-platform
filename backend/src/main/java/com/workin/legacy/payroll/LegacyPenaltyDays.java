package com.workin.legacy.payroll;

/** Frozen {@code penalty_days_helper.php}: quarter-day through five-day whitelist. */
final class LegacyPenaltyDays {

	private static final double[] ALLOWED = {0.25, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0};

	private LegacyPenaltyDays() {
	}

	static Double normalize(double value) {
		for (double allowed : ALLOWED) {
			if (Math.abs(value - allowed) < 0.0001) {
				return allowed;
			}
		}
		return null;
	}
}
