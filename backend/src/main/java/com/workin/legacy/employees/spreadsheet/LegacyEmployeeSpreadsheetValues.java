package com.workin.legacy.employees.spreadsheet;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.spreadsheet.LegacyXlsxReader;

/**
 * The cell-level helpers {@code employee_excel_helper.php} reads a row with:
 * the boolean and gender vocabularies, the date normalization, the row-shape
 * predicates, and the display-row rewriting.
 *
 * <p>All pure, all keyed off exact membership rather than prefixes, and all
 * with expectations measured against a real PHP 8.3 CLI -- the vocabularies in
 * particular are closed lists, so {@code maybe} is neither true nor false and
 * {@code م} is not a gender.
 */
public final class LegacyEmployeeSpreadsheetValues {

	/** {@code ['1', 'true', 'yes', 'y', 'نعم', 'مفعل', 'active']}. */
	private static final List<String> TRUE_WORDS =
			List.of("1", "true", "yes", "y", "نعم", "مفعل", "active");

	/** {@code ['0', 'false', 'no', 'n', 'لا', 'غير مفعل', 'inactive']}. */
	private static final List<String> FALSE_WORDS =
			List.of("0", "false", "no", "n", "لا", "غير مفعل", "inactive");

	private static final List<String> MALE_WORDS = List.of("male", "m", "ذكر", "رجل");

	private static final List<String> FEMALE_WORDS =
			List.of("female", "f", "أنثى", "انثى", "امرأة", "امراة");

	private static final Pattern ISO_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");

	private static final Pattern FOUR_DIGITS = Pattern.compile("^\\d{4}$");

	private LegacyEmployeeSpreadsheetValues() {
	}

	/**
	 * {@code employee_excel_parse_bool()}: a closed vocabulary, and the caller's
	 * default for everything else -- including the empty string, which is how
	 * "missing" and "unrecognised" are told apart by passing {@code -1}.
	 */
	public static int parseBool(Object value, int fallback) {
		String raw = LegacyValues.phpTrim(asString(value));
		if (value == null || raw.isEmpty()) {
			return fallback;
		}
		// strtolower(), not mb_strtolower(): the vocabulary is ASCII or Arabic,
		// and Arabic has no case for either to change.
		String folded = raw.toLowerCase(java.util.Locale.ROOT);
		if (TRUE_WORDS.contains(folded)) {
			return 1;
		}
		if (FALSE_WORDS.contains(folded)) {
			return 0;
		}
		return fallback;
	}

	/** {@code employee_excel_parse_gender()}: {@code male}, {@code female}, or null. */
	public static String parseGender(Object value) {
		String raw = LegacyValues.phpTrim(asString(value));
		if (value == null || raw.isEmpty()) {
			return null;
		}
		String folded = LegacyValues.mbStrToLower(raw);
		if (MALE_WORDS.contains(folded)) {
			return "male";
		}
		if (FEMALE_WORDS.contains(folded)) {
			return "female";
		}
		return null;
	}

	/**
	 * {@code employee_excel_normalize_date_value()}, in its four steps: an ISO
	 * prefix is truncated to ten characters, a numeric value that is not a bare
	 * four-digit year is an Excel serial, {@code /} and {@code .} become
	 * {@code -} and the result goes through {@code strtotime()}, and anything
	 * that fails all of it comes back unchanged.
	 *
	 * <p>The serial window is {@code 1 <= serial < 100000}, so {@code 0} is not
	 * a date and neither is {@code 100000} -- both fall through to
	 * {@code strtotime()}, where the first fails and the second is read as the
	 * time 10:00:00.
	 */
	public static String normalizeDateValue(Object value, LocalDate today) {
		if (value == null) {
			return null;
		}
		String raw = LegacyValues.phpTrim(asString(value));
		if (raw.isEmpty()) {
			return null;
		}

		if (ISO_PREFIX.matcher(raw).find()) {
			return raw.substring(0, 10);
		}

		if (isNumeric(raw) && !FOUR_DIGITS.matcher(raw).matches()) {
			double serial = Double.parseDouble(raw);
			if (serial >= 1 && serial < 100_000) {
				String converted = LegacyXlsxReader.excelSerialToDateTime(serial);
				// substr($converted, 0, 10): a whole-day serial is already ten
				// characters, and a fractional one loses its time here.
				return converted.length() > 10 ? converted.substring(0, 10) : converted;
			}
		}

		String normalized = raw.replace('/', '-').replace('.', '-');
		LocalDate parsed = LegacyPhpStrtotime.dateOf(normalized, today);
		return parsed == null ? raw : parsed.toString();
	}

