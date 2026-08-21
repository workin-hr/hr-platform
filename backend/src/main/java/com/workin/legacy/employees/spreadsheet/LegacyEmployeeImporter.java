package com.workin.legacy.employees.spreadsheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyPhpArray;

/**
 * {@code employee_excel_import_rows()} -- the batch loop.
 *
 * <h2>One transaction per row, not one per batch</h2>
 * <p>Each row is created in its own transaction, so a row that fails rolls back
 * only itself and every row already inserted stays inserted. The response
 * carries the failures rather than the HTTP status doing it: after the guards
 * and the {@code rows} check, this endpoint answers 200 whatever happens to
 * individual rows.
 *
 * <h2>The reservation order that surprises people</h2>
 * <p>A non-empty employee code is added to {@code seen_codes} <em>before</em>
 * the row is validated, so an invalid row still reserves its code. Two rows
 * sharing code 1001 where the first is invalid produce one validation failure
 * and one {@code employee_code_duplicate_in_file} -- the second row is not
 * promoted just because the first one failed. Preserved deliberately: it is the
 * difference between importing a duplicate and refusing one.
 */
@Component
public class LegacyEmployeeImporter {

	private final LegacyEmployeeSpreadsheetAnalyzer analyzer;
	private final LegacyEmployeeCreateHelper createHelper;

	public LegacyEmployeeImporter(
			LegacyEmployeeSpreadsheetAnalyzer analyzer, LegacyEmployeeCreateHelper createHelper) {
		this.analyzer = analyzer;
		this.createHelper = createHelper;
	}

	/**
	 * {@code ['inserted' => int, 'failed' => [...], 'created_ids' => [...]]},
	 * in that key order.
	 */
	public Map<String, Object> importRows(
			long companyId, LegacyPhpArray rows, LegacyEmployeeSpreadsheetLookups lookups) {
		long inserted = 0;
		List<Map<String, Object>> failed = new ArrayList<>();
		List<Long> createdIds = new ArrayList<>();
		Set<String> seenCodes = new HashSet<>();

		for (LegacyPhpArray.Entry entry : rows.entries()) {
			Map<String, Object> row = rowOf(entry.value());

			String code = LegacyEmployeeSpreadsheetErrors.normalizeEmployeeCode(
					LegacyEmployeeSpreadsheetValues.asString(row.get("employee_code")));
			if (!code.isEmpty()) {
				if (seenCodes.contains(code)) {
					failed.add(failure(entry, List.of("employee_code_duplicate_in_file"), row));
					continue;
				}
				// Reserved before validation, so an invalid row still holds it.
				seenCodes.add(code);
			}

			LegacyEmployeeSpreadsheetAnalyzer.Parsed parsed =
					analyzer.rowToPayload(row, companyId, lookups);
			if (!parsed.errors().isEmpty()) {
				failed.add(failure(entry, parsed.errors(), row));
				continue;
			}

			LegacyEmployeeCreateHelper.Result result = createHelper.create(companyId, parsed.payload());
			if (!result.ok()) {
				failed.add(failure(entry, result.errors(), row));
				continue;
			}

			inserted++;
			createdIds.add(result.employeeId());
		}

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("inserted", inserted);
		summary.put("failed", failed);
		summary.put("created_ids", createdIds);
		return summary;
	}

	/**
	 * One failure row: four keys, and {@code data} is the row exactly as it
	 * arrived -- not the display row {@code analyze()} builds, and with no
	 * {@code field_errors}, which this endpoint does not produce.
	 */
	private static Map<String, Object> failure(
			LegacyPhpArray.Entry entry, List<String> errors, Map<String, Object> row) {
		Map<String, Object> failure = new LinkedHashMap<>();
		// $index + 1 -- a TypeError for a string key, which nothing catches.
		failure.put("row_index", entry.indexPlusOne());
		failure.put("errors", errors);
		failure.put("error_messages", LegacyEmployeeSpreadsheetErrors.messages(errors, row));
		failure.put("data", entry.value());
		return failure;
	}

	/**
	 * {@code employee_excel_row_to_payload(array $row, ...)} is typed, so a row
	 * that is not an array is a {@code TypeError} under {@code strict_types=1}
	 * rather than a row failure -- and that reaches the client as D-084's
	 * deterministic 500.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> rowOf(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> row = new LinkedHashMap<>();
			map.forEach((key, cell) -> row.put(String.valueOf(key), cell));
			return row;
		}
		throw new IllegalStateException(
				"employee_excel_row_to_payload(): Argument #1 ($row) must be of type array");
	}

}
