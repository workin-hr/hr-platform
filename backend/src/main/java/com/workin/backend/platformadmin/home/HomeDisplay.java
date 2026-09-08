package com.workin.backend.platformadmin.home;

/**
 * The home panels' cell formatting, as {@code home_service.php} does it.
 *
 * @see com.workin.backend.platformadmin.hr.EmployeeDisplay the same idea one
 *     page over, and the reason the Arabic is written out rather than
 *     pluralised by a library
 */
public final class HomeDisplay {

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
	 * {@code home_format_datetime()}: the stored timestamp to the minute.
	 *
	 * <p>The seconds are noise on a feed of the last few events, and legacy
	 * drops them.
	 */
	public static String dateTime(String stored) {
		return stored == null || stored.isBlank()
				? "" : stored.substring(0, Math.min(16, stored.length())).replace('T', ' ');
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
