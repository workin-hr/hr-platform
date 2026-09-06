package com.workin.legacy.employees.spreadsheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.phone.LegacyPhoneCountries;
import com.workin.legacy.phone.LegacyPhoneNumbers;

/**
 * {@code employee_excel_row_to_update_payload()} and
 * {@code employee_excel_analyze_update()} -- the bulk-update sheet's
 * counterpart to {@link LegacyEmployeeSpreadsheetAnalyzer}.
 *
 * <p>The two sheets look alike and validate very differently. On the create
 * sheet every required column must be filled; on this one <b>an empty cell
 * means "leave that field alone"</b>, so only {@code employee_code} is
 * mandatory and everything else is validated only when present. A field
 * absent from the payload is a field the update will not touch.
 *
 * <p>Its own class rather than a flag on the create analyzer: the clause
 * order differs, the early returns differ, and the lookups fall back to the
 * <em>employee's current values</em> rather than to nothing. Threading that
 * through one method with a boolean would make both paths harder to check
 * against their PHP counterparts, which is the only thing making either
 * verifiable.
 */
@Component
public class LegacyEmployeeUpdateAnalyzer {

	/** {@code is_valid_employee_code_format()}: {@code /^[0-9]{1,64}$/}. */
	private static final Pattern EMPLOYEE_CODE_FORMAT = Pattern.compile("^[0-9]{1,64}$");

	private final LegacyEmployeeStore store;

	private final LegacyPhoneNumbers phoneNumbers;

	private final LegacyPhoneCountries phoneCountries;

	private final LegacyClock clock;

	/** Shared with the create sheet so both agree on branch/department reachability. */
	private final LegacyEmployeeSpreadsheetAnalyzer createAnalyzer;

	public LegacyEmployeeUpdateAnalyzer(
			LegacyEmployeeStore store, LegacyPhoneNumbers phoneNumbers,
			LegacyPhoneCountries phoneCountries, LegacyClock clock,
			LegacyEmployeeSpreadsheetAnalyzer createAnalyzer) {
		this.store = store;
		this.phoneNumbers = phoneNumbers;
		this.phoneCountries = phoneCountries;
		this.clock = clock;
		this.createAnalyzer = createAnalyzer;
	}

	/** {@code ['payload' => ..., 'errors' => ..., 'warnings' => ...]}. */
	public record Parsed(Map<String, Object> payload, List<String> errors) {
	}

	/**
	 * {@code employee_excel_analyze_update()}: template structure, then rows,
	 * then one analysis per surviving row.
	 *
	 * <p>Duplicate codes <em>within the file</em> are flagged here rather than
	 * in {@link #rowToUpdatePayload}, because "seen already" is a property of
	 * the sheet and not of the row: the first occurrence stays valid and only
	 * the second is marked.
	 */
	public Map<String, Object> analyze(byte[] content, long companyId, boolean arabic,
			LegacyEmployeeSpreadsheetLookups lookups) {
		LegacyEmployeeSpreadsheetReader.assertTemplateStructure(content, arabic);
		List<Map<String, Object>> rows = LegacyEmployeeSpreadsheetReader.loadRows(content);
		Map<String, Map<String, Object>> employeesByCode = this.store.employeesByCode(companyId);
		Set<String> seenCodes = new HashSet<>();

		List<Map<String, Object>> outRows = new ArrayList<>(rows.size());
		long valid = 0;
		long invalid = 0;

		for (int index = 0; index < rows.size(); index++) {
			Map<String, Object> row = rows.get(index);
			Parsed parsed = rowToUpdatePayload(row, companyId, lookups, employeesByCode);
			List<String> errors = new ArrayList<>(parsed.errors());

			String code = LegacyEmployeeSpreadsheetErrors.normalizeEmployeeCode(text(row.get("employee_code")));
			if (!code.isEmpty() && !seenCodes.add(code)) {
				errors.add("employee_code_duplicate_in_file");
			}

			boolean isValid = errors.isEmpty();
			if (isValid) {
				valid++;
			} else {
				invalid++;
			}

			Map<String, Object> outRow = new LinkedHashMap<>();
			outRow.put("row_index", (long) index + 1);
			outRow.put("status", isValid ? "valid" : "invalid");
			outRow.put("errors", errors);
			outRow.put("error_messages", LegacyEmployeeSpreadsheetErrors.messages(errors, row));
			outRow.put("field_errors", LegacyEmployeeSpreadsheetErrors.fieldErrors(errors, row));
			outRow.put("data", LegacyEmployeeSpreadsheetValues.normalizeDisplayRow(row, this.clock.today()));
			outRow.put("payload", parsed.payload());
			outRows.add(outRow);
		}

		Map<String, Object> analysis = new LinkedHashMap<>();
		analysis.put("columns", updateColumns());
		analysis.put("lookups", lookupNames(lookups));
		analysis.put("rows", outRows);
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("total", (long) outRows.size());
		summary.put("valid", valid);
		summary.put("invalid", invalid);
		analysis.put("summary", summary);
		return analysis;
	}