	/** {@code employee_excel_is_row_empty()}: every cell null or blank after trimming. */
	public static boolean isRowEmpty(Map<String, Object> row) {
		for (Object value : row.values()) {
			if (value != null && !LegacyValues.phpTrim(asString(value)).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@code employee_excel_is_example_row()}: a row whose code or name starts
	 * with {@code مثال} or {@code example} is the template's own sample.
	 */
	public static boolean isExampleRow(Map<String, Object> row) {
		for (String key : List.of("employee_code", "first_name", "last_name")) {
			String value = LegacyValues.phpTrim(asString(row.get(key)));
			if (value.isEmpty()) {
				continue;
			}
			if (value.startsWith("مثال")
					|| LegacyValues.mbStrToLower(value).startsWith("example")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code employee_excel_is_hint_row()}: the example check, then a
	 * per-column list of label fragments matched by {@code str_contains} -- a
	 * <em>substring</em> test, unlike the header normalization's exact
	 * membership, so a cell merely containing a column label is a hint row.
	 */
	public static boolean isHintRow(Map<String, Object> row) {
		if (isExampleRow(row)) {
			return true;
		}
		for (Map.Entry<String, List<String>> check : HINTS.entrySet()) {
			String value = LegacyValues.mbStrToLower(LegacyValues.phpTrim(asString(row.get(check.getKey()))));
			if (value.isEmpty()) {
				continue;
			}
			for (String needle : check.getValue()) {
				if (value.contains(LegacyValues.mbStrToLower(needle))) {
					return true;
				}
			}
		}
		return false;
	}

	/** {@code $checks} in {@code employee_excel_is_hint_row()}, in source order. */
	private static final Map<String, List<String>> HINTS = hints();

	private static Map<String, List<String>> hints() {
		Map<String, List<String>> checks = new LinkedHashMap<>();
		checks.put("first_name", List.of("الاسم الأول", "الاسم الاول", "first name", "first_name"));
		checks.put("last_name", List.of("الاسم الأخير", "الاسم الاخير", "last name", "last_name"));
		checks.put("employee_code", List.of("كود الموظف", "employee code", "employee_code"));
		checks.put("phone", List.of("رقم التلفون", "رقم التليفون", "رقم الهاتف",
				"phone (optional)", "phone (required)"));
		checks.put("country_code", List.of("كود الدولة", "country code", "country_code"));
		checks.put("shift_name", List.of("الوردية (اجباري)", "الوردية *", "shift (required)",
				"shift name", "shift_name"));
		checks.put("branch_name", List.of("الفرع (اجباري)", "الفرع (اختياري)", "branch (required)",
				"branch (optional)", "branch_name"));
		checks.put("department_name", List.of("القسم (اجباري)", "القسم (اختياري)",
				"department (required)", "department (optional)", "department_name"));
		checks.put("job_title_name", List.of("المسمى الوظيفي (اجباري)", "المسمى الوظيفي (اختياري)",
				"job title (required)", "job title (optional)", "job_title"));
		checks.put("salary_basic", List.of("الراتب الأساسي", "الراتب الاساسي", "basic salary", "salary_basic"));
		return Map.copyOf(checks);
	}

	/**
	 * {@code employee_excel_normalize_display_row()}: four cells are rewritten
	 * for display, and only when the key is present -- a row that never had a
	 * {@code gender} column does not gain one.
	 */
	public static Map<String, Object> normalizeDisplayRow(Map<String, Object> row, LocalDate today) {
		Map<String, Object> display = new LinkedHashMap<>(row);
		for (String key : List.of("is_mobile_attendance_enabled", "gender", "birth_date", "hire_date")) {
			if (display.containsKey(key)) {
				display.put(key, normalizeDisplayCell(key, display.get(key), today));
			}
		}
		return display;
	}

	/**
	 * {@code employee_excel_normalize_display_cell()}: null and blank pass
	 * through untouched, dates are normalized, and the boolean and gender
	 * vocabularies are rendered back as Arabic words. A value outside a
	 * vocabulary is returned exactly as it arrived.
	 */
	private static Object normalizeDisplayCell(String key, Object value, LocalDate today) {
		if (value == null) {
			return null;
		}
		String raw = LegacyValues.phpTrim(asString(value));
		if (raw.isEmpty()) {
			return value;
		}
		if ("birth_date".equals(key) || "hire_date".equals(key)) {
			String normalized = normalizeDateValue(value, today);
			return normalized == null ? value : normalized;
		}
		String folded = LegacyValues.mbStrToLower(raw);
		if ("is_mobile_attendance_enabled".equals(key)) {
			if (TRUE_WORDS.contains(folded)) {
				return "نعم";
			}
			if (FALSE_WORDS.contains(folded)) {
				return "لا";
			}
		}
		if ("gender".equals(key)) {
			if (MALE_WORDS.contains(folded)) {
				return "ذكر";
			}
			if (FEMALE_WORDS.contains(folded)) {
				return "أنثى";
			}
		}
		return value;
	}

	/**
	 * {@code spreadsheet_assoc_row()}: a short row is padded with nulls and a
	 * long one is truncated, both to the header's width. An empty header yields
	 * null, which the caller drops.
	 */
	public static Map<String, Object> assocRow(List<String> header, List<String> row) {
		if (header.isEmpty()) {
			return null;
		}
		Map<String, Object> combined = new LinkedHashMap<>();
		for (int index = 0; index < header.size(); index++) {
			combined.put(header.get(index), index < row.size() ? row.get(index) : null);
		}
		return combined;
	}

	/** {@code is_numeric()} for the shapes a spreadsheet cell can hold. */
	private static boolean isNumeric(String value) {
		try {
			Double.parseDouble(value);
			// PHP's is_numeric rejects hex and the Java-only suffixes.
			return !value.matches(".*[xXdDfF].*");
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	static String asString(Object value) {
		return value == null ? "" : LegacyValues.toPhpString(value);
	}

}
