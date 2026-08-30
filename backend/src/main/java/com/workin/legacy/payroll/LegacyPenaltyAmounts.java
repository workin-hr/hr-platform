package com.workin.legacy.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyValues;

/**
 * {@code helpers/penalties_amount_helper.php} -- the monetary value of a
 * penalty, extracted so the two callers cannot drift.
 *
 * <p>It was inlined in {@link LegacyPenaltyService} while
 * {@code penalties/stats.php} was its only consumer. Item 13.5 adds a second:
 * {@code dashboard/stats.php} computes {@code total_penalties_amount} through
 * the same helper with a bare company predicate. Two copies of a money
 * calculation that must agree is exactly the shape the change-propagation rule
 * exists to prevent.
 *
 * <h2>The divisor is fixed at 30, deliberately (D-031)</h2>
 * <p>{@code PENALTY_CALENDAR_DAYS_PER_MONTH = 30}: a penalty day is the gross
 * monthly package divided by thirty regardless of the month's real length,
 * and weekly offs and official holidays are ignored. The helper's own comment
 * says "intentionally".
 *
 * <h2>Rounding happens three times, not once</h2>
 * <p>PHP rounds the gross package, rounds the day rate, rounds each row's
 * amount, and rounds the total -- so the result is not the same as summing
 * unrounded products and rounding once. The order is reproduced exactly;
 * collapsing it would move totals by cents on large result sets.
 */
@Service
public class LegacyPenaltyAmounts {

	/** {@code PENALTY_CALENDAR_DAYS_PER_MONTH}. */
	private static final BigDecimal CALENDAR_DAYS_PER_MONTH = BigDecimal.valueOf(30);

	private final LegacyPenaltyStore store;

	public LegacyPenaltyAmounts(LegacyPenaltyStore store) {
		this.store = store;
	}

	/**
	 * {@code penalties_total_amount($where, $bind, $company_id)}.
	 *
	 * <p>PHP returns {@code 0.0} immediately when {@code $where} is empty,
	 * rather than summing every penalty in the database. Reproduced: an empty
	 * predicate list here is a caller bug, and legacy answers zero rather than
	 * leaking a cross-tenant total.
	 */
	public double totalAmount(List<String> predicates, List<Object> binds) {
		if (predicates.isEmpty()) {
			return 0.0;
		}
		return totalAmount(store.amountRows(predicates, binds));
	}

	/** The row-loop half, for a caller that already has the rows. */
	public double totalAmount(List<Map<String, Object>> rows) {
		BigDecimal total = BigDecimal.ZERO;
		for (Map<String, Object> row : rows) {
			total = total.add(amountForRow(row));
		}
		return total.setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	/**
	 * {@code penalty_monetary_amount_for_row()}.
	 *
	 * <p>Note what makes a row worth zero: non-positive days, a missing
	 * employee or date, <b>no contract effective on or before the penalty
	 * date</b>, or a non-positive gross. A penalty predating the employee's
	 * first contract is therefore free, silently.
	 */
	private BigDecimal amountForRow(Map<String, Object> row) {
		double days = LegacyValues.toPhpDecimal(row.get("penalty_days")).doubleValue();
		long employeeId = LegacyValues.toPhpLong(row.get("employee_id"));
		String date = LegacyValues.toPhpString(row.get("penalty_date"));
		if (days <= 0 || employeeId <= 0 || date.isEmpty()) {
			return BigDecimal.ZERO;
		}
		Map<String, Object> contract = store.salaryContractAt(employeeId, date);
		if (contract == null) {
			return BigDecimal.ZERO;
		}
		BigDecimal gross = grossMonthly(contract);
		if (gross.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal rate = gross.divide(CALENDAR_DAYS_PER_MONTH, 2, RoundingMode.HALF_UP);
		return rate.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * {@code penalty_contract_gross_monthly()}.
	 *
	 * <p>A daily contract's wage is multiplied up by the same fixed 30, while
	 * its allowances stay monthly -- so the two branches are not the same
	 * formula with a different base.
	 */
	private static BigDecimal grossMonthly(Map<String, Object> contract) {
		BigDecimal base = "daily".equals(contract.get("salary_mode"))
				? money(contract.get("daily_wage")).multiply(CALENDAR_DAYS_PER_MONTH)
				: money(contract.get("basic_salary"));
		return base
				.add(money(contract.get("housing_allowance")))
				.add(money(contract.get("transport_allowance")))
				.add(money(contract.get("food_allowance")))
				.add(money(contract.get("risk_allowance")))
				.add(money(contract.get("incentives")))
				.setScale(2, RoundingMode.HALF_UP);
	}

	private static BigDecimal money(Object value) {
		return value == null ? BigDecimal.ZERO : LegacyValues.toPhpDecimal(value);
	}
}
