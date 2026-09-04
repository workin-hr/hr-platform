package com.workin.backend.config;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Refuses to start if the old documentation placeholder JWT secret is
 * ever reused as a real runtime value. Missing secrets already fail
 * earlier via property resolution; this check closes the second failure
 * mode where a human explicitly sets the known placeholder string.
 *
 * <p>Also prints the secret's <b>fingerprint</b>, which is how R-024 gets
 * checked before it can do any damage. Phase 1's zero-client-change
 * property (D-111) requires this deployment and the PHP one to sign with
 * the same HS256 secret: if they differ, cutover force-logs-out every
 * live session at once and a rollback does it a second time. Nothing
 * compared the two values, and neither may be printed to compare them by
 * eye.
 *
 * <p>The fingerprint is {@code HMAC-SHA256(secret, FINGERPRINT_LABEL)}
 * truncated to 16 hex characters -- the same construction an SSH host
 * key fingerprint uses, and safe to log for the same reason: HMAC is a
 * pseudo-random function, so the digest reveals nothing about the key,
 * and 64 bits is far too short to be useful as a signature while being
 * long enough that two different secrets will not collide in practice.
 * Print it on both stacks and compare; equal means the transition is
 * transparent in both directions. See
 * {@code docs/operations/verifying-the-signing-secret.md} for the PHP
 * side of the comparison.
 */
@Component
public class JwtSecretStartupCheck implements ApplicationRunner {

	static final String PLACEHOLDER_SECRET = "dev-only-placeholder-secret-replace-before-any-real-deployment-000000";

	/**
	 * The constant both stacks HMAC. Versioned so that if the scheme ever
	 * changes, an old fingerprint cannot be silently compared against a
	 * new one and read as a mismatch.
	 */
	static final String FINGERPRINT_LABEL = "workin-jwt-secret-fingerprint-v1";

	private static final Logger log = LoggerFactory.getLogger(JwtSecretStartupCheck.class);

	private final String jwtSecret;

	public JwtSecretStartupCheck(@Value("${app.jwt.secret}") String jwtSecret) {
		this.jwtSecret = jwtSecret;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (PLACEHOLDER_SECRET.equals(jwtSecret)) {
			throw new IllegalStateException(
					"Application JWT secret is still set to the known placeholder value. "
							+ "Refusing to start with a forgeable token-signing key.");
		}
		log.info("JWT signing secret fingerprint: {} -- must equal the PHP deployment's "
				+ "(docs/operations/verifying-the-signing-secret.md, R-024).", fingerprint(jwtSecret));
	}

	/**
	 * @return 16 hex characters of {@code HMAC-SHA256(secret, FINGERPRINT_LABEL)}
	 */
	static String fingerprint(String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] digest = mac.doFinal(FINGERPRINT_LABEL.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HmacSHA256 must be available to fingerprint the signing secret", ex);
		}
	}

}
