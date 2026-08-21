package com.workin.legacy.employees.spreadsheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpDateYear;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.employees.LegacyEmployeeStore;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code employee_create_from_payload()} -- the bulk import's own create path.
 *
 * <h2>Why this is not employees/create.php</h2>
 * <p>They differ in validation order, in what a failure produces, and in what
 * they write. This one returns {@code ['ok' => false, 'errors' => [...]]} for
 * most failures so the batch can record a row and carry on, where
 * {@code create.php} answers the request. It always writes a
 * {@code leave_balance} row. And its year comes from
 * {@code date('Y', strtotime($hire_date))} <em>inside</em> the transaction, so
 * an unparseable hire date is a rolled-back row rather than the 500
 * {@code create.php} produces. Sharing an implementation would quietly change
 * all three.
 *
 * <h2>Two places that still end the whole request</h2>
 * <p>{@code resolve_employee_phone_and_country_code()} and
 * {@code assert_valid_employee_code()} call {@code fail()}, and {@code fail()}
 * ends in {@code exit} -- so they terminate the request mid-batch rather than
 * failing one row, and rows already committed stay committed. That is
 * reproduced here by letting {@link LegacyApiException} propagate.
 *
 * <p>It also makes the {@code try { assert_valid_employee_code(...) } catch
 * (Throwable) { return invalid_input; }} in the PHP unreachable: {@code exit}
 * is not catchable. The {@code invalid_input} arm is dead code, and is not
 * reproduced as a live branch here.
 *
 * <p>Neither is reachable from the batch as it stands, because
 * {@code row_to_payload()} has already rejected an invalid code and has already
 * resolved the phone against the same country -- but the port keeps the
 * behaviour rather than the reachability argument, so it stays correct if a
 * caller ever hands the helper a payload it did not build.
 */
@Component
public class LegacyEmployeeCreateHelper {

	/** {@code is_valid_employee_code_format()}: {@code /^[0-9]{1,64}$/}. */
	private static final Pattern EMPLOYEE_CODE_FORMAT = Pattern.compile("^[0-9]{1,64}$");

	/** {@code preg_match("/for key '([^']+)'/i", $message, $m)}. */
	private static final Pattern DUPLICATE_KEY =
			Pattern.compile("for key '([^']+)'", Pattern.CASE_INSENSITIVE);

	private final LegacyEmployeeStore store;
	private final LegacyPhoneNumbers phoneNumbers;
	private final LegacyClock clock;
	private final PasswordEncoder bcrypt;

	public LegacyEmployeeCreateHelper(
			LegacyEmployeeStore store, LegacyPhoneNumbers phoneNumbers,
			LegacyClock clock, PasswordEncoder bcrypt) {
		this.store = store;
		this.phoneNumbers = phoneNumbers;
		this.clock = clock;
		this.bcrypt = bcrypt;
	}

	/** {@code ['ok' => bool, 'errors' => [...], 'employee_id' => int]}. */
	public record Result(boolean ok, List<String> errors, Long employeeId) {

		static Result failure(String... errors) {
			return new Result(false, List.of(errors), null);
		}

		static Result success(long employeeId) {
			return new Result(true, List.of(), employeeId);
		}

	}

