package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Hand-verified against {@code payroll_compute_employee_payslip()}
 * ({@code payroll_calculation.php:1101-1276}) -- each scenario's expected
 * numbers are computed by hand from the same formula this class mirrors,
 * not copied from the implementation under test.
 */
class LegacyPayrollCalculationServiceTest {

	private final LegacyPayrollCalculationService service = new LegacyPayrollCalculationService();

	private static LegacyPayrollCalculationService.Contract monthlyContract(String basicSalary) {
		BigDecimal zero = BigDecimal.ZERO;
		return new LegacyPayrollCalculationService.Contract(
				"monthly", new BigDecimal(basicSalary), zero, zero, zero, zero, zero, zero, zero, zero, zero, zero);
	}

	private static LegacyPayrollCalculationService.OvertimePolicy standardOvertime() {
		return new LegacyPayrollCalculationService.OvertimePolicy(BigDecimal.valueOf(1.25), true);
	}

	@Test
	void aCompletePeriodWithFullAttendancePaysTheWholeGrossSalary() {
		LegacyPayrollCalculationService.AttendanceFigures attendance = new LegacyPayrollCalculationService.AttendanceFigures(
				22, BigDecimal.valueOf(176), 0, 0, 4, 0, BigDecimal.valueOf(8));

		LegacyPayrollCalculationService.PayslipComputation result = service.compute(
				monthlyContract("6000"), attendance, BigDecimal.ZERO, BigDecimal.ZERO,
				LegacyPayrollCalculationService.PeriodProgress.complete(), standardOvertime());

		assertThat(result.grossSalary()).isEqualByComparingTo("6000.00");
		assertThat(result.totalEntitlements()).isEqualByComparingTo("6000.00");
		assertThat(result.netSalary()).isEqualByComparingTo("6000.00");
		assertThat(result.daysPresent()).isEqualTo(26); // 22 punch + 4 earned rest
	}

	@Test
	void absentDaysDeductAtTheDayRateFromTheWholeGross() {
		LegacyPayrollCalculationService.AttendanceFigures attendance = new LegacyPayrollCalculationService.AttendanceFigures(
				20, BigDecimal.valueOf(160), 2, 0, 4, 0, BigDecimal.valueOf(8));

		LegacyPayrollCalculationService.PayslipComputation result = service.compute(
				monthlyContract("6000"), attendance, BigDecimal.ZERO, BigDecimal.ZERO,
				LegacyPayrollCalculationService.PeriodProgress.complete(), standardOvertime());

		// day rate 200.00 x 2 absent days = 400.00 off the 6000.00 gross.
		assertThat(result.totalEntitlements()).isEqualByComparingTo("5600.00");
		assertThat(result.netSalary()).isEqualByComparingTo("5600.00");
		assertThat(result.daysAbsent()).isEqualTo(2);
		assertThat(result.daysLeave()).isEqualTo(0);
	}

	@Test
	void overtimeHoursBeyondExpectedArePaidAtTheDayRateTimesTheMultiplier() {
		LegacyPayrollCalculationService.AttendanceFigures attendance = new LegacyPayrollCalculationService.AttendanceFigures(
				22, BigDecimal.valueOf(180), 0, 0, 4, 0, BigDecimal.valueOf(8));

		LegacyPayrollCalculationService.PayslipComputation result = service.compute(
				monthlyContract("6000"), attendance, BigDecimal.ZERO, BigDecimal.ZERO,
				LegacyPayrollCalculationService.PeriodProgress.complete(), standardOvertime());

		// hourly rate 200/8 = 25; 4 overtime hours x 25 x 1.25 = 125.00.
		assertThat(result.overtimeHours()).isEqualByComparingTo("4.0");
		assertThat(result.overtimePay()).isEqualByComparingTo("125.00");
		assertThat(result.totalEntitlements()).isEqualByComparingTo("6125.00");
	}

	@Test
	void overtimePayIsZeroWhenTheCompanyDoesNotPayItButHoursStillReport() {
		LegacyPayrollCalculationService.AttendanceFigures attendance = new LegacyPayrollCalculationService.AttendanceFigures(
				22, BigDecimal.valueOf(180), 0, 0, 4, 0, BigDecimal.valueOf(8));
		LegacyPayrollCalculationService.OvertimePolicy noOvertimePay =
				new LegacyPayrollCalculationService.OvertimePolicy(BigDecimal.valueOf(1.25), false);

		LegacyPayrollCalculationService.PayslipComputation result = service.compute(
				monthlyContract("6000"), attendance, BigDecimal.ZERO, BigDecimal.ZERO,
				LegacyPayrollCalculationService.PeriodProgress.complete(), noOvertimePay);

		assertThat(result.overtimeHours()).isEqualByComparingTo("4.0");
		assertThat(result.overtimePay()).isEqualByComparingTo("0.00");
	}

