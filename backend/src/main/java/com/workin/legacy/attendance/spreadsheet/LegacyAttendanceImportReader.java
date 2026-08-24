package com.workin.legacy.attendance.spreadsheet;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.spreadsheet.LegacyCsvReader;
import com.workin.legacy.spreadsheet.LegacySpreadsheetFormat;
import com.workin.legacy.spreadsheet.LegacySpreadsheetRows;
import com.workin.legacy.spreadsheet.LegacyXlsxReader;

/**
 * The reading half of {@code attendance_excel_analyzer.php} that
 * {@code import_excel.php} reaches: {@code attendance_import_load_rows()},
 * {@code attendance_import_detect_format()},
 * {@code attendance_import_resolve_punch_columns()},
 * {@code attendance_import_guess_punch_column_order()},
 * {@code attendance_import_extract_punches()} and
 * {@code attendance_import_group_punches()}.
 *
 * <h2>D-085 is not applied here</h2>
 * <p>{@code attendance_import_load_rows()} carries the same two defects D-085
 * corrected in {@code employee_excel_load_rows()} -- the inverted BOM handling
 * and the "an XLSX that parsed to no rows must be a broken XLSX" fallback --
 * and both are <b>reproduced</b>, because D-085 is scoped by its own text to
 * the employee reader and ADR-0011 requires a divergence to carry a decision.
 * Concretely: a CSV with no BOM has its first three bytes consumed, and a CSV
 * with one keeps it. For the punch-log format that is nearly invisible, because
 * a two-column punch sheet is detected by column <em>count</em> and its header
 * names are then ignored entirely; for the template format it can change which
 * column {@code detectCol()} finds. Reported rather than corrected.
 *
 * <h2>Which formats are actually readable</h2>
 * <p>All three. CSV and XLSX are read here; {@code .xls} is read by
 * {@link LegacySimpleXlsReader}, which is D-097's bounded Apache POI HSSF
 * adapter for the Excel 97-2003 surface {@code parse_legacy_xls_spreadsheet()}
 * documents. Pre-97 BIFF is the one workbook shape legacy can sometimes read
 * and this cannot; that is D-097's accepted divergence, not a gap.
 *
 * <h2>The {@code .xls} branch is not the other two</h2>
 * <p>It is a separate early return in {@code attendance_import_load_rows()},
 * and it differs from the shared path in three ways that all matter. Its rows
 * come from the wrapper's own header handling, which drops any row with fewer
 * than two non-blank cells -- the XLSX path has no such filter. It has no
 * {@code 'empty'} short-circuit, so a workbook with no rows still reports a
 * format. And its {@code unknown -> punch_log} fallback is
 * <b>unconditional</b>, where the shared path only applies it to a sheet with
 * exactly two non-blank columns. A three-column {@code .xls} therefore arrives
 * as {@code punch_log} and fails on the two-column check, while the same three
 * columns as CSV are {@code unknown} and fail as an unsupported format.
 */
public final class LegacyAttendanceImportReader {

	/** {@code preg_match('/^\d+$/', $code)}. */
	static final Pattern DIGITS = Pattern.compile("^\\d+$");

	private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

	// uuuu, not yyyy: year-of-era prints year 0000 as "0001" (era BCE year 1),
	// where PHP's explicit formats and the proleptic-year field both keep it 0.
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd");

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

	/** {@code attendance_import_employee_code_column_aliases()} ({@code xlsx_parser.php:395}). */
	static final List<String> EMPLOYEE_CODE_ALIASES = List.of(
			"employee_code", "emp_code", "employee_no", "emp_no", "code", "badge", "card_no",
			"enrollment_no", "كود_الموظف", "كود الموظف", "رقم_الموظف", "رقم الموظف");

	/**
	 * {@code attendance_punch_log_code_column_aliases()} -- the employee-code
	 * aliases plus the fingerprint-device ones. {@code رقم_البصمه} appears twice
	 * in the PHP list; the duplicate is harmless and kept so the two lists stay
	 * diffable against the source.
	 */
	static final List<String> PUNCH_LOG_CODE_ALIASES = concat(EMPLOYEE_CODE_ALIASES, List.of(
			"no", "no.", "fingerprint_no", "fingerprint_number", "fingerprint", "badge_no",
			"enroll_no", "رقم_البصمه", "رقم البصمه", "رقم_البصمة", "رقم البصمة", "رقم_البصمه",
			"بصمه", "بصمة"));

