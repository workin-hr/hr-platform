package com.workin.backend.platformadmin.web;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

/**
 * Where a list page's row action sends the administrator back to: the list
 * they were looking at, on the page and under the filters they had, not page
 * one of the unfiltered list (#347).
 *
 * <p>Legacy redirects to the bare page after every write, which was harmless
 * while every list was a single page. Once the lists page (D-282, D-283), a
 * refusal on page 3 at fifty a page landed on page 1 at ten, and the row the
 * operator had just acted on was nowhere in view.
 *
 * <p>The state is read from the {@code Referer} of the POST rather than posted
 * by every form, so the fourteen list pages need no template change: a browser
 * sends the full URL for a same-origin request under the default referrer
 * policy, and the admin pages set none. Only the query string is taken, and
 * only when the referring path is the list's own, so the redirect can never
 * leave {@code path}. Each key must look like a parameter this dashboard
 * uses, and every value is decoded and re-encoded, so nothing in the header
 * reaches the {@code Location} unescaped. Without a usable referrer -- a
 * client that strips it, or a test -- the answer is the bare path, which is
 * exactly the behaviour before this existed.
 */
final class AdminReturnTo {

	/** A dashboard query key: lower-case words joined by underscores, and {@code x[]} lists. */
	private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,39}(?:\\[\\])?");

	/**
	 * Never carried: {@code error} is the previous action's message, and the
	 * form-opening keys belong to the page the action replaced.
	 */
	private static final Set<String> DROPPED = Set.of("error", "action", "id");

	/** The page keys a list uses; dropped with the company filter when it no longer applies. */
	private static final Pattern PAGE_KEY = Pattern.compile("(?:[a-z]+_)?page");

	private static final int MAX_PARAMETERS = 40;

	private AdminReturnTo() {
	}

	/**
	 * The list's own query, as {@code ?a=1&b=2}, or {@code ""}.
	 *
	 * @param writtenCompany the company the action wrote to, or {@code 0}; a
	 *        carried {@code company_id} naming a different company is dropped,
	 *        with the page numbers, so {@code rememberAfterWrite}'s filter
	 *        applies and the row just written is on screen
	 */
	static String query(HttpServletRequest request, String path, long writtenCompany) {
		List<String> kept = carried(request, path, writtenCompany);
		return kept.isEmpty() ? "" : "?" + String.join("&", kept);
	}

	/** {@link #query} with {@code error=<key>} appended. */
	static String queryWithError(HttpServletRequest request, String path, String errorKey) {
		List<String> kept = carried(request, path, 0L);
		kept.add("error=" + encode(errorKey));
		return "?" + String.join("&", kept);
	}

	/**
	 * The value {@code query} (as returned by {@link #query}) gives {@code key},
	 * or {@code null} when it names no such key.
	 */
	static String parameter(String query, String key) {
		if (query.isEmpty()) {
			return null;
		}
		for (String pair : query.substring(1).split("&")) {
			int equals = pair.indexOf('=');
			if (equals > 0 && key.equals(decode(pair.substring(0, equals)))) {
				return decode(pair.substring(equals + 1));
			}
		}
		return null;
	}

	private static List<String> carried(HttpServletRequest request, String path, long writtenCompany) {
		List<String> kept = new ArrayList<>();
		String referer = request.getHeader(HttpHeaders.REFERER);
		if (referer == null || referer.isBlank()) {
			return kept;
		}
		URI uri;
		try {
			uri = URI.create(referer.strip());
		} catch (IllegalArgumentException malformed) {
			return kept;
		}
		if (!path.equals(uri.getPath()) || uri.getRawQuery() == null) {
			return kept;
		}
		List<String[]> pairs = new ArrayList<>();
		boolean dropCompany = false;
		for (String pair : uri.getRawQuery().split("&")) {
			if (pair.isEmpty() || pairs.size() >= MAX_PARAMETERS) {
				continue;
			}
			int equals = pair.indexOf('=');
			String key = decode(equals < 0 ? pair : pair.substring(0, equals));
			String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
			if (key == null || value == null || !KEY.matcher(key).matches() || DROPPED.contains(key)) {
				continue;
			}
			if ("company_id".equals(key) && writtenCompany > 0 && !value.equals(Long.toString(writtenCompany))) {
				dropCompany = true;
			}
			pairs.add(new String[] {key, value});
		}
		for (String[] pair : pairs) {
			if (dropCompany && ("company_id".equals(pair[0]) || PAGE_KEY.matcher(pair[0]).matches())) {
				continue;
			}
			kept.add(encode(pair[0]) + "=" + encode(pair[1]));
		}
		return kept;
	}

	private static String decode(String raw) {
		try {
			return URLDecoder.decode(raw, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException malformed) {
			return null;
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
