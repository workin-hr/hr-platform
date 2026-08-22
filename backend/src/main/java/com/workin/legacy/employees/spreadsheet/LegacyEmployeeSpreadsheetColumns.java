package com.workin.legacy.employees.spreadsheet;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

import com.workin.legacy.LegacyValues;

import tools.jackson.databind.ObjectMapper;

/**
 * The employee spreadsheet column table and its header normalization
 * ({@code hr-legacy/apis/helpers/employee_excel_helper.php:12-210}).
 *
 * <h2>Why the table is vendored rather than transcribed</h2>
 * <p>These 28 entries decide which uploaded spreadsheets are accepted and which
 * column each header lands in. A single mistyped Arabic character, a dropped
 * alias or a reordered list would silently change that, and a hand-copied table
 * has no oracle to catch it. So the table is not hand-copied: it is
 * {@code json_encode(employee_excel_columns_meta())} taken from a real PHP 8.3
 * CLI and vendored at {@code legacy/spreadsheet/employee_columns.json}, the same
 * arrangement the lang catalogs use.
 * {@code scripts/check_legacy_spreadsheet_columns_drift.py} re-runs the export
 * and fails if the vendored copy drifts from the PHP.
 *
 * <p>Order matters twice over: the columns are the template's column order, and
 * {@link #normalizeHeaderKey(String)} returns the <em>first</em> column whose
 * label or alias matches, so moving an entry moves the collisions with it.
 */
public final class LegacyEmployeeSpreadsheetColumns {

	/** {@code $allowed} in {@code employee_excel_is_salary_group_row()}. */
	private static final List<String> GROUP_ROW_LABELS =
			List.of("استحقاقات", "استقطاعات", "entitlements", "deductions");

	private static final String ENTITLEMENTS = "استحقاقات";

	private static final String DEDUCTIONS = "استقطاعات";

	private static final List<Column> COLUMNS = load();

	/**
	 * One row of {@code employee_excel_columns_meta()}. {@code groupAr} and
	 * {@code groupEn} are null for the columns outside the two salary blocks,
	 * which is how PHP stores them -- the key is absent, and every read is
	 * {@code (string) ($col['group_ar'] ?? '')}.
	 */
	public record Column(
			String key,
			boolean required,
			String labelAr,
			String labelEn,
			String groupAr,
			String groupEn,
			List<String> aliases) {

		public Column {
			aliases = List.copyOf(aliases);
		}

		/** {@code (string) ($col['group_ar'] ?? '')}. */
		public String groupArOrEmpty() {
			return groupAr == null ? "" : groupAr;
		}

	}

	private LegacyEmployeeSpreadsheetColumns() {
	}

	/** The 28 columns, in template order. */
	public static List<Column> columns() {
		return COLUMNS;
	}

	@SuppressWarnings("unchecked")
	private static List<Column> load() {
		ClassPathResource resource = new ClassPathResource("legacy/spreadsheet/employee_columns.json");
		List<Map<String, Object>> raw;
		try (InputStream stream = resource.getInputStream()) {
			raw = new ObjectMapper().readValue(stream, List.class);
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot read the vendored employee column metadata", ex);
		}
		List<Column> columns = new ArrayList<>(raw.size());
		for (Map<String, Object> entry : raw) {
			columns.add(new Column(
					(String) entry.get("key"),
					Boolean.TRUE.equals(entry.get("required")),
					(String) entry.get("label_ar"),
					(String) entry.get("label_en"),
					(String) entry.get("group_ar"),
					(String) entry.get("group_en"),
					(List<String>) entry.getOrDefault("aliases", List.of())));
		}
		return List.copyOf(columns);
	}

	// ------------------------------------------------------------------
	// employee_excel_normalize_header_key()
	// ------------------------------------------------------------------

