package com.workin.legacy.employees.spreadsheet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.phone.LegacyPhoneCountries;
import com.workin.legacy.phone.LegacyPhoneNumbers;

/**
 * {@code employee_excel_row_to_payload()} and {@code employee_excel_analyze()}.
 *
 * <h2>Why this is not create.php's validation</h2>
 * <p>The spreadsheet path validates the same employee with different rules, and
 * the differences are not incidental. It resolves organization units by
 * <em>name</em> against per-company maps rather than by id; it has its own
 * phone resolution that falls back to Egypt twice before giving up, where
 * {@code create.php} rejects outright; it defaults mobile attendance to enabled
 * in the payload while reporting the missing cell as an error; and it fixes
 * {@code can_check_in_any_branch} at 0 and {@code shift_effective_from} to the
 * hire date. Routing it through the create service would silently apply the
 * stricter rules and change which rows a customer's file imports.
 *
 * <p>Error order is part of the contract: the client renders {@code errors} as
 * an ordered list, so the checks below run in source order and append as they
 * go.
 */
@Component
public class LegacyEmployeeSpreadsheetAnalyzer {

	/** {@code is_valid_employee_code_format()}: {@code /^[0-9]{1,64}$/}. */
	private static final Pattern EMPLOYEE_CODE_FORMAT = Pattern.compile("^[0-9]{1,64}$");

	/** {@code $salaryKeys}: spreadsheet column to payload key, in source order. */
	private static final Map<String, String> SALARY_KEYS = salaryKeys();

	private final LegacyEmployeeStore store;
	private final LegacyPhoneNumbers phoneNumbers;
	private final LegacyPhoneCountries phoneCountries;
	private final LegacyClock clock;

	public LegacyEmployeeSpreadsheetAnalyzer(
			LegacyEmployeeStore store, LegacyPhoneNumbers phoneNumbers,
			LegacyPhoneCountries phoneCountries, LegacyClock clock) {
		this.store = store;
		this.phoneNumbers = phoneNumbers;
		this.phoneCountries = phoneCountries;
		this.clock = clock;
	}

	/** {@code ['payload' => ..., 'errors' => ..., 'warnings' => ...]}. */
	public record Parsed(Map<String, Object> payload, List<String> errors) {
	}

	/**
	 * {@code employee_excel_analyze()}: structure first, then rows, then one
	 * analysis per surviving row.
	 *
	 * <p>{@code row_index} is the position in the surviving data-row list plus
	 * one -- not the physical line in the file. A file whose first two lines are
	 * a group row and a header row, followed by a blank line and then an
	 * employee, reports that employee as {@code row_index: 1}.
	 */
	public Map<String, Object> analyze(byte[] content, long companyId, boolean arabic,
			LegacyEmployeeSpreadsheetLookups lookups) {
		LegacyEmployeeSpreadsheetReader.assertTemplateStructure(content, arabic);
		List<Map<String, Object>> rows = LegacyEmployeeSpreadsheetReader.loadRows(content);

		List<Map<String, Object>> outRows = new ArrayList<>(rows.size());
		long valid = 0;
		long invalid = 0;
		for (int index = 0; index < rows.size(); index++) {
			Map<String, Object> row = rows.get(index);
			Parsed parsed = rowToPayload(row, companyId, lookups);
			boolean isValid = parsed.errors().isEmpty();
			if (isValid) {
				valid++;
			} else {
				invalid++;
			}

			Map<String, Object> outRow = new LinkedHashMap<>();
			outRow.put("row_index", (long) index + 1);
			outRow.put("status", isValid ? "valid" : "invalid");
			outRow.put("errors", parsed.errors());
			outRow.put("error_messages", LegacyEmployeeSpreadsheetErrors.messages(parsed.errors(), row));
			outRow.put("field_errors", LegacyEmployeeSpreadsheetErrors.fieldErrors(parsed.errors(), row));
			outRow.put("data", LegacyEmployeeSpreadsheetValues.normalizeDisplayRow(row, clock.today()));
			outRow.put("payload", parsed.payload());
			// `warnings` is deliberately absent: row_to_payload() returns one and
			// analyze() does not put it in the response.
			outRows.add(outRow);
		}

		Map<String, Object> analysis = new LinkedHashMap<>();
		analysis.put("columns", columns());
		analysis.put("lookups", lookupNames(lookups));
		analysis.put("rows", outRows);

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("total", (long) outRows.size());
		summary.put("valid", valid);
		summary.put("invalid", invalid);
		analysis.put("summary", summary);
		return analysis;
	}

