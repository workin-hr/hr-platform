package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;

import com.workin.backend.penalties.Penalty;

/**
 * The single source of payroll math for this backend -- the direct
 * structural fix for hr-legacy#12/#13 (three independent, divergent
 * implementations in legacy). Every entry point that needs a payslip
 * computed must call this class and nothing else.
 *
 * <p><b>Now a verified port</b> of {@code payroll_compute_employee_payslip}
 * (hr-legacy/apis/helpers/payroll_calculation.php:1101-1276 @ d113204),
 * read in full. It previously carried a self-declared placeholder
 * overtime multiplier and, less visibly, five other divergences that
 * each moved money:
 *
 * <ul>
 * <li>the day rate was built from <em>basic salary</em>; legacy builds it
 * from <b>gross</b> (basic plus every allowance);</li>
 * <li>absence was deducted from basic, then allowances added back on top,
 * so an absent day never reduced the allowance half of the pay;</li>
 * <li>the hourly rate divided by a flat 8 rather than the employee's
 * resolved daily hours;</li>
 * <li>there was no overtime multiplier at all -- legacy defaults to
 * <b>1.25</b>, so overtime was paid at 80% of what legacy pays;</li>
 * <li>{@code salary_contracts.penalty_deduction} was deducted; legacy
 * never reads that column;</li>
 * <li>{@code salary_contracts.advances_deduction} -- the fixed monthly
 * one, distinct from the computed advance instalment -- was not
 * deducted at all.</li>
 * </ul>
 *
 * <p><b>Rounding.</b> Legacy uses PHP {@code round($v, 2)} everywhere in
 * the salary path -- half away from zero, never truncation, never
 * bankers'. Each {@code round()} in the source is reproduced here as a
 * {@code setScale(2, HALF_UP)} at the same point, because rounding
 * per-component and rounding only at the end give different answers and
 * legacy rounds per-component.
 *
 * <p><b>The 30-day divisor is real.</b> It is
 * {@code PENALTY_CALENDAR_DAYS_PER_MONTH = 30}
 * ({@code penalties_amount_helper.php:8}), used for the day rate, the
 * daily-wage-to-monthly conversion, and penalties alike -- not a
 * calendar-days count. That settles the open question in
 * {@code docs/bootstrap/open-questions.md}.
 */
@Service
public class PayrollCalculationService {

	/** {@code PENALTY_CALENDAR_DAYS_PER_MONTH} -- fixed, not the period's real length. */
	private static final BigDecimal CALENDAR_DAYS_PER_MONTH = BigDecimal.valueOf(30);

	private static final int SCALE = 2;

	/** Legacy's default when the {@code overtime_rate} setting is unset or non-positive. */
	public static final BigDecimal DEFAULT_OVERTIME_MULTIPLIER = BigDecimal.valueOf(1.25);

	/**
	 * What attendance contributed, as legacy's payslip computation
	 * consumes it.
	 *
	 * @param punchPresentDays     days with a real punch — the basis for expected hours, <b>not</b> the reported {@code days_present}
	 * @param totalWorkedHours     summed worked minutes over the period, in hours
	 * @param daysAbsent           workday absences <b>plus</b> void weekly-rest days, which legacy folds in here
	 * @param daysLeave            approved paid-leave days in the period
	 * @param earnedWeeklyRestDays paid rest days, reported but not paid separately — they are already inside gross
	 * @param officialHolidayDays  credited holidays, same treatment
	 * @param workHoursPerDay      the employee's resolved daily hours; the overtime divisor
	 */
	public record AttendanceFigures(
			int punchPresentDays,
			BigDecimal totalWorkedHours,
			int daysAbsent,
			int daysLeave,
			int earnedWeeklyRestDays,
			int officialHolidayDays,
			BigDecimal workHoursPerDay) {
	}

	/**
	 * Whether the period is still running, and how much of it has
	 * elapsed. Legacy prorates an in-progress period to elapsed calendar
	 * days rather than paying the whole month up front.
	 */
	public record PeriodProgress(boolean inProgress, int elapsedCalendarDays) {

		public static PeriodProgress complete() {
			return new PeriodProgress(false, 0);
		}
	}

	/** The {@code overtime_rate} and {@code pay_overtime} company settings, resolved once per batch. */
	public record OvertimePolicy(BigDecimal multiplier, boolean paysOvertime) {

		public static OvertimePolicy standard() {
			return new OvertimePolicy(DEFAULT_OVERTIME_MULTIPLIER, true);
		}
	}

