package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.workin.legacy.wire.LegacyApiException;

/**
 * The widest date range an attendance report builds day by day (D-289).
 *
 * <p>PHP accepts any {@code from}/{@code to}, and every report that takes one
 * runs its per-day work for every employee in scope, so one request naming a
 * decade cost a decade of days times the roster. The cap is a year, leap day
 * included: no client is known to ask for less, and a yearly report is a
 * request someone legitimately makes, while a decade is refused. Anything
 * wider is refused with the same 400 the endpoint already answers an
 * inverted range with, so no client meets a new error shape.
 */
public final class LegacyReportRange {

	public static final int MAX_DAYS = 366;

	private LegacyReportRange() {
	}

	/**
	 * @param from an ISO date, already validated and not after {@code to}
	 * @param errorKey the key the caller's inverted-range refusal uses
	 */
	public static void requireWithinCap(String from, String to, String errorKey) {
		if (ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) + 1 > MAX_DAYS) {
			throw new LegacyApiException(400, errorKey);
		}
	}
}