	/**
	 * {@code employee_create_from_payload()}, in its own order: the name and
	 * phone checks, the code, the branch, the department, the job title, the
	 * shift, the hours, then one transaction for exactly one employee.
	 *
	 * <p>Note where the accumulated {@code $errors} are returned: only after
	 * every early-return check has passed. A row missing both a first name and
	 * a valid branch reports the branch, not the name.
	 */
	public Result create(long companyId, Map<String, Object> body) {
		List<String> errors = new ArrayList<>();

		if (trimmed(body.get("first_name")).isEmpty()) {
			errors.add("first_name");
		}
		if (trimmed(body.get("last_name")).isEmpty()) {
			errors.add("last_name");
		}

		// May end the request outright -- see the class note.
		Phone phone = resolvePhone(body);
		if (phone.phone() != null && store.phoneExistsGlobally(phone.phone(), null)) {
			return Result.failure("phone_already_exists");
		}

		String employeeCode = LegacyEmployeeSpreadsheetErrors.normalizeEmployeeCode(
				LegacyEmployeeSpreadsheetValues.asString(body.get("employee_code")));
		if (employeeCode.isEmpty()) {
			errors.add("employee_code");
		} else {
			if (!EMPLOYEE_CODE_FORMAT.matcher(employeeCode).matches()) {
				// assert_valid_employee_code() -> fail(), which exits. The PHP's
				// catch around this cannot run, so neither does a catch here.
				throw new LegacyApiException(400, "employee_code_invalid", null,
						Map.of("field", "employee_code"));
			}
			if (store.employeeCodeExistsInCompany(companyId, employeeCode, null)) {
				return Result.failure("employee_code_already_exists");
			}
		}

		Long branchId = normalizeOptionalBranchId(body.get("branch_id"));
		if (branchId != null) {
			if (!store.branchExistsInCompany(branchId, companyId)) {
				return Result.failure("branch_not_found");
			}
		} else {
			// employees.branch_id is NOT NULL, so an omitted branch takes the
			// company default rather than being left unset.
			branchId = store.companyDefaultActiveBranchId(companyId);
			if (branchId == null) {
				return Result.failure("branch_not_found");
			}
		}

		Long departmentId = positiveOrNull(body.get("department_id"));
		if (departmentId != null && !departmentValidForBranch(departmentId, branchId, companyId)) {
			return Result.failure("department_not_found");
		}

		Long jobTitleId = positiveOrNull(body.get("job_title_id"));
		if (jobTitleId != null && departmentId != null
				&& !store.jobTitleBelongsToDepartment(jobTitleId, departmentId)) {
			return Result.failure("job_title_not_found");
		}

		Long shiftId = positiveOrNull(body.get("shift_id"));
		if (shiftId == null || shiftId <= 0) {
			errors.add("shift_id");
		} else if (!store.shiftBelongsToCompany(shiftId, companyId)) {
			return Result.failure("shift_not_found");
		}

		double expectedHours = body.containsKey("expected_daily_hours") && body.get("expected_daily_hours") != null
				? LegacyValues.toPhpDecimal(body.get("expected_daily_hours")).doubleValue()
				: 0.0d;
		if (expectedHours <= 0) {
			errors.add("expected_daily_hours");
		}

		if (!errors.isEmpty()) {
			return new Result(false, List.copyOf(errors), null);
		}

		return write(companyId, body, phone, employeeCode, branchId, departmentId,
				jobTitleId, shiftId, expectedHours);
	}