	/**
	 * The four keys of {@code $c} {@code analyze()} exposes -- deliberately not
	 * the whole metadata row. Aliases and salary groups stay internal.
	 */
	private static List<Map<String, Object>> columns() {
		List<Map<String, Object>> columns = new ArrayList<>();
		for (LegacyEmployeeSpreadsheetColumns.Column column : LegacyEmployeeSpreadsheetColumns.columns()) {
			Map<String, Object> exposed = new LinkedHashMap<>();
			exposed.put("key", column.key());
			exposed.put("required", column.required());
			exposed.put("label_ar", column.labelAr());
			exposed.put("label_en", column.labelEn());
			columns.add(exposed);
		}
		return columns;
	}

	/** {@code array_keys($lookups[...])}: the normalized, lower-cased names. */
	private static Map<String, Object> lookupNames(LegacyEmployeeSpreadsheetLookups lookups) {
		Map<String, Object> names = new LinkedHashMap<>();
		names.put("branches", lookups.branchNames());
		names.put("departments", lookups.departmentNames());
		names.put("job_titles", lookups.jobTitleNames());
		names.put("shifts", lookups.shiftNames());
		return names;
	}

	/**
	 * {@code employee_excel_row_to_payload()}, clause for clause and in source
	 * order, because the order decides how {@code errors} reads.
	 */
	public Parsed rowToPayload(
			Map<String, Object> row, long companyId, LegacyEmployeeSpreadsheetLookups lookups) {
		List<String> errors = new ArrayList<>();

		String first = trimmed(row.get("first_name"));
		String last = trimmed(row.get("last_name"));
		String code = LegacyEmployeeSpreadsheetErrors.normalizeEmployeeCode(text(row.get("employee_code")));
		String hoursRaw = trimmed(row.get("expected_daily_hours"));

		if (first.isEmpty()) {
			errors.add("first_name_required");
		}
		if (last.isEmpty()) {
			errors.add("last_name_required");
		}
		if (code.isEmpty()) {
			errors.add("employee_code_required");
		} else if (!EMPLOYEE_CODE_FORMAT.matcher(code).matches()) {
			errors.add("employee_code_invalid");
		} else if (store.employeeCodeExistsInCompany(companyId, code, null)) {
			errors.add("employee_code_exists");
		}

		double hours = hoursRaw.isEmpty() ? 0.0d : toFloat(hoursRaw);
		if (hours <= 0) {
			errors.add("expected_daily_hours_required");
		}

		Long shiftId = null;
		String shiftName = trimmed(row.get("shift_name"));
		if (shiftName.isEmpty()) {
			errors.add("shift_required");
		} else {
			shiftId = lookups.shifts().get(LegacyValues.mbStrToLower(shiftName));
			if (shiftId == null) {
				errors.add("shift_not_found");
			} else if (!store.shiftBelongsToCompany(shiftId, companyId)) {
				errors.add("shift_not_found");
				shiftId = null;
			}
		}

		Long branchId = null;
		String branchName = trimmed(row.get("branch_name"));
		if (branchName.isEmpty()) {
			errors.add("branch_required");
		} else {
			branchId = lookups.branches().get(LegacyValues.mbStrToLower(branchName));
			if (branchId == null) {
				errors.add("branch_not_found");
			}
		}

		Long departmentId = null;
		String departmentName = trimmed(row.get("department_name"));
		if (departmentName.isEmpty()) {
			errors.add("department_required");
		} else {
			departmentId = lookups.departments().get(LegacyValues.mbStrToLower(departmentName));
			if (departmentId == null) {
				errors.add("department_not_found");
			}
		}

		// employee_department_valid_for_branch(): checked against the branch when
		// there is one, and against the company when there is not.
		if (departmentId != null && !departmentValidForBranch(departmentId, branchId, companyId)) {
			errors.add("department_branch_mismatch");
		}

		Long jobTitleId = null;
		String jobTitleName = trimmed(row.get("job_title_name"));
		if (jobTitleName.isEmpty()) {
			errors.add("job_title_required");
		} else {
			jobTitleId = lookups.jobTitles().get(LegacyValues.mbStrToLower(jobTitleName));
			if (jobTitleId == null) {
				errors.add("job_title_not_found");
			}
		}

		if (jobTitleId != null && departmentId != null
				&& !store.jobTitleBelongsToDepartment(jobTitleId, departmentId)) {
			errors.add("job_title_department_mismatch");
		}

		String mobileRaw = trimmed(row.get("is_mobile_attendance_enabled"));
		Integer mobileAttendance = null;
		if (mobileRaw.isEmpty()) {
			errors.add("mobile_attendance_required");
		} else {
			int parsed = LegacyEmployeeSpreadsheetValues.parseBool(mobileRaw, -1);
			if (parsed < 0) {
				errors.add("mobile_attendance_invalid");
			} else {
				mobileAttendance = parsed;
			}
		}

		Phone phone = resolvePhone(row, errors);

		LocalDate today = clock.today();
		String hireDate = LegacyEmployeeSpreadsheetValues.normalizeDateValue(row.get("hire_date"), today);
		if (hireDate == null || hireDate.isEmpty()) {
			hireDate = today.toString();
		}
		String birthDate = LegacyEmployeeSpreadsheetValues.normalizeDateValue(row.get("birth_date"), today);

		Integer contractMonths = contractMonths(row);

		String basicRaw = trimmed(row.get("salary_basic"));
		if (basicRaw.isEmpty() || toFloat(basicRaw) < 0) {
			errors.add("salary_basic_required");
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("first_name", first);
		payload.put("last_name", last);
		payload.put("employee_code", code);
		payload.put("expected_daily_hours", hours);
		payload.put("shift_id", shiftId);
		payload.put("branch_id", branchId);
		payload.put("department_id", departmentId);
		payload.put("job_title_id", jobTitleId);
		payload.put("phone", phone.phone());
		payload.put("country_code", phone.countryCode());
		payload.put("password", row.get("password"));
		payload.put("national_id", row.get("national_id"));
		payload.put("birth_date", birthDate);
		payload.put("gender", LegacyEmployeeSpreadsheetValues.parseGender(row.get("gender")));
		payload.put("address", row.get("address"));
		payload.put("hire_date", hireDate);
		payload.put("contract_duration_months", contractMonths);
		// The payload defaults to enabled even though a missing cell is an
		// error: `$mobileAttendance ?? 1`.
		payload.put("is_mobile_attendance_enabled",
				mobileAttendance == null ? Integer.valueOf(1) : mobileAttendance);
		payload.put("can_check_in_any_branch", 0);
		payload.put("shift_effective_from", hireDate);

		Map<String, Object> salary = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : SALARY_KEYS.entrySet()) {
			Object cell = row.get(entry.getKey());
			if (cell != null && !trimmed(cell).isEmpty()) {
				salary.put(entry.getValue(), toFloat(trimmed(cell)));
			}
		}
		// Written twice in PHP, and the second write is unconditional on a
		// non-empty cell -- which matters only when the cell is whitespace.
		if (!basicRaw.isEmpty()) {
			salary.put("basic", toFloat(basicRaw));
		}
		if (!salary.isEmpty()) {
			payload.put("salary", salary);
		}

		return new Parsed(payload, List.copyOf(errors));
	}

