package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Measured oracle for {@code data_export_payslip_csv_row()} and
 * {@code data_export_payslips_csv()}'s filename rule.
 *
 * <p>The row is thirty-one positional cells, so every case here is about a
 * column landing where a client expects it. Three of them are traps that look
 * like transcription slips and are not.
 */
class LegacyPayslipExportSheetTest {

	@Test
	void theSheetDeclaresThirtyOneDistinctColumns() {
		assertThat(LegacyPayslipExportSheet.HEADER_KEYS).hasSize(31).doesNotHaveDuplicates();
	}

	@Test
	void aFullRowFillsEveryColumnInHeaderOrder() {
		List<String> row = LegacyPayslipExportSheet.row(full(), 7);

		assertThat(row).hasSameSizeAs(LegacyPayslipExportSheet.HEADER_KEYS);
		assertThat(row.get(0)).as("serial").isEqualTo("7");
		assertThat(row.get(1)).isEqualTo("E-1");
		assertThat(row.get(2)).isEqualTo("Ada L");
		assertThat(row.get(30)).as("net salary is the last column").isEqualTo("4321.00");
	}

	/**
	 * The {@code csv_medical_insurance} header is fed by {@code advances_deduction}
	 * -- header and key genuinely disagree in the frozen tree, and the column is
	 * read positionally, so "fixing" it would move a client's number.
	 */
	@Test
	void theMedicalInsuranceColumnCarriesAdvancesDeduction() {
		int index = LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_medical_insurance");
		Map<String, Object> source = full();
		source.put("advances_deduction", "99.99");

		assertThat(LegacyPayslipExportSheet.row(source, 1).get(index)).isEqualTo("99.99");
	}

	/**
	 * {@code advance_deduction} and {@code advances_deduction} are two different
	 * columns one position apart in the source map -- singular and plural.
	 */
	@Test
	void theSingularAndPluralAdvanceColumnsAreNotTheSameCell() {
		Map<String, Object> source = full();
		source.put("advance_deduction", "11.00");
		source.put("advances_deduction", "22.00");
		List<String> row = LegacyPayslipExportSheet.row(source, 1);

		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_advance_deduction")))
				.isEqualTo("11.00");
		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_medical_insurance")))
				.isEqualTo("22.00");
	}

	/** {@code month_days} defaults to 30, where every other numeric column defaults to 0. */
	@Test
	void monthDaysDefaultsToThirtyAndOtherNumbersToZero() {
		List<String> row = LegacyPayslipExportSheet.row(new LinkedHashMap<>(), 1);

		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_month_days")))
				.as("the fixed-30-day divisor D-031 accepted, surfacing in the export")
				.isEqualTo("30");
		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_net_salary"))).isEqualTo("0");
		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_employee_name")))
				.as("text columns default to empty, not to 0")
				.isEmpty();
	}

	/** PHP's {@code ??} falls through on null only -- a present zero wins. */
	@Test
	void theTwoFallbackColumnsPreferAPresentZeroOverTheFallback() {
		Map<String, Object> source = full();
		source.put("salary_by_present_days", "0.00");
		source.put("salary_by_attendance", "500.00");
		source.put("contract_basic_salary", "0.00");
		source.put("basic_salary", "900.00");
		List<String> row = LegacyPayslipExportSheet.row(source, 1);

		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_salary_by_attendance")))
				.isEqualTo("0.00");
		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_contract_basic_salary")))
				.isEqualTo("0.00");
	}

	@Test
	void theTwoFallbackColumnsUseTheirFallbackWhenThePrimaryIsAbsent() {
		Map<String, Object> source = full();
		source.remove("salary_by_present_days");
		source.remove("contract_basic_salary");
		source.put("salary_by_attendance", "500.00");
		source.put("basic_salary", "900.00");
		List<String> row = LegacyPayslipExportSheet.row(source, 1);

		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_salary_by_attendance")))
				.isEqualTo("500.00");
		assertThat(row.get(LegacyPayslipExportSheet.HEADER_KEYS.indexOf("csv_contract_basic_salary")))
				.isEqualTo("900.00");
	}

	@Test
	void theFilenameNamesTheBatchOrThePeriodOrToday() {
		assertThat(LegacyPayslipExportSheet.filename(41L, "", "", "2026-08-28"))
				.isEqualTo("payslips_batch_41.xlsx");
		assertThat(LegacyPayslipExportSheet.filename(null, "", "", "2026-08-28"))
				.isEqualTo("payslips_2026-08-28.xlsx");
		assertThat(LegacyPayslipExportSheet.filename(null, "2026-05-01", "2026-05-31", "2026-08-28"))
				.isEqualTo("payslips_2026-05-01_2026-05-31.xlsx");
	}

	/** The date branch runs second and overwrites, so dates beat a batch. */
	@Test
	void aCompleteDateRangeOverridesTheBatchInTheFilename() {
		assertThat(LegacyPayslipExportSheet.filename(41L, "2026-05-01", "2026-05-31", "2026-08-28"))
				.isEqualTo("payslips_2026-05-01_2026-05-31.xlsx");
	}

	/** One bound alone never reaches the filename -- the caller refuses it first. */
	@Test
	void aHalfSuppliedRangeFallsBackToTheBatchOrToday() {
		assertThat(LegacyPayslipExportSheet.filename(41L, "2026-05-01", "", "2026-08-28"))
				.isEqualTo("payslips_batch_41.xlsx");
		assertThat(LegacyPayslipExportSheet.filename(null, "", "2026-05-31", "2026-08-28"))
				.isEqualTo("payslips_2026-08-28.xlsx");
	}

	private static Map<String, Object> full() {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("employee_code", "E-1");
		source.put("employee_name", "Ada L");
		source.put("job_title_name", "Engineer");
		source.put("department_name", "R&D");
		source.put("branch_name", "Main");
		source.put("gross_salary", "5000.00");
		source.put("daily_basic_rate", "166.67");
		source.put("month_days", "30");
		source.put("days_present", "22");
		source.put("salary_by_present_days", "3666.74");
		source.put("overtime_hours", "4.00");
		source.put("overtime_pay", "100.00");
		source.put("contract_basic_salary", "5000.00");
		source.put("transport_allowance", "200.00");
		source.put("food_allowance", "150.00");
		source.put("risk_allowance", "0.00");
		source.put("incentives", "50.00");
		source.put("total_entitlements", "5400.00");
		source.put("penalty_days", "1");
		source.put("penalties_total", "166.67");
		source.put("days_absent", "2");
		source.put("absence_cost", "333.34");
		source.put("advance_deduction", "100.00");
		source.put("insurance_deduction", "75.00");
		source.put("tax_deduction", "50.00");
		source.put("advances_deduction", "25.00");
		source.put("fund_deduction", "10.00");
		source.put("other_deductions", "5.00");
		source.put("total_deductions", "765.01");
		source.put("net_salary", "4321.00");
		return source;
	}
}
