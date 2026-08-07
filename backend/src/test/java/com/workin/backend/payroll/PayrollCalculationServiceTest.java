package com.workin.backend.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.workin.backend.penalties.Penalty;

/**
 * Pure unit test, no Spring context needed -- exercises the exact
 * defect hr-legacy#12 describes (payslips/create.php silently dropping
 * base pay for daily-wage employees) and the fixed-30-day divisor rule
 * from docs/legacy/business-rule-extraction.md.
 */
class PayrollCalculationServiceTest {

	private final PayrollCalculationService service = new PayrollCalculationService();

	@Test
	void dailyWageEmployeeGetsCorrectBasicSalaryNotZero() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.DAILY);
		contract.setDailyWage(BigDecimal.valueOf(100));

		PayrollCalculationService.AttendanceFigures fullAttendance =
				new PayrollCalculationService.AttendanceFigures(30, 0, 0, BigDecimal.ZERO);

		PayrollCalculationService.PayslipComputation result =
				service.compute(contract, fullAttendance, List.of(), BigDecimal.ZERO);

		// dailyWage(100) * 30 = 3000 monthly-equivalent, zero absence ->
		// full 3000 basic salary. hr-legacy#12's bug produced 0 here.
		assertThat(result.basicSalary()).isEqualByComparingTo("3000.00");
		assertThat(result.netSalary()).isEqualByComparingTo("3000.00");
	}

	@Test
	void fixedThirtyDayDivisorAppliesRegardlessOfRealMonthLength() {
		// business-rule-extraction.md: the divisor is always 30, even in
		// a 28-day or 31-day real month -- this is the documented,
		// preserved legacy convention, not a bug.
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));

		PayrollCalculationService.AttendanceFigures oneDayAbsentIn28DayMonth =
				new PayrollCalculationService.AttendanceFigures(27, 1, 0, BigDecimal.ZERO);
		PayrollCalculationService.AttendanceFigures oneDayAbsentIn31DayMonth =
				new PayrollCalculationService.AttendanceFigures(30, 1, 0, BigDecimal.ZERO);

		PayrollCalculationService.PayslipComputation feb =
				service.compute(contract, oneDayAbsentIn28DayMonth, List.of(), BigDecimal.ZERO);
		PayrollCalculationService.PayslipComputation jan =
				service.compute(contract, oneDayAbsentIn31DayMonth, List.of(), BigDecimal.ZERO);

		// day_rate = 3000 / 30 = 100, one day absent -> 2900 either way,
		// identical regardless of the real month length.
		assertThat(feb.basicSalary()).isEqualByComparingTo("2900.00");
		assertThat(jan.basicSalary()).isEqualByComparingTo(feb.basicSalary());
	}

	@Test
	void monthlyModeIgnoresDailyWageField() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(5000));
		contract.setDailyWage(BigDecimal.valueOf(999)); // must be ignored in MONTHLY mode

		PayrollCalculationService.AttendanceFigures fullAttendance =
				new PayrollCalculationService.AttendanceFigures(30, 0, 0, BigDecimal.ZERO);

		PayrollCalculationService.PayslipComputation result =
				service.compute(contract, fullAttendance, List.of(), BigDecimal.ZERO);

		assertThat(result.basicSalary()).isEqualByComparingTo("5000.00");
	}

	@Test
	void penaltiesAndAdvancesReduceNetSalary() {
		SalaryContract contract = new SalaryContract(1L, 1L, LocalDate.of(2026, 1, 1));
		contract.setSalaryMode(SalaryMode.MONTHLY);
		contract.setBasicSalary(BigDecimal.valueOf(3000));

		Penalty penalty = new Penalty(1L, 1L, "late", BigDecimal.valueOf(1), "late arrival", LocalDate.of(2026, 1, 5));
		PayrollCalculationService.AttendanceFigures fullAttendance =
				new PayrollCalculationService.AttendanceFigures(30, 0, 0, BigDecimal.ZERO);

		PayrollCalculationService.PayslipComputation result =
				service.compute(contract, fullAttendance, List.of(penalty), BigDecimal.valueOf(200));

		// day_rate 100 * 1 penalty day = 100; plus 200 advance deduction.
		assertThat(result.penaltiesTotal()).isEqualByComparingTo("100.00");
		assertThat(result.netSalary()).isEqualByComparingTo("2700.00");
	}

}
