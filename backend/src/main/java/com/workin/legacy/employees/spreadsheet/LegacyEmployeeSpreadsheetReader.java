package com.workin.legacy.employees.spreadsheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.spreadsheet.LegacyCsvReader;
import com.workin.legacy.spreadsheet.LegacySpreadsheetFormat;
import com.workin.legacy.spreadsheet.LegacyXlsxReader;

/**
 * {@code employee_excel_read_headers()},
 * {@code employee_excel_assert_template_structure()} and
 * {@code employee_excel_load_rows()} -- the employee flow's own reader, with
 * D-085's two corrections and nothing else.
 *
 * <h2>What D-085 changes</h2>
 * <p>Two defects in {@code employee_excel_load_rows()} are corrected here, and
 * only those two. First, its CSV branch consumed three bytes when there was
 * <em>no</em> BOM and kept the BOM when there was one, which glued the BOM to
 * the group row's first cell and shifted every subsequent row by one. Second,
 * its XLSX branch treats "the workbook parsed but held no data rows" as "the
 * workbook could not be read" and re-opens the ZIP as CSV, producing rows whose
 * keys are deflate bytes. Neither happens here: the BOM is consumed exactly
 * once when present, and ZIP bytes are never handed to the CSV reader.
 *
 * <p>Everything else is legacy's, defects included -- notably that
 * {@code read_headers()} already had the BOM logic <em>right</em>, which is why
 * structure validation passed on files whose rows loaded corrupted.
 *
 * <h2>Which formats the employee flow supports</h2>
 * <p>CSV and XLSX. An OLE2 {@code .xls} is matched by neither of legacy's two
 * branches, so {@code $headerRaw} stays null and {@code read_headers()} throws
 * {@code Empty or unreadable file} -- and because {@code analyze()} asserts the
 * structure first, that is the whole outcome of an XLS upload. The attendance
 * module's XLS support is not borrowed: this flow never had it, and giving it
 * one would accept files legacy rejects.
 */
public final class LegacyEmployeeSpreadsheetReader {

	/** {@code throw new RuntimeException('Empty or unreadable file')}. */
	public static final String EMPTY_OR_UNREADABLE = "Empty or unreadable file";

	private LegacyEmployeeSpreadsheetReader() {
	}

	/** A {@code RuntimeException} out of the spreadsheet helper: its message becomes the API message. */
	public static class LegacySpreadsheetException extends RuntimeException {

		public LegacySpreadsheetException(String message) {
			super(message);
		}

	}

	/** {@code ['normalized' => ..., 'raw' => ...]}. */
	public record Headers(List<String> normalized, List<String> raw) {
	}

	/**
	 * {@code employee_excel_read_headers()}: the header row, with the group row
	 * skipped when there is one, trailing blank columns dropped, and each
	 * remaining label normalized to a canonical key.
	 *
	 * <p>The blank-column rule is {@code continue}, not truncation: a blank cell
	 * <em>anywhere</em> is dropped, so a gap in the middle closes up rather than
	 * shifting the columns after it.
	 */
	public static Headers readHeaders(byte[] content) {
		LegacySpreadsheetFormat format = LegacySpreadsheetFormat.detect(content);
		if (format == LegacySpreadsheetFormat.EMPTY || format == LegacySpreadsheetFormat.UNKNOWN) {
			throw new LegacySpreadsheetException(EMPTY_OR_UNREADABLE);
		}

		List<String> headerRaw = null;
		if (format == LegacySpreadsheetFormat.XLSX) {
			List<List<String>> matrix = readWorkbook(content);
			if (matrix != null && !matrix.isEmpty()) {
				List<List<String>> rows = new ArrayList<>(matrix);
				if (LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(rows.get(0))) {
					rows.remove(0);
				}
				headerRaw = rows.isEmpty() ? List.of() : rows.get(0);
			} else if (matrix == null) {
				// The XlsxParser threw, and legacy falls back to reading the
				// same bytes as CSV. Only reachable for a file that is not a
				// readable workbook at all.
				format = LegacySpreadsheetFormat.CSV;
			}
		}

		if (headerRaw == null
				&& (format == LegacySpreadsheetFormat.CSV || format == LegacySpreadsheetFormat.XLSX)) {
			List<List<String>> records = LegacyCsvReader.read(content);
			List<String> first = records.isEmpty() ? null : records.get(0);
			if (first != null && LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(first)) {
				first = records.size() > 1 ? records.get(1) : null;
			}
			headerRaw = first == null ? List.of() : first;
		}

		// An XLS reaches here with headerRaw still null, which is legacy's own
		// outcome for a format neither branch handles.
		if (headerRaw == null || headerRaw.isEmpty()) {
			throw new LegacySpreadsheetException(EMPTY_OR_UNREADABLE);
		}

		List<String> normalized = new ArrayList<>();
		List<String> raw = new ArrayList<>();
		for (String cell : headerRaw) {
			String label = LegacyValues.phpTrim(LegacyEmployeeSpreadsheetValues.asString(cell));
			if (label.isEmpty()) {
				continue;
			}
			raw.add(label);
			normalized.add(LegacyEmployeeSpreadsheetColumns.normalizeHeaderKey(label));
		}
		return new Headers(List.copyOf(normalized), List.copyOf(raw));
	}

