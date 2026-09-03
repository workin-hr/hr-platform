package com.workin.backend.platformadmin.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords.
 *
 * <p>Written out rather than pulled in as a dependency: the cryptography is
 * {@code javax.crypto}'s HMAC, and what remains is counter formatting and the
 * RFC's dynamic truncation -- about thirty lines, against a specification with
 * published test vectors. {@code TotpTest} runs those vectors, so this is
 * checked against the standard rather than against itself.
 *
 * <p>HMAC-SHA1, six digits, thirty-second steps: the parameters every
 * authenticator application implements. Anything else is interoperable in
 * theory and unusable in practice.
 */
public final class Totp {

	public static final Duration TIME_STEP = Duration.ofSeconds(30);

	public static final int DIGITS = 6;

	/**
	 * How many steps either side of the current one are accepted, to tolerate
	 * clock skew between the server and the authenticator.
	 *
	 * <p>One, not more. Each extra step widens the guessing surface and lengthens
	 * the replay window that prerequisite 12's single-use rule then has to close.
	 */
	public static final int SKEW_STEPS = 1;

	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {
	}

	/** A new 160-bit seed -- the SHA-1 block size, per RFC 4226 section 4. */
	public static byte[] newSeed() {
		byte[] seed = new byte[20];
		RANDOM.nextBytes(seed);
		return seed;
	}

	public static long timeStepAt(Instant instant) {
		return Math.floorDiv(instant.getEpochSecond(), TIME_STEP.getSeconds());
	}

	public static String codeAt(byte[] seed, long timeStep) {
		byte[] hmac = hmacSha1(seed, ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array());
		// RFC 4226 section 5.3 dynamic truncation.
		int offset = hmac[hmac.length - 1] & 0x0F;
		int binary = ((hmac[offset] & 0x7F) << 24)
				| ((hmac[offset + 1] & 0xFF) << 16)
				| ((hmac[offset + 2] & 0xFF) << 8)
				| (hmac[offset + 3] & 0xFF);
		int modulus = (int) Math.pow(10, DIGITS);
		return String.format("%0" + DIGITS + "d", binary % modulus);
	}

	/**
	 * The time step a code matches, within the accepted skew, or empty.
	 *
	 * <p>Returns the step rather than a boolean so the caller can enforce
	 * single use: recording <em>which</em> step was accepted is what lets a
	 * replay of the same six digits be refused (ADR-0015 prerequisite 12). A
	 * boolean cannot express that.
	 */
	public static java.util.OptionalLong matchingTimeStep(byte[] seed, String presentedCode, Instant now) {
		if (presentedCode == null || presentedCode.length() != DIGITS) {
			return java.util.OptionalLong.empty();
		}
		long current = timeStepAt(now);
		for (long step = current - SKEW_STEPS; step <= current + SKEW_STEPS; step++) {
			if (constantTimeEquals(codeAt(seed, step), presentedCode)) {
				return java.util.OptionalLong.of(step);
			}
		}
		return java.util.OptionalLong.empty();
	}

	/**
	 * Comparison that does not leak how much of the code was right.
	 *
	 * <p>A short-circuiting equals on a six-digit secret is a small leak, but it
	 * is a free one to remove and the codes are exactly the kind of low-entropy
	 * value where it would matter.
	 */
	private static boolean constantTimeEquals(String expected, String presented) {
		return MessageDigest.isEqual(
				expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
				presented.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	private static byte[] hmacSha1(byte[] key, byte[] message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			return mac.doFinal(message);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA1 unavailable", ex);
		}
	}

	/** Base32 (RFC 4648, no padding) -- what authenticator apps consume. */
	public static String toBase32(byte[] data) {
		final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		StringBuilder out = new StringBuilder();
		int buffer = 0;
		int bitsLeft = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xFF);
			bitsLeft += 8;
			while (bitsLeft >= 5) {
				out.append(alphabet.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
				bitsLeft -= 5;
			}
		}
		if (bitsLeft > 0) {
			out.append(alphabet.charAt((buffer << (5 - bitsLeft)) & 0x1F));
		}
		return out.toString();
	}

}
