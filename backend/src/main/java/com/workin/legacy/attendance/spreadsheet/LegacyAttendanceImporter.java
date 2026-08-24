package com.workin.legacy.attendance.spreadsheet;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyValues;

/**
 * {@code attendance_excel_import_punch_log()}
 * ({@code attendance_excel_analyzer.php:977-1062}), its
 * {@code attendance_import_resolve_employee_id()} helper
 * ({@code :865-976}) and the {@code template} branch's
 * {@code import_fingerprint_attendance_rows()}
 * ({@code xlsx_parser.php:562-666}).
 *
 * <h2>Three shapes of result, one response</h2>
 * <p>All three paths return {@code [inserted, skipped, errors]} in that key
 * order, which becomes the response's {@code data} verbatim. They do not agree
 * on what {@code skipped} counts: the punch-log path returns
 * {@code $skipped + count($errors)} -- unmatched employees <em>plus</em> every
 * error -- while the template path and the unsupported-format path return
 * {@code count($errors)} and {@code 0}. Preserved as written.
 *
 * <h2>The error strings are the contract</h2>
 * <p>{@code import_excel.php} decides between two failure messages by searching
 * the <em>first</em> error string for {@code 'Cannot find employee'},
 * {@code 'Cannot find check-in'}, {@code 'Unsupported file'} or
 * {@code 'not found'}. So the wording below is load-bearing in two directions
 * at once: it is shown to the client, and it steers the endpoint. Note that
 * neither of the first two phrases is produced by any branch reachable from
 * this endpoint -- {@code 'not found'} and {@code 'Unsupported file'} are, and
 * they are the ones that fire.
 *
 * <h2>Everything here is inside the endpoint's transaction</h2>
 * <p>Including the employee writes a {@code mappings} entry can trigger. A
 * created employee, a synced {@code employee_code} and a shift assignment all
 * roll back with the attendance rows if the import throws.
 */
public class LegacyAttendanceImporter {

	/** {@code preg_match('/^(employee_code|كود[_ ]?الموظف)$/ui', $empCodeRaw)}. */
	private static final Pattern TEMPLATE_LABEL_ROW = Pattern.compile(
			"^(employee_code|كود[_ ]?الموظف)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	/** {@code UserRoleEnum::EMPLOYEE->value}. */
	private static final String EMPLOYEE_ROLE = "employee";

	private LegacyAttendanceImporter() {
	}

	/**
	 * The three keys, in PHP's own order, so the response body's {@code data}
	 * serializes identically.
	 */
	static Map<String, Object> result(long inserted, long skipped, List<String> errors) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("inserted", inserted);
		result.put("skipped", skipped);
		result.put("errors", errors);
		return result;
	}

	/**
	 * {@code attendance_excel_import_punch_log()} -- the whole helper, including
	 * its dispatch to the template importer and its unsupported-format answer.
	 *
	 * @param mappings the decoded {@code mappings} field, keyed by sheet code
	 * @param now legacy's current instant, for the punch parser's fallback and
	 *        for a created employee's default {@code hire_date}
	 */
	public static Map<String, Object> importPunchLog(
			byte[] content, long companyId, LegacyAttendanceImportStore store,
			Map<String, Object> mappings, LocalDateTime now, ZoneOffset offset) {
		LegacyAttendanceImportReader.Loaded loaded = LegacyAttendanceImportReader.loadRows(content);

		if (!"punch_log".equals(loaded.format())) {
			if ("template".equals(loaded.format())) {
				return importTemplate(loaded, companyId, store, now, offset);
			}
			// 'empty' and 'unknown' both land here. The endpoint turns this
			// into cannot_detect_csv_columns, because the string contains
			// 'Unsupported file'.
			return result(0, 0, List.of("Unsupported file format"));
		}

		List<LegacyAttendanceImportReader.Punch> punches =
				LegacyAttendanceImportReader.extractPunches(loaded.rows(), loaded.keys(), now);
		List<LegacyAttendanceImportReader.DayRecord> records =
				LegacyAttendanceImportReader.groupPunches(punches);

		long inserted = 0;
		long skipped = 0;
		List<String> errors = new ArrayList<>();

		for (int index = 0; index < records.size(); index++) {
			LegacyAttendanceImportReader.DayRecord record = records.get(index);
			// $rowNum = $i + 1 here, and $i + 2 in the template importer. The
			// two numbers mean different things and neither is the sheet row.
			int rowNum = index + 1;
			String code = LegacyAttendanceImportReader.sheetCode(record.sheetCode());
			if (code.isEmpty()) {
				continue;
			}
			if (record.checkIn() == null) {
				errors.add("Day " + rowNum + " (" + code + " " + record.date()
						+ "): missing check-in — skipped");
				continue;
			}

			Long employeeId = resolveEmployeeId(
					companyId, code, mappings, store, record.sheetName(), now);
			if (employeeId == null) {
				// Counted in `skipped`, and deliberately not reported as an
				// error -- which is why a file whose codes match nobody
				// produces zero errors and the endpoint's
				// attendance_excel_no_matched_employees branch.
				skipped++;
				continue;
			}

			if (store.hasAttendanceOnDay(employeeId, record.checkIn())) {
				errors.add("Day " + rowNum + ": duplicate for '" + code + "' on "
						+ record.date() + " — skipped");
				continue;
			}

			store.insertAttendance(employeeId, record.checkIn(), emptyToNull(record.checkOut()));
			inserted++;
		}

		// `$skipped + count($errors)` -- unmatched employees plus every error.
		return result(inserted, skipped + errors.size(), errors);
	}