	/**
	 * {@code employee_excel_assert_template_structure()}: all 28 canonical
	 * columns must be present, in any order, with nothing unknown and nothing
	 * duplicated.
	 *
	 * <p>The rejection message is built from counts, not from the offending
	 * names, and it is localized by the request's own locale -- so the Arabic
	 * and English forms are both reproduced literally, newlines included.
	 */
	public static void assertTemplateStructure(byte[] content, boolean arabic) {
		Headers headers = readHeaders(content);
		List<String> found = headers.normalized();

		if (found.isEmpty()) {
			throw new LegacySpreadsheetException(arabic
					? "تم رفض الملف: لا يوجد صف عناوين. نزّل قالب الموظفين من النظام واملأه دون تعديل أسماء الأعمدة."
					: "File rejected: no header row found. Download the employee template and fill it "
							+ "without changing column titles.");
		}

		List<String> expected = LegacyEmployeeSpreadsheetColumns.columns().stream()
				.map(LegacyEmployeeSpreadsheetColumns.Column::key).toList();

		int missing = 0;
		for (LegacyEmployeeSpreadsheetColumns.Column column : LegacyEmployeeSpreadsheetColumns.columns()) {
			if (!found.contains(column.key())) {
				missing++;
			}
		}

		int unknown = 0;
		for (String key : found) {
			if (!expected.contains(key)) {
				unknown++;
			}
		}

		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String key : found) {
			counts.merge(key, 1, Integer::sum);
		}
		int duplicates = 0;
		for (Map.Entry<String, Integer> count : counts.entrySet()) {
			if (count.getValue() > 1 && expected.contains(count.getKey())) {
				duplicates++;
			}
		}

		if (missing == 0 && unknown == 0 && duplicates == 0) {
			return;
		}

