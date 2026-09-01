package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one accepted parity deviation in payroll: {@code overtime_hours} is
 * rounded to one decimal by exact decimal arithmetic, not by PHP's
 * {@code round()}.
 *
 * <p>PHP computes overtime in binary floating point and rounds with
 * {@code round($overtime_hours, 1)} ({@code payroll_calculation.php:1250}),
 * which pre-rounds its argument to roughly 15 significant digits before
 * rounding. Measured against the harness's PHP 8.2:
 * {@code round(7.04999999999999893, 1)} returns {@code 7.1} — the pre-round
 * lifts a value that is strictly below the midpoint up onto it. Java holds the
 * same quantity exactly and rounds half-up without a fuzz window, so it returns
 * {@code 7.0} for that input.
 *
 * <p>The deviation is display-only and was accepted by the repository owner
 * rather than fixed: reproducing PHP's behaviour would mean reintroducing
 * double arithmetic into payroll to recreate a 0.1-hour artifact. It affected 5
 * of 1,070 payslips in the batch-78 comparison and moved no money — see
 * <b>D-150</b>.
 *
 * <p>These tests fail if the rounding mode changes, if the scale changes, or if
 * anyone routes the value through a {@code double} to chase PHP's output.
 */
class LegacyOvertimeHoursRoundingParityTest {

	private final LegacyPayrollCalculationService service = new LegacyPayrollCalculationService();

	private static LegacyPayrollCalculationService.Contract monthlyContract(String basicSalary) {
		BigDecimal zero = BigDecimal.ZERO;
		return new LegacyPayrollCalculationService.Contract(
				"monthly", new BigDecimal(basicSalary), zero, zero, zero, zero, zero, zero, zero, zero, zero, zero);
	}

	/** 22 days at 8h each = 176 expected hours, so overtime is worked - 176. */
	private static LegacyPayrollCalculationService.AttendanceFigures workedHours(String totalWorkedHours) {
		return new LegacyPayrollCalculationService.AttendanceFigures(
				22, new BigDecimal(totalWorkedHours), 0, 0, 0, 0, BigDecimal.valueOf(8));
	}

	private BigDecimal overtimeHoursFor(String totalWorkedHours) {
		return service.compute(
				monthlyContract("6000"),
				workedHours(totalWorkedHours),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				LegacyPayrollCalculationService.PeriodProgress.complete(),
				new LegacyPayrollCalculationService.OvertimePolicy(BigDecimal.valueOf(1.25), true))
			.overtimeHours();
	}

	@Test
	@DisplayName("an exact .x5 overtime value rounds away from zero, deterministically")
	void exactMidpointRoundsHalfUp() {
		// 183.05 - 176 = exactly 7.05. Half-up on an exact midpoint gives 7.1,
		// and PHP agrees here: its float error is small enough that the
		// pre-round lands on the same midpoint.
		assertThat(overtimeHoursFor("183.05")).isEqualByComparingTo("7.1");
		assertThat(overtimeHoursFor("183.15")).isEqualByComparingTo("7.2");
		assertThat(overtimeHoursFor("183.25")).isEqualByComparingTo("7.3");
	}

	@Test
	@DisplayName("a value below the midpoint rounds down — this is where PHP diverges")
	void belowMidpointRoundsDownUnlikePhp() {
		// 183.0499999999 - 176 = 7.0499999999, strictly below the midpoint.
		// Java rounds it to 7.0. PHP's round() pre-rounds to ~15 significant
		// digits first and returns 7.1. The 7.0 here is the accepted answer.
		assertThat(overtimeHoursFor("183.0499999999")).isEqualByComparingTo("7.0");
		assertThat(overtimeHoursFor("183.0499999999")).isNotEqualByComparingTo("7.1");
	}

	@Test
	@DisplayName("the rounded hours carry exactly one decimal, matching decimal(5,1)")
	void scaleIsPinnedToOneDecimal() {
		// The column is decimal(5,1); emitting a different scale would change
		// the JSON body clients read even when the value is equal.
		assertThat(overtimeHoursFor("183.05").scale()).isEqualTo(1);
		assertThat(overtimeHoursFor("176").scale()).isEqualTo(1);
	}

	@Test
	@DisplayName("rounding the displayed hours does not move the overtime pay")
	void payIsComputedFromUnroundedHours() {
		// Pay is computed before the hours are rounded for display, so the
		// two must not track each other. 6000/30 = 200 day rate, /8 = 25
		// hourly, x1.25 = 31.25 per overtime hour.
		BigDecimal atMidpoint = service.compute(
				monthlyContract("6000"), workedHours("183.05"), BigDecimal.ZERO, BigDecimal.ZERO,
				LegacyPayrollCalculationService.PeriodProgress.complete(),
				new LegacyPayrollCalculationService.OvertimePolicy(BigDecimal.valueOf(1.25), true))
			.overtimePay();

		// 7.05 unrounded x 31.25 = 220.3125 -> 220.31, not 7.1 x 31.25 = 221.88.
		assertThat(atMidpoint).isEqualByComparingTo("220.31");
		assertThat(atMidpoint).isNotEqualByComparingTo("221.88");
	}
}