	/**
	 * {@code attendance_import_resolve_employee_id()}.
	 *
	 * <p>The mapping is looked up by the <em>normalized</em> code first and by
	 * the raw sheet code second. Four recognised types, each with two accepted
	 * spellings; anything else -- an unknown type, a missing
	 * {@code employee_id}, a mapping that is not an object at all -- falls
	 * through to the plain lookup by code rather than failing.
	 */
	static Long resolveEmployeeId(
			long companyId, String sheetCode, Map<String, Object> mappings,
			LegacyAttendanceImportStore store, String sheetName, LocalDateTime now) {
		String normalized = LegacyAttendanceImportReader.sheetCode(sheetCode);
		Object candidate = mappings.get(normalized);
		if (candidate == null) {
			candidate = mappings.get(sheetCode);
		}

		if (candidate instanceof Map<?, ?> raw) {
			Map<String, Object> mapping = asMap(raw);
			Object typeValue = mapping.get("type");
			if (typeValue == null) {
				typeValue = mapping.get("action");
			}
			String type = LegacyValues.phpTrim(
					typeValue == null ? "" : LegacyValues.toPhpString(typeValue))
					.toLowerCase(java.util.Locale.ROOT);

			if ("link".equals(type) || "map_to_employee".equals(type) || "use_existing".equals(type)) {
				long employeeId = LegacyValues.toPhpLong(mapping.get("employee_id"));
				if (employeeId > 0) {
					// A foreign employee_id is a null resolution, so the day is
					// skipped -- it is never silently attributed to the caller's
					// own company.
					return store.employeeExistsInCompany(employeeId, companyId) ? employeeId : null;
				}
			}

			if ("sync_code".equals(type) || "update_employee_code".equals(type)) {
				long employeeId = LegacyValues.toPhpLong(mapping.get("employee_id"));
				if (employeeId > 0) {
					// Company-scoped, and the result is not checked: an id from
					// another company updates nothing and is still returned, so
					// the punches are then written against a foreign employee.
					// Legacy's behaviour, reproduced.
					store.updateEmployeeCode(normalized, employeeId, companyId);
					return employeeId;
				}
			}

			if ("create".equals(type) || "create_employee".equals(type)) {
				return createEmployee(companyId, normalized, mapping, store, sheetName, now);
			}
		}

		return store.employeeIdByCodeScoped(companyId, normalized);
	}

	/**
	 * The {@code create}/{@code create_employee} branch.
	 *
	 * <p>Guard order matters: the shift and the hours are validated before the
	 * code is checked for an existing employee, so a mapping with a bad shift
	 * resolves to null even when the employee already exists and would have been
	 * found.
	 */
	private static Long createEmployee(
			long companyId, String normalized, Map<String, Object> mapping,
			LegacyAttendanceImportStore store, String sheetName, LocalDateTime now) {
		long shiftId = LegacyValues.toPhpLong(mapping.get("shift_id"));
		double expectedHours = mapping.containsKey("expected_daily_hours")
				&& mapping.get("expected_daily_hours") != null
						? toPhpFloat(mapping.get("expected_daily_hours"))
						: 8d;
		if (shiftId <= 0 || expectedHours <= 0) {
			return null;
		}
		if (!store.shiftBelongsToCompany(shiftId, companyId)) {
			return null;
		}
		if (store.employeeCodeExistsInCompany(companyId, normalized)) {
			return store.employeeIdByCodeScoped(companyId, normalized);
		}

		String[] nameParts = splitSheetName(sheetName);
		String first = LegacyValues.phpTrim(
				string(mapping, "first_name", nameParts[0]));
		String last = LegacyValues.phpTrim(string(mapping, "last_name", nameParts[1]));
		if (first.isEmpty()) {
			first = "Employee";
		}

		String hireDate = LegacyValues.phpTrim(
				string(mapping, "hire_date", now.toLocalDate().toString()));
		String shiftEffective = LegacyValues.phpTrim(
				string(mapping, "shift_effective_from", hireDate));

		long newId = store.insertEmployee(
				companyId, normalized, expectedHours, first, last, EMPLOYEE_ROLE, hireDate);
		if (newId > 0) {
			store.insertShiftAssignment(newId, shiftId, shiftEffective);
		}
		return newId > 0 ? newId : null;
	}