		List<String> parts = new ArrayList<>();
		if (arabic) {
			parts.add("تم رفض الملف لأنه غير مطابق لقالب الموظفين.");
			if (missing > 0) {
				parts.add("أعمدة ناقصة: " + missing);
			}
			if (unknown > 0) {
				parts.add("أعمدة غير معروفة أو تم تعديل اسمها: " + unknown);
			}
			if (duplicates > 0) {
				parts.add("أعمدة مكررة: " + duplicates);
			}
			parts.add("نزّل القالب من النظام من جديد واملأ البيانات دون حذف أو تغيير أسماء الأعمدة.");
		} else {
			parts.add("File rejected: it does not match the employee template.");
			if (missing > 0) {
				parts.add("Missing columns: " + missing);
			}
			if (unknown > 0) {
				parts.add("Unknown or renamed columns: " + unknown);
			}
			if (duplicates > 0) {
				parts.add("Duplicate columns: " + duplicates);
			}
			parts.add("Download a fresh template and fill it without removing or renaming columns.");
		}
		throw new LegacySpreadsheetException(String.join("\n", parts));
	}

	/**
	 * {@code employee_excel_load_rows()}: the surviving data rows, keyed by
	 * canonical column.
	 *
	 * <p>The filtering after the read is legacy's, in order: an entirely empty
	 * row is dropped, a hint or example row is dropped, and the first row only
	 * is dropped when it looks like an old template's label row -- no employee
	 * code, a first name, and the word {@code اسم} or {@code first} in it.
	 */
	public static List<Map<String, Object>> loadRows(byte[] content) {
		LegacySpreadsheetFormat format = LegacySpreadsheetFormat.detect(content);
		if (format == LegacySpreadsheetFormat.EMPTY || format == LegacySpreadsheetFormat.UNKNOWN) {
			throw new LegacySpreadsheetException(EMPTY_OR_UNREADABLE);
		}

		List<Map<String, Object>> rawRows = new ArrayList<>();
		boolean readAsWorkbook = false;
		if (format == LegacySpreadsheetFormat.XLSX) {
			List<List<String>> matrix = readWorkbook(content);
			if (matrix == null) {
				// Legacy's `catch (RuntimeException) { $format = 'csv'; }`.
				format = LegacySpreadsheetFormat.CSV;
			} else {
				readAsWorkbook = true;
				if (!matrix.isEmpty()) {
					List<List<String>> rows = new ArrayList<>(matrix);
					if (LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(rows.get(0))) {
						rows.remove(0);
					}
					List<String> header = rows.isEmpty() ? List.of() : normalize(rows.remove(0));
					for (List<String> row : rows) {
						Map<String, Object> combined = LegacyEmployeeSpreadsheetValues.assocRow(header, row);
						if (combined != null) {
							rawRows.add(combined);
						}
					}
				}
			}
		}

		// D-085: the CSV branch runs for a CSV, and for an XLSX only when the
		// workbook could not be parsed at all. Legacy also re-ran it whenever a
		// parsed workbook happened to hold no data rows, which is the defect.
		if (format == LegacySpreadsheetFormat.CSV && !readAsWorkbook) {
			List<List<String>> records = LegacyCsvReader.read(content);
			int index = 0;
			List<String> headerRaw = index < records.size() ? records.get(index++) : null;
			if (headerRaw != null && LegacyEmployeeSpreadsheetColumns.isSalaryGroupRow(headerRaw)) {
				headerRaw = index < records.size() ? records.get(index++) : null;
			}
			List<String> header = headerRaw == null ? List.of() : normalize(headerRaw);
			while (index < records.size()) {
				Map<String, Object> combined =
						LegacyEmployeeSpreadsheetValues.assocRow(header, records.get(index++));
				if (combined != null) {
					rawRows.add(combined);
				}
			}
		}

		List<Map<String, Object>> dataRows = new ArrayList<>();
		for (int index = 0; index < rawRows.size(); index++) {
			Map<String, Object> row = rawRows.get(index);
			if (LegacyEmployeeSpreadsheetValues.isRowEmpty(row)) {
				continue;
			}
			if (LegacyEmployeeSpreadsheetValues.isHintRow(row)) {
				continue;
			}
			if (index == 0 && isLegacyLabelRow(row)) {
				continue;
			}
			dataRows.add(row);
		}
		return dataRows;
	}

	/**
	 * The older-template skip: {@code $index === 0 && empty($employee_code) && !empty($first_name)}
	 * and the first name reads like a label rather than a name.
	 *
	 * <p>{@code empty()}, so a code of {@code '0'} counts as absent and a first
	 * name of {@code '0'} counts as missing -- the PHP predicate, not a blank
	 * check.
	 */
	private static boolean isLegacyLabelRow(Map<String, Object> row) {
		if (!LegacyValues.isPhpEmpty(row.get("employee_code"))
				|| LegacyValues.isPhpEmpty(row.get("first_name"))) {
			return false;
		}
		String first = LegacyValues.mbStrToLower(
				LegacyValues.phpTrim(LegacyEmployeeSpreadsheetValues.asString(row.get("first_name"))));
		return first.contains("اسم") || first.contains("first");
	}

	private static List<String> normalize(List<String> headerRow) {
		List<String> header = new ArrayList<>(headerRow.size());
		for (String cell : headerRow) {
			header.add(LegacyEmployeeSpreadsheetColumns.normalizeHeaderKey(
					LegacyEmployeeSpreadsheetValues.asString(cell)));
		}
		return header;
	}

	/**
	 * The workbook as a matrix, or null when the parser threw -- which is the
	 * only case legacy's {@code catch (RuntimeException)} covers. A workbook
	 * that parses to nothing is an empty matrix, never a null.
	 */
	private static List<List<String>> readWorkbook(byte[] content) {
		try {
			return LegacyXlsxReader.readFirstSheet(content);
		} catch (LegacyXlsxReader.LegacyXlsxException ex) {
			return null;
		}
	}

}
