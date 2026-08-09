package com.workin.backend.attendance;

import java.time.LocalTime;

/**
 * What a single calendar day was supposed to look like for one
 * employee — legacy's {@code attendance_import_expected_for_day} return
 * shape ({@code attendance_excel_analyzer.php:498-548}), field for
 * field.
 *
 * @param expectedMinutes scheduled length; always 0 on a rest day
 * @param shiftName       may be blank (legacy emits {@code ''}, not null, when a shift exists with no name)
 * @param shiftStart      null when no shift resolves for the day
 * @param shiftEnd        null when no shift resolves for the day
 * @param restDay         weekly rest <b>or</b> official holiday — legacy collapses both into this one flag
 * @param restNote        the localized label behind {@code restDay}, null otherwise
 */
public record ExpectedDay(
		int expectedMinutes,
		String shiftName,
		LocalTime shiftStart,
		LocalTime shiftEnd,
		boolean restDay,
		String restNote) {
}
