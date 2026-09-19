package com.workin.backend.platformadmin.hr;

import java.util.Locale;

/**
 * A file URL read from the database, as the link a page opens (D-262).
 *
 * <p>Legacy writes a stored URL into an {@code href} as it is. The upload endpoints store their own
 * absolute URL, so nothing they wrote names another scheme; this keeps a {@code javascript:} value
 * written some other way from becoming a link an administrator clicks.
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

}