	/**
	 * {@code attendance_import_split_sheet_name()}: the first whitespace-run
	 * separated word is the first name and the rest is the last name. Always
	 * {@code ['Employee', '']} on this endpoint, because a punch-log sheet has
	 * no name column -- kept because the mapping's own {@code first_name} is
	 * optional and this is what it defaults to.
	 */
	static String[] splitSheetName(String name) {
		String collapsed = LegacyValues.phpTrim((name == null ? "" : name).replaceAll("\\s+", " "));
		if (collapsed.isEmpty()) {
			return new String[] {"Employee", ""};
		}
		String[] parts = collapsed.split("\\s+");
		if (parts.length == 1) {
			return new String[] {parts[0], ""};
		}
		StringBuilder last = new StringBuilder();
		for (int index = 1; index < parts.length; index++) {
			if (index > 1) {
				last.append(' ');
			}
			last.append(parts[index]);
		}
		return new String[] {parts[0], last.toString()};
	}

	/**
	 * {@code import_fingerprint_attendance_rows()} -- the {@code template}
	 * branch, with its own column detection, its own date/time normalizers and
	 * its own error wording.
	 *
	 * <p>Its check-out logic has a quirk worth naming: the compound condition
	 * {@code ($colCheckOutDate && $colCheckOutTime) || ($colCheckOutTime && $colCheckInDate)}
	 * lets a sheet with a check-out <em>time</em> but no check-out <em>date</em>
	 * borrow the check-in date, which is how a same-day shift is expressed.
	 */
	private static Map<String, Object> importTemplate(
			LegacyAttendanceImportReader.Loaded loaded, long companyId,
			LegacyAttendanceImportStore store, LocalDateTime now, ZoneOffset offset) {
		List<String> keys = loaded.keys();
		String colEmpCode = LegacyAttendanceImportReader.detectCol(
				keys, LegacyAttendanceImportReader.EMPLOYEE_CODE_ALIASES);
		String colCheckIn = LegacyAttendanceImportReader.detectCol(
				keys, LegacyAttendanceImportReader.TEMPLATE_IN_ALIASES);
		String colCheckInDate = LegacyAttendanceImportReader.detectCol(
				keys, LegacyAttendanceImportReader.TEMPLATE_IN_DATE_ALIASES);
		// Longer than detect_format()'s list: this one also carries
		// وقت_الحضور and وقت الحضور. The difference is legacy's.
		String colCheckInTime = LegacyAttendanceImportReader.detectCol(keys, List.of(
				"check_in_time", "in_time", "time_in", "check_in", "بصمة_الدخول", "بصمة الدخول",
				"وقت_الحضور", "وقت الحضور", "دخول"));
		String colCheckOutDate = LegacyAttendanceImportReader.detectCol(keys, List.of(
				"check_out_date", "out_date", "date_out", "تاريخ_الانصراف", "تاريخ الانصراف"));
		String colCheckOutTime = LegacyAttendanceImportReader.detectCol(keys, List.of(
				"check_out_time", "out_time", "time_out", "check_out", "بصمة_الخروج",
				"بصمة الخروج", "وقت_الانصراف", "وقت الانصراف", "خروج"));
		String colCheckOut = LegacyAttendanceImportReader.detectCol(
				keys, List.of("datetime_out", "punch_out"));

		long inserted = 0;
		List<String> errors = new ArrayList<>();

		List<Map<String, Object>> rows = loaded.rows();
		for (int index = 0; index < rows.size(); index++) {
			Map<String, Object> row = rows.get(index);
			int rowNum = index + 2;
			// `(string) $colEmpCode` in PHP: a null column name becomes '' and
			// the lookup then simply misses on every row.
			String empCodeRaw = LegacyValues.phpTrim(
					stringCell(row, colEmpCode == null ? "" : colEmpCode));
			String empCode = LegacyAttendanceImportReader.sheetCode(empCodeRaw);
			if (empCode.isEmpty() || TEMPLATE_LABEL_ROW.matcher(empCodeRaw).matches()) {
				continue;
			}

			String checkIn = null;
			if (colCheckInDate != null && colCheckInTime != null) {
				checkIn = LegacyPhpTemplateDateTimes.compose(
						LegacyPhpTemplateDateTimes.normalizeDate(row.get(colCheckInDate), now, offset),
						LegacyPhpTemplateDateTimes.normalizeTime(row.get(colCheckInTime), now, offset));
			} else if (colCheckIn != null) {
				// Not normalized at all on this branch: the cell is trimmed and
				// handed straight to the INSERT.
				checkIn = LegacyValues.phpTrim(stringCell(row, colCheckIn));
			}

			// `!$checkIn` is PHP's falsiness, so '' and '0' both count as absent.
			if (empCode.isEmpty() || checkIn == null || LegacyValues.isPhpEmpty(checkIn)) {
				errors.add("Row " + rowNum + ": missing employee_code or check_in — skipped");
				continue;
			}

			Long employeeId = store.employeeIdByCode(
					companyId, LegacyAttendanceImportReader.sheetCode(empCodeRaw));
			if (employeeId == null) {
				errors.add("Row " + rowNum + ": employee_code '" + empCode
						+ "' not found in company " + companyId);
				continue;
			}

			if (store.hasAttendanceOnDay(employeeId, checkIn)) {
				errors.add("Row " + rowNum + ": duplicate record for '" + empCode + "' on "
						+ substr10(checkIn) + " — skipped");
				continue;
			}

			String checkOut = null;
			if ((colCheckOutDate != null && colCheckOutTime != null)
					|| (colCheckOutTime != null && colCheckInDate != null)) {
				String outDate = colCheckOutDate == null ? null
						: LegacyPhpTemplateDateTimes.normalizeDate(row.get(colCheckOutDate), now, offset);
				if (outDate == null && colCheckInDate != null) {
					outDate = LegacyPhpTemplateDateTimes.normalizeDate(
							row.get(colCheckInDate), now, offset);
				}
				String outTime = colCheckOutTime == null ? null
						: LegacyPhpTemplateDateTimes.normalizeTime(row.get(colCheckOutTime), now, offset);
				checkOut = LegacyPhpTemplateDateTimes.compose(outDate, outTime);
			} else if (colCheckOut != null) {
				checkOut = LegacyValues.phpTrim(stringCell(row, colCheckOut));
			}

			store.insertAttendance(employeeId, checkIn, emptyToNull(checkOut));
			inserted++;
		}

		// `count($errors)` here, not `$skipped + count($errors)`.
		return result(inserted, errors.size(), errors);
	}

