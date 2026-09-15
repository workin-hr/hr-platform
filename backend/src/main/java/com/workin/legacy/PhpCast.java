package com.workin.legacy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHP 8's {@code (int)} cast of a string, for values legacy reads with
 * {@code (int) $_POST[...]}.
 *
 * <p>Held to PHP 8.3 itself by {@code PhpCastTest}. The cast reads the longest
 * leading decimal or exponent number, so {@code "1.5"} is 1 and {@code "1e2"}
 * is 100 -- a parser that keeps only the leading digits reads that as 1.
 * Anything that is not such a number is 0, including {@code ".5"} read as a
 * float first ({@code 0.5}, then 0), hexadecimal, {@code "INF"} and non-ASCII
 * digits. A value past the 64-bit range saturates; a float that overflows to
 * infinity is 0.
 */
public final class PhpCast {

	/** PHP's whitespace for a numeric string: space, tab, newline, return, vertical tab, form feed. */
	private static final String WHITESPACE = " \t\n\r\013\f";

	private static final Pattern LEADING_NUMBER =
			Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

	private PhpCast() {
	}

	public static long intval(String raw) {
		if (raw == null) {
			return 0L;
		}
		int start = 0;
		while (start < raw.length() && WHITESPACE.indexOf(raw.charAt(start)) >= 0) {
			start++;
		}
		Matcher number = LEADING_NUMBER.matcher(raw).region(start, raw.length());
		if (!number.lookingAt()) {
			return 0L;
		}
		String text = number.group();
		if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
			try {
				return Long.parseLong(text);
			} catch (NumberFormatException outOfRange) {
				return text.charAt(0) == '-' ? Long.MIN_VALUE : Long.MAX_VALUE;
			}
		}
		double value = Double.parseDouble(text);
		// Java's narrowing saturates at the long range, as PHP 8's cast does on a
		// 64-bit build; only a float that overflowed to infinity becomes 0.
		return Double.isInfinite(value) ? 0L : (long) value;
	}
}
