package com.workin.backend.platformadmin.hr;

/**
 * The two rows {@code dashboard/pages/attendance/page.php} renders.
 *
 * <p>The page shows one range twice: {@link Row} is the punch-by-punch list,
 * {@link AggregateRow} is the same range summarised per employee. They do
 * <b>not</b> agree on worked time, and that is the source's behaviour rather
 * than an oversight -- see {@link AttendanceStore} for the measurement.
 */
public final class AttendanceRecord {

	private AttendanceRecord() {
	}

	/**
	 * One attendance punch.
	 *
	 * @param workedMinutes {@code attendance_row_worked_minutes()}'s answer,
	 *     null when the row has no check-out or the engine returns nothing
	 *     positive -- PHP stores {@code null} there and the column renders as
	 *     an em dash rather than a zero
	 */
	public record Row(
			long id,
			long employeeId,
			long companyId,
			String checkIn,
			String checkOut,
			Long exceptionTypeId,
			String employeeName,
			String empCode,
			String exceptionName,
			Integer workedMinutes) {
	}

	/**
	 * One employee's totals over the filtered range.
	 *
	 * @param totalMinutes the raw {@code TIMESTAMPDIFF} sum from SQL, <b>not</b>
	 *     the worked-minutes engine the detail list uses. The two columns can
	 *     therefore disagree for the same employee and range; reconciling them
	 *     would change what the page reports.
	 * @param overtimeMinutes {@code total_minutes - expected_minutes}, which is
	 *     negative when the employee worked less than their shift -- PHP does
	 *     not clamp it and neither does this
	 */
	public record AggregateRow(
			long employeeId,
			String empCode,
			String employeeName,
			String jobTitleName,
			int daysInPeriod,
			int presentDays,
			int officialHolidayDays,
			int paidLeaveDays,
			int absentDays,
			int exceptionDays,
			int paidRestMinutes,
			long totalMinutes,
			long overtimeMinutes) {
	}

	/** An employee the add/edit form can pick. */
	public record EmployeeOption(long id, String label) {
	}

	/** An exception type the add/edit form can pick, scoped to one company. */
	public record ExceptionTypeOption(long id, String name) {
	}

}
