package com.workin.backend.platformadmin.hr;

/**
 * What a list cell shows for a long free-text column: legacy's
 * {@code hr_request_notes_display()} ({@code includes/hr_list_helper.php:495-506}).
 *
 * <p>Legacy trims the text, shows an em dash when nothing is left, and otherwise cuts it to a
 * number of <em>characters</em> and appends a horizontal ellipsis. The cell carries the whole
 * text in its {@code title}, so nothing is lost -- that part belongs to the template.
 *
 * <p>The count is in code points, because {@code mb_strlen} and {@code mb_substr} count
 * characters and {@code String.length()} counts UTF-16 units: an emoji is one character to PHP
 * and two units here, so a naive cut would both count it twice and split it in half.
 */
public final class ListDisplay {

	/** {@code int $maxLen = 60}. */
	public static final int DEFAULT_MAX = 60;

	/** {@code '—'}: what legacy shows for an empty column. */
	public static final String EMPTY = "—";

	/**
	 * PHP's {@code trim()} default character list. Java's {@code String.trim()} removes every
	 * character up to {@code U+0020}, which includes the form feed PHP leaves alone, and
	 * {@code strip()} removes every Unicode space, which is wider still.
	 */
	private static final String PHP_TRIM = " \t\n\r\0";

	private ListDisplay() {
	}

	/** {@code hr_request_notes_display($notes)}. */
	public static String notes(String text) {
		return notes(text, DEFAULT_MAX);
	}

	/** {@code hr_request_notes_display($notes, $maxLen)}. */
	public static String notes(String text, int max) {
		String trimmed = trim(text);
		if (trimmed.isEmpty()) {
			return EMPTY;
		}
		if (trimmed.codePointCount(0, trimmed.length()) <= max) {
			return trimmed;
		}
		return trimmed.substring(0, trimmed.offsetByCodePoints(0, max)) + "…";
	}

	private static String trim(String text) {
		if (text == null) {
			return "";
		}
		int start = 0;
		int end = text.length();
		while (start < end && PHP_TRIM.indexOf(text.charAt(start)) >= 0) {
			start++;
		}
		while (end > start && PHP_TRIM.indexOf(text.charAt(end - 1)) >= 0) {
			end--;
		}
		return text.substring(start, end);
	}

}
