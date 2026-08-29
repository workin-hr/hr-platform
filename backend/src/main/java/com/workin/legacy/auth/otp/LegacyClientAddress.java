package com.workin.legacy.auth.otp;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code otp_client_ip()} and {@code otp_client_user_agent()}
 * ({@code otp_helper.php:39-69}).
 *
 * <h2>The header order is a trust decision, and it is legacy's</h2>
 * <p>{@code CF-Connecting-IP}, then {@code X-Forwarded-For}, then
 * {@code X-Real-IP}, then the socket address. The first three are
 * <b>client-supplied</b> and are trusted ahead of the one that is not, so any
 * caller can choose the IP that the per-IP rate limit will see -- which is to
 * say, can bypass it by rotating a header. That is preserved (D-058) and it is
 * one more reason R-014's global cap is currently the only cap that bites.
 *
 * <p>The first entry of a comma-separated {@code X-Forwarded-For} wins, and
 * every candidate must parse as an IP address ({@code FILTER_VALIDATE_IP}) or
 * it is skipped. An unparseable value therefore falls through to the next
 * header rather than being used.
 */
public final class LegacyClientAddress {

	private LegacyClientAddress() {
	}

	/** @return the resolved address, or {@code ""} when none parsed */
	public static String clientIp(HttpServletRequest request) {
		String[] candidates = {
			request.getHeader("CF-Connecting-IP"),
			request.getHeader("X-Forwarded-For"),
			request.getHeader("X-Real-IP"),
			request.getRemoteAddr(),
		};
		for (String candidate : candidates) {
			if (candidate == null) {
				continue;
			}
			String raw = candidate.trim();
			if (raw.isEmpty()) {
				continue;
			}
			int comma = raw.indexOf(',');
			if (comma >= 0) {
				raw = raw.substring(0, comma).trim();
			}
			if (isIpAddress(raw)) {
				return raw;
			}
		}
		return "";
	}

	/** {@code mb_substr($ua, 0, 512)} -- 512 <em>characters</em>, not bytes. */
	public static String userAgent(HttpServletRequest request) {
		String header = request.getHeader("User-Agent");
		if (header == null) {
			return "";
		}
		String trimmed = header.trim();
		if (trimmed.isEmpty()) {
			return "";
		}
		return trimmed.codePointCount(0, trimmed.length()) <= 512
				? trimmed
				: trimmed.substring(0, trimmed.offsetByCodePoints(0, 512));
	}

	/**
	 * {@code filter_var($raw, FILTER_VALIDATE_IP)} -- a <b>literal</b> parser,
	 * with no name resolution of any kind.
	 *
	 * <p>{@code InetAddress.getByName()} is deliberately not used. It resolves
	 * hostnames, which {@code FILTER_VALIDATE_IP} never does, and a
	 * hex-and-dots hostname like {@code bad.cafe} passes any character screen --
	 * so an <b>unauthenticated</b> OTP caller could put one in
	 * {@code X-Forwarded-For} and make the server perform a blocking DNS lookup
	 * of a name they chose, on the request thread. Two problems in one: a
	 * divergence from PHP, and outbound resolution driven by anonymous input.
	 *
	 * <p>So both families are parsed by hand: dotted-quad IPv4 with no leading
	 * zeros beyond a bare {@code "0"}, and IPv6 including the {@code ::}
	 * compression and the IPv4-mapped tail form.
	 */
	private static boolean isIpAddress(String raw) {
		return raw.indexOf(':') >= 0 ? isIpv6(raw) : isIpv4(raw);
	}

	private static boolean isIpv4(String raw) {
		String[] parts = raw.split("\\.", -1);
		if (parts.length != 4) {
			return false;
		}
		for (String part : parts) {
			if (part.isEmpty() || part.length() > 3) {
				return false;
			}
			for (int i = 0; i < part.length(); i++) {
				if (part.charAt(i) < '0' || part.charAt(i) > '9') {
					return false;
				}
			}
			// "01" is not a valid dotted-quad octet for FILTER_VALIDATE_IP.
			if (part.length() > 1 && part.charAt(0) == '0') {
				return false;
			}
			if (Integer.parseInt(part) > 255) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Groups of 1-4 hex digits separated by colons: exactly eight, or fewer
	 * with one {@code ::} standing in for the rest. The final group may be a
	 * dotted-quad IPv4 literal, which counts as two.
	 */
	private static boolean isIpv6(String raw) {
		int compression = raw.indexOf("::");
		if (compression >= 0 && raw.indexOf("::", compression + 1) >= 0) {
			return false;
		}
		String head = compression < 0 ? raw : raw.substring(0, compression);
		String tail = compression < 0 ? "" : raw.substring(compression + 2);
		if (compression < 0 && (raw.startsWith(":") || raw.endsWith(":"))) {
			return false;
		}

		List<String> groups = new ArrayList<>();
		for (String half : List.of(head, tail)) {
			if (!half.isEmpty()) {
				groups.addAll(List.of(half.split(":", -1)));
			}
		}

		int counted = 0;
		for (int i = 0; i < groups.size(); i++) {
			String group = groups.get(i);
			if (group.indexOf('.') >= 0) {
				// Only the very last group may be an IPv4 literal.
				if (i != groups.size() - 1 || !isIpv4(group)) {
					return false;
				}
				counted += 2;
				continue;
			}
			if (group.isEmpty() || group.length() > 4) {
				return false;
			}
			for (int c = 0; c < group.length(); c++) {
				char ch = group.charAt(c);
				boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
						|| (ch >= 'A' && ch <= 'F');
				if (!hex) {
					return false;
				}
			}
			counted++;
		}
		return compression < 0 ? counted == 8 : counted < 8;
	}
}
