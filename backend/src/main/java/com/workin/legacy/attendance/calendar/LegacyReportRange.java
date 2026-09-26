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

	/**
	 * The fingerprints export's own cap, a quarter. It holds every row of
	 * the range in memory before it writes the workbook: measured on 500
	 * employees with a punch per working day, a year took 253 s and peaked
	 * at 1.9 GB of heap -- past the production container's 768 MB, whose
	 * {@code ExitOnOutOfMemoryError} would stop it for every tenant -- while
	 * a quarter completed at 560 MB under a 768 MB heap (D-289).
	 */
	public static final int MAX_EXPORT_DAYS = 93;

	private LegacyReportRange() {
	}

	/**
	 * @param from an ISO date, already validated and not after {@code to}
	 * @param errorKey the key the caller's inverted-range refusal uses
	 */
	public static void requireWithinCap(String from, String to, String errorKey) {
		requireWithinCap(from, to, MAX_DAYS, errorKey);
	}

	/** The same refusal against a cap of the caller's own. */
	public static void requireWithinCap(String from, String to, int maxDays, String errorKey) {
		if (ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) + 1 > maxDays) {
			throw new LegacyApiException(400, errorKey);
		}
	}
}