	/**
	 * {@code employee_excel_normalize_header_key()}: fold the header, then return
	 * the first column whose key, Arabic label, English label or alias it
	 * matches -- either exactly, or as a prefix ending in {@code _}.
	 *
	 * <p>That prefix rule is why the English label of
	 * {@code is_mobile_attendance_enabled} resolves to {@code phone}: folded it
	 * becomes {@code mobile_attendance}, and {@code phone} carries the alias
	 * {@code mobile}, which it reaches first. Preserved deliberately -- an upload
	 * whose header row says {@code Mobile attendance} today writes into the phone
	 * column, and D-085 is explicit that this collision is not fixed here.
	 * (The underscored key {@code is_mobile_attendance_enabled} is unaffected: it
	 * starts with {@code is_}, so it matches its own column.)
	 *
	 * <p>An unmatched header returns its folded form, not null -- that is how the
	 * caller later reports it as an unknown column.
	 */
	public static String normalizeHeaderKey(String header) {
		String key = trimUnderscores(fold(header));

		for (Column column : COLUMNS) {
			// PHP's second arm, `rtrim($col['key'] . ($col['required'] ? '' : ''), '_')`,
			// concatenates the empty string either way, so it is the first test again.
			if (key.equals(column.key())) {
				return column.key();
			}
			List<String> candidates = new ArrayList<>();
			candidates.add(column.labelAr() == null ? "" : column.labelAr());
			candidates.add(column.labelEn() == null ? "" : column.labelEn());
			candidates.addAll(column.aliases());
			for (String alias : candidates) {
				String aliasKey = trimUnderscores(fold(alias));
				if (aliasKey.isEmpty()) {
					continue;
				}
				if (key.equals(aliasKey) || key.startsWith(aliasKey + "_")) {
					return column.key();
				}
			}
		}
		return key;
	}

	/** {@code rtrim($key, '_')}. */
	private static String trimUnderscores(String value) {
		int end = value.length();
		while (end > 0 && value.charAt(end - 1) == '_') {
			end--;
		}
		return value.substring(0, end);
	}

	// Every pattern below carries PHP's /u modifier, which sets PCRE2_UTF and
	// PCRE2_UCP -- so \s there is Unicode whitespace, not ASCII whitespace.
	// UNICODE_CHARACTER_CLASS is Java's equivalent switch.
	private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;

	private static final Pattern ARABIC_EXAMPLE_SUFFIX = Pattern.compile("[_\\s]+مثال\\s*:.*$", UNICODE);

