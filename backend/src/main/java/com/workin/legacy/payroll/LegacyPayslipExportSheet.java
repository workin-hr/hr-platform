package com.workin.legacy.payroll;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.workin.legacy.LegacyValues;

/**
 * {@code data_export_payslip_csv_headers()} and
 * {@code data_export_payslip_csv_row()} ({@code data_export_helper.php:377-454}),
 * plus {@code data_export_payslips_csv()}'s filename rule.
 *
 * <p>Thirty-one columns, and three of them read a <b>fallback key</b> rather
 * than a single one -- see {@link #row}. Splitting this out of the controller
 * keeps that table where it can be read against the PHP side by side.
 */
public final class LegacyPayslipExportSheet {

	/** The thirty-one columns, in order. */
	public static final List<String> HEADER_KEYS = List.of(
			"csv_serial", "csv_emp_code", "csv_employee_name", "csv_job_title", "csv_department",
			"csv_branch", "csv_gross_salary", "csv_daily_basic_rate", "csv_month_days",
			"csv_days_present", "csv_salary_by_attendance", "csv_overtime_hours", "csv_overtime_pay",
			"csv_contract_basic_salary", "csv_transport_allowance", "csv_food_allowance",
			"csv_risk_allowance", "csv_incentives", "csv_total_entitlements", "csv_penalty_days",
			"csv_penalties_total", "csv_days_absent", "csv_absence_cost", "csv_advance_deduction",
			"csv_insurance_deduction", "csv_tax_deduction", "csv_medical_insurance",
			"csv_fund_deduction", "csv_other_deductions", "csv_total_deductions", "csv_net_salary");

	private LegacyPayslipExportSheet() {
	}

	/**
	 * One data row.
	 *
	 * <h2>Three columns fall back to a second key</h2>
	 * <ul>
	 *   <li>{@code salary_by_present_days ?? salary_by_attendance}</li>
	 *   <li>{@code contract_basic_salary ?? basic_salary}</li>
	 * </ul>
	 * and {@code month_days} defaults to <b>30</b> where every other numeric
	 * column defaults to {@code 0} -- the fixed-30-day payroll divisor D-031
	 * accepted, surfacing in the export.
	 *
	 * <p>PHP's {@code ??} tests for null, so a present-but-zero value wins over
	 * the fallback. {@link #coalesce} reproduces that rather than treating zero
	 * as absent.
	 */
	public static List<String> row(Map<String, Object> source, int serial) {
		List<String> cells = new ArrayList<>(HEADER_KEYS.size());
		cells.add(String.valueOf(serial));
		cells.add(text(source.get("employee_code")));
		cells.add(text(source.get("employee_name")));
		cells.add(text(source.get("job_title_name")));
		cells.add(text(source.get("department_name")));
		cells.add(text(source.get("branch_name")));
		cells.add(number(source.get("gross_salary")));
		cells.add(number(source.get("daily_basic_rate")));
		cells.add(numberOr(source.get("month_days"), "30"));
		cells.add(number(source.get("days_present")));
		cells.add(number(coalesce(source, "salary_by_present_days", "salary_by_attendance")));
		cells.add(number(source.get("overtime_hours")));
		cells.add(number(source.get("overtime_pay")));
		cells.add(number(coalesce(source, "contract_basic_salary", "basic_salary")));
		cells.add(number(source.get("transport_allowance")));
		cells.add(number(source.get("food_allowance")));
		cells.add(number(source.get("risk_allowance")));
		cells.add(number(source.get("incentives")));
		cells.add(number(source.get("total_entitlements")));
		cells.add(number(source.get("penalty_days")));
		cells.add(number(source.get("penalties_total")));
		cells.add(number(source.get("days_absent")));
		cells.add(number(source.get("absence_cost")));
		cells.add(number(source.get("advance_deduction")));
		cells.add(number(source.get("insurance_deduction")));
		cells.add(number(source.get("tax_deduction")));
		// The medical-insurance column is fed by `advances_deduction`. Not a
		// transcription slip -- the header and the key genuinely disagree in the
		// frozen tree, and a client reads this column positionally.
		cells.add(number(source.get("advances_deduction")));
		cells.add(number(source.get("fund_deduction")));
		cells.add(number(source.get("other_deductions")));
		cells.add(number(source.get("total_deductions")));
		cells.add(number(source.get("net_salary")));
		return cells;
	}

	/**
	 * {@code $suffix = $batch_id > 0 ? 'batch_' . $batch_id : date('Y-m-d');}
	 * then {@code if ($from !== '' && $to !== '') $suffix = $from . '_' . $to;}
	 *
	 * <p>Order matters: a request carrying <b>both</b> a batch and a complete
	 * date range is named for the dates, because the date branch runs second and
	 * overwrites.
	 */
	public static String filename(Long batchId, String from, String to, String today) {
		String suffix = batchId != null && batchId > 0 ? "batch_" + batchId : today;
		if (from != null && !from.isEmpty() && to != null && !to.isEmpty()) {
			suffix = from + "_" + to;
		}
		return "payslips_" + suffix + ".xlsx";
	}

	/** PHP's {@code ??}: null falls through, a present zero does not. */
	private static Object coalesce(Map<String, Object> source, String primary, String fallback) {
		Object value = source.get(primary);
		return value != null ? value : source.get(fallback);
	}

	private static String text(Object value) {
		return value == null ? "" : LegacyValues.toPhpString(value);
	}

	private static String number(Object value) {
		return value == null ? "0" : LegacyValues.toPhpString(value);
	}

	private static String numberOr(Object value, String fallback) {
		return value == null ? fallback : LegacyValues.toPhpString(value);
	}
}
