package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.workin.legacy.PhpMath;

/**
 * The employees table's cell formatting, as {@code employee_helper.php} does it.
 *
 * <p>Six of legacy's columns were selected by the store and rendered by
 * nothing: the avatar, the basic salary, the hire and created dates, the length
 * of service and the contract duration. The data was already on
 * {@link Employee}; what was missing was the formatting and the cells.
 *
 * <p>The Arabic here is not decoration. {@code employee_tenure_unit_label()}
 * inflects for the dual and the 3-10 plural -- سنة / سنتين / 3 سنين / 11 سنة --
 * and getting that wrong is what a native reader notices immediately and a
 * test never does.
 */
public final class EmployeeDisplay {

	private EmployeeDisplay() {
	}

	/** {@code employee_basic_salary_display()}: {@code number_format($n, 0)}, or a dash. */
	public static String basicSalary(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			return "—";
		}
		return PhpMath.numberFormat(amount.doubleValue());
	}

	/** The first ten characters of a stored date, or a dash. Legacy's {@code substr(..., 0, 10)}. */
	public static String date(String stored) {
		if (stored == null || stored.isBlank()) {
			return "—";
		}
		return stored.length() <= 10 ? stored : stored.substring(0, 10);
	}

	/**
	 * {@code employee_work_tenure_display()}: whole months between the hire date
	 * and today, rendered as years and months.
	 *
	 * <p>A hire date in the future returns a dash rather than a negative
	 * tenure, and the month count decrements when the day of the month has not
	 * come round yet -- both legacy's, and both reachable in the data.
	 */
	public static String workTenure(String hireDate, boolean arabic, LocalDate today) {
		LocalDate start = parse(hireDate);
		if (start == null || start.isAfter(today)) {
			return "—";
		}
		int months = (today.getYear() - start.getYear()) * 12
				+ (today.getMonthValue() - start.getMonthValue());
		if (today.getDayOfMonth() < start.getDayOfMonth()) {
			months--;
		}
		return duration(Math.max(0, months), arabic);
	}

	/** {@code employee_contract_duration_display()}: the stored month count, spelled out. */
	public static String contractDuration(Long months, boolean arabic) {
		return duration(months == null ? 0 : months.intValue(), arabic);
	}

	private static String duration(int months, boolean arabic) {
		if (months < 1) {
			return "—";
		}
		int years = months / 12;
		int rest = months % 12;
		if (years == 0) {
			return unit(rest, false, arabic);
		}
		if (rest == 0) {
			return unit(years, true, arabic);
		}
		return unit(years, true, arabic) + (arabic ? " و " : " and ") + unit(rest, false, arabic);
	}

	/** {@code employee_tenure_unit_label()}. */
	private static String unit(int number, boolean year, boolean arabic) {
		if (number <= 0) {
			return "—";
		}
		if (!arabic) {
			String word = year ? "year" : "month";
			return number == 1 ? "1 " + word : number + " " + word + "s";
		}
		if (year) {
			return switch (number) {
				case 1 -> "سنة";
				case 2 -> "سنتين";
				case 3, 4, 5, 6, 7, 8, 9, 10 -> number + " سنين";
				default -> number + " سنة";
			};
		}
		return switch (number) {
			case 1 -> "شهر";
			case 2 -> "شهرين";
			case 3, 4, 5, 6, 7, 8, 9, 10 -> number + " شهور";
			default -> number + " شهر";
		};
	}

	/**
	 * {@code dashboard_avatar_initials_html()}: the first letter of each of the
	 * first two words.
	 *
	 * <p>Rendered when an employee has no uploaded photo, which most have not.
	 */
	public static String initials(String name) {
		if (name == null || name.isBlank()) {
			return "؟";
		}
		String[] words = name.trim().split("\\s+");
		StringBuilder letters = new StringBuilder();
		for (int at = 0; at < Math.min(2, words.length); at++) {
			if (!words[at].isEmpty()) {
				letters.appendCodePoint(words[at].codePointAt(0));
				letters.append(' ');
			}
		}
		return letters.toString().trim();
	}

	private static LocalDate parse(String stored) {
		if (stored == null || stored.length() < 10) {
			return null;
		}
		try {
			return LocalDate.parse(stored.substring(0, 10));
		}
		catch (DateTimeParseException ex) {
			// Legacy's createFromFormat returns false for a zero date and the
			// cell shows a dash; a thrown parse here would take the page down.
			return null;
		}
	}

}
