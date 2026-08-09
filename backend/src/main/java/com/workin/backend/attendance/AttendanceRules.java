package com.workin.backend.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * The pure per-row rules of hr-legacy's attendance calendar
 * ({@code attendance_calendar_helper.php} @ d113204), with no database
 * or clock behind them.
 *
 * <p><b>All day arithmetic is UTC</b>, matching the rest of this module:
 * {@link AttendanceService} already stores an exception day as
 * {@code date.atStartOfDay(ZoneOffset.UTC)} and buckets its list reads
 * on UTC boundaries. Legacy instead pins a fixed {@code +02:00}/{@code +03:00}
 * offset chosen at runtime from a {@code configs} row, so the two
 * systems will disagree about which day a punch near midnight belongs
 * to. That divergence predates this engine — reproducing legacy's
 * offset here would break exception-day detection against every row the
 * existing create path has written — and is filed for a decision before
 * any data migration.
 */
public final class AttendanceRules {

	/**
	 * Subtracted from the expected day length when exactly one punch
	 * exists ({@code ATTENDANCE_INCOMPLETE_PUNCH_DEDUCTION_MINUTES},
	 * attendance_calendar_helper.php:7). The same figure drives the
	 * synthetic checkout that auto-close writes.
	 */
	public static final int INCOMPLETE_PUNCH_DEDUCTION_MINUTES = 120;

	private AttendanceRules() {
	}

	/**
	 * attendance_is_exception_only_row (lines 12-26): an exception type
	 * is set, there is no check-out, and the check-in lands exactly on
	 * midnight — the shape {@link AttendanceService#applyShape} writes
	 * for a category-only day.
	 *
	 * <p>Legacy keys purely on the {@code 00:00:00} time component, so a
	 * genuine night-shift punch at exactly midnight carrying an
	 * exception type is misread as synthetic and scored zero. Ported
	 * as-is and filed rather than quietly corrected.
	 */
	public static boolean isExceptionOnlyRow(Attendance row) {
		return isExceptionOnlyRow(row.getCheckIn(), row.getCheckOut(), row.getExceptionTypeId());
	}

	public static boolean isExceptionOnlyRow(Instant checkIn, Instant checkOut, Long exceptionTypeId) {
		if (exceptionTypeId == null || exceptionTypeId <= 0 || checkOut != null || checkIn == null) {
			return false;
		}
		return LocalTime.MIDNIGHT.equals(checkIn.atZone(ZoneOffset.UTC).toLocalTime());
	}

	/**
	 * attendance_synthetic_row_id (lines 206-209): the stable negative
	 * id a day with no attendance row is reported under, so a client can
	 * key on it without mistaking it for a real row.
	 *
	 * <p>{@code -(employeeId * 100000000 + yyyymmdd)}. Legacy's
	 * {@code % 100000000} is a no-op for every Gregorian date and is
	 * dropped. Needs 64 bits.
	 */
	public static long syntheticRowId(long employeeId, LocalDate date) {
		long compact = date.getYear() * 10000L + date.getMonthValue() * 100L + date.getDayOfMonth();
		return -1L * ((employeeId * 100_000_000L) + compact);
	}

	/**
	 * attendance_display_duration_minutes (lines 31-46). Both punches →
	 * the raw difference; exactly one → the expected day minus the
	 * incomplete-punch deduction; neither → zero. Never negative.
	 */
	public static int displayDurationMinutes(Instant checkIn, Instant checkOut, int expectedMinutes) {
		boolean hasIn = checkIn != null;
		boolean hasOut = checkOut != null;
		if (hasIn && hasOut) {
			return Math.max(0, (int) java.time.Duration.between(checkIn, checkOut).toMinutes());
		}
		if (hasIn || hasOut) {
			return Math.max(0, expectedMinutes - INCOMPLETE_PUNCH_DEDUCTION_MINUTES);
		}
		return 0;
	}

	/** The UTC calendar day a punch belongs to — legacy's {@code DATE(check_in)} bucketing. */
	public static LocalDate dayOf(Instant instant) {
		return instant.atZone(ZoneOffset.UTC).toLocalDate();
	}

	public static Instant startOfDay(LocalDate date) {
		return date.atStartOfDay(ZoneOffset.UTC).toInstant();
	}

}