	/** {@code $colTemplateIn} in {@code attendance_import_detect_format()}. */
	static final List<String> TEMPLATE_IN_ALIASES =
			List.of("datetime_in", "date_time", "datetime", "punch_in", "punch_time");

	/** {@code $colTemplateInDate}. */
	static final List<String> TEMPLATE_IN_DATE_ALIASES = List.of(
			"check_in_date", "in_date", "attendance_date", "date_in", "date", "التاريخ",
			"تاريخ_الحضور", "تاريخ الحضور");

	/**
	 * {@code $colTemplateInTime} as {@code detect_format()} spells it -- shorter
	 * than the list {@code import_punch_log()} uses for the same concept, which
	 * also carries {@code وقت_الحضور} and {@code وقت الحضور}. The two lists
	 * genuinely differ in PHP and are kept apart here for that reason.
	 */
	static final List<String> DETECT_IN_TIME_ALIASES = List.of(
			"check_in_time", "in_time", "time_in", "check_in", "بصمة_الدخول", "بصمة الدخول", "دخول");

	private LegacyAttendanceImportReader() {
	}

	/** {@code ['format' => ..., 'rows' => ..., 'keys' => ...]}. */
	public record Loaded(String format, List<Map<String, Object>> rows, List<String> keys) {
	}

	/** One entry of {@code $punches}. */
	public record Punch(String sheetCode, String sheetName, LocalDateTime punchedAt, int rowNum) {
	}

	/** One entry of {@code $records}, in {@code group_punches()}' key order. */
	public record DayRecord(
			String sheetCode, String sheetName, String date, String checkIn, String checkOut,
			int punchCount, int actualMinutes, boolean complete) {
	}

	/**
	 * {@code attendance_import_load_rows()}.
	 *
	 * @throws LegacyAttendanceImportException with legacy's own messages --
	 *         {@code Empty or unreadable file} for a format neither branch
	 *         handles, {@code Cannot read file} when the handle cannot be
	 *         opened, and the {@code SimpleXLS} message shape for an OLE2 file
	 */
	public static Loaded loadRows(byte[] content) {
		LegacySpreadsheetFormat detected = LegacySpreadsheetFormat.detect(content);
		if (detected == LegacySpreadsheetFormat.EMPTY || detected == LegacySpreadsheetFormat.UNKNOWN) {
			throw new LegacyAttendanceImportException("Empty or unreadable file");
		}
		if (detected == LegacySpreadsheetFormat.XLS) {
			// The whole `if ($format === 'xls')` branch, which returns before
			// the CSV/XLSX path below ever runs.
			List<Map<String, Object>> rows = xlsAssoc(LegacySimpleXlsReader.readFirstSheet(content));
			List<String> keys = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
			String importFormat = detectFormat(keys);
			return new Loaded("unknown".equals(importFormat) ? "punch_log" : importFormat, rows, keys);
		}

		List<Map<String, Object>> rows = new ArrayList<>();
		boolean workbookParsed = false;
		if (detected == LegacySpreadsheetFormat.XLSX) {
			List<List<String>> matrix = readWorkbook(content);
			if (matrix == null) {
				// `catch (RuntimeException) { $format = 'csv'; }`.
				detected = LegacySpreadsheetFormat.CSV;
			} else {
				workbookParsed = true;
				rows.addAll(assoc(matrix));
			}
		}

		// `if ($format === 'csv' || ($format === 'xlsx' && empty($rows)))`. The
		// second half is the defect D-085 corrected for employees and left
		// standing here: a workbook that parses to no data rows is re-read as
		// CSV, over its own ZIP bytes.
		if (detected == LegacySpreadsheetFormat.CSV || (workbookParsed && rows.isEmpty())) {
			rows.clear();
			rows.addAll(readCsv(content));
		}

		if (rows.isEmpty()) {
			return new Loaded("empty", List.of(), List.of());
		}

		List<String> keys = List.copyOf(rows.get(0).keySet());
		String format = detectFormat(keys);
		if ("unknown".equals(format) && nonEmptyKeys(keys).size() == 2) {
			// Device exports are often exactly two columns with varying headers.
			format = "punch_log";
		}
		return new Loaded(format, rows, keys);
	}

