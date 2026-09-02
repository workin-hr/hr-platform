package com.workin.devices;

import java.util.regex.Pattern;

/**
 * Handling for text a device supplies. Every value reaching this class came
 * from an unauthenticated request, so nothing here trusts a length, a
 * character set, or the absence of control characters.
 *
 * <p>One place for the four things the module kept re-deriving: bounding a
 * value before it reaches a column, making one safe to log, and deciding
 * whether a serial number or a stamp is well formed at all.
 */
public final class DeviceInput {

	/**
	 * A vendor serial number. Bounded to the column's 64 characters, because
	 * the legacy database runs non-strict (D-037) and would otherwise
	 * <em>truncate</em> an over-long value silently -- turning two different
	 * serials into one registry key. The character set is what serials
	 * actually use; anything else is a caller that is not a terminal.
	 */
	private static final Pattern SERIAL_NUMBER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}$");

	/**
	 * An {@code ATTLOGStamp}. Digits only: the value is echoed back to the
	 * device in the handshake, so anything else -- a CR/LF above all -- would
	 * let a caller write extra lines into the protocol response.
	 */
	private static final Pattern STAMP = Pattern.compile("^\\d{1,32}$");

	/**
	 * A device PIN. One rule for every path that handles one -- the receiver's
	 * parser, the binding API and both {@code VARCHAR(32)} columns -- because
	 * a PIN the API accepts but the parser rejects is the worst combination:
	 * the binding reports success and every punch it should have matched is
	 * quarantined as malformed instead.
	 */
	private static final Pattern PIN = Pattern.compile("^\\d{1,32}$");

	private DeviceInput() {
	}

	public static boolean isValidSerialNumber(String value) {
		return value != null && SERIAL_NUMBER.matcher(value).matches();
	}

	public static boolean isValidStamp(String value) {
		return value != null && STAMP.matcher(value).matches();
	}

	public static boolean isValidPin(String value) {
		return value != null && PIN.matcher(value).matches();
	}

	/** Stripped, bounded to {@code max}, and null rather than empty. */
	public static String bounded(String value, int max) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
	}

	/**
	 * A value safe to put in a log line: every ISO control character becomes
	 * {@code .}, so a caller cannot forge log entries by embedding newlines,
	 * and the result is bounded so it cannot flood one.
	 */
	public static String forLog(String value, int max) {
		if (value == null) {
			return "";
		}
		String bounded = value.length() <= max ? value : value.substring(0, max);
		StringBuilder safe = new StringBuilder(bounded.length());
		for (int index = 0; index < bounded.length(); index++) {
			char character = bounded.charAt(index);
			safe.append(Character.isISOControl(character) ? '.' : character);
		}
		return safe.toString();
	}
}