	/** {@code $checkOut ?: null} -- PHP falsiness, so {@code ''} and {@code '0'} both become NULL. */
	private static String emptyToNull(String value) {
		return value == null || LegacyValues.isPhpEmpty(value) ? null : value;
	}

	/** {@code substr($checkIn, 0, 10)}. */
	private static String substr10(String value) {
		return value.length() <= 10 ? value : value.substring(0, 10);
	}

	private static String stringCell(Map<String, Object> row, String column) {
		Object value = row.get(column);
		return value == null ? "" : LegacyValues.toPhpString(value);
	}

	/** {@code trim((string) ($mapping[$key] ?? $fallback))}, before the trim. */
	private static String string(Map<String, Object> mapping, String key, String fallback) {
		Object value = mapping.get(key);
		return value == null ? fallback : LegacyValues.toPhpString(value);
	}

	/** {@code (float) $value} for the shapes a decoded JSON value can hold. */
	private static double toPhpFloat(Object value) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof Boolean flag) {
			return flag ? 1d : 0d;
		}
		String text = LegacyValues.phpTrim(LegacyValues.toPhpString(value));
		java.util.regex.Matcher leading =
				Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?").matcher(text);
		return leading.lookingAt() ? Double.parseDouble(leading.group()) : 0d;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Map<?, ?> raw) {
		Map<String, Object> mapping = new LinkedHashMap<>();
		raw.forEach((key, value) -> mapping.put(String.valueOf(key), value));
		return mapping;
	}

}