	/** {@code attendance_import_detect_format()}. */
	public static String detectFormat(List<String> keys) {
		if (nonEmptyKeys(keys).size() == 2) {
			return "punch_log";
		}
		String colCode = detectCol(keys, PUNCH_LOG_CODE_ALIASES);
		String colTemplateIn = detectCol(keys, TEMPLATE_IN_ALIASES);
		String colTemplateInDate = detectCol(keys, TEMPLATE_IN_DATE_ALIASES);
		String colTemplateInTime = detectCol(keys, DETECT_IN_TIME_ALIASES);

		boolean hasIn = colTemplateIn != null
				|| (colTemplateInDate != null && colTemplateInTime != null)
				|| colTemplateInDate != null;
		return colCode != null && hasIn ? "template" : "unknown";
	}

	/**
	 * {@code detectCol()} ({@code xlsx_parser.php:671}): every alias is tried
	 * for an exact match first, and only then every alias for a substring
	 * match. So an earlier alias never wins a fuzzy match over a later alias'
	 * exact one.
	 */
	public static String detectCol(List<String> keys, List<String> aliases) {
		for (String alias : aliases) {
			for (String key : keys) {
				if (asciiLowerTrim(key).equals(alias)) {
					return key;
				}
			}
		}
		for (String alias : aliases) {
			for (String key : keys) {
				if (asciiLowerTrim(key).contains(alias)) {
					return key;
				}
			}
		}
		return null;
	}

	/**
	 * {@code attendance_import_resolve_punch_columns()}: the sheet must have
	 * exactly two non-blank columns, and the first 25 rows carrying a code must
	 * all hold digits and a parseable datetime.
	 */
	public static String[] resolvePunchColumns(
			List<String> keys, List<Map<String, Object>> rows, LocalDateTime now) {
		List<String> present = nonEmptyKeys(keys);
		if (present.size() != 2) {
			throw new LegacyAttendanceImportException("attendance_excel_must_have_two_columns");
		}

		// Note that guess_punch_column_order() is handed the *filtered* key
		// list, so its $keys[0] and $keys[1] are the trimmed labels -- which is
		// what the row lookups below then use as map keys too.
		String[] columns = guessPunchColumnOrder(present, rows, now);
		String colCode = columns[0];
		String colDateTime = columns[1];

		int checked = 0;
		for (Map<String, Object> row : rows) {
			String code = sheetCode(row.get(colCode));
			if (code.isEmpty()) {
				continue;
			}
			if (!DIGITS.matcher(code).matches()) {
				throw new LegacyAttendanceImportException("attendance_excel_code_must_be_digits");
			}
			if (LegacyAttendancePunchDateTimeParser.parse(row.get(colDateTime), now) == null) {
				throw new LegacyAttendanceImportException("attendance_excel_datetime_column_invalid");
			}
			checked++;
			if (checked >= 25) {
				break;
			}
		}
		if (checked == 0) {
			throw new LegacyAttendanceImportException("attendance_excel_no_valid_punch_rows");
		}
		return new String[] {colCode, colDateTime};
	}