	/** The four keys exposed, from {@code employee_excel_columns_meta(true)}. */
	private static List<Map<String, Object>> updateColumns() {
		List<Map<String, Object>> columns = new ArrayList<>();
		for (LegacyEmployeeSpreadsheetColumns.Column column
				: LegacyEmployeeSpreadsheetColumns.updateColumns()) {
			Map<String, Object> exposed = new LinkedHashMap<>();
			exposed.put("key", column.key());
			exposed.put("required", column.required());
			exposed.put("label_ar", column.labelAr());
			exposed.put("label_en", column.labelEn());
			columns.add(exposed);
		}
		return columns;
	}

	private static Map<String, Object> lookupNames(LegacyEmployeeSpreadsheetLookups lookups) {
		Map<String, Object> names = new LinkedHashMap<>();
		names.put("branches", lookups.branchNames());
		names.put("departments", lookups.departmentNames());
		names.put("job_titles", lookups.jobTitleNames());
		names.put("shifts", lookups.shiftNames());
		return names;
	}

	/**
	 * {@code employee_excel_row_to_update_payload()}, clause for clause and in
	 * source order, because the order decides how {@code errors} reads.
	 *
	 * <p>The three code failures <b>return immediately</b> with an empty
	 * payload: without an existing employee there is nothing for any later
	 * clause to validate against, and PHP returns rather than accumulating.
	 */
	public Parsed rowToUpdatePayload(Map<String, Object> row, long companyId,
			LegacyEmployeeSpreadsheetLookups lookups,
			Map<String, Map<String, Object>> employeesByCode) {

		List<String> errors = new ArrayList<>();
		Map<String, Object> payload = new LinkedHashMap<>();

		String code = LegacyEmployeeSpreadsheetErrors.normalizeEmployeeCode(text(row.get("employee_code")));
		if (code.isEmpty()) {
			errors.add("employee_code_required");
			return new Parsed(payload, errors);
		}
		if (!EMPLOYEE_CODE_FORMAT.matcher(code).matches()) {
			errors.add("employee_code_invalid");
			return new Parsed(payload, errors);
		}
		Map<String, Object> employee = employeesByCode.get(code);
		if (employee == null) {
			errors.add("employee_not_found");
			return new Parsed(payload, errors);
		}

		long employeeId = asLong(employee.get("id"));
		payload.put("id", employeeId);
		payload.put("employee_code", code);

		if (cellFilled(row, "first_name")) {
			payload.put("first_name", trimmed(row.get("first_name")));
		}
		if (cellFilled(row, "last_name")) {
			payload.put("last_name", trimmed(row.get("last_name")));
		}

		if (cellFilled(row, "expected_daily_hours")) {
			double hours = toFloat(trimmed(row.get("expected_daily_hours")));
			if (hours <= 0) {
				errors.add("expected_daily_hours_required");
			} else {
				payload.put("expected_daily_hours", hours);
			}
		}

		if (cellFilled(row, "shift_name")) {
			Long shiftId = lookups.shifts().get(LegacyValues.mbStrToLower(trimmed(row.get("shift_name"))));
			if (shiftId == null || !this.store.shiftBelongsToCompany(shiftId, companyId)) {
				errors.add("shift_not_found");
			} else {
				payload.put("shift_id", shiftId);
			}
		}

		// The branch and department the checks below compare start as the
		// employee's *current* values, so a sheet that changes only the
		// department is still validated against the branch they are in.
		Long branchId = normalizeOptionalBranchId(employee.get("branch_id"));
		if (cellFilled(row, "branch_name")) {
			Long found = lookups.branches().get(LegacyValues.mbStrToLower(trimmed(row.get("branch_name"))));
			if (found == null) {
				errors.add("branch_not_found");
			} else {
				branchId = found;
				payload.put("branch_id", branchId);
			}
		}

		Long departmentId = positiveOrNull(employee.get("department_id"));
		if (cellFilled(row, "department_name")) {
			Long found = lookups.departments().get(LegacyValues.mbStrToLower(trimmed(row.get("department_name"))));
			if (found == null) {
				errors.add("department_not_found");
			} else {
				departmentId = found;
				payload.put("department_id", departmentId);
			}
		}

		// Only checked when the sheet actually moved one of the two. Leaving a
		// pre-existing mismatch alone is deliberate: this endpoint edits the
		// fields it is given and does not audit rows it was not asked about.
		if ((payload.containsKey("branch_id") || payload.containsKey("department_id"))
				&& departmentId != null
				&& !this.createAnalyzer.departmentValidForBranch(departmentId, branchId, companyId)) {
			errors.add("department_branch_mismatch");
		}

		Long jobTitleId = positiveOrNull(employee.get("job_title_id"));
		if (cellFilled(row, "job_title_name")) {
			Long found = lookups.jobTitles().get(LegacyValues.mbStrToLower(trimmed(row.get("job_title_name"))));
			if (found == null) {
				errors.add("job_title_not_found");
			} else {
				jobTitleId = found;
				payload.put("job_title_id", jobTitleId);
			}
		}

		if ((payload.containsKey("department_id") || payload.containsKey("job_title_id"))
				&& jobTitleId != null && departmentId != null
				&& !this.store.jobTitleBelongsToDepartment(jobTitleId, departmentId)) {
			errors.add("job_title_department_mismatch");
		}

		if (cellFilled(row, "is_mobile_attendance_enabled")) {
			int mobile = LegacyEmployeeSpreadsheetValues.parseBool(
					row.get("is_mobile_attendance_enabled"), -1);
			if (mobile < 0) {
				errors.add("mobile_attendance_invalid");
			} else {
				payload.put("is_mobile_attendance_enabled", mobile);
			}
		}

		resolvePhone(row, employee, employeeId, errors, payload);

		if (cellFilled(row, "password")) {
			// Stored raw here and hashed at apply time, and only if still
			// non-empty after trimming -- a cell of spaces changes nothing.
			payload.put("password", text(row.get("password")));
		}
		if (cellFilled(row, "national_id")) {
			payload.put("national_id", trimmed(row.get("national_id")));
		}
		if (cellFilled(row, "birth_date")) {
			// An unparseable date is skipped, not rejected: PHP adds no error
			// and omits the field, so the stored value stays.
			String birthDate = LegacyEmployeeSpreadsheetValues.normalizeDateValue(
					row.get("birth_date"), this.clock.today());
			if (birthDate != null) {
				payload.put("birth_date", birthDate);
			}
		}
		if (cellFilled(row, "gender")) {
			String gender = LegacyEmployeeSpreadsheetValues.parseGender(row.get("gender"));
			if (gender == null) {
				errors.add("gender_invalid");
			} else {
				payload.put("gender", gender);
			}
		}
		if (cellFilled(row, "address")) {
			payload.put("address", trimmed(row.get("address")));
		}
		if (cellFilled(row, "hire_date")) {
			String hireDate = LegacyEmployeeSpreadsheetValues.normalizeDateValue(
					row.get("hire_date"), this.clock.today());
			if (hireDate != null) {
				payload.put("hire_date", hireDate);
			}
		}

		// Read with a bare trim rather than cellFilled -- the same test, but
		// PHP writes it differently here, and the column name differs from the
		// payload key it produces.
		String yearsRaw = trimmed(row.get("contract_duration_years"));
		if (!yearsRaw.isEmpty()) {
			double years = toFloat(yearsRaw);
			if (years > 0) {
				payload.put("contract_duration_months", (long) Math.round(years * 12));
			}
		}

		Map<String, Object> salary = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : LegacyEmployeeSpreadsheetAnalyzer.salaryKeys().entrySet()) {
			if (cellFilled(row, entry.getKey())) {
				salary.put(entry.getValue(), toFloat(trimmed(row.get(entry.getKey()))));
			}
		}
		if (!salary.isEmpty()) {
			payload.put("salary", salary);
		}

