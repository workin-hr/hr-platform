package com.workin.legacy.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

/**
 * {@code payroll_compute_employee_payslip()} ({@code payroll_calculation.php:1101-1276}),
 * ported for the legacy MySQL wire contract.
 *
 * <p>This mirrors {@code com.workin.backend.payroll.PayrollCalculationService}'s
 * already-verified formula, rounding order (PHP's {@code round($v, 2)} is
 * half-away-from-zero, reproduced per-component as {@code setScale(2,
 * HALF_UP)} at the same point the PHP source rounds, not once at the end),
 * and the fixed {@code PENALTY_CALENDAR_DAYS_PER_MONTH = 30} divisor -- see
 * that class's own javadoc for the six specific historical divergences this
 * formula fixes relative to an earlier, unverified Java attempt. It is not
 * imported directly: that class is built against Phase 2's JPA entities on
 * the PostgreSQL runtime datasource, and importing it would cross the
 * Phase 1/Phase 2 boundary this codebase's conventions keep elsewhere (D-104).
 * The two are expected to stay in formula lockstep by inspection, the same
 * way any other cross-checked pair of legacy helpers in this codebase is
 * kept in sync -- not by a shared dependency.
 */
@Service
public class LegacyPayrollCalculationService {

	private static final BigDecimal CALENDAR_DAYS_PER_MONTH = BigDecimal.valueOf(30);
	private static final int SCALE = 2;

	/** {@code SalaryContract} row fields {@code compute()} reads. */
	public record Contract(
			String salaryMode, BigDecimal basicSalary, BigDecimal dailyWage, BigDecimal housingAllowance,
			BigDecimal transportAllowance, BigDecimal foodAllowance, BigDecimal riskAllowance,
			BigDecimal incentives, BigDecimal insuranceDeduction, BigDecimal taxDeduction,
			BigDecimal advancesDeduction, BigDecimal fundDeduction) {
	}

	public record AttendanceFigures(
			int punchPresentDays, BigDecimal totalWorkedHours, int daysAbsent, int daysLeave,
			int earnedWeeklyRestDays, int officialHolidayDays, BigDecimal workHoursPerDay) {
	}

	public record PeriodProgress(boolean inProgress, int elapsedCalendarDays) {

		public static PeriodProgress complete() {
			return new PeriodProgress(false, 0);
		}
	}

	public record OvertimePolicy(BigDecimal multiplier, boolean paysOvertime) {
	}

	public record PayslipComputation(
			BigDecimal basicSalary, BigDecimal allowances, BigDecimal overtimeHours, BigDecimal overtimePay,
			BigDecimal foodAllowance, BigDecimal riskAllowance, BigDecimal transportAllowance,
			BigDecimal incentives, BigDecimal penaltiesTotal, BigDecimal advanceDeduction,
			BigDecimal otherDeductions, BigDecimal grossSalary, BigDecimal totalEntitlements,
			BigDecimal totalDeductions, BigDecimal netSalary, int daysPresent,
			int daysAbsent, int daysLeave, BigDecimal insuranceDeduction, BigDecimal taxDeduction,
			BigDecimal advancesDeduction, BigDecimal fundDeduction) {
	}

	public PayslipComputation compute(
			Contract contract, AttendanceFigures attendance, BigDecimal penaltyDays,
			BigDecimal advanceDeductionForPeriod, PeriodProgress period, OvertimePolicy overtimePolicy) {

		BigDecimal contractBasic = "daily".equals(contract.salaryMode())
				? round(contract.dailyWage().multiply(CALENDAR_DAYS_PER_MONTH))
				: round(contract.basicSalary());

		BigDecimal grossSalary = round(contractBasic
				.add(contract.housingAllowance())
				.add(contract.transportAllowance())
				.add(contract.foodAllowance())
				.add(contract.riskAllowance())
				.add(contract.incentives()));

		BigDecimal dayRate = grossSalary.signum() > 0
				? round(grossSalary.divide(CALENDAR_DAYS_PER_MONTH, 10, RoundingMode.HALF_UP))
				: BigDecimal.ZERO.setScale(SCALE);

		int unpaidDays = Math.max(0, attendance.daysAbsent());
		BigDecimal absenceCost = round(dayRate.multiply(BigDecimal.valueOf(unpaidDays)));
		BigDecimal salaryBase = period.inProgress()
				? round(dayRate.multiply(BigDecimal.valueOf(Math.max(0, period.elapsedCalendarDays()))))
				: grossSalary;
		BigDecimal salaryByAttendance = max(BigDecimal.ZERO, round(salaryBase.subtract(absenceCost)));

		BigDecimal workHoursPerDay = attendance.workHoursPerDay();
		BigDecimal hourlyRate = dayRate.signum() > 0 && workHoursPerDay.signum() > 0
				? dayRate.divide(workHoursPerDay, 10, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;
		BigDecimal expectedHours = workHoursPerDay.multiply(BigDecimal.valueOf(attendance.punchPresentDays()));
		BigDecimal overtimeHours = max(BigDecimal.ZERO, attendance.totalWorkedHours().subtract(expectedHours));
		BigDecimal overtimePay = overtimePolicy.paysOvertime()
				? round(overtimeHours.multiply(hourlyRate).multiply(overtimePolicy.multiplier()))
				: BigDecimal.ZERO.setScale(SCALE);

		BigDecimal penaltiesTotal = round(dayRate.multiply(penaltyDays));

		BigDecimal insurance = round(contract.insuranceDeduction());
		BigDecimal tax = round(contract.taxDeduction());
		BigDecimal contractAdvances = round(contract.advancesDeduction());
		BigDecimal fund = round(contract.fundDeduction());

		BigDecimal totalEntitlements = round(salaryByAttendance.add(overtimePay));
		BigDecimal totalDeductions = round(penaltiesTotal
				.add(advanceDeductionForPeriod)
				.add(insurance)
				.add(tax)
				.add(contractAdvances)
				.add(fund));
		BigDecimal netSalary = max(BigDecimal.ZERO, round(totalEntitlements.subtract(totalDeductions)));

		int daysPresent = Math.max(0, attendance.punchPresentDays())
				+ Math.max(0, attendance.earnedWeeklyRestDays())
				+ Math.max(0, attendance.officialHolidayDays());

		return new PayslipComputation(
				contractBasic,
				round(contract.housingAllowance()),
				overtimeHours.setScale(1, RoundingMode.HALF_UP),
				overtimePay,
				round(contract.foodAllowance()),
				round(contract.riskAllowance()),
				round(contract.transportAllowance()),
				round(contract.incentives()),
				penaltiesTotal,
				round(advanceDeductionForPeriod),
				BigDecimal.ZERO.setScale(SCALE),
				grossSalary,
				totalEntitlements,
				totalDeductions,
				netSalary,
				daysPresent,
				Math.max(0, attendance.daysAbsent()),
				Math.max(0, attendance.daysLeave()),
				insurance,
				tax,
				contractAdvances,
				fund);
	}

	private static BigDecimal round(BigDecimal value) {
		return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
	}

	private static BigDecimal max(BigDecimal floor, BigDecimal value) {
		return value.compareTo(floor) < 0 ? floor.setScale(SCALE) : value;
	}
}