	/** The transaction, and the classification of everything that can come out of it. */
	private Result write(long companyId, Map<String, Object> body, Phone phone, String employeeCode,
			Long branchId, Long departmentId, Long jobTitleId, Long shiftId, double expectedHours) {
		String hireDate = body.get("hire_date") == null
				? clock.todayAsString()
				: LegacyEmployeeSpreadsheetValues.asString(body.get("hire_date"));
		Object shiftEffectiveRaw = body.get("shift_effective_from") != null
				? body.get("shift_effective_from")
				: (body.get("hire_date") != null ? body.get("hire_date") : clock.todayAsString());
		String shiftEffective = LegacyEmployeeSpreadsheetValues.asString(shiftEffectiveRaw);

		long employeeId;
		try {
			employeeId = store.inTransaction(() -> {
				String plainPassword = body.get("password") == null
						? "" : trimmed(body.get("password"));
				String passwordHash = phone.phone() != null && !plainPassword.isEmpty()
						? bcrypt.encode(plainPassword) : null;

				Integer contractMonths = null;
				Object rawMonths = body.get("contract_duration_months");
				if (rawMonths != null && !"".equals(rawMonths)) {
					int months = (int) LegacyValues.toPhpDecimal(rawMonths).doubleValue();
					contractMonths = months > 0 ? months : null;
				}

				// Strict comparison in PHP: only true, 1, '1' and 'true' enable it.
				int mobileAttendance = 1;
				if (body.containsKey("is_mobile_attendance_enabled")) {
					mobileAttendance = isStrictlyTruthy(body.get("is_mobile_attendance_enabled")) ? 1 : 0;
				}

				Map<String, Object> columns = new LinkedHashMap<>();
				columns.put("company_id", companyId);
				columns.put("branch_id", branchId);
				columns.put("department_id", departmentId);
				columns.put("job_title_id", jobTitleId);
				columns.put("employee_code", employeeCode);
				columns.put("expected_daily_hours", expectedHours);
				columns.put("first_name", trimmed(body.get("first_name")));
				columns.put("last_name", trimmed(body.get("last_name")));
				columns.put("country_code", phone.countryCode());
				columns.put("phone", phone.phone());
				columns.put("password_hash", passwordHash);
				columns.put("role", "employee");
				columns.put("national_id", body.get("national_id"));
				columns.put("birth_date", body.get("birth_date"));
				columns.put("gender", body.get("gender"));
				columns.put("address", body.get("address"));
				columns.put("hire_date", hireDate);
				columns.put("contract_duration_months", contractMonths);
				columns.put("is_mobile_attendance_enabled", mobileAttendance);
				columns.put("is_active", 1);
				if (store.employeesHasColumn("can_check_in_any_branch")) {
					int canCheckInAnyBranch = body.containsKey("can_check_in_any_branch")
							&& isStrictlyTruthy(body.get("can_check_in_any_branch")) ? 1 : 0;
					columns.put("can_check_in_any_branch", canCheckInAnyBranch);
				}

				long newEmployeeId = store.insertEmployee(columns);

				// !empty($body['salary']) -- an empty salary map writes no contract.
				if (!LegacyValues.isPhpEmpty(body.get("salary"))) {
					store.insertSalaryContract(
							newEmployeeId, salaryAmounts(body.get("salary")), hireDate);
				}

				// date('Y', strtotime($hire_date)) runs *inside* the transaction
				// here, so an unparseable hire date rolls this row back and
				// becomes employee_create_failed -- not the 500 create.php gives.
				long leaveYear = LegacyPhpDateYear.of(hireDate, clock.today());
				store.insertLeaveBalance(newEmployeeId, leaveYear, 21.0d, 1L, 12L, null);

				if (shiftId != null) {
					store.insertShiftAssignment(newEmployeeId, shiftId, shiftEffective);
				}
				return newEmployeeId;
			});
		} catch (LegacyApiException ex) {
			// fail() from inside the transaction body still ends the request.
			throw ex;
		} catch (Throwable ex) { // NOPMD - catch (Throwable $e), as PHP does
			return classify(ex);
		}

		// The reread is *after* the commit, and its failure is reported as an
		// ordinary row failure -- so a row can be reported failed while its
		// employee, salary contract, leave balance and shift assignment all
		// remain in the database. PHP compensates for none of that, and neither
		// does this: adding a deletion here would invent a rollback legacy has
		// never performed.
		if (store.findByIdWithOrgLabels(employeeId) == null) {
			return Result.failure("employee_create_failed");
		}
		// PHP then attaches the latest salary contract and shift assignment to
		// the row it just read. The batch keeps only employee_id, so neither
		// attachment is observable and neither is performed.
		return Result.success(employeeId);
	}

	/**
	 * The {@code catch (Throwable)} arm: a duplicate is mapped by <em>key
	 * name</em>, and everything else -- including a duplicate on a constraint
	 * this does not recognise -- is the generic failure. No SQL text, index name
	 * or exception message ever reaches the response.
	 */
	private static Result classify(Throwable ex) {
		String message = messageChain(ex);
		if (!isDuplicateEntry(message)) {
			return Result.failure("employee_create_failed");
		}
		String key = duplicateKeyName(message);
		if (key.contains("phone")) {
			return Result.failure("phone_already_exists");
		}
		if (key.contains("employee_code") || "unique_employee_code_per_company".equals(key)) {
			return Result.failure("employee_code_already_exists");
		}
		return Result.failure("employee_create_failed");
	}

	/**
	 * {@code db_is_duplicate_entry()}: MySQL 1062 only. Deliberately not every
	 * SQLSTATE 23000, which also covers NOT NULL and foreign-key violations.
	 */
	private static boolean isDuplicateEntry(String message) {
		return message.contains("1062")
				|| message.toLowerCase(java.util.Locale.ROOT).contains("duplicate entry");
	}

