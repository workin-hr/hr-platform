package com.workin.backend.platformadmin.org;

/**
 * A row of {@code shifts} as the dashboard's list and form need it
 * ({@code dashboard/pages/shifts}).
 *
 * <p>The simplest of the four org pages: a name, two times, and a count of the
 * employees assigned to it. The only rule is that the name is not empty --
 * the times are <b>not</b> validated at all.
 *
 * @param employeeCount distinct employees in {@code employee_shift_assignments},
 *                      counted across every assignment period rather than only
 *                      current ones
 */
public record Shift(
		long id, long companyId, String companyName, String name, String startTime, String endTime,
		boolean active, String createdAt, int employeeCount) {

	/**
	 * {@code $_POST['start_time'] ?? '08:00'}: the default applies only when
	 * the field is <b>absent</b>, not when it is empty.
	 *
	 * <p>That distinction is legacy's and it matters: a form that posts an
	 * empty time box stores the empty string, which MariaDB's non-strict mode
	 * then coerces to {@code 00:00:00}. A shift at midnight is wrong but it is
	 * what the live system holds, and validating here would refuse rows that
	 * already exist.
	 */
	public static String timeOr(String raw, String fallback) {
		return raw == null ? fallback : raw;
	}

	/** {@code substr((string) $row['created_at'], 0, 10)}. */
	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

	/** {@code HH:mm} for the form's {@code <input type="time">}, which rejects seconds. */
	public String startForInput() {
		return trimSeconds(this.startTime);
	}

	public String endForInput() {
		return trimSeconds(this.endTime);
	}

	private static String trimSeconds(String time) {
		return time != null && time.length() >= 5 ? time.substring(0, 5) : (time == null ? "" : time);
	}

}