	/**
	 * {@code attendance_import_guess_punch_column_order()}: up to 40 rows where
	 * both cells are non-blank decide whether the sheet is code-then-datetime
	 * (the default) or the reversed device export.
	 */
	public static String[] guessPunchColumnOrder(
			List<String> keys, List<Map<String, Object>> rows, LocalDateTime now) {
		String colA = keys.get(0);
		String colB = keys.get(1);

		int aAsCode = 0;
		int aAsDateTime = 0;
		int bAsCode = 0;
		int bAsDateTime = 0;
		int sampled = 0;

		for (Map<String, Object> row : rows) {
			if (sampled >= 40) {
				break;
			}
			Object rawA = row.get(colA);
			Object rawB = row.get(colB);
			// `=== null || === ''` -- a literal empty string, not PHP's empty().
			if (rawA == null || "".equals(rawA) || rawB == null || "".equals(rawB)) {
				continue;
			}
			sampled++;

			String codeA = sheetCode(rawA);
			if (!codeA.isEmpty() && DIGITS.matcher(codeA).matches()) {
				aAsCode++;
			}
			if (LegacyAttendancePunchDateTimeParser.parse(rawA, now) != null) {
				aAsDateTime++;
			}
			String codeB = sheetCode(rawB);
			if (!codeB.isEmpty() && DIGITS.matcher(codeB).matches()) {
				bAsCode++;
			}
			if (LegacyAttendancePunchDateTimeParser.parse(rawB, now) != null) {
				bAsDateTime++;
			}
		}

		int codeThenDateTime = Math.min(aAsCode, bAsDateTime);
		int dateTimeThenCode = Math.min(aAsDateTime, bAsCode);
		// Strictly greater, so a tie keeps the preferred contract.
		return dateTimeThenCode > codeThenDateTime
				? new String[] {colB, colA}
				: new String[] {colA, colB};
	}

	/** {@code attendance_import_extract_punches()}. */
	public static List<Punch> extractPunches(
			List<Map<String, Object>> rows, List<String> keys, LocalDateTime now) {
		String[] columns = resolvePunchColumns(keys, rows, now);
		String colCode = columns[0];
		String colDateTime = columns[1];

		List<Punch> punches = new ArrayList<>();
		for (int index = 0; index < rows.size(); index++) {
			Map<String, Object> row = rows.get(index);
			String code = sheetCode(row.get(colCode));
			if (code.isEmpty() || !DIGITS.matcher(code).matches()) {
				continue;
			}
			LocalDateTime punchedAt = LegacyAttendancePunchDateTimeParser.parse(row.get(colDateTime), now);
			if (punchedAt == null) {
				continue;
			}
			// `sheet_name` is always '' on this path: the punch-log sheet has
			// two columns and neither is a name.
			punches.add(new Punch(code, "", punchedAt, index + 2));
		}
		// usort() is stable in PHP 8, so equal instants keep sheet order.
		punches.sort(Comparator.comparing(Punch::punchedAt));
		return punches;
	}

	/**
	 * {@code attendance_import_group_punches()}: first punch of a day is the
	 * check-in, last is the check-out, and a day with a single punch has no
	 * check-out at all.
	 *
	 * <h2>The final ordering is PHP's, comparison semantics included</h2>
	 * <p>{@code [$a['sheet_code'], $a['date']] <=> [$b[...], ...]} compares the
	 * two arrays element by element with PHP 8's {@code <=>}, and two numeric
	 * strings compare <b>numerically</b> there -- so employee code {@code 9}
	 * sorts before {@code 10}, not after it. That order decides the
	 * {@code Day N} number in every error message the response carries, so it
	 * is reproduced rather than replaced with a lexicographic sort.
	 */
	public static List<DayRecord> groupPunches(List<Punch> punches) {
		Map<String, List<Punch>> byEmployeeDay = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		Map<String, String> dates = new LinkedHashMap<>();
		Map<String, String> codes = new LinkedHashMap<>();

		for (Punch punch : punches) {
			String date = punch.punchedAt().format(DATE);
			String key = punch.sheetCode() + "|" + date;
			byEmployeeDay.computeIfAbsent(key, ignored -> new ArrayList<>()).add(punch);
			codes.putIfAbsent(key, punch.sheetCode());
			dates.putIfAbsent(key, date);
			names.putIfAbsent(key, punch.sheetName());
			if (names.get(key).isEmpty() && !punch.sheetName().isEmpty()) {
				names.put(key, punch.sheetName());
			}
		}

		List<DayRecord> records = new ArrayList<>();
		for (Map.Entry<String, List<Punch>> group : byEmployeeDay.entrySet()) {
			List<LocalDateTime> times = new ArrayList<>();
			for (Punch punch : group.getValue()) {
				times.add(punch.punchedAt());
			}
			times.sort(Comparator.naturalOrder());
			LocalDateTime checkIn = times.isEmpty() ? null : times.get(0);
			LocalDateTime checkOut = times.size() > 1 ? times.get(times.size() - 1) : null;
			boolean complete = checkIn != null && checkOut != null && checkOut.isAfter(checkIn);
			int actualMinutes = 0;
			if (complete) {
				actualMinutes = (int) Math.round(
						java.time.Duration.between(checkIn, checkOut).getSeconds() / 60d);
			}
			records.add(new DayRecord(
					codes.get(group.getKey()),
					names.get(group.getKey()),
					dates.get(group.getKey()),
					checkIn == null ? null : checkIn.format(DATE_TIME),
					complete ? checkOut.format(DATE_TIME) : null,
					times.size(),
					actualMinutes,
					complete));
		}

		records.sort((left, right) -> {
			int byCode = phpCompare(left.sheetCode(), right.sheetCode());
			return byCode != 0 ? byCode : phpCompare(left.date(), right.date());
		});
		return records;
	}