	@Test
	void aDailySalaryModeContractsBasicIsTheDailyWageTimesThirty() {
		LegacyPayrollCalculationService.Contract contract = new LegacyPayrollCalculationService.Contract(
				"daily", BigDecimal.ZERO, new BigDecimal("300"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
				BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		LegacyPayrollCalculationService.AttendanceFigures attendance = new LegacyPayrollCalculationService.AttendanceFigures(
				22, BigDecimal.valueOf(176), 0, 0, 4, 0, BigDecimal.valueOf(8));

		LegacyPayrollCalculationService.PayslipComputation result = service.compute(
				contract, attendance, BigDecimal.ZERO, BigDecimal.ZERO,
				LegacyPayrollCalculationService.PeriodProgress.complete(), standardOvertime());

		assertThat(result.basicSalary()).isEqualByComparingTo("9000.00");
		assertThat(result.grossSalary()).isEqualByComparingTo("9000.00");
	}

	@Test
	void netSalaryNeverGoesNegativeWhenDeductionsExceedEntitlements() {
		LegacyPayrollCalculationService.Contract contract = new LegacyPayrollCalculationService.Contract(
				"monthly", new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
				BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		LegacyPayrollCalculationService.AttendanceFigures attendance = new LegacyPayrollCalculationService.AttendanceFigures(
				0, BigDecimal.ZERO, 0, 0, 0, 0, BigDecimal.valueOf(8));

		LegacyPayrollCalculationService.PayslipComputation result = service.compute(
				contract, attendance, BigDecimal.ZERO, BigDecimal.ZERO,
				LegacyPayrollCalculationService.PeriodProgress.complete(), standardOvertime());

		assertThat(result.totalDeductions()).isEqualByComparingTo("2000.00");
		assertThat(result.netSalary()).isEqualByComparingTo("0.00");
	}

	@Test
	void anInProgressPeriodProratesToElapsedCalendarDaysNotTheWholeGross() {
		LegacyPayrollCalculationService.AttendanceFigures attendance = new LegacyPayrollCalculationService.AttendanceFigures(
				10, BigDecimal.valueOf(80), 0, 0, 2, 0, BigDecimal.valueOf(8));
		// day rate 200.00 x 15 elapsed calendar days = 3000.00 salary base (not the full 6000 gross).
		LegacyPayrollCalculationService.PeriodProgress inProgress =
				new LegacyPayrollCalculationService.PeriodProgress(true, 15);

		LegacyPayrollCalculationService.PayslipComputation result = service.compute(
				monthlyContract("6000"), attendance, BigDecimal.ZERO, BigDecimal.ZERO, inProgress, standardOvertime());

		assertThat(result.totalEntitlements()).isEqualByComparingTo("3000.00");
	}

	@Test
	void penaltyDaysAndAdvanceDeductionAndFixedContractDeductionsAllSubtractFromNetSalary() {
		LegacyPayrollCalculationService.Contract contract = new LegacyPayrollCalculationService.Contract(
				"monthly", new BigDecimal("6000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
				BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("50"),
				new BigDecimal("25"), new BigDecimal("10"));
		LegacyPayrollCalculationService.AttendanceFigures attendance = new LegacyPayrollCalculationService.AttendanceFigures(
				22, BigDecimal.valueOf(176), 0, 0, 4, 0, BigDecimal.valueOf(8));

		LegacyPayrollCalculationService.PayslipComputation result = service.compute(
				contract, attendance, BigDecimal.ONE, new BigDecimal("500"),
				LegacyPayrollCalculationService.PeriodProgress.complete(), standardOvertime());

		// 1 penalty day x 200.00 day rate = 200.00, + 500.00 advance + 100+50+25+10 fixed = 885.00.
		assertThat(result.penaltiesTotal()).isEqualByComparingTo("200.00");
		assertThat(result.totalDeductions()).isEqualByComparingTo("885.00");
		assertThat(result.netSalary()).isEqualByComparingTo("5115.00");

		// Regression: insertPayslip() persists these four alongside totalDeductions -- previously
		// hard-coded to zero in the store, silently zeroing the payslip breakdown while
		// totalDeductions (used by stats.php) stayed correct. Pin each contributor individually.
		assertThat(result.insuranceDeduction()).isEqualByComparingTo("100.00");
		assertThat(result.taxDeduction()).isEqualByComparingTo("50.00");
		assertThat(result.advancesDeduction()).isEqualByComparingTo("25.00");
		assertThat(result.fundDeduction()).isEqualByComparingTo("10.00");
	}
}
