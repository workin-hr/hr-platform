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
 * truncated to 16 hex characters.
 *
 * <p><b>Why logging this is safe.</b> It is a digest of a secret under a
 * label published in this repository, so on its face it is an offline
 * oracle: anyone holding it can test candidate secrets. The reason that
 * costs nothing here is that <b>every access token this application
 * issues is already a stronger oracle over the same key</b> --
 * {@code HMAC-SHA256(secret, header + "." + payload)} with both halves
 * known and the whole thing handed to every client. An attacker who can
 * brute-force the secret from this line could brute-force it from any
 * token they hold, faster, without needing log access at all. The
 * marginal exposure is therefore zero, and the property the deployment
 * actually depends on is that an HS256 signing key has enough entropy to
 * resist that -- which {@link #PLACEHOLDER_SECRET} above exists to
 * enforce the floor of.
 *
 * <p>Note this is <i>not</i> the SSH host key fingerprint argument, which
 * an earlier version of this comment claimed: those digest a
 * <i>public</i> key, where confidentiality is not a property at all.
 * Truncating to 64 bits is a legibility choice, not a security one --
 * it is short enough to compare by eye and long enough that two real
 * secrets will not collide.
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
