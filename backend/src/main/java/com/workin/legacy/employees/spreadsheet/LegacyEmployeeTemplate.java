package com.workin.legacy.employees.spreadsheet;

import java.util.List;
import java.util.Map;

import com.workin.legacy.spreadsheet.LegacyCsvWriter;
import com.workin.legacy.spreadsheet.LegacyXlsxWriter;

/**
 * {@code employees/template_excel.php} and
 * {@code stream_employee_template_xlsx()}: the two files the endpoint hands
 * out, as bytes.
 *
 * <p>The two branches are not the same document with a different extension.
 * The XLSX writes the group label once per block and lets a merge carry it
 * across; the CSV, which has no merges, repeats the label in every column of
 * the block. Both are legacy's, and D-085 keeps this generator's contract
 * unchanged -- the reader is what that decision corrects.
 */
public final class LegacyEmployeeTemplate {

	/** {@code header('Content-Type: text/csv; charset=utf-8')}. */
	public static final String CSV_CONTENT_TYPE = "text/csv; charset=utf-8";

	/** The XLSX branch's own {@code Content-Type}, spelled out in full. */
	public static final String XLSX_CONTENT_TYPE =
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	/** {@code 'Employees'}, the sheet name {@code stream_employee_template_xlsx()} passes. */
	public static final String SHEET_NAME = "Employees";

	private LegacyEmployeeTemplate() {
	}

	/**
	 * The CSV branch: a BOM, the repeated group labels, then the 28 headers.
	 *
	 * <p>Only two records -- the template is handed out empty, and the examples
	 * live inside the header cells rather than in a sample row.
	 */
	public static byte[] csv(List<String> headers) {
		return LegacyCsvWriter.writeWithBom(List.of(
				LegacyEmployeeSpreadsheetColumns.csvGroupRow(), headers));
	}

	/**
	 * The XLSX branch: row 1 the merged group titles, row 2 the column headers,
	 * no data rows, both leading rows styled and frozen.
	 */
	public static byte[] xlsx(List<String> headers) {
		Map<Integer, Map<Integer, Integer>> cellStyles =
				LegacyEmployeeSpreadsheetColumns.templateCellStyles();
		return LegacyXlsxWriter.build(
				headers,
				List.of(),
				SHEET_NAME,
				List.of(LegacyEmployeeSpreadsheetColumns.templateGroupRow()),
				LegacyEmployeeSpreadsheetColumns.templateGroupMerges(),
				2,
				cellStyles);
	}

	/** {@code 'employees_template_' . date('Y-m-d') . '.csv'} -- or {@code .xlsx}. */
	public static String filename(String today, boolean csv) {
		return "employees_template_" + today + (csv ? ".csv" : ".xlsx");
	}

	/** {@code header('Content-Disposition: attachment; filename="' . $filename . '"')}. */
	public static String contentDisposition(String filename) {
		return "attachment; filename=\"" + filename + "\"";
	}

}
