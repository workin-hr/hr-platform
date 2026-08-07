package com.workin.backend.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * The single source of payroll math for this backend -- the direct
 * structural fix for hr-legacy#12/#13 (three independent, divergent
 * implementations in legacy). Every entry point that needs a payslip
 * computed -- batch calculate, and manually adding one payslip to a
 * draft batch -- must call this class and nothing else. See
 * docs/migration/payroll-module-execution-plan.md's
 * "PayrollCalculationService" section for the full rationale and the
 * open product decisions this depends on.
 *
 * <p><strong>What is verified against legacy evidence</strong> (safe to
 * treat as correct): the fixed-30-day divisor
 * (business-rule-extraction.md), the daily-wage-to-monthly-equivalent
 * conversion (dailyWage * 30) that hr-legacy#12 confirmed
 * `payslips/create.php` was skipping, and that DAILY-mode contracts
 * never have a nonzero basicSalary to fall back on.
 *
 * <p><strong>What is NOT verified against legacy evidence</strong>: the
 * exact overtime-pay multiplier. `docs/legacy/business-rule-extraction.md`
 * and `docs/api/existing-endpoint-inventory.md` document that overtime
 * pay exists and is computed in `apis/helpers/payroll_calculation.php`,
 * but neither captured the literal formula. This class uses
 * {@code dayRate / 8} per overtime hour (a common day-rate/8-hour
 * convention) as an explicit, documented placeholder -- not a
 * confirmed port of legacy behavior. Do not treat this as
 * differential-testing-ready (PMR-10, `hr-platform#16`) until it is
 * checked directly against `apis/helpers/payroll_calculation.php`'s
 * real formula.
 */
@Service
public class PayrollCalculationService {

	/** Legacy's fixed divisor -- business-rule-extraction.md, "Payroll day-rate uses a fixed 30-day month." */
	private static final BigDecimal CALENDAR_DAYS_PER_MONTH = BigDecimal.valueOf(30);
	private static final int SCALE = 2;

	public record AttendanceFigures(int daysPresent, int daysAbsent, int daysLeave, BigDecimal overtimeHours) {
	}

	public record PayslipComputation(
			BigDecimal basicSalary,
			BigDecimal allowances, // housingAllowance carried here -- see execution plan's "allowances" naming note
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
			BigDecimal netSalary) {
	}

	public PayslipComputation compute(
			SalaryContract contract,
			AttendanceFigures attendance,
			List<Penalty> unappliedPenaltiesInPeriod,
			BigDecimal advanceDeductionForPeriod) {

		BigDecimal monthlyEquivalentBasic = contract.getSalaryMode() == SalaryMode.DAILY
				? contract.getDailyWage().multiply(CALENDAR_DAYS_PER_MONTH)
				: contract.getBasicSalary();

		BigDecimal dayRate = monthlyEquivalentBasic.divide(CALENDAR_DAYS_PER_MONTH, 6, RoundingMode.HALF_UP);
		BigDecimal absenceCost = dayRate.multiply(BigDecimal.valueOf(attendance.daysAbsent()));

		// See class Javadoc -- overtime multiplier is an explicit,
		// documented placeholder, not a confirmed legacy formula.
		BigDecimal hourlyRate = dayRate.divide(BigDecimal.valueOf(8), 6, RoundingMode.HALF_UP);
		BigDecimal overtimePay = hourlyRate.multiply(attendance.overtimeHours()).setScale(SCALE, RoundingMode.HALF_UP);

		BigDecimal basicAfterAbsence = monthlyEquivalentBasic.subtract(absenceCost).setScale(SCALE, RoundingMode.HALF_UP);

		BigDecimal penaltiesTotal = unappliedPenaltiesInPeriod.stream()
				.map(p -> dayRate.multiply(p.getPenaltyDays()))
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(SCALE, RoundingMode.HALF_UP);

		BigDecimal otherDeductions = contract.getInsuranceDeduction()
				.add(contract.getTaxDeduction())
				.add(contract.getFundDeduction())
				.add(contract.getPenaltyDeduction());

		BigDecimal totalEntitlements = basicAfterAbsence
				.add(contract.getHousingAllowance())
				.add(contract.getTransportAllowance())
				.add(contract.getFoodAllowance())
				.add(contract.getRiskAllowance())
				.add(contract.getIncentives())
				.add(overtimePay)
				.setScale(SCALE, RoundingMode.HALF_UP);

		BigDecimal totalDeductions = penaltiesTotal
				.add(advanceDeductionForPeriod)
				.add(otherDeductions)
				.setScale(SCALE, RoundingMode.HALF_UP);

		BigDecimal grossSalary = totalEntitlements;
		BigDecimal netSalary = grossSalary.subtract(totalDeductions).setScale(SCALE, RoundingMode.HALF_UP);

		return new PayslipComputation(
				basicAfterAbsence,
				contract.getHousingAllowance(),
				overtimePay,
				contract.getFoodAllowance(),
				contract.getRiskAllowance(),
				contract.getTransportAllowance(),
				contract.getIncentives(),
				penaltiesTotal,
				advanceDeductionForPeriod,
				otherDeductions,
				grossSalary,
				totalEntitlements,
				totalDeductions,
				netSalary);
	}

}
