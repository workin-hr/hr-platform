package com.workin.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque (non-JWT) token primitives shared by both session domains.
 * Refresh tokens are deliberately opaque random values looked up
 * server-side "so [they] can be looked up, listed, and revoked
 * individually" (docs/security/authentication-remediation-design.md,
 * Target Design item 1) -- only the SHA-256 digest is ever persisted.
 */
public final class OpaqueTokens {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private OpaqueTokens() {
	}

	public static String newToken() {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public static String sha256Hex(String rawToken) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

}
