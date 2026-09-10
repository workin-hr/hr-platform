package com.workin.devices;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The query string of a device request, parsed without touching the body.
 *
 * <p>This exists because {@code @RequestParam} does not mean "read the query
 * string". On a POST it means "read the servlet parameter map", and for
 * {@code application/x-www-form-urlencoded} the container builds that map by
 * <b>consuming the request body</b>. A handler that then reads the body sees
 * an empty stream -- so an upload of punches would parse as zero records and
 * be acknowledged as delivered, and the terminal would drop them. Devices do
 * not all agree on the content type they send, and that failure is silent and
 * unrecoverable, so the device-facing POST handlers take their parameters
 * from here instead.
 *
 * <p>Bounded on both counts: a caller supplies this string, so neither the
 * number of parameters nor their length is trusted.
 */
public final class QueryParameters {

	private static final int MAX_PARAMETERS = 32;

	/**
	 * Segments looked at before giving up, empty ones included.
	 *
	 * <p>Separate from {@link #MAX_PARAMETERS} because empty segments now cost
	 * a loop iteration rather than ending the parse, and a query string of
	 * nothing but separators must not be able to spend more than a bounded
	 * amount of work.
	 */
	private static final int MAX_SEGMENTS = 256;

	private static final int MAX_VALUE_LENGTH = 256;

	private QueryParameters() {
	}

	/**
	 * @return the decoded parameters; on a repeated name the first wins, so a
	 *         caller cannot change which value a handler sees by appending
	 *         another copy
	 */
	public static Map<String, String> parse(String queryString) {
		Map<String, String> parameters = new LinkedHashMap<>();
		if (queryString == null || queryString.isEmpty()) {
			return parameters;
		}
		// An empty segment is SKIPPED, never a stop. `SN=DEV-1&&table=ATTLOG` --
		// a redundant separator from a terminal or an intermediary -- used to
		// end the parse at the gap, so `table` was never read, the upload fell
		// into the unknown-table default, and the whole punch body was
		// discarded behind a 200 OK. The terminal takes that as delivery and
		// drops records that were never stored.
		//
		// Split with no limit, and bound the work by segments EXAMINED rather
		// than by split parts: with a limit, enough empty segments would push
		// the real parameters into one unsplit remainder and parse it as a
		// single malformed name.
		int examined = 0;
		for (String pair : queryString.split("&", -1)) {
			if (parameters.size() >= MAX_PARAMETERS || ++examined > MAX_SEGMENTS) {
				break;
			}
			if (pair.isEmpty()) {
				continue;
			}
			int equals = pair.indexOf('=');
			String name = decode(equals < 0 ? pair : pair.substring(0, equals));
			String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
			parameters.putIfAbsent(name, value);
		}
		return parameters;
	}

	private static String decode(String raw) {
		String bounded = raw.length() <= MAX_VALUE_LENGTH ? raw : raw.substring(0, MAX_VALUE_LENGTH);
		try {
			return URLDecoder.decode(bounded, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			// A malformed %-escape is not worth refusing the request over; the
			// caller's value simply stays as written and fails validation.
			return bounded;
		}
	}
}
