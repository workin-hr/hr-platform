package com.workin.backend.platformadmin.home;

import java.time.LocalDateTime;

import com.workin.backend.platformadmin.hr.AttendanceDisplay;
import com.workin.backend.platformadmin.hr.ListDisplay;

/**
 * The home panels' cell formatting, as {@code home_service.php} does it.
 *
 * @see com.workin.backend.platformadmin.hr.EmployeeDisplay the same idea one
 *     page over, and the reason the Arabic is written out rather than
 *     pluralised by a library
 */
public final class HomeDisplay {

	/** {@code home_format_datetime()}'s own array ({@code home_service.php:1053-1057}). */
	private static final String[] ARABIC_MONTHS = {
		"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
		"يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
	};

	/** PHP's {@code M}, which is always English and always abbreviated. */
	private static final String[] ENGLISH_MONTHS = {
		"Jan", "Feb", "Mar", "Apr", "May", "Jun",
		"Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
	};

	private HomeDisplay() {
	}

	/**
	 * {@code home_employee_complaints_subtitle()}.
	 *
	 * <p>Arabic inflects for none, the singular, the dual and the plural, and
	 * the four are different sentences rather than a number and a noun. English
	 * is the count followed by one phrase, which is what the PHP does.
	 */
	public static String complaintsSubtitle(long total, boolean arabic, String englishSuffix) {
		if (!arabic) {
			return total + " " + englishSuffix;
		}
		return switch ((int) Math.min(total, 3)) {
			case 0 -> "لا توجد شكاوى جديدة";
			case 1 -> "شكوى واحدة جديدة في انتظار المراجعة";
			case 2 -> "شكويان جديدتان في انتظار المراجعة";
			default -> total + " شكاوى جديدة في انتظار المراجعة";
		};
	}

	/**
	 * {@code home_format_datetime()} ({@code home_service.php:1043-1072}):
	 * "20 سبتمبر 2026 2:30 م", or "Sep 20, 2026 2:30 PM".
	 *
	 * <p>Legacy prints the day, the month's name, the year and a twelve-hour
	 * time, in Arabic with ص and م and in English with PHP's {@code M j, Y g:i A}.
	 * The port printed the stored timestamp cut to sixteen characters instead --
	 * an ISO date and a twenty-four-hour clock -- on the home page's activity
	 * feed and complaints panel, and on the join requests page. Legacy calls it
	 * in four places: {@code index.php:160} and {@code :242} for those two
	 * panels, {@code home_service.php:1079} for the activity row's own side
	 * time, and {@code join_requests/page.php:57}. The three ported call sites
	 * are the ones above; nothing here reads the fourth, which is legacy's own
	 * feed builder.
	 *
	 * <p>Nothing stored is an em dash, and a value no date can be read out of is
	 * returned as it came, both as legacy's {@code strtotime()} branch does. The
	 * month names are written out for the reason {@link
	 * com.workin.backend.platformadmin.hr.AttendanceDisplay} gives: legacy
	 * indexes a literal array, and a JVM locale would print whatever its CLDR
	 * data says.
	 */
	public static String dateTime(String stored, boolean arabic) {
		if (ListDisplay.trim(stored).isEmpty()) {
			return ListDisplay.EMPTY;
		}
		LocalDateTime parsed = AttendanceDisplay.parse(stored);
		if (parsed == null) {
			return stored;
		}
		int hour = parsed.getHour();
		int hour12 = hour % 12 == 0 ? 12 : hour % 12;
		String time = "%d:%02d %s".formatted(hour12, parsed.getMinute(),
				arabic ? (hour < 12 ? "ص" : "م") : (hour < 12 ? "AM" : "PM"));
		if (arabic) {
			return parsed.getDayOfMonth() + " " + ARABIC_MONTHS[parsed.getMonthValue() - 1]
					+ " " + parsed.getYear() + " " + time;
		}
		return ENGLISH_MONTHS[parsed.getMonthValue() - 1] + " " + parsed.getDayOfMonth()
				+ ", " + parsed.getYear() + " " + time;
	}

	/** The digits of a phone number, for a {@code wa.me} link, or null. */
	public static String whatsapp(String phone) {
		if (phone == null) {
			return null;
		}
		String digits = phone.replaceAll("\\D", "");
		return digits.isEmpty() ? null : "https://wa.me/" + digits;
	}

}
