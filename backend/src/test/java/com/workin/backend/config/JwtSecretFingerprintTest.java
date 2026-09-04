package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the fingerprint construction R-024 is checked with.
 *
 * <p>The expected value comes from an independent implementation --
 * Python's {@code hmac.new(secret, label, sha256).hexdigest()[:16]} --
 * rather than from this class, so the test would fail if the Java side
 * changed the label, the algorithm, the truncation, or the order of key
 * and data. That last one is the real trap: PHP's
 * {@code hash_hmac($algo, $data, $key)} takes the message second and the
 * key third, the opposite way round from most APIs, and getting it
 * backwards produces a stable, plausible, entirely wrong fingerprint on
 * one side only.
 */
class JwtSecretFingerprintTest {

	private static final String SECRET = "a-test-secret-not-used-anywhere-real";

	@Test
	void matchesAnIndependentlyComputedVector() {
		assertThat(JwtSecretStartupCheck.fingerprint(SECRET)).isEqualTo("2c2006634e5a13bb");
	}

	@Test
	void isSixteenHexCharacters() {
		assertThat(JwtSecretStartupCheck.fingerprint(SECRET)).matches("[0-9a-f]{16}");
	}

	/** A mismatch is the whole point: two secrets must not print the same thing. */
	@Test
	void differsForADifferentSecret() {
		assertThat(JwtSecretStartupCheck.fingerprint(SECRET))
				.isNotEqualTo(JwtSecretStartupCheck.fingerprint(SECRET + "x"));
	}

	/** The label is versioned so a scheme change cannot be misread as a key mismatch. */
	@Test
	void theLabelIsVersioned() {
		assertThat(JwtSecretStartupCheck.FINGERPRINT_LABEL).endsWith("-v1");
	}

}