	/** {@code $phone} and {@code $country_code} as the phone clause leaves them. */
	private record Phone(String phone, String countryCode) {
	}

	/**
	 * The spreadsheet's own phone resolution, which is more forgiving than
	 * {@code create.php}'s: a label-like country cell is ignored, an unknown
	 * dial code falls back to the configured default, and a number that fails
	 * its own country is retried as Egyptian before being rejected.
	 */
	private Phone resolvePhone(Map<String, Object> row, List<String> errors) {
		Object phoneCell = row.get("phone");
		if (!LegacyValues.phpTrim(LegacyPhoneNumbers.excelCellToRaw(phoneCell)).isEmpty()) {
			String rawCountry = trimmed(row.get("country_code"));
			String foldedCountry = LegacyValues.mbStrToLower(rawCountry);
			if (rawCountry.isEmpty() || foldedCountry.contains("دولة") || foldedCountry.contains("country")) {
				rawCountry = phoneCountries.defaultCode();
			}
			String countryCode = LegacyPhoneNumbers.normalizeDialCode(rawCountry);
			if (countryCode.isEmpty() || phoneCountries.find(countryCode).isEmpty()) {
				countryCode = phoneCountries.defaultCode();
			}
			String phone = phoneNumbers.normalizeLocal(countryCode, phoneCell);
			if (!phoneNumbers.isValidLocal(countryCode, phone)) {
				String egyptian = phoneNumbers.normalizeLocal("+20", phoneCell);
				if (phoneNumbers.isValidLocal("+20", egyptian)) {
					return new Phone(egyptian, "+20");
				}
				errors.add("invalid_phone");
				return new Phone(null, null);
			}
			if (store.phoneExistsGlobally(phone, null)) {
				errors.add("phone_exists");
			}
			return new Phone(phone, countryCode);
		}
		if (!LegacyValues.isPhpEmpty(row.get("country_code"))) {
			// No phone, but a country cell: the code is kept on its own.
			return new Phone(null, LegacyPhoneNumbers.normalizeDialCode(trimmed(row.get("country_code"))));
		}
		return new Phone(null, null);
	}

