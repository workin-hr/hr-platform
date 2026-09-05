package com.workin.backend.platformadmin.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.workin.legacy.PhpMath;

/**
 * The calculator against the page it replaces, case for case.
 *
 * <p>{@code legacy-parity/php-salary.tsv} was produced by running
 * {@code dashboard/pages/salary_calculator/page.php}'s own parsing and
 * {@code EgyptMonthlySalaryCalculator::compute()} inside a {@code php:8.3-cli}
 * container over 547 inputs, and printing every one of the ten returned
 * figures twice: as raw IEEE-754 bits, so the arithmetic is compared exactly
 * rather than to some agreed number of decimals, and as
 * {@code number_format($v, 0, '.', ',')}, so the rendered string is compared
 * too.
 *
 * <p>The inputs are raw strings a user could type. That is deliberate: the
 * cast is half the behaviour -- "12,500" is twelve and a half thousand,
 * "7500abc" is seven and a half, "abc" is nothing at all -- and testing
 * {@code compute()} on numbers already parsed would leave it uncovered.
 *
 * <p>The corpus covers the social-insurance band's two edges, every tax
 * bracket boundary, a declared base above and below the band, a negative and
 * an absent one, negative and very large non-taxable allowances, thousands
 * separators, scientific notation, leading and trailing rubbish, and a sweep
 * of 400-odd values chosen by stride rather than by hand.
 */
class EgyptSalaryCalculatorTest {

	private record Vector(String grossRaw, String siRaw, String otherRaw,
			boolean hasResult, double[] figures, String[] labels) {
	}

	/** The order {@code compute()} returns, and the order the corpus stores. */
	private static final List<Function<EgyptSalaryCalculator.Estimate, Double>> FIGURES = List.of(
			EgyptSalaryCalculator.Estimate::grossMonthly,
			EgyptSalaryCalculator.Estimate::socialInsuranceBase,
			EgyptSalaryCalculator.Estimate::employeeSi,
			EgyptSalaryCalculator.Estimate::martyrsFund,
			EgyptSalaryCalculator.Estimate::incomeTax,
			EgyptSalaryCalculator.Estimate::netMonthly,
			EgyptSalaryCalculator.Estimate::employerSi,
			EgyptSalaryCalculator.Estimate::otherNonTaxableMonthly,
			EgyptSalaryCalculator.Estimate::totalDeductions,
			EgyptSalaryCalculator.Estimate::netYearly);

	@Test
	void everyFigureMatchesThePhpPageBitForBit() {
		List<Vector> corpus = corpus();
		assertThat(corpus).hasSizeGreaterThan(500);
		for (Vector vector : corpus) {
			EgyptSalaryCalculator.Estimate estimate = SalaryCalculatorForm
					.of(vector.grossRaw(), vector.siRaw(), vector.otherRaw()).estimate();
			String where = "gross=%s si_base=%s other=%s"
					.formatted(vector.grossRaw(), vector.siRaw(), vector.otherRaw());
			if (!vector.hasResult()) {
				assertThat(estimate).as("%s renders no result", where).isNull();
				continue;
			}
			assertThat(estimate).as("%s renders a result", where).isNotNull();
			for (int index = 0; index < FIGURES.size(); index++) {
				assertThat(FIGURES.get(index).apply(estimate))
						.as("%s figure %d", where, index)
						.isEqualTo(vector.figures()[index]);
			}
		}
	}

	@Test
	void everyRenderedLabelMatchesThePhpPage() {
		for (Vector vector : corpus()) {
			if (!vector.hasResult()) {
				continue;
			}
			EgyptSalaryCalculator.Estimate estimate = SalaryCalculatorForm
					.of(vector.grossRaw(), vector.siRaw(), vector.otherRaw()).estimate();
			for (int index = 0; index < FIGURES.size(); index++) {
				assertThat(PhpMath.numberFormat(FIGURES.get(index).apply(estimate)))
						.as("gross=%s label %d", vector.grossRaw(), index)
						.isEqualTo(vector.labels()[index]);
			}
		}
	}

