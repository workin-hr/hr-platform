package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * A row of {@code penalties} as the dashboard's list and form need it
 * ({@code dashboard/pages/penalties}).
 *
 * @param appliedToPayroll once true the row is <b>frozen</b>: legacy refuses
 *                         to edit it, because payroll has already deducted
 *                         against it and changing the days would silently
 *                         disagree with a payslip already issued
 */
public record Penalty(
		long id, long employeeId, long companyId, String companyName, String employeeCode,
		String employeeName, String penaltyType, BigDecimal penaltyDays, String reason,
		String penaltyDate, boolean appliedToPayroll, String createdAt) {

	/** The day count as the form posts it back: {@code 0.25}, {@code 1}. */
	public String daysDisplay() {
		return this.penaltyDays == null ? "0" : this.penaltyDays.stripTrailingZeros().toPlainString();
	}

	/** {@code dashboard_penalty_days_option_label((float) $pen['penalty_days'])}. */
	public String daysLabel(Function<String, String> t) {
		return daysLabel(this.penaltyDays == null ? 0d : this.penaltyDays.doubleValue(), t);
	}

	/**
	 * {@code dashboard_penalty_days_option_label()} ({@code includes/penalty_helper.php:6-21}):
	 * words for a quarter, a half and one day, "N أيام" for any other whole number -- zero
	 * included -- and the bare number otherwise.
	 */
	public static String daysLabel(double days, Function<String, String> t) {
		if (Math.abs(days - 0.25) < 0.001) {
			return t.apply("penalty_quarter_day");
		}
		if (Math.abs(days - 0.5) < 0.001) {
			return t.apply("penalty_half_day");
		}
		if (Math.abs(days - 1.0) < 0.001) {
			return t.apply("penalty_one_day");
		}
		if (days == Math.floor(days)) {
			return (long) days + " " + t.apply("penalty_days_unit");
		}
		return BigDecimal.valueOf(days).stripTrailingZeros().toPlainString();
	}

	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

}
