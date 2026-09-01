package com.workin.legacy.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins this module against PHP's {@code json_encode}, value by value.
 *
 * <p>Every expectation here was measured against the running legacy PHP rather
 * than reasoned about — `php -r 'echo json_encode((float) $v);'` — because the
 * rule is not obvious from either language's documentation: PHP writes the
 * <em>shortest</em> representation of a float, so a whole value loses its
 * fraction and a trailing zero is dropped.
 *
 * <p>The case that matters most is {@code 100.50}. An earlier version of this
 * module handled only whole values and passed the original scaled
 * {@code BigDecimal} through for everything else, emitting {@code 100.50} where
 * PHP emits {@code 100.5} — and payslip enrichment works entirely in scale-2
 * {@code BigDecimal}s, so any amount ending in one fractional zero diverged.
 */
class LegacyPhpNumberJsonConfigTest {

	private final ObjectMapper mapper = JsonMapper.builder()
			.addModule(new LegacyPhpNumberJsonConfig().legacyPhpNumberModule())
			.build();

	private String render(Object value) {
		return mapper.writeValueAsString(Map.of("v", value)).replace("{\"v\":", "").replace("}", "");
	}

	@Test
	void wholeDoublesLoseTheirFractionAsPhpDoes() {
		assertThat(render(0.0d)).isEqualTo("0");
		assertThat(render(2604.0d)).isEqualTo("2604");
		assertThat(render(-7.0d)).isEqualTo("-7");
	}

	@Test
	void fractionalDoublesAreUnchanged() {
		assertThat(render(2604.5d)).isEqualTo("2604.5");
		assertThat(render(0.1d)).isEqualTo("0.1");
	}

	@Test
	void wholeBigDecimalsLoseTheirScale() {
		// DECIMAL(10,2) holding a whole amount: PHP renders 5000, not 5000.00.
		assertThat(render(new BigDecimal("5000.00"))).isEqualTo("5000");
		assertThat(render(new BigDecimal("0.00"))).isEqualTo("0");
	}

	@Test
	void fractionalBigDecimalsDropTrailingZeroes() {
		// The regression this test exists for.
		assertThat(render(new BigDecimal("100.50"))).isEqualTo("100.5");
		assertThat(render(new BigDecimal("0.10"))).isEqualTo("0.1");
		assertThat(render(new BigDecimal("2604.50"))).isEqualTo("2604.5");
	}

	@Test
	void genuineFractionsSurviveIntact() {
		assertThat(render(new BigDecimal("100.55"))).isEqualTo("100.55");
		assertThat(render(new BigDecimal("0.01"))).isEqualTo("0.01");
	}

	/**
	 * Above 2^53 a double cannot represent every integer, so "is this whole?"
	 * stops being a safe question and the value is left to Jackson rather than
	 * guessed at. PHP switches to exponent form here too, which this module
	 * deliberately does not reproduce — no field on this surface carries values
	 * near that magnitude, and inventing the behaviour without measuring it is
	 * how the trailing-zero bug got in.
	 */
	@Test
	void valuesBeyondExactIntegerRangeAreLeftAlone() {
		assertThat(render(1.0e20d)).contains("E");
	}
}