	@Test
	void aSalaryBelowTheInsuranceFloorNetsOutNegative() {
		// Not a defensive check that never fires: the base is clamped up to
		// 2,700, so half a pound of gross is charged 297 in contributions. The
		// page prints the loss, and the parity corpus pins it.
		EgyptSalaryCalculator.Estimate estimate =
				SalaryCalculatorForm.of("0.5", "", "").estimate();
		assertThat(estimate.socialInsuranceBase()).isEqualTo(2700);
		assertThat(estimate.employeeSi()).isEqualTo(297);
		assertThat(estimate.netMonthly()).isEqualTo(-296.5);
		assertThat(estimate.netMonthlyLabel()).isEqualTo("-297");
	}

	@Test
	void aNegativeGrossRendersNothingRatherThanAClampedResult() {
		// compute() would clamp it to zero, but the page never calls compute():
		// the guard is $gross > 0, not $gross >= 0.
		assertThat(SalaryCalculatorForm.of("-5000", "", "").estimate()).isNull();
		assertThat(SalaryCalculatorForm.of("0", "", "").estimate()).isNull();
		assertThat(SalaryCalculatorForm.of("abc", "", "").estimate()).isNull();
		assertThat(SalaryCalculatorForm.of("", "", "").estimate()).isNull();
	}

	@Test
	void aDeclaredBaseIsClampedIntoTheBandAndANonPositiveOneFallsBackToTheGross() {
		assertThat(EgyptSalaryCalculator.resolveSocialInsuranceBase(10000, 2000d)).isEqualTo(2700);
		assertThat(EgyptSalaryCalculator.resolveSocialInsuranceBase(10000, 25000d)).isEqualTo(16700);
		assertThat(EgyptSalaryCalculator.resolveSocialInsuranceBase(10000, 9000d)).isEqualTo(9000);
		assertThat(EgyptSalaryCalculator.resolveSocialInsuranceBase(10000, -500d)).isEqualTo(10000);
		assertThat(EgyptSalaryCalculator.resolveSocialInsuranceBase(10000, 0d)).isEqualTo(10000);
		assertThat(EgyptSalaryCalculator.resolveSocialInsuranceBase(10000, null)).isEqualTo(10000);
		// And the gross itself is clamped once it stands in for the base.
		assertThat(EgyptSalaryCalculator.resolveSocialInsuranceBase(50000, null)).isEqualTo(16700);
	}

	@Test
	void theRawStringsSurviveForTheFormToEchoBack() {
		SalaryCalculatorForm form = SalaryCalculatorForm.of("  12,500  ", "8,000", "");
		assertThat(form.grossRaw()).as("trimmed, but the separators stay").isEqualTo("12,500");
		assertThat(form.gross()).isEqualTo(12500);
		assertThat(form.siRaw()).isEqualTo("8,000");
		assertThat(form.siBase()).isEqualTo(8000);
		assertThat(form.otherRaw()).isEmpty();
		assertThat(form.otherNonTaxable()).isZero();
	}

	@Test
	void aBlankInsuranceBaseIsAbsentAndNotZero() {
		// The difference decides whether resolveSocialInsuranceBase() falls back
		// to the gross, so it cannot be collapsed to a primitive.
		assertThat(SalaryCalculatorForm.of("10000", "", "").siBase()).isNull();
		assertThat(SalaryCalculatorForm.of("10000", "0", "").siBase()).isEqualTo(0);
	}

	@Test
	void aNegativeAllowanceIsFlooredAtZero() {
		assertThat(SalaryCalculatorForm.of("20000", "", "-800").otherNonTaxable()).isZero();
		assertThat(SalaryCalculatorForm.of("20000", "", "800").otherNonTaxable()).isEqualTo(800);
	}

	private static List<Vector> corpus() {
		List<Vector> vectors = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				EgyptSalaryCalculatorTest.class.getClassLoader()
						.getResourceAsStream("legacy-parity/php-salary.tsv"),
				StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				String[] cells = line.split("\t", -1);
				boolean hasResult = "1".equals(cells[3]);
				double[] figures = new double[FIGURES.size()];
				String[] labels = new String[FIGURES.size()];
				for (int index = 0; hasResult && index < FIGURES.size(); index++) {
					figures[index] = Double.longBitsToDouble(
							Long.parseUnsignedLong(cells[4 + index * 2], 16));
					labels[index] = cells[5 + index * 2];
				}
				vectors.add(new Vector(cells[0], cells[1], cells[2], hasResult, figures, labels));
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("could not read the PHP salary corpus", ex);
		}
		return vectors;
	}

}
