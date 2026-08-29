package com.workin.legacy.auth.otp;

import java.net.InetAddress;
import java.net.UnknownHostException;

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
	 * {@code filter_var($raw, FILTER_VALIDATE_IP)}.
	 *
	 * <p>{@link InetAddress#getByName} would resolve a hostname, which
	 * {@code FILTER_VALIDATE_IP} never does, so the input is screened first:
	 * it must contain only characters that can appear in a literal address.
	 */
	private static boolean isIpAddress(String raw) {
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
					|| c == '.' || c == ':';
			if (!allowed) {
				return false;
			}
		}
		try {
			InetAddress.getByName(raw);
			return true;
		} catch (UnknownHostException ex) {
			return false;
		}
	}
}