	/** {@code attendance_import_sheet_code()} -> {@code normalize_employee_code()}. */
	public static String sheetCode(Object code) {
		String value = LegacyValues.phpTrim(code == null ? "" : LegacyValues.toPhpString(code));
		if (value.isEmpty()) {
			return "";
		}
		// preg_replace('/\s+/u', ' ', $code): the /u modifier makes \s match
		// Unicode whitespace (a non-breaking space included), hence (?U).
		return value.replaceAll("(?U)\\s+", " ");
	}

	/**
	 * PHP 8's {@code <=>} for two strings: numeric when <b>both</b> are numeric
	 * strings, byte-wise otherwise.
	 */
	static int phpCompare(String left, String right) {
		String leftTrimmed = LegacyValues.phpTrim(left);
		String rightTrimmed = LegacyValues.phpTrim(right);
		if (isIntegerString(leftTrimmed) && isIntegerString(rightTrimmed)) {
			return toBigInteger(leftTrimmed).compareTo(toBigInteger(rightTrimmed));
		}
		Double leftNumber = numeric(left);
		Double rightNumber = numeric(right);
		if (leftNumber != null && rightNumber != null) {
			return Double.compare(leftNumber, rightNumber);
		}
		return left.compareTo(right);
	}

	/** No decimal point, no exponent -- the case a {@code double} cannot compare exactly past 2^53. */
	private static boolean isIntegerString(String trimmed) {
		return trimmed.matches("^[+-]?\\d+$");
	}

	private static BigInteger toBigInteger(String trimmed) {
		return new BigInteger(trimmed.startsWith("+") ? trimmed.substring(1) : trimmed);
	}

