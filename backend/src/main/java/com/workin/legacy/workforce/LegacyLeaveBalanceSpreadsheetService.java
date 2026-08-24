package com.workin.legacy.workforce;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.spreadsheet.LegacyCsvReader;
import com.workin.legacy.spreadsheet.LegacySpreadsheetFormat;
import com.workin.legacy.spreadsheet.LegacyXlsxReader;
import com.workin.legacy.spreadsheet.LegacyXlsxWriter;

/** {@code leave_balance_excel_helper.php}. */
@Service
public class LegacyLeaveBalanceSpreadsheetService {

	private static final List<Column> COLUMNS = List.of(
			new Column("employee_code", true,
					"كود الموظف (اجباري)\nأرقام فقط",
					"Employee code (required)\nDigits only",
					List.of("employee_code", "emp_code", "code", "كود_الموظف", "كود الموظف")),
			new Column("employee_name", false,
					"اسم الموظف (للعرض فقط)\nلا يتم تعديله عند الرفع",
					"Employee name (display only)\nIgnored on upload",
					List.of("employee_name", "name", "اسم_الموظف", "اسم الموظف", "الموظف")),
			new Column("year", true, "السنة (اجباري)", "Year (required)",
					List.of("year", "السنة", "سنه")),
			new Column("remaining_days", true,
					"الأيام المتبقية حتى نهاية السنة (اجباري)\nرقم عشري مسموح",
					"Remaining days until year end (required)\nDecimals allowed",
					List.of("remaining_days", "remaining", "balance", "leave_balance", "الأيام_المتبقية",
							"الايام المتبقية", "الأيام المتبقية", "الأيام المتبقية حتى نهاية السنة",
							"رصيد_متبقي", "رصيد متبقي")));

	private final LegacyLeaveBalanceStore store;
	private final LegacyClock clock;

	public LegacyLeaveBalanceSpreadsheetService(LegacyLeaveBalanceStore store, LegacyClock clock) {
		this.store = store;
		this.clock = clock;
	}

	public byte[] template(long companyId, int year, String locale) {
		boolean arabic = "ar".equals(locale);
		List<String> headers = COLUMNS.stream().map(c -> arabic ? c.labelAr() : c.labelEn()).toList();
		List<List<String>> data = new ArrayList<>();
		for (Map<String, Object> employee : store.templateEmployees(companyId, year)) {
			Object remaining = employee.get("remaining_days");
			data.add(List.of(
					text(employee.get("employee_code")),
					text(employee.get("employee_name")),
					String.valueOf(year),
					remaining == null ? "" : text(remaining)));
		}
		return LegacyXlsxWriter.build(
				headers, data, arabic ? "رصيد الإجازات" : "Leave balances",
				List.of(), List.of(), 1, Map.of(), arabic);
	}

