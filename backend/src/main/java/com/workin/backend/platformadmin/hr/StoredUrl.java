package com.workin.backend.platformadmin.hr;

import java.util.Locale;

/**
 * A file URL read from the database, as the link a page opens (D-262).
 *
 * <p>Legacy writes a stored URL into an {@code href} as it is. The upload endpoints store their own
 * absolute URL, so nothing they wrote names another scheme; this keeps a {@code javascript:} value
 * written some other way from becoming a link an administrator clicks.
 *
 * <p>A value with no scheme is a path on this host, and stays one. Two leading separators name
 * another origin instead, whichever way they lean -- a browser reads {@code \} as {@code /} here,
 * so {@code //host}, {@code \\host}, {@code /\host} and {@code \/host} are all refused. A value
 * carrying a control character is refused outright: browsers drop those before parsing, so the
 * text checked here would not be the URL the browser follows.
 */
public final class StoredUrl {

	private StoredUrl() {
	}

	/**
	 * The stored URL, stripped, when it is http, https or has no scheme; otherwise, or when it is
	 * blank, null.
	 */
	public static String href(String stored) {
		if (stored == null || stored.isBlank()) {
			return null;
		}
		String url = stored.strip();
		for (int at = 0; at < url.length(); at++) {
			char character = url.charAt(at);
			if (character < ' ' || character == 0x7F) {
				return null;
			}
		}
		if (url.length() > 1 && separator(url.charAt(0)) && separator(url.charAt(1))) {
			return null;
		}
		int colon = url.indexOf(':');
		int path = -1;
		for (char separator : new char[] {'/', '?', '#'}) {
			int at = url.indexOf(separator);
			if (at >= 0 && (path < 0 || at < path)) {
				path = at;
			}
		}
		if (colon < 0 || (path >= 0 && path < colon)) {
			return url;
		}
		String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
		return scheme.equals("http") || scheme.equals("https") ? url : null;
	}

	/** A browser reads a backslash as a slash in this position, so both open a host. */
	private static boolean separator(char character) {
		return character == '/' || character == '\\';
	}

}
