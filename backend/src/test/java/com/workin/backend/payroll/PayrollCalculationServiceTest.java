package com.workin.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.workin.backend.penalties.Penalty;

/**
 * Pure unit test of the verified port of
 * {@code payroll_compute_employee_payslip} (hr-legacy @ d113204). Every
 * expected figure below is hand-computed from legacy's formulas, so a
 * change to the math has to change a number here first.
 *
 * <p>Several cases exist specifically because the previous
 * implementation got them wrong in ways that moved money.
 */
class PayrollCalculationServiceTest {

	private final PayrollCalculationService service = new PayrollCalculationService();

	private static PayrollCalculationService.AttendanceFigures present(int days, BigDecimal workHoursPerDay) {
		return new PayrollCalculationService.AttendanceFigures(
				days, workHoursPerDay.multiply(BigDecimal.valueOf(days)), 0, 0, 0, 0, workHoursPerDay);
	}

	private PayrollCalculationService.PayslipComputation compute(
			SalaryContract contract, PayrollCalculationService.AttendanceFigures attendance,
			List<Penalty> penalties, BigDecimal advance) {
		return service.compute(contract, attendance, penalties, advance,
				PayrollCalculationService.PeriodProgress.complete(),
				PayrollCalculationService.OvertimePolicy.standard());
	}

