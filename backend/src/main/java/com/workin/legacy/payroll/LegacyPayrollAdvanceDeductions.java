package com.workin.legacy.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.workin.legacy.LegacyValues;

/**
 * {@code payroll_advance_planned_for_batch()}, {@code
 * payroll_advance_deduction_items_for_batch()} and {@code
 * payroll_advance_deduction_for_batch()} ({@code payroll_calculation.php:870-952}) --
 * pure planning, no database. {@link LegacyPayrollBatchStore} owns the reads
 * and writes ({@code advances} rows, {@code payroll_apply_advance_payment()},
 * {@code payroll_restore_advance_deductions()}).
 */
@Component
public class LegacyPayrollAdvanceDeductions {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final int SCALE = 2;

	public record DeductionItem(long advanceId, BigDecimal amount) {
	}

	/** {@code payroll_batch_month_index()}. */
	static int monthIndex(int year, int month) {
		return year * 12 + Math.max(1, Math.min(12, month));
	}

	/**
	 * {@code payroll_advance_planned_for_batch()} ({@code :879-925}): the
	 * amount this one advance would contribute to this batch's month, before
	 * capping by its remaining balance.
	 */
	public BigDecimal plannedForBatch(Map<String, Object> advance, int batchYear, int batchMonth) {
		Object modeRaw = advance.get("deduction_mode");
		String mode = modeRaw == null ? "single_payroll_month" : LegacyValues.toPhpString(modeRaw);

		if ("single_payroll_month".equals(mode)) {
			int year = (int) LegacyValues.toPhpLong(advance.get("deduction_payroll_year"));
			int month = (int) LegacyValues.toPhpLong(advance.get("deduction_payroll_month"));
			return year == batchYear && month == batchMonth
					? decimal(advance.get("amount"))
					: BigDecimal.ZERO;
		}

		Object json = advance.get("deduction_installments_json");
		if (json != null && !"".equals(json)) {
			BigDecimal fromInstallments = plannedFromInstallments(
					LegacyValues.toPhpString(json), batchYear, batchMonth);
			if (fromInstallments != null) {
				return fromInstallments;
			}
		}

		BigDecimal per = decimal(advance.get("deduction_amount_per_month"));
		int startYear = (int) LegacyValues.toPhpLong(advance.get("deduction_payroll_year"));
		int startMonth = (int) LegacyValues.toPhpLong(advance.get("deduction_payroll_month"));
		int count = Math.max(1, (int) LegacyValues.toPhpLong(advance.get("deduction_month_count")));
		if (per.signum() > 0 && startYear > 0 && startMonth > 0) {
			int batchIdx = monthIndex(batchYear, batchMonth);
			int startIdx = monthIndex(startYear, startMonth);
			if (batchIdx >= startIdx && batchIdx < startIdx + count) {
				return per;
			}
		}
		return BigDecimal.ZERO;
	}

	/** The first matching {@code {year|payroll_year, month|payroll_month, amount}} entry, or null if none match. */
	private BigDecimal plannedFromInstallments(String json, int batchYear, int batchMonth) {
		JsonNode root;
		try {
			root = JSON.readTree(json);
		} catch (RuntimeException ex) {
			return null;
		}
		if (root == null || !root.isArray()) {
			return null;
		}
		for (JsonNode item : root) {
			if (!item.isObject()) {
				continue;
			}
			int year = intField(item, "year", "payroll_year");
			int month = intField(item, "month", "payroll_month");
			BigDecimal amount = decimalField(item, "amount");
			if (year == batchYear && month == batchMonth && amount.signum() > 0) {
				return amount;
			}
		}
		return null;
	}

	/**
	 * {@code payroll_advance_deduction_items_for_batch()} ({@code :927-950}):
	 * one item per advance with a positive remaining balance and a positive
	 * planned amount, capped at the remaining balance.
	 */
	public List<DeductionItem> itemsForBatch(List<Map<String, Object>> advances, int batchYear, int batchMonth) {
		List<DeductionItem> items = new ArrayList<>();
		for (Map<String, Object> advance : advances) {
			BigDecimal remaining = decimal(advance.get("remaining"));
			if (remaining.signum() <= 0) {
				continue;
			}
			BigDecimal planned = plannedForBatch(advance, batchYear, batchMonth);
			if (planned.signum() <= 0) {
				continue;
			}
			BigDecimal deduct = planned.min(remaining);
			if (deduct.signum() > 0) {
				items.add(new DeductionItem(
						LegacyValues.toPhpLong(advance.get("id")), deduct.setScale(SCALE, RoundingMode.HALF_UP)));
			}
		}
		return items;
	}

	/** {@code payroll_advance_deduction_for_batch()} ({@code :952-959}). */
	public BigDecimal deductionForBatch(List<Map<String, Object>> advances, int batchYear, int batchMonth) {
		BigDecimal sum = BigDecimal.ZERO;
		for (DeductionItem item : itemsForBatch(advances, batchYear, batchMonth)) {
			sum = sum.add(item.amount());
		}
		return sum.setScale(SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * The proportional restore split from {@code payroll_restore_advance_deductions()}
	 * ({@code :981-1019}) -- pure math; {@link LegacyPayrollBatchStore} applies
	 * the resulting per-advance amounts.
	 */
	public record RestoreShare(long advanceId, BigDecimal amount) {
	}

	public List<RestoreShare> restoreShares(
			List<Map<String, Object>> approvedAdvances, BigDecimal deductedTotal, int batchYear, int batchMonth) {
		if (deductedTotal.signum() <= 0) {
			return List.of();
		}
		record Plan(long id, BigDecimal planned) {
		}
		List<Plan> plans = new ArrayList<>();
		for (Map<String, Object> advance : approvedAdvances) {
			BigDecimal planned = plannedForBatch(advance, batchYear, batchMonth);
			if (planned.signum() > 0) {
				plans.add(new Plan(LegacyValues.toPhpLong(advance.get("id")), planned));
			}
		}
		if (plans.isEmpty()) {
			return List.of();
		}
		BigDecimal plannedSum = BigDecimal.ZERO;
		for (Plan plan : plans) {
			plannedSum = plannedSum.add(plan.planned());
		}

		List<RestoreShare> shares = new ArrayList<>();
		BigDecimal left = deductedTotal;
		int lastIndex = plans.size() - 1;
		for (int i = 0; i < plans.size(); i++) {
			Plan plan = plans.get(i);
			BigDecimal add = i == lastIndex
					? left.setScale(SCALE, RoundingMode.HALF_UP)
					: deductedTotal.multiply(plan.planned()).divide(plannedSum, SCALE, RoundingMode.HALF_UP);
			left = left.subtract(add);
			if (add.signum() > 0) {
				shares.add(new RestoreShare(plan.id(), add));
			}
		}
		return shares;
	}

	private static int intField(JsonNode item, String key, String fallbackKey) {
		JsonNode node = item.has(key) ? item.get(key) : item.get(fallbackKey);
		return node == null || node.isNull() ? 0 : node.asInt(0);
	}

	private static BigDecimal decimalField(JsonNode item, String key) {
		JsonNode node = item.get(key);
		return node == null || node.isNull() ? BigDecimal.ZERO : BigDecimal.valueOf(node.asDouble(0));
	}

	private static BigDecimal decimal(Object raw) {
		return raw == null ? BigDecimal.ZERO : LegacyValues.toPhpDecimal(raw);
	}
}
