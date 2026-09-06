package com.workin.backend.platformadmin.hr;

/**
 * A row of {@code requests} as the dashboard's list needs it
 * ({@code dashboard/pages/requests}).
 *
 * <p>Like every HR row it reaches its company through {@code employee_id}, and
 * it carries its <em>type's</em> flags -- {@code deduct_balance},
 * {@code add_attendance_exception} -- because approving it acts on them.
 *
 * @param status one of {@code pending}, {@code approved}, {@code rejected}
 */
public record EmployeeRequest(
		long id, long employeeId, long companyId, String companyName, String employeeCode,
		String employeeName, String requestTypeName, boolean deductBalance,
		boolean addAttendanceException, Long exceptionTypeId, String status, String fromDate,
		String toDate, String notes, String reply, String decidedAt, String createdAt) {

	/** {@code substr((string) $row['created_at'], 0, 10)}. */
	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

	public boolean isPending() {
		return "pending".equals(this.status);
	}

	/**
	 * {@code dashboard_request_inclusive_day_count()}: both ends counted, and
	 * <b>1</b> for an unparseable or inverted span rather than 0 or an error.
	 *
	 * <p>That floor is load-bearing: it is what a deduction uses, so a
	 * malformed span costs the employee one day rather than none.
	 */
	public static int inclusiveDays(String fromDate, String toDate) {
		java.time.LocalDate from = date(fromDate);
		java.time.LocalDate to = date(toDate);
		if (from == null || to == null || to.isBefore(from)) {
			return 1;
		}
		return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
	}

	/** The year a deduction lands in: the <em>from</em> date's, not today's. */
	public static int yearOf(String fromDate) {
		java.time.LocalDate from = date(fromDate);
		return from == null ? java.time.LocalDate.now().getYear() : from.getYear();
	}

	static java.time.LocalDate date(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String text = raw.trim();
		try {
			return java.time.LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
		} catch (java.time.format.DateTimeParseException ex) {
			return null;
		}
	}

	/** One row of the type filter. */
	public record TypeOption(long id, String name) {
	}

}