	private static final Pattern ENGLISH_EXAMPLE_SUFFIX = Pattern.compile(
			"[_\\s]+example\\s*:.*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | UNICODE);

	private static final Pattern DASH_NOTE = Pattern.compile("\\s*[—–].*$", UNICODE);

	private static final Pattern ARABIC_OPTIONAL = Pattern.compile("\\s*\\(اختياري\\)\\s*", UNICODE);

	private static final Pattern ARABIC_REQUIRED = Pattern.compile("\\s*\\(اجباري\\)\\s*", UNICODE);

	private static final Pattern ENGLISH_OPTIONAL = Pattern.compile(
			"\\s*\\(optional[^)]*\\)\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | UNICODE);

	private static final Pattern ENGLISH_REQUIRED = Pattern.compile(
			"\\s*\\(required[^)]*\\)\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | UNICODE);

	private static final Pattern ASTERISKS = Pattern.compile("\\*+", UNICODE);

	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", UNICODE);

	/**
	 * The {@code $fold} closure, statement for statement: keep the title line of
	 * a multi-line header, drop a trailing example, turn separators into spaces,
	 * strip the required/optional markers, lower-case, then fold the Arabic
	 * letter variants that the same word is written with in practice.
	 */
	private static String fold(String header) {
		String value = LegacyValues.phpTrim(header == null ? "" : header);
		value = value.replace("\r\n", "\n").replace("\r", "\n");
		int newline = value.indexOf('\n');
		if (newline >= 0) {
			value = LegacyValues.phpTrim(value.substring(0, newline));
		}
		// The template's example line survives when a spreadsheet reader has
		// already flattened the newline into a space or an underscore.
		value = ARABIC_EXAMPLE_SUFFIX.matcher(value).replaceAll("");
		value = ENGLISH_EXAMPLE_SUFFIX.matcher(value).replaceAll("");
		value = value.replace('_', ' ').replace('-', ' ');
		value = DASH_NOTE.matcher(value).replaceAll("");
		value = ARABIC_OPTIONAL.matcher(value).replaceAll(" ");
		value = ARABIC_REQUIRED.matcher(value).replaceAll(" ");
		value = ENGLISH_OPTIONAL.matcher(value).replaceAll(" ");
		value = ENGLISH_REQUIRED.matcher(value).replaceAll(" ");
		value = ASTERISKS.matcher(value).replaceAll(" ");
		value = WHITESPACE_RUN.matcher(value).replaceAll(" ");
		value = LegacyValues.phpTrim(value);
		value = LegacyValues.mbStrToLower(value);
		value = value.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا');
		value = value.replace('ى', 'ي').replace('ة', 'ه');
		return WHITESPACE_RUN.matcher(value).replaceAll("_");
	}

	// ------------------------------------------------------------------
	// Template shape
	// ------------------------------------------------------------------

	/** The default examples, by column key, from {@code employee_excel_template_headers()}. */
	private static final Map<String, String> EXAMPLES = examples();

	private static Map<String, String> examples() {
		Map<String, String> examples = new LinkedHashMap<>();
		examples.put("employee_code", "1001");
		examples.put("first_name", "محمد");
		examples.put("last_name", "أحمد");
		examples.put("country_code", "+20");
		examples.put("phone", "01012345678");
		examples.put("password", "123456");
		examples.put("national_id", "29801011234567");
		examples.put("birth_date", "1990-01-15");
		examples.put("gender", "ذكر");
		examples.put("address", "القاهرة");
		examples.put("is_mobile_attendance_enabled", "نعم");
		examples.put("expected_daily_hours", "8");
		examples.put("contract_duration_years", "1");
		examples.put("salary_basic", "5000");
		examples.put("salary_transport", "200");
		examples.put("salary_food_allowance", "150");
		return Map.copyOf(examples);
	}

	/**
	 * {@code employee_excel_template_headers()}: the Arabic label, plus
	 * {@code "\nمثال: "} and an example where the column has one.
	 *
	 * <p>The four org examples come from the company's own lookups and fall back
	 * to legacy's placeholders when a lookup is empty; {@code hire_date} is
	 * always the current date, which is why it arrives as a parameter rather
	 * than being read from the system clock here.
	 */
	public static List<String> templateHeaders(
			String shiftName, String branchName, String departmentName, String jobTitleName, String today) {
		Map<String, String> withLookups = new LinkedHashMap<>(EXAMPLES);
		withLookups.put("shift_name", orDefault(shiftName, "صباحي"));
		withLookups.put("branch_name", orDefault(branchName, "الفرع الرئيسي"));
		withLookups.put("department_name", orDefault(departmentName, "الموارد البشرية"));
		withLookups.put("job_title_name", orDefault(jobTitleName, "موظف"));
		withLookups.put("hire_date", today);

		List<String> headers = new ArrayList<>(COLUMNS.size());
		for (Column column : COLUMNS) {
			String label = column.labelAr();
			String example = LegacyValues.phpTrim(withLookups.getOrDefault(column.key(), ""));
			headers.add(example.isEmpty() ? label : label + "\nمثال: " + example);
		}
		return List.copyOf(headers);
	}

	/** {@code $shift = trim(...); $shift !== '' ? $shift : 'صباحي'}. */
	private static String orDefault(String candidate, String fallback) {
		String trimmed = LegacyValues.phpTrim(candidate == null ? "" : candidate);
		return trimmed.isEmpty() ? fallback : trimmed;
	}

	/**
	 * {@code employee_excel_template_group_row()}: the XLSX group row, where the
	 * label sits only on the first column of each block and the merge covers the
	 * rest.
	 */
	public static List<String> templateGroupRow() {
		List<String> row = new ArrayList<>(COLUMNS.size());
		for (Column column : COLUMNS) {
			boolean firstOfGroup = "salary_basic".equals(column.key())
					|| "salary_insurance_deduction".equals(column.key());
			row.add(firstOfGroup ? column.groupArOrEmpty() : "");
		}
		return List.copyOf(row);
	}

	/**
	 * The group row {@code template_excel.php}'s CSV branch builds instead
	 * ({@code template_excel.php:36-42}): a different row from the XLSX one,
	 * repeating each group label across every column of its block, because a CSV
	 * has no merges to carry the label across.
	 */
	public static List<String> csvGroupRow() {
		List<String> row = new ArrayList<>(COLUMNS.size());
		for (Column column : COLUMNS) {
			row.add(column.groupArOrEmpty());
		}
		return List.copyOf(row);
	}

	/**
	 * {@code employee_excel_template_group_merges()}: one merge per salary block
	 * across row 1, and only when the block is more than one column wide.
	 */
	public static List<String> templateGroupMerges() {
		int entitlementsStart = -1;
		int entitlementsEnd = -1;
		int deductionsStart = -1;
		int deductionsEnd = -1;
		for (int index = 0; index < COLUMNS.size(); index++) {
			String group = COLUMNS.get(index).groupArOrEmpty();
			if (ENTITLEMENTS.equals(group)) {
				entitlementsStart = entitlementsStart < 0 ? index : entitlementsStart;
				entitlementsEnd = index;
			} else if (DEDUCTIONS.equals(group)) {
				deductionsStart = deductionsStart < 0 ? index : deductionsStart;
				deductionsEnd = index;
			}
		}
		List<String> merges = new ArrayList<>(2);
		if (entitlementsStart >= 0 && entitlementsEnd > entitlementsStart) {
			merges.add(mergeRange(entitlementsStart, entitlementsEnd));
		}
		if (deductionsStart >= 0 && deductionsEnd > deductionsStart) {
			merges.add(mergeRange(deductionsStart, deductionsEnd));
		}
		return List.copyOf(merges);
	}

	private static String mergeRange(int start, int end) {
		return com.workin.legacy.spreadsheet.LegacyXlsxWriter.columnLetter(start) + "1:"
				+ com.workin.legacy.spreadsheet.LegacyXlsxWriter.columnLetter(end) + "1";
	}

	/**
	 * {@code employee_excel_template_cell_styles()}: yellow across the
	 * entitlements block and red across the deductions block, on both the group
	 * row and the header row.
	 */
	public static Map<Integer, Map<Integer, Integer>> templateCellStyles() {
		Map<Integer, Integer> byColumn = new LinkedHashMap<>();
		for (int index = 0; index < COLUMNS.size(); index++) {
			String group = COLUMNS.get(index).groupArOrEmpty();
			if (ENTITLEMENTS.equals(group)) {
				byColumn.put(index, com.workin.legacy.spreadsheet.LegacyXlsxWriter.STYLE_HEADER_YELLOW);
			} else if (DEDUCTIONS.equals(group)) {
				byColumn.put(index, com.workin.legacy.spreadsheet.LegacyXlsxWriter.STYLE_HEADER_RED);
			}
		}
		return Map.of(0, Map.copyOf(byColumn), 1, Map.copyOf(byColumn));
	}

	/**
	 * {@code employee_excel_is_salary_group_row()}: true when the row has at
	 * least one non-empty cell and every non-empty cell is a group label, so the
	 * template's own first row is skipped rather than read as data or as headers.
	 */
	public static boolean isSalaryGroupRow(List<String> cells) {
		boolean sawValue = false;
		for (String cell : cells) {
			String value = LegacyValues.phpTrim(cell == null ? "" : cell);
			if (value.isEmpty()) {
				continue;
			}
			sawValue = true;
			String folded = LegacyValues.mbStrToLower(value);
			boolean allowed = false;
			for (String label : GROUP_ROW_LABELS) {
				if (folded.equals(label) || folded.startsWith(label)) {
					allowed = true;
					break;
				}
			}
			if (!allowed) {
				return false;
			}
		}
		return sawValue;
	}

}
