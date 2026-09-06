package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link PhpMath} against PHP 8.3 itself.
 *
 * <p>{@code legacy-parity/php-round.txt} was produced by running the two
 * functions in a {@code php:8.3-cli} container and printing the raw IEEE-754
 * bits of every input and every {@code round()} result, so nothing is lost to
 * decimal formatting on the way here. The generator walked the {@code .5} edge
 * of ten decades three ULPs either side -- which is where PHP and Java
 * disagree -- and sampled the products the salary calculator actually forms.
 *
 * <p>A wider run of the same comparison over 20,160 cases found no
 * disagreement either; this file keeps the boundary half of it, which is the
 * half that would catch a regression.
 */
class PhpMathTest {

	private record Case(double input, double round, String numberFormat) {
	}

	@Test
	void roundAgreesWithPhpOnEveryBoundaryInTheCorpus() {
		List<Case> corpus = corpus();
		assertThat(corpus).hasSizeGreaterThan(200);
		for (Case sample : corpus) {
			assertThat(PhpMath.round(sample.input()))
					.as("round(%s)", Double.toString(sample.input()))
					.isEqualTo(sample.round());
		}
	}

	@Test
	void numberFormatAgreesWithPhpOnEveryBoundaryInTheCorpus() {
		for (Case sample : corpus()) {
			assertThat(PhpMath.numberFormat(sample.input()))
					.as("number_format(%s, 0, '.', ',')", Double.toString(sample.input()))
					.isEqualTo(sample.numberFormat());
		}
	}

	@Test
	void roundIsHalfAwayFromZeroWhereMathRoundIsNot() {
		// Math.round(-2.5) is -2: it is floor(v + 0.5), which breaks on the
		// negative half of every boundary. The salary calculator reaches
		// negatives whenever the insurance floor costs more than the salary.
		assertThat(PhpMath.round(-2.5)).isEqualTo(-3);
		assertThat(PhpMath.round(-0.5)).isEqualTo(-1);
		assertThat(PhpMath.round(2.5)).isEqualTo(3);
		assertThat(Math.round(-2.5)).as("the trap this class exists to avoid").isEqualTo(-2);
	}

	@Test
	void roundPreRoundsSoNearMissesLandOnTheBoundary() {
		// 1234.4999999999998 is a double that "should" be 1234.5. PHP rounds to
		// 11 places first -- 14 minus the magnitude's exponent -- and so gets
		// 1235. A plain half-up gets 1234.
		assertThat(PhpMath.round(1234.4999999999998)).isEqualTo(1235);
		// The window is relative, not absolute, so a value of the same distance
		// from a much smaller boundary is not pulled across it.
		assertThat(PhpMath.round(0.4999999999999999)).isEqualTo(0);
	}

	@Test
	void numberFormatGroupsInThreesAndNeverPrintsMinusZero() {
		assertThat(PhpMath.numberFormat(1234567.5)).isEqualTo("1,234,568");
		assertThat(PhpMath.numberFormat(999)).isEqualTo("999");
		assertThat(PhpMath.numberFormat(1000)).isEqualTo("1,000");
		assertThat(PhpMath.numberFormat(-296.5)).isEqualTo("-297");
		assertThat(PhpMath.numberFormat(-0.4)).isEqualTo("0");
	}

	private static List<Case> corpus() {
		List<Case> cases = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				PhpMathTest.class.getClassLoader()
						.getResourceAsStream("legacy-parity/php-round.txt"),
				StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] parts = line.split(" ", 3);
				cases.add(new Case(
						Double.longBitsToDouble(Long.parseUnsignedLong(parts[0], 16)),
						Double.longBitsToDouble(Long.parseUnsignedLong(parts[1], 16)),
						parts[2]));
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("could not read the PHP rounding corpus", ex);
		}
		return cases;
	}

}
