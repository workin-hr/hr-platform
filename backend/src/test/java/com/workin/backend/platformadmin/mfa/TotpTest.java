package com.workin.backend.platformadmin.mfa;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 6238's own test vectors, so this implementation is checked against the
 * specification rather than against itself.
 *
 * <p>The RFC's Appendix B table publishes eight-digit codes; this
 * implementation issues six, which is what authenticator applications use, so
 * the expectations below are the last six digits of each published value. That
 * is a property of truncation, not a reinterpretation of the vector: RFC 4226
 * section 5.3 takes the value modulo 10^Digits, so the six-digit code is always
 * the eight-digit one's final six characters.
 */
class TotpTest {

	/** The RFC's SHA-1 seed: the ASCII string "12345678901234567890". */
	private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	@ParameterizedTest(name = "RFC 6238 vector at T={0} -> {1}")
	@CsvSource({
		"59,          287082",
		"1111111109,  081804",
		"1111111111,  050471",
		"1234567890,  005924",
		"2000000000,  279037",
		"20000000000, 353130",
	})
	void matchesTheRfc6238TestVectors(long epochSecond, String expectedCode) {
		long timeStep = Totp.timeStepAt(Instant.ofEpochSecond(epochSecond));

		assertThat(Totp.codeAt(RFC_SEED, timeStep)).isEqualTo(expectedCode);
	}

	@Test
	void acceptsACodeFromTheStepEitherSideToToleratePartialClockSkew() {
		Instant now = Instant.ofEpochSecond(1_111_111_111L);
		long current = Totp.timeStepAt(now);

		assertThat(Totp.matchingTimeStep(RFC_SEED, Totp.codeAt(RFC_SEED, current - 1), now))
			.hasValue(current - 1);
		assertThat(Totp.matchingTimeStep(RFC_SEED, Totp.codeAt(RFC_SEED, current), now))
			.hasValue(current);
		assertThat(Totp.matchingTimeStep(RFC_SEED, Totp.codeAt(RFC_SEED, current + 1), now))
			.hasValue(current + 1);
	}

	@Test
	void refusesACodeFromOutsideTheAcceptedSkew() {
		Instant now = Instant.ofEpochSecond(1_111_111_111L);
		long current = Totp.timeStepAt(now);

		assertThat(Totp.matchingTimeStep(RFC_SEED, Totp.codeAt(RFC_SEED, current - 2), now))
			.isEmpty();
		assertThat(Totp.matchingTimeStep(RFC_SEED, Totp.codeAt(RFC_SEED, current + 2), now))
			.isEmpty();
	}

	@Test
	void reportsWhichStepMatchedSoASingleUseRuleCanBeEnforced() {
		Instant now = Instant.ofEpochSecond(1_234_567_890L);
		long current = Totp.timeStepAt(now);

		OptionalLong matched = Totp.matchingTimeStep(RFC_SEED, Totp.codeAt(RFC_SEED, current), now);

		assertThat(matched)
			.as("a boolean cannot express which code was spent, so a replay could not be refused")
			.hasValue(current);
	}

	@Test
	void refusesMalformedInputWithoutThrowing() {
		Instant now = Instant.now();

		assertThat(Totp.matchingTimeStep(RFC_SEED, null, now)).isEmpty();
		assertThat(Totp.matchingTimeStep(RFC_SEED, "", now)).isEmpty();
		assertThat(Totp.matchingTimeStep(RFC_SEED, "12345", now)).isEmpty();
		assertThat(Totp.matchingTimeStep(RFC_SEED, "1234567", now)).isEmpty();
		assertThat(Totp.matchingTimeStep(RFC_SEED, "abcdef", now)).isEmpty();
	}

	@Test
	void seedsAreOneHundredAndSixtyBitsAndNotRepeated() {
		byte[] first = Totp.newSeed();
		byte[] second = Totp.newSeed();

		assertThat(first).hasSize(20);
		assertThat(first).isNotEqualTo(second);
	}

	@Test
	void base32MatchesRfc4648() {
		// RFC 4648 section 10 test vectors, unpadded.
		assertThat(Totp.toBase32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
		assertThat(Totp.toBase32("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
		assertThat(Totp.toBase32("foo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6");
		assertThat(Totp.toBase32("foob".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YQ");
		assertThat(Totp.toBase32("fooba".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTB");
		assertThat(Totp.toBase32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
	}

}
