package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link PhpCast} against PHP 8.3 itself.
 *
 * <p>{@code legacy-parity/php-intval.json} was produced by running
 * {@code (int) $s} over each input in a {@code php:8.3-cli-alpine} container with
 * no network, and recording what PHP returned. The inputs cover what a
 * {@code type="number"} field can post once the browser's own check is off, and
 * the edges of the cast: decimals, exponents, whitespace, leading-numeric strings,
 * strings that are not numbers, values past the 64-bit range, and non-ASCII digits.
 */
class PhpCastTest {

	@Test
	@SuppressWarnings("unchecked")
	void intvalAgreesWithPhpOnEveryInputInTheCorpus() throws IOException {
		Map<String, Object> corpus;
		try (InputStream in = PhpCastTest.class.getResourceAsStream("/legacy-parity/php-intval.json")) {
			corpus = new tools.jackson.databind.ObjectMapper().readValue(in, Map.class);
		}
		assertThat((String) corpus.get("php")).startsWith("8.3.");
		List<Map<String, Object>> cases = (List<Map<String, Object>>) corpus.get("cases");
		assertThat(cases).hasSizeGreaterThan(50);
		for (Map<String, Object> sample : cases) {
			String input = (String) sample.get("in");
			assertThat(PhpCast.intval(input))
					.as("(int) %s", new tools.jackson.databind.ObjectMapper().writeValueAsString(input))
					.isEqualTo(((Number) sample.get("int")).longValue());
		}
	}

	@Test
	void theCastsAPlannedCountFieldCanPostAreTheOnesLegacyStores() {
		assertThat(PhpCast.intval("1.5")).isEqualTo(1);
		assertThat(PhpCast.intval("1e2")).as("a leading-digits parser reads 1").isEqualTo(100);
		assertThat(PhpCast.intval("")).isZero();
		assertThat(PhpCast.intval(null)).isZero();
	}
}