	/** {@code db_duplicate_key_name()}: the last dot-separated part, lower-cased. */
	private static String duplicateKeyName(String message) {
		Matcher matcher = DUPLICATE_KEY.matcher(message);
		if (!matcher.find()) {
			return "";
		}
		String raw = matcher.group(1);
		int lastDot = raw.lastIndexOf('.');
		return (lastDot < 0 ? raw : raw.substring(lastDot + 1)).toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * PHP sees one {@code PDOException} message; Spring wraps the driver's
	 * exception, so the same text lives further down the chain. The whole chain
	 * is searched to keep the two equivalent.
	 */
	private static String messageChain(Throwable ex) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = ex; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				text.append(current.getMessage()).append('\n');
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return text.toString();
	}

	/** The salary contract's amounts, defaulting each missing key to 0 as PHP's {@code ?? 0} does. */
	private static Map<String, Object> salaryAmounts(Object raw) {
		Map<?, ?> salary = raw instanceof Map<?, ?> map ? map : Map.of();
		Map<String, Object> amounts = new LinkedHashMap<>();
		amounts.put("basic_salary", amount(salary, "basic"));
		amounts.put("transport_allowance", amount(salary, "transport"));
		amounts.put("food_allowance", amount(salary, "food_allowance"));
		amounts.put("risk_allowance", amount(salary, "risk_allowance"));
		amounts.put("incentives", amount(salary, "incentives"));
		amounts.put("insurance_deduction", amount(salary, "insurance_deduction"));
		amounts.put("tax_deduction", amount(salary, "tax_deduction"));
		amounts.put("advances_deduction", amount(salary, "advances_deduction"));
		amounts.put("fund_deduction", amount(salary, "fund_deduction"));
		amounts.put("penalty_deduction", amount(salary, "penalty_deduction"));
		return amounts;
	}

	private static double amount(Map<?, ?> salary, String key) {
		Object value = salary.get(key);
		return value == null ? 0.0d : LegacyValues.toPhpDecimal(value).doubleValue();
	}

	/** {@code $flag === true || $flag === 1 || $flag === '1' || $flag === 'true'}. */
	private static boolean isStrictlyTruthy(Object flag) {
		return Boolean.TRUE.equals(flag)
				|| Integer.valueOf(1).equals(flag) || Long.valueOf(1L).equals(flag)
				|| "1".equals(flag) || "true".equals(flag);
	}

	/** {@code resolve_employee_phone_and_country_code()}. */
	private record Phone(String phone, String countryCode) {
	}

	private Phone resolvePhone(Map<String, Object> body) {
		String rawPhone = trimmed(body.get("phone"));
		if (LegacyPhoneNumbers.digitsOnly(rawPhone).isEmpty()) {
			return new Phone(null, null);
		}
		String countryCode = LegacyPhoneNumbers.normalizeDialCode(trimmed(body.get("country_code")));
		if (countryCode.isEmpty()) {
			// fail() -> exit: the whole request ends here, mid-batch.
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "country_code"));
		}
		String phone = phoneNumbers.normalizeLocal(countryCode, rawPhone);
		if (!phoneNumbers.isValidLocal(countryCode, phone)) {
			throw new LegacyApiException(400, "invalid_phone_number");
		}
		return new Phone(phone, countryCode);
	}

	/** {@code normalize_optional_branch_id()}: null and the empty string are absent, and 0 is too. */
	private static Long normalizeOptionalBranchId(Object value) {
		if (value == null || "".equals(value)) {
			return null;
		}
		long id = LegacyValues.toPhpLong(value);
		return id > 0 ? id : null;
	}

	/** {@code !empty($body[$key]) ? (int) $body[$key] : null}. */
	private static Long positiveOrNull(Object value) {
		if (LegacyValues.isPhpEmpty(value)) {
			return null;
		}
		return LegacyValues.toPhpLong(value);
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

	private static String trimmed(Object value) {
		return LegacyValues.phpTrim(LegacyEmployeeSpreadsheetValues.asString(value));
	}

}