		// id and employee_code identify the row rather than change it, so a
		// payload holding only those two is a row that asked for nothing.
		long updatable = payload.keySet().stream()
				.filter(key -> !"id".equals(key) && !"employee_code".equals(key))
				.count();
		if (updatable == 0) {
			errors.add("nothing_to_update");
		}

		return new Parsed(payload, errors);
	}

	/**
	 * The update sheet's phone resolution. Differs from the create sheet's in
	 * two ways that matter: an absent or label-like country cell falls back to
	 * the <b>employee's own</b> country code before the configured default,
	 * and the uniqueness check excludes this employee -- otherwise re-sending
	 * a row carrying its own unchanged phone would fail as a duplicate of
	 * itself.
	 */
	private void resolvePhone(Map<String, Object> row, Map<String, Object> employee,
			long employeeId, List<String> errors, Map<String, Object> payload) {

		Object phoneCell = row.get("phone");
		if (LegacyValues.phpTrim(LegacyPhoneNumbers.excelCellToRaw(phoneCell)).isEmpty()) {
			return;
		}

		String rawCountry = trimmed(row.get("country_code"));
		String folded = LegacyValues.mbStrToLower(rawCountry);
		if (rawCountry.isEmpty() || folded.contains("دولة") || folded.contains("country")) {
			rawCountry = text(employee.get("country_code"));
			if (rawCountry.isEmpty()) {
				rawCountry = this.phoneCountries.defaultCode();
			}
		}
		String countryCode = LegacyPhoneNumbers.normalizeDialCode(rawCountry);
		if (countryCode.isEmpty() || this.phoneCountries.find(countryCode).isEmpty()) {
			countryCode = this.phoneCountries.defaultCode();
		}

		String phone = this.phoneNumbers.normalizeLocal(countryCode, phoneCell);
		if (!this.phoneNumbers.isValidLocal(countryCode, phone)) {
			String egyptian = this.phoneNumbers.normalizeLocal("+20", phoneCell);
			if (this.phoneNumbers.isValidLocal("+20", egyptian)) {
				phone = egyptian;
				countryCode = "+20";
			} else {
				errors.add("invalid_phone");
				return;
			}
		}

		if (this.store.phoneExistsGlobally(phone, employeeId)) {
			// Reported, and neither field written: the row fails, so the
			// payload must not carry a number that was rejected.
			errors.add("phone_exists");
			return;
		}
		payload.put("phone", phone);
		payload.put("country_code", countryCode);
	}

	/** {@code employee_excel_cell_filled()}: present and non-blank after trimming. */
	static boolean cellFilled(Map<String, Object> row, String key) {
		return !LegacyValues.phpTrim(text(row.get(key))).isEmpty();
	}

	/**
	 * {@code normalize_optional_branch_id()}: null, empty and non-positive all
	 * become null. Duplicated from {@link LegacyEmployeeCreateHelper}, where it
	 * is private -- widening it there for one caller would make an internal
	 * rule of the create path look like shared API.
	 */
	private static Long normalizeOptionalBranchId(Object value) {
		return positiveOrNull(value);
	}

	private static Long positiveOrNull(Object value) {
		long parsed = asLong(value);
		return parsed > 0 ? parsed : null;
	}

	private static long asLong(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
		} catch (NumberFormatException ex) {
			return 0L;
		}
	}

	/** PHP's {@code (float) $string}: leading numeric prefix, else zero. */
	private static double toFloat(String raw) {
		return LegacyValues.toPhpDecimal(raw).doubleValue();
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String trimmed(Object value) {
		return LegacyValues.phpTrim(text(value));
	}

}