	public Map<String, Object> analyze(byte[] bytes, long companyId, int requestedYear, String locale) {
		int year = requestedYear < 2000 || requestedYear > 2100 ? clock.today().getYear() : requestedYear;
		List<Map<String, Object>> rawRows = loadRows(bytes);
		List<Map<String, Object>> output = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		int valid = 0;
		int invalid = 0;
		for (int index = 0; index < rawRows.size(); index++) {
			Map<String, Object> raw = rawRows.get(index);
			Parsed parsed = parse(raw, companyId, year);
			List<String> errors = new ArrayList<>(parsed.errors());
			String code = normalizeEmployeeCode(text(raw.get("employee_code")));
			int rowYear = parsed.payload() != null
					? ((Number) parsed.payload().get("year")).intValue()
					: phpInt(raw.get("year"), year);
			String duplicateKey = code + "|" + rowYear;
			if (!code.isEmpty() && !seen.add(duplicateKey)) {
				errors.add("employee_code_duplicate_in_file");
			}
			boolean ok = errors.isEmpty();
			if (ok) {
				valid++;
			} else {
				invalid++;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("row_index", index + 1);
			row.put("status", ok ? "valid" : "invalid");
			row.put("errors", errors);
			row.put("error_messages", errors.stream().map(error -> errorMessage(error, locale)).toList());
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("employee_code", code);
			data.put("employee_name", LegacyValues.phpTrim(text(raw.get("employee_name"))));
			data.put("year", rowYear);
			data.put("remaining_days", raw.getOrDefault("remaining_days", ""));
			row.put("data", data);
			row.put("payload", parsed.payload());
			output.add(row);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("columns", COLUMNS.stream().map(Column::wire).toList());
		result.put("rows", output);
		result.put("summary", Map.of("total", output.size(), "valid", valid, "invalid", invalid));
		result.put("year", year);
		return result;
	}

	public Map<String, Object> importRows(long companyId, Object rawRows, String locale) {
		Collection<?> rows = LegacyValues.phpArrayValues(rawRows);
		int inserted = 0;
		int updated = 0;
		List<Map<String, Object>> failed = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		int index = 0;
		for (Object raw : rows) {
			index++;
			if (!(raw instanceof Map<?, ?> source)) {
				continue;
			}
			Map<String, Object> row = stringMap(source);
			Object nested = row.get("payload");
			Map<String, Object> payload = nested instanceof Map<?, ?> map ? stringMap(map) : row;
			long employeeId = LegacyValues.toPhpLong(payload.get("employee_id"));
			int year = (int) LegacyValues.toPhpLong(payload.get("year"));
			double remaining = payload.containsKey("remaining_days")
					? LegacyValues.toPhpDecimal(payload.get("remaining_days")).doubleValue() : -1.0d;
			if (employeeId <= 0 || year < 2000 || remaining < 0) {
				failed.add(failure(index, "invalid_payload", payload, locale));
				continue;
			}
			String duplicateKey = employeeId + "|" + year;
			if (!seen.add(duplicateKey)) {
				failed.add(failure(index, "employee_code_duplicate_in_file", payload, locale));
				continue;
			}
			if (!store.employeeOwned(companyId, employeeId)) {
				failed.add(failure(index, "employee_not_found", payload, locale));
				continue;
			}
			Map<String, Object> existing = store.byEmployeeAndYear(employeeId, year);
			double used = existing == null ? 0.0d : decimal(existing.get("used_days"));
			BigDecimal total = BigDecimal.valueOf(remaining + used);
			if (existing == null) {
				store.insert(employeeId, year, total, BigDecimal.valueOf(used), 1, 12, null);
				inserted++;
			} else {
				LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
				fields.put("total_days", total);
				store.update(number(existing.get("id")), fields);
				updated++;
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("inserted", inserted);
		result.put("updated", updated);
		result.put("failed", failed);
		return result;
	}

	private List<Map<String, Object>> loadRows(byte[] bytes) {
		LegacySpreadsheetFormat format = LegacySpreadsheetFormat.detect(bytes);
		if (format == LegacySpreadsheetFormat.EMPTY || format == LegacySpreadsheetFormat.UNKNOWN) {
			throw new IllegalArgumentException("Empty or unreadable file");
		}
		List<List<String>> matrix;
		if (format == LegacySpreadsheetFormat.XLSX) {
			matrix = LegacyXlsxReader.readFirstSheet(bytes);
		} else if (format == LegacySpreadsheetFormat.CSV) {
			matrix = LegacyCsvReader.read(bytes);
		} else {
			throw new IllegalArgumentException("Unsupported file type. Use .xlsx or .csv");
		}
		if (matrix.isEmpty()) {
			return List.of();
		}
		List<String> header = matrix.getFirst();
		List<String> keys = header.stream().map(this::normalizeHeader).toList();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int rowIndex = 1; rowIndex < matrix.size(); rowIndex++) {
			List<String> source = matrix.get(rowIndex);
			Map<String, Object> row = new LinkedHashMap<>();
			boolean any = false;
			for (int column = 0; column < keys.size(); column++) {
				String value = column < source.size() ? source.get(column) : null;
				row.put(keys.get(column), value);
				if (value != null && !LegacyValues.phpTrim(value).isEmpty()) {
					any = true;
				}
			}
			if (any) {
				rows.add(row);
			}
		}
		return rows;
	}

	private Parsed parse(Map<String, Object> row, long companyId, int defaultYear) {
		List<String> errors = new ArrayList<>();
		String code = normalizeEmployeeCode(text(row.get("employee_code")));
		if (code.isEmpty()) {
			errors.add("employee_code_required");
		}
		String yearRaw = LegacyValues.phpTrim(text(row.get("year")));
		int year = yearRaw.isEmpty() ? defaultYear : (int) LegacyValues.toPhpLong(yearRaw);
		if (year < 2000 || year > 2100) {
			errors.add("year_invalid");
		}
		String remainingRaw = LegacyValues.phpTrim(text(row.get("remaining_days")));
		Double remaining = null;
		if (remainingRaw.isEmpty()) {
			errors.add("remaining_days_required");
		} else if (!isNumeric(remainingRaw)) {
			errors.add("remaining_days_invalid");
		} else {
			remaining = Double.parseDouble(remainingRaw);
			if (remaining < 0) {
				errors.add("remaining_days_negative");
			}
		}
		Long employeeId = code.isEmpty() ? null : store.employeeIdByCode(companyId, code);
		if (!code.isEmpty() && employeeId == null) {
			errors.add("employee_not_found");
		}
		if (!errors.isEmpty()) {
			return new Parsed(errors, null);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("employee_id", employeeId);
		payload.put("employee_code", code);
		payload.put("employee_name", LegacyValues.phpTrim(text(row.get("employee_name"))));
		payload.put("year", year);
		payload.put("remaining_days", remaining);
		return new Parsed(List.of(), payload);
	}

	private String normalizeHeader(String header) {
		String key = foldHeader(header);
		for (Column column : COLUMNS) {
			if (key.equals(column.key())) {
				return column.key();
			}
			List<String> aliases = new ArrayList<>();
			aliases.add(column.labelAr());
			aliases.add(column.labelEn());
			aliases.addAll(column.aliases());
			for (String alias : aliases) {
				String aliasKey = foldHeader(alias);
				if (!aliasKey.isEmpty() && (key.equals(aliasKey) || key.startsWith(aliasKey + "_"))) {
					return column.key();
				}
			}
		}
		return key;
	}

	/** Literal Java port of {@code leave_balance_excel_normalize_header_key()}'s fold closure. */
	private static String foldHeader(String raw) {
		String value = LegacyValues.phpTrim(raw == null ? "" : raw)
				.replace("\r\n", "\n").replace('\r', '\n');
		int newline = value.indexOf('\n');
		if (newline >= 0) {
			value = LegacyValues.phpTrim(value.substring(0, newline));
		}
		value = value
				.replaceFirst("(?iu)[_\\s]+مثال\\s*:.*$", "")
				.replaceFirst("(?iu)[_\\s]+example\\s*:.*$", "")
				.replace('_', ' ').replace('-', ' ')
				.replaceFirst("(?u)\\s*[—–].*$", "")
				.replaceAll("(?iu)\\s*\\(اختياري\\)\\s*", " ")
				.replaceAll("(?iu)\\s*\\(اجباري\\)\\s*", " ")
				.replaceAll("(?iu)\\s*\\(optional[^)]*\\)\\s*", " ")
				.replaceAll("(?iu)\\s*\\(required[^)]*\\)\\s*", " ")
				.replaceAll("\\*+", " ")
				.replaceAll("(?u)\\s+", " ");
		value = LegacyValues.phpTrim(value).toLowerCase(Locale.ROOT)
				.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
				.replace('ى', 'ي').replace('ة', 'ه')
				.replaceAll("(?u)\\s+", "_");
		return value.replaceFirst("_+$", "");
	}

	private static String normalizeEmployeeCode(String raw) {
		return LegacyValues.phpTrim(raw);
	}

	private static Map<String, Object> failure(int rowIndex, String error, Map<String, Object> data, String locale) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("row_index", rowIndex);
		result.put("errors", List.of(error));
		result.put("error_messages", List.of(errorMessage(error, locale)));
		result.put("data", data);
		return result;
	}

	private static String errorMessage(String error, String locale) {
		boolean arabic = "ar".equals(locale);
		return switch (error) {
			case "employee_code_required" -> arabic ? "كود الموظف مطلوب" : "Employee code is required";
			case "employee_not_found" -> arabic ? "الموظف غير موجود" : "Employee not found";
			case "year_invalid" -> arabic ? "السنة غير صحيحة" : "Invalid year";
			case "remaining_days_required" -> arabic ? "الأيام المتبقية مطلوبة" : "Remaining days are required";
			case "remaining_days_invalid" -> arabic ? "الأيام المتبقية غير صحيحة" : "Invalid remaining days";
			case "remaining_days_negative" -> arabic ? "الأيام المتبقية لا يمكن أن تكون سالبة" : "Remaining days cannot be negative";
			case "employee_code_duplicate_in_file" -> arabic ? "كود مكرر في الملف" : "Duplicate employee code in file";
			case "invalid_payload" -> arabic ? "بيانات الصف غير صالحة" : "Invalid row payload";
			default -> error;
		};
	}

	private static Map<String, Object> stringMap(Map<?, ?> source) {
		Map<String, Object> result = new LinkedHashMap<>();
		source.forEach((key, value) -> result.put(String.valueOf(key), value));
		return result;
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static long number(Object value) {
		return LegacyValues.toPhpLong(value);
	}

	private static double decimal(Object value) {
		return LegacyValues.toPhpDecimal(value).doubleValue();
	}

	private static int phpInt(Object raw, int fallback) {
		String value = LegacyValues.phpTrim(text(raw));
		return value.isEmpty() ? fallback : (int) LegacyValues.toPhpLong(value);
	}

	private static boolean isNumeric(String value) {
		try {
			new BigDecimal(value);
			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	private record Parsed(List<String> errors, Map<String, Object> payload) {
	}

	private record Column(String key, boolean required, String labelAr, String labelEn, List<String> aliases) {
		Map<String, Object> wire() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("key", key);
			result.put("required", required);
			result.put("label_ar", labelAr);
			result.put("label_en", labelEn);
			return result;
		}
	}
}
