package com.workin.legacy.attendance.spreadsheet;

/**
 * A {@code RuntimeException} thrown out of the attendance import helper.
 *
 * <p>{@code import_excel.php} catches {@code RuntimeException} specifically, so
 * this is not an unexpected failure: it is a control-flow signal whose
 * <em>message</em> decides the response. A message beginning
 * {@code attendance_excel_} is used as the API message key directly; anything
 * else answers {@code invalid_file_type} with the message carried in
 * {@code data}. Distinct from every other exception the helper can raise --
 * those are caught by {@code catch (Throwable)}, rolled back and rethrown, and
 * become D-084's generic 500.
 */
public class LegacyAttendanceImportException extends RuntimeException {

	public LegacyAttendanceImportException(String message) {
		super(message);
	}

}