	private static Double numeric(String value) {
		String trimmed = LegacyValues.phpTrim(value);
		if (!trimmed.matches("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$")) {
			return null;
		}
		try {
			return Double.valueOf(trimmed);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * {@code array_values(array_filter(array_map('trim', $keys), fn => $k !== ''))}
	 * -- the count that decides "exactly two columns".
	 */
	static List<String> nonEmptyKeys(List<String> keys) {
		List<String> present = new ArrayList<>();
		for (String key : keys) {
			String trimmed = LegacyValues.phpTrim(key == null ? "" : key);
			if (!trimmed.isEmpty()) {
				present.add(trimmed);
			}
		}
		return present;
	}

	/** {@code strtolower(trim((string) $key))} -- ASCII lower-casing only, as PHP's is. */
	private static String asciiLowerTrim(String key) {
		String trimmed = LegacyValues.phpTrim(key == null ? "" : key);
		StringBuilder out = new StringBuilder(trimmed.length());
		for (int index = 0; index < trimmed.length(); index++) {
			char character = trimmed.charAt(index);
			out.append(character >= 'A' && character <= 'Z' ? (char) (character + 32) : character);
		}
		return out.toString();
	}

	/** The XLSX branch's header handling: {@code array_shift}, normalize, combine. */
	private static List<Map<String, Object>> assoc(List<List<String>> matrix) {
		List<Map<String, Object>> rows = new ArrayList<>();
		if (matrix.isEmpty()) {
			return rows;
		}
		List<String> header = LegacySpreadsheetRows.normalizeHeaderRow(matrix.get(0));
		for (int index = 1; index < matrix.size(); index++) {
			Map<String, Object> combined = LegacySpreadsheetRows.assocRow(header, matrix.get(index));
			if (combined != null) {
				rows.add(combined);
			}
		}
		return rows;
	}

	/**
	 * {@code parse_legacy_xls_spreadsheet()}'s own header handling, which is
	 * not {@link #assoc}.
	 *
	 * <p>The difference is the blank-row filter: a row reaching
	 * {@code spreadsheet_assoc_row()} must carry at least two cells that are
	 * neither null nor whitespace. {@code SimpleXLS} pads every row out to the
	 * sheet's column count, so without it a workbook whose {@code DIMENSION}
	 * claims more rows than were written would contribute a run of entirely
	 * empty records.
	 */
	private static List<Map<String, Object>> xlsAssoc(List<List<String>> grid) {
		List<Map<String, Object>> rows = new ArrayList<>();
		if (grid.isEmpty()) {
			return rows;
		}
		List<String> header = LegacySpreadsheetRows.normalizeHeaderRow(grid.get(0));
		for (int index = 1; index < grid.size(); index++) {
			List<String> record = grid.get(index);
			int filled = 0;
			for (String cell : record) {
				if (!LegacyValues.phpTrim(cell).isEmpty()) {
					filled++;
				}
			}
			if (filled < 2) {
				continue;
			}
			Map<String, Object> combined = LegacySpreadsheetRows.assocRow(header, record);
			if (combined != null) {
				rows.add(combined);
			}
		}
		return rows;
	}

	/**
	 * The CSV branch, byte positioning included.
	 *
	 * <p>The three {@code fread}/{@code rewind} calls are legacy's and they are
	 * inverted: with no BOM the stream ends up three bytes in, and with a BOM it
	 * ends up at zero with the BOM still attached. Both are reproduced. The
	 * delimiter is decided from the first physical line read <em>before</em>
	 * that final positioning, so it is unaffected either way.
	 */
	private static List<Map<String, Object>> readCsv(byte[] content) {
		boolean hasBom = content.length >= 3
				&& content[0] == BOM[0] && content[1] == BOM[1] && content[2] == BOM[2];

		// $firstLine = fgets($handle) from offset 3 with a BOM, 0 without.
		int lineStart = hasBom ? 3 : 0;
		int lineEnd = lineStart;
		while (lineEnd < content.length && content[lineEnd] != '\n') {
			lineEnd++;
		}
		if (lineEnd < content.length) {
			lineEnd++;
		}
		String firstLine = new String(content, lineStart,
				Math.max(0, lineEnd - lineStart), StandardCharsets.UTF_8);
		long commas = firstLine.chars().filter(character -> character == ',').count();
		long semicolons = firstLine.chars().filter(character -> character == ';').count();
		char delimiter = commas >= semicolons ? ',' : ';';

		// rewind(); if (!$hasBom) fread($handle, 3);
		int offset = hasBom ? 0 : Math.min(3, content.length);
		String text = new String(content, offset, content.length - offset, StandardCharsets.UTF_8);

		List<List<String>> records = LegacyCsvReader.parseRecords(text, delimiter);
		List<Map<String, Object>> rows = new ArrayList<>();
		if (records.isEmpty()) {
			return rows;
		}
		List<String> header = LegacySpreadsheetRows.normalizeHeaderRow(records.get(0));
		for (int index = 1; index < records.size(); index++) {
			List<String> record = records.get(index);
			// `if (count($csvRow) >= 2)` -- a blank line is one empty field and
			// is dropped before it can become a row.
			if (record.size() < 2) {
				continue;
			}
			Map<String, Object> combined = LegacySpreadsheetRows.assocRow(header, record);
			if (combined != null) {
				rows.add(combined);
			}
		}
		return rows;
	}

	/** The workbook as a matrix, or null when the parser threw -- legacy's only fallback trigger. */
	private static List<List<String>> readWorkbook(byte[] content) {
		try {
			return LegacyXlsxReader.readFirstSheet(content);
		} catch (LegacyXlsxReader.LegacyXlsxException ex) {
			return null;
		}
	}

	private static List<String> concat(List<String> first, List<String> second) {
		List<String> all = new ArrayList<>(first);
		all.addAll(second);
		return List.copyOf(all);
	}

}