	@Test
	void dailyWageEmployeeGetsCorrectBasicSalaryNotZero() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.DAILY);
		contract.setDailyWage(BigDecimal.valueOf(100));

		PayrollCalculationService.PayslipComputation result =
				compute(contract, present(30, BigDecimal.valueOf(8)), List.of(), BigDecimal.ZERO);

		// 100 x 30 = 3000 monthly equivalent, no absence. hr-legacy#12's
		// bug produced 0 here.
		assertThat(result.basicSalary()).isEqualByComparingTo("3000.00");
		assertThat(result.netSalary()).isEqualByComparingTo("3000.00");
	}

	@Test
	void theDayRateComesFromGrossNotBasic() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));
		contract.setHousingAllowance(BigDecimal.valueOf(600));

		PayrollCalculationService.AttendanceFigures oneDayAbsent =
				new PayrollCalculationService.AttendanceFigures(
						29, BigDecimal.valueOf(8 * 29), 1, 0, 0, 0, BigDecimal.valueOf(8));

		PayrollCalculationService.PayslipComputation result =
				compute(contract, oneDayAbsent, List.of(), BigDecimal.ZERO);

		// gross 3600 -> day rate 120, not 3000/30 = 100. An absent day
		// costs the allowance share too, which the previous implementation
		// never deducted.
		assertThat(result.grossSalary()).isEqualByComparingTo("3600.00");
		assertThat(result.netSalary()).isEqualByComparingTo("3480.00");
	}

	@Test
	void allowancesAreNotPaidTwice() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));
		contract.setHousingAllowance(BigDecimal.valueOf(500));
		contract.setTransportAllowance(BigDecimal.valueOf(300));

		PayrollCalculationService.PayslipComputation result =
				compute(contract, present(30, BigDecimal.valueOf(8)), List.of(), BigDecimal.ZERO);

		// Allowances sit inside gross, which is what attendance pays out.
		// Adding them on top again -- the previous behaviour -- produced
		// 4600 for a full month instead of 3800.
		assertThat(result.totalEntitlements()).isEqualByComparingTo("3800.00");
		assertThat(result.netSalary()).isEqualByComparingTo("3800.00");
	}

	@Test
	void overtimeUsesTheEmployeesDailyHoursAndTheMultiplier() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(6000));

		// 20 present days on a 6-hour day = 120 expected; 130 worked.
		PayrollCalculationService.AttendanceFigures withOvertime =
				new PayrollCalculationService.AttendanceFigures(
						20, BigDecimal.valueOf(130), 0, 0, 0, 0, BigDecimal.valueOf(6));

		PayrollCalculationService.PayslipComputation result =
				compute(contract, withOvertime, List.of(), BigDecimal.ZERO);

		// dayRate 200 / 6h = 33.3333.../h; 10 overtime hours x 1.25 = 416.67.
		// The old dayRate/8 with no multiplier gave 250 -- 40% short.
		assertThat(result.overtimeHours()).isEqualByComparingTo("10.0");
		assertThat(result.overtimePay()).isEqualByComparingTo("416.67");
	}

	@Test
	void workingExactlyTheExpectedHoursEarnsNoOvertime() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(6000));

		PayrollCalculationService.PayslipComputation result =
				compute(contract, present(22, BigDecimal.valueOf(8)), List.of(), BigDecimal.ZERO);

		assertThat(result.overtimeHours()).isEqualByComparingTo("0.0");
		assertThat(result.overtimePay()).isEqualByComparingTo("0.00");
	}

	@Test
	void overtimeIsComputedButNotPaidWhenTheCompanyDoesNotPayIt() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(6000));

		PayrollCalculationService.PayslipComputation result = service.compute(
				contract,
				new PayrollCalculationService.AttendanceFigures(
						20, BigDecimal.valueOf(170), 0, 0, 0, 0, BigDecimal.valueOf(8)),
				List.of(), BigDecimal.ZERO,
				PayrollCalculationService.PeriodProgress.complete(),
				new PayrollCalculationService.OvertimePolicy(
						PayrollCalculationService.DEFAULT_OVERTIME_MULTIPLIER, false));

		// The hours are still recorded; only the money is zeroed.
		assertThat(result.overtimeHours()).isEqualByComparingTo("10.0");
		assertThat(result.overtimePay()).isEqualByComparingTo("0.00");
	}

	@Test
	void fixedThirtyDayDivisorAppliesRegardlessOfRealMonthLength() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));

		PayrollCalculationService.AttendanceFigures oneDayAbsent =
				new PayrollCalculationService.AttendanceFigures(
						27, BigDecimal.valueOf(8 * 27), 1, 0, 0, 0, BigDecimal.valueOf(8));

		// A day always costs gross/30, never gross/28 or gross/31.
		assertThat(compute(contract, oneDayAbsent, List.of(), BigDecimal.ZERO).netSalary())
				.isEqualByComparingTo("2900.00");
	}

	@Test
	void anInProgressPeriodIsProratedToElapsedDays() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));

		PayrollCalculationService.PayslipComputation result = service.compute(
				contract, present(10, BigDecimal.valueOf(8)), List.of(), BigDecimal.ZERO,
				new PayrollCalculationService.PeriodProgress(true, 10),
				PayrollCalculationService.OvertimePolicy.standard());

		// dayRate 100 x 10 elapsed days, rather than the whole month.
		assertThat(result.netSalary()).isEqualByComparingTo("1000.00");
	}

	@Test
	void theContractPenaltyDeductionColumnIsNeverApplied() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));
		contract.setPenaltyDeduction(BigDecimal.valueOf(250));
		contract.setAdvancesDeduction(BigDecimal.valueOf(100));

		PayrollCalculationService.PayslipComputation result =
				compute(contract, present(30, BigDecimal.valueOf(8)), List.of(), BigDecimal.ZERO);

		// Legacy reads advances_deduction and ignores penalty_deduction
		// entirely. The previous implementation did the exact opposite.
		assertThat(result.totalDeductions()).isEqualByComparingTo("100.00");
		assertThat(result.netSalary()).isEqualByComparingTo("2900.00");
	}

	@Test
	void penaltiesAndAdvancesReduceNetSalary() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));

		Penalty penalty = new Penalty(1L, 1L, "Late", BigDecimal.valueOf(1), "Late arrival", LocalDate.of(2026, 1, 5));

		PayrollCalculationService.PayslipComputation result = compute(
				contract, present(29, BigDecimal.valueOf(8)), List.of(penalty), BigDecimal.valueOf(200));

		// One penalty day at the gross-derived day rate of 100.
		assertThat(result.penaltiesTotal()).isEqualByComparingTo("100.00");
		assertThat(result.netSalary()).isEqualByComparingTo("2700.00");
	}

	@Test
	void netSalaryIsFlooredAtZero() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(1000));

		PayrollCalculationService.PayslipComputation result = compute(
				contract, present(30, BigDecimal.valueOf(8)), List.of(), BigDecimal.valueOf(5000));

		assertThat(result.netSalary()).isEqualByComparingTo("0.00");
	}

	@Test
	void daysPresentCountsEarnedRestAndCreditedHolidays() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));

		PayrollCalculationService.PayslipComputation result = compute(
				contract,
				new PayrollCalculationService.AttendanceFigures(
						20, BigDecimal.valueOf(160), 0, 2, 8, 1, BigDecimal.valueOf(8)),
				List.of(), BigDecimal.ZERO);

		// 20 punched + 8 earned rest + 1 holiday. Reported, not paid
		// separately -- they are already inside gross.
		assertThat(result.daysPresent()).isEqualTo(29);
	}

}
