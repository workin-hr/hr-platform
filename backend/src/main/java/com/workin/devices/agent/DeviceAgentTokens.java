package com.workin.devices.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Agent bearer tokens: 256 random bits behind a {@code wda_} prefix, stored
 * only as SHA-256.
 *
 * <p>A plain digest, not a password hash, because the input is random rather
 * than chosen by a person: there is no dictionary to slow down, and a lookup by
 * digest is what lets authentication be one indexed query. The prefix makes a
 * leaked token recognisable to a secret scanner.
 */
public final class DeviceAgentTokens {

	static final String PREFIX = "wda_";

	/** The prefix and 43 base64url characters; anything else is refused before it is hashed. */
	private static final Pattern SHAPE = Pattern.compile("^wda_[A-Za-z0-9_-]{43}$");

	private static final SecureRandom RANDOM = new SecureRandom();

	private DeviceAgentTokens() {
	}

	/** @param token shown to the administrator once and never stored */
	public record Issued(String token, String sha256, String hint) {
	}

	public static Issued issue() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return new Issued(token, sha256(token), token.substring(token.length() - 4));
	}

	public static boolean isWellFormed(String token) {
		return token != null && SHAPE.matcher(token).matches();
	}

	public static String sha256(String token) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}