	public record PayslipComputation(
			BigDecimal basicSalary,
			BigDecimal allowances, // housing lives here -- legacy's `allowances` column
			BigDecimal overtimeHours,
			BigDecimal overtimePay,
			BigDecimal foodAllowance,
			BigDecimal riskAllowance,
			BigDecimal transportAllowance,
			BigDecimal incentives,
			BigDecimal penaltiesTotal,
			BigDecimal advanceDeduction,
			BigDecimal otherDeductions,
			BigDecimal grossSalary,
			BigDecimal totalEntitlements,
			BigDecimal totalDeductions,
			BigDecimal netSalary,
			int daysPresent) {
	}

	public PayslipComputation compute(
			SalaryContract contract,
			AttendanceFigures attendance,
			List<Penalty> unappliedPenaltiesInPeriod,
			BigDecimal advanceDeductionForPeriod,
			PeriodProgress period,
			OvertimePolicy overtimePolicy) {

		// Step 9 -- contract basic, gross, day rate.
		BigDecimal contractBasic = contract.getSalaryMode() == SalaryMode.DAILY
				? round(contract.getDailyWage().multiply(CALENDAR_DAYS_PER_MONTH))
				: round(contract.getBasicSalary());

		BigDecimal grossSalary = round(contractBasic
				.add(contract.getHousingAllowance())
				.add(contract.getTransportAllowance())
				.add(contract.getFoodAllowance())
				.add(contract.getRiskAllowance())
				.add(contract.getIncentives()));

		BigDecimal dayRate = grossSalary.signum() > 0
				? round(grossSalary.divide(CALENDAR_DAYS_PER_MONTH, 10, RoundingMode.HALF_UP))
				: BigDecimal.ZERO.setScale(SCALE);

		// Step 10 -- absence comes off the whole gross, allowances included.
		int unpaidDays = Math.max(0, attendance.daysAbsent());
		BigDecimal absenceCost = round(dayRate.multiply(BigDecimal.valueOf(unpaidDays)));
		BigDecimal salaryBase = period.inProgress()
				? round(dayRate.multiply(BigDecimal.valueOf(Math.max(0, period.elapsedCalendarDays()))))
				: grossSalary;
		BigDecimal salaryByAttendance = max(BigDecimal.ZERO, round(salaryBase.subtract(absenceCost)));

		// Step 11 -- overtime. The hourly rate is deliberately left
		// unrounded; legacy divides and only rounds the resulting pay.
		BigDecimal workHoursPerDay = attendance.workHoursPerDay();
		BigDecimal hourlyRate = dayRate.signum() > 0 && workHoursPerDay.signum() > 0
				? dayRate.divide(workHoursPerDay, 10, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;
		BigDecimal expectedHours = workHoursPerDay.multiply(BigDecimal.valueOf(attendance.punchPresentDays()));
		BigDecimal overtimeHours = max(BigDecimal.ZERO, attendance.totalWorkedHours().subtract(expectedHours));
		BigDecimal overtimePay = overtimePolicy.paysOvertime()
				? round(overtimeHours.multiply(hourlyRate).multiply(overtimePolicy.multiplier()))
				: BigDecimal.ZERO.setScale(SCALE);

		// Step 12 -- penalties are priced at the same day rate, over the
		// whole period rather than capped at today.
		BigDecimal penaltyDays = unappliedPenaltiesInPeriod.stream()
				.map(Penalty::getPenaltyDays)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal penaltiesTotal = round(dayRate.multiply(penaltyDays));

		// Step 14 -- fixed contract deductions, never attendance-prorated.
		BigDecimal insurance = round(contract.getInsuranceDeduction());
		BigDecimal tax = round(contract.getTaxDeduction());
		BigDecimal contractAdvances = round(contract.getAdvancesDeduction());
		BigDecimal fund = round(contract.getFundDeduction());

		// Step 15 -- totals. Allowances are already inside
		// salaryByAttendance via gross; adding them again would pay them
		// twice, which is what the previous implementation did.
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
				round(contract.getHousingAllowance()),
				overtimeHours.setScale(1, RoundingMode.HALF_UP),
				overtimePay,
				round(contract.getFoodAllowance()),
				round(contract.getRiskAllowance()),
				round(contract.getTransportAllowance()),
				round(contract.getIncentives()),
				penaltiesTotal,
				round(advanceDeductionForPeriod),
				// Legacy hard-codes other_deductions to 0 and leaves it out
				// of the total; the fixed contract deductions are counted
				// individually above, not here.
				BigDecimal.ZERO.setScale(SCALE),
				grossSalary,
				totalEntitlements,
				totalDeductions,
				netSalary,
				daysPresent);
	}

	private static BigDecimal round(BigDecimal value) {
		return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
	}

	private static BigDecimal max(BigDecimal floor, BigDecimal value) {
		return value.compareTo(floor) < 0 ? floor.setScale(SCALE) : value;
	}

}
