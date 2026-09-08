package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;

/**
 * A row of {@code leave_balance} as the dashboard's list and form need it
 * ({@code dashboard/pages/leave_balances}).
 *
 * <p>The table has no {@code company_id} of its own: it reaches a company
 * through {@code employee_id}, which is why every tenant check on this page is
 * a join rather than a column comparison (<b>R-046</b>).
 *
 * @param remainingDays {@code total_days - used_days}, computed in the query
 *                      rather than stored, so it cannot drift from its parts
 */
public record LeaveBalance(
		long id, long employeeId, long companyId, String companyName, String employeeCode,
		String employeeName, int year, BigDecimal totalDays, BigDecimal usedDays,
		BigDecimal remainingDays) {

	/** {@code (float) ($_POST['total_days'] ?? 15)}: the leading number, or 0. */
	public static BigDecimal days(String raw, BigDecimal fallback) {
		if (raw == null) {
			return fallback;
		}
		String trimmed = raw.trim();
		int end = 0;
		if (end < trimmed.length() && (trimmed.charAt(end) == '+' || trimmed.charAt(end) == '-')) {
			end++;
		}
		while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
			end++;
		}
		if (end < trimmed.length() && trimmed.charAt(end) == '.') {
			end++;
			while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
				end++;
			}
		}
		String number = trimmed.substring(0, end);
		if (number.isEmpty() || "+".equals(number) || "-".equals(number) || ".".equals(number)) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(number);
		} catch (NumberFormatException ex) {
			return BigDecimal.ZERO;
		}
	}

	public String totalDisplay() {
		return plain(this.totalDays);
	}

	public String usedDisplay() {
		return plain(this.usedDays);
	}

	public String remainingDisplay() {
		return plain(this.remainingDays);
	}

	private static String plain(BigDecimal value) {
		return value == null ? "0" : value.stripTrailingZeros().toPlainString();
	}

	/** One row of the employee picker: {@code hr_employees_picker_list()}. */
	public record EmployeeOption(long id, String code, String name, String companyName) {
	}

}
