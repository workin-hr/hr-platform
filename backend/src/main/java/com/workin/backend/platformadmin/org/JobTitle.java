package com.workin.backend.platformadmin.org;

import java.math.BigDecimal;

/**
 * A row of {@code job_titles} as the dashboard's list and form need it
 * ({@code dashboard/pages/job_titles}).
 *
 * <p>Two fields make this page's rules different from the other three org
 * pages. {@code department_id} is <b>optional</b> but must belong to the same
 * company when given, and {@code work_hours} is <b>required and positive</b> --
 * the only numeric validation in the four.
 *
 * @param departmentName joined through a LEFT JOIN, so {@code null} both for a
 *                       job title with no department and for one pointing at a
 *                       row that no longer exists
 */
public record JobTitle(
		long id, long companyId, String companyName, Long departmentId, String departmentName,
		String name, BigDecimal workHours, boolean active, String createdAt, int employeeCount) {

	/**
	 * {@code (float) $raw} when non-empty, else {@code null}, and the caller
	 * then requires {@code > 0}.
	 *
	 * <p>PHP's float cast takes the leading number and yields {@code 0.0} for
	 * anything that does not start with one -- so {@code "eight"} becomes 0
	 * and fails the positivity test rather than raising. Reproduced, because
	 * the alternative is a 400 on a form legacy re-renders with a message.
	 */
	public static BigDecimal workHours(String raw) {
		String trimmed = raw == null ? "" : raw.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
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

	/** {@code substr((string) $row['created_at'], 0, 10)}. */
	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

	public String departmentDisplay() {
		return this.departmentName == null || this.departmentName.isBlank() ? "—" : this.departmentName;
	}

	public String workHoursDisplay() {
		return this.workHours == null ? "—" : this.workHours.stripTrailingZeros().toPlainString();
	}

	/** One row of the department picker: {@code org_departments_for_company()}. */
	public record DepartmentOption(long id, String name, String companyName) {
	}

}