	/**
	 * The sheet holds years and the column stores months. The older,
	 * months-valued sheets are still accepted through
	 * {@code contract_duration_months}, but only when the years cell is absent
	 * or blank.
	 */
	private static Integer contractMonths(Map<String, Object> row) {
		Object years = row.get("contract_duration_years");
		String yearsRaw = trimmed(years != null ? years : row.get("contract_duration_months"));
		if (yearsRaw.isEmpty()) {
			return null;
		}
		if (row.containsKey("contract_duration_years") && !trimmed(years).isEmpty()) {
			double value = toFloat(trimmed(years));
			return value > 0 ? (int) Math.round(value * 12) : null;
		}
		int months = (int) toFloat(yearsRaw);
		return months > 0 ? months : null;
	}

	/** {@code employee_department_valid_for_branch()}. */
	private boolean departmentValidForBranch(Long departmentId, Long branchId, long companyId) {
		if (departmentId == null || departmentId <= 0) {
			return true;
		}
		if (branchId != null && branchId > 0) {
			return store.departmentBelongsToBranch(departmentId, branchId);
		}
		return store.departmentBelongsToCompany(departmentId, companyId);
	}

	private static Map<String, String> salaryKeys() {
		Map<String, String> keys = new LinkedHashMap<>();
		keys.put("salary_basic", "basic");
		keys.put("salary_transport", "transport");
		keys.put("salary_food_allowance", "food_allowance");
		keys.put("salary_risk_allowance", "risk_allowance");
		keys.put("salary_incentives", "incentives");
		keys.put("salary_insurance_deduction", "insurance_deduction");
		keys.put("salary_tax_deduction", "tax_deduction");
		keys.put("salary_advances_deduction", "advances_deduction");
		keys.put("salary_fund_deduction", "fund_deduction");
		keys.put("salary_penalty_deduction", "penalty_deduction");
		return Map.copyOf(keys);
	}

	/** PHP's {@code (float)} cast: the leading numeric run, or zero. */
	private static double toFloat(String raw) {
		return LegacyValues.toPhpDecimal(raw).doubleValue();
	}

	private static String text(Object value) {
		return LegacyEmployeeSpreadsheetValues.asString(value);
	}

	private static String trimmed(Object value) {
		return LegacyValues.phpTrim(text(value));
	}

}
