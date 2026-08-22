package com.workin.legacy.employees;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyPhpArray;
import com.workin.legacy.LegacyPhpDateYear;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.uploads.LegacyFileUploads;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.authorization.LegacyHrPermissionEnforcer;
import com.workin.legacy.authorization.LegacyHrPermissionKey;
import com.workin.legacy.employees.LegacyEmployee.Role;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetAnalyzer;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetColumns;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetLookups;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeSpreadsheetReader;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeImporter;
import com.workin.legacy.employees.spreadsheet.LegacyEmployeeTemplate;
import com.workin.legacy.notifications.LegacyNotifications;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code employees/list.php} and {@code employees/one.php}, ported clause for
 * clause. Every filter keeps the exact PHP guard that admits it -- {@code !empty}
 * for the org filters and the date range, {@code isset} for {@code is_active},
 * a trimmed non-empty string for {@code search} -- because those guards are what
 * decide whether {@code 0}, {@code '0'} or {@code ''} filters or is ignored.
 */
@Service
public class LegacyEmployeeService {

	/** {@code preg_match('/^\d+$/', $search_needle)}. */
	private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");

	/** {@code /^[0-9]{1,64}$/} -- {@code is_valid_employee_code_format()}. */
	private static final Pattern EMPLOYEE_CODE_FORMAT = Pattern.compile("^[0-9]{1,64}$");

	/** {@code preg_replace('/\s+/u', ' ', $code)} in {@code normalize_employee_code()}. */
	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

	/** {@code $self_allowed} -- what an HR session may change about itself. */
	private static final java.util.Set<String> SELF_UPDATABLE_FIELDS = java.util.Set.of(
			"first_name", "last_name", "phone", "country_code", "address", "password");

	/** {@code $allowed_columns}, in {@code update.php}'s own order. */
	private static final List<String> UPDATABLE_COLUMNS = List.of(
			"employee_code", "first_name", "last_name", "phone", "country_code", "branch_id", "department_id",
			"job_title_id", "national_id", "birth_date", "gender", "address", "hire_date",
			"contract_duration_months", "expected_daily_hours", "is_mobile_attendance_enabled", "is_active");

	/** The same list plus the column {@code employees_has_column()} may add. */
	private static final List<String> UPDATABLE_COLUMNS_WITH_ANY_BRANCH = java.util.stream.Stream.concat(
			UPDATABLE_COLUMNS.stream(), java.util.stream.Stream.of("can_check_in_any_branch")).toList();

	/** Columns whose already-normalised value is bound as a number, not a PHP string cast. */
	private static final java.util.Set<String> NUMERIC_UPDATE_COLUMNS = java.util.Set.of(
			"branch_id", "department_id", "expected_daily_hours", "is_mobile_attendance_enabled",
			"is_active", "can_check_in_any_branch");

	private final LegacyEmployeeStore store;
	private final LegacyNotifications notifications;
	private final LegacyHrPermissionEnforcer permissionEnforcer;
	private final LegacyPhoneNumbers phoneNumbers;
	private final LegacyClock clock;
	private final LegacyFileUploads fileUploads;
	private final LegacyEmployeeSpreadsheetAnalyzer spreadsheetAnalyzer;
	private final LegacyEmployeeImporter importer;
	/**
	 * {@code password_hash($p, PASSWORD_BCRYPT)}. The shared bean is the same
	 * encoder legacy login already verifies with; it writes the {@code $2a$}
	 * bcrypt tag where PHP writes {@code $2y$}. The two are the same algorithm
	 * and verify each other's hashes in both directions, and the column is never
	 * serialized, so the tag is the one byte-level difference here.
	 */
	private final PasswordEncoder bcrypt;

	public LegacyEmployeeService(
			LegacyEmployeeStore store, LegacyNotifications notifications,
			LegacyHrPermissionEnforcer permissionEnforcer, LegacyPhoneNumbers phoneNumbers,
			LegacyClock clock, LegacyFileUploads fileUploads, PasswordEncoder bcrypt,
			LegacyEmployeeSpreadsheetAnalyzer spreadsheetAnalyzer, LegacyEmployeeImporter importer) {
		this.store = store;
		this.notifications = notifications;
		this.permissionEnforcer = permissionEnforcer;
		this.phoneNumbers = phoneNumbers;
		this.clock = clock;
		this.fileUploads = fileUploads;
		this.bcrypt = bcrypt;
		this.spreadsheetAnalyzer = spreadsheetAnalyzer;
		this.importer = importer;
	}

	/**
	 * {@code list.php}. The WHERE clauses are assembled in PHP's own order --
	 * company, roster, branch, department, search, manager, job title, active,
	 * date range -- so the generated SQL and its bound parameters line up with
	 * the original statement.
	 */
	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		LegacyPagination.Params pagination = paginationParams(query);

		List<String> where = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		where.add("e.company_id=?");
		params.add(context.companyId());
		where.add(store.rosterClause());

		Object branchId = query.value("branch_id");
		if (!LegacyValues.isPhpEmpty(branchId)) {
			where.add("e.branch_id=?");
			params.add(LegacyValues.toPhpLong(branchId));
		}

		Object departmentId = query.value("department_id");
		if (!LegacyValues.isPhpEmpty(departmentId)) {
			where.add("e.department_id=?");
			params.add(LegacyValues.toPhpLong(departmentId));
		}

		String search = searchQueryParam(query);
		if (search != null) {
			String pattern = "%" + search + "%";
			if (DIGITS_ONLY.matcher(search).matches()) {
				where.add("e.employee_code LIKE ?");
				params.add(pattern);
			} else {
				where.add("(" + store.displayNameExpression() + " LIKE ? OR e.employee_code LIKE ?)");
				params.add(pattern);
				params.add(pattern);
			}
		}

		if (context.role() == Role.MANAGER) {
			where.add(store.managerScopeClause());
			params.add(context.employeeId());
			params.add(context.companyId());
		}

		Object jobTitleId = query.value("job_title_id");
		if (!LegacyValues.isPhpEmpty(jobTitleId)) {
			where.add("e.job_title_id = ?");
			params.add(LegacyValues.toPhpLong(jobTitleId));
		}

		// isset(), not !empty(): '0' is a meaningful filter value here and
		// selects the inactive rows, where '0' would be dropped above.
		if (query.value("is_active") != null) {
			where.add("e.is_active = ?");
			params.add(LegacyValues.toPhpLong(query.value("is_active")));
		}

		String hireDateExpression = "DATE(COALESCE(e.hire_date, e.created_at))";
		// Request::DATE_FROM/DATE_TO are 'from'/'to' on the wire
		// (apis/config/request.php:26-27), not 'date_from'/'date_to'.
		Object from = query.value("from");
		if (!LegacyValues.isPhpEmpty(from)) {
			where.add(hireDateExpression + " >= ?");
			params.add(LegacyValues.toPhpString(from));
		}
		Object to = query.value("to");
		if (!LegacyValues.isPhpEmpty(to)) {
			where.add(hireDateExpression + " <= ?");
			params.add(LegacyValues.toPhpString(to));
		}

		String whereSql = String.join(" AND ", where);
		long total = store.count(whereSql, params);
		List<Map<String, Object>> rows = store.list(
				whereSql, orderSql(query), params, pagination.limit(), pagination.offset());
		return new Page(rows, paginationMeta(total, pagination));
	}

	/**
	 * {@code one.php}. The employee lookup is scoped by id <em>and</em> company,
	 * so a missing row and another tenant's row are the same 404 -- legacy's own
	 * behaviour here, not a Phase 1 divergence.
	 */
	public Map<String, Object> one(LegacyRequestContext context, long employeeId) {
		Map<String, Object> employee = store.findOne(employeeId, context.companyId());
		if (employee == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		if (context.role() == Role.MANAGER
				&& !store.managerCanAccessEmployeeBranch(context.employeeId(), employeeId, context.companyId())) {
			// 403 forbidden, not 404: legacy tells a manager the employee
			// exists and is out of scope.
			throw new LegacyApiException(403, "forbidden");
		}
		store.attachLatestSalaryContract(employee);
		store.attachLatestShiftAssignment(employee);
		return employee;
	}

	/**
	 * {@code employees/create.php}, in PHP's order: every validation runs, in
	 * sequence, <em>before</em> the transaction opens, so a rejected request
	 * never writes anything.
	 *
	 * <p>The ordering is observable and is preserved exactly -- phone before
	 * employee code, branch before department, department before job title --
	 * because a request that is wrong in two ways gets the first error PHP would
	 * have produced, not the first one a different order happens to hit.
	 */
	public Map<String, Object> create(LegacyRequestContext context, Map<String, Object> body) {
		// required($body, [...]) -- this exact order, isset() plus the exact
		// empty-string check, so 0, '0', false and [] all pass.
		requireFields(body, "first_name", "last_name", "employee_code", "shift_id", "expected_daily_hours");

		// resolve_employee_phone_and_country_code(): both null, or both set.
		String[] phoneAndCountry = resolvePhoneAndCountryCode(body);
		String phone = phoneAndCountry[0];
		String countryCode = phoneAndCountry[1];
		if (phone != null && store.phoneExistsGlobally(phone, null)) {
			throw new LegacyApiException(409, "phone_already_exists");
		}

		String employeeCode = normalizeEmployeeCode(body.get("employee_code"));
		if (employeeCode.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "employee_code"));
		}
		if (!EMPLOYEE_CODE_FORMAT.matcher(employeeCode).matches()) {
			throw new LegacyApiException(400, "employee_code_invalid", null, Map.of("field", "employee_code"));
		}
		if (store.employeeCodeExistsInCompany(context.companyId(), employeeCode, null)) {
			throw new LegacyApiException(409, "employee_code_already_exists");
		}

		// normalize_optional_branch_id(): null, '' and any non-positive cast all
		// mean "not supplied", which then selects the company default.
		Long branchId = normalizeOptionalBranchId(body.get("branch_id"));
		if (branchId != null) {
			if (!store.branchExistsInCompany(branchId, context.companyId())) {
				throw new LegacyApiException(404, "branch_not_found");
			}
		} else {
			branchId = store.companyDefaultActiveBranchId(context.companyId());
			if (branchId == null) {
				throw new LegacyApiException(404, "branch_not_found");
			}
		}

		Long departmentId = optionalPositiveId(body.get("department_id"));
		if (departmentId != null && !departmentValidForBranch(departmentId, branchId, context.companyId())) {
			throw new LegacyApiException(404, "department_not_found");
		}

		Long jobTitleId = optionalPositiveId(body.get("job_title_id"));
		// The second condition is PHP's: with no department, the job title is
		// not validated at all -- it is stored unchecked.
		if (jobTitleId != null && departmentId != null
				&& !jobTitleValidForDepartment(jobTitleId, departmentId, context.companyId())) {
			throw new LegacyApiException(404, "job_title_not_found");
		}

		// shift_id passed required() above, but !empty() turns '0' and 0 into
		// null here -- so a create can satisfy the required check and still
		// produce no shift assignment at all.
		Long shiftId = optionalPositiveId(body.get("shift_id"));
		String hireDate = body.get("hire_date") == null
				? clock.todayAsString()
				: LegacyValues.toPhpString(body.get("hire_date"));
		String shiftEffectiveFrom = body.get("shift_effective_from") != null
				? LegacyValues.toPhpString(body.get("shift_effective_from"))
				: hireDate;
		if (shiftId != null && !store.shiftBelongsToCompany(shiftId, context.companyId())) {
			throw new LegacyApiException(404, "shift_not_found");
		}

		double expectedHours = LegacyValues.toPhpDecimal(body.get("expected_daily_hours")).doubleValue();
		if (expectedHours <= 0) {
			throw new LegacyApiException(
					400, "invalid_input", null, Map.of("field", "expected_daily_hours"));
		}

		// employees_has_column() caches per PHP request; resolved once here and
		// passed down rather than cached on a singleton.
		boolean hasAnyBranchColumn = store.employeesHasColumn("can_check_in_any_branch");
		long newEmployeeId;
		try {
			final Long finalBranchId = branchId;
			final Long finalDepartmentId = departmentId;
			final Long finalJobTitleId = jobTitleId;
			final Long finalShiftId = shiftId;
			final String finalPhone = phone;
			newEmployeeId = store.inTransaction(() -> insertNewEmployee(
					context, body, finalPhone, countryCode, employeeCode, finalBranchId, finalDepartmentId,
					finalJobTitleId, finalShiftId, hireDate, shiftEffectiveFrom, expectedHours,
					hasAnyBranchColumn));
		} catch (Throwable ex) { // NOPMD - catch (Throwable $e), around the transaction only
			// catch (Throwable $e) { $pdo->rollBack(); fail(EMPLOYEE_CREATE_FAILED, 500, $e->getMessage()); }
			// -- the rollback is the transaction template's; the exception text
			// travels as the response's data payload, exactly as in PHP.
			// Throwable, not RuntimeException: PHP rolls back for anything at
			// all, and a half-written employee is worse than a lost stack trace.
			// The catch covers the transaction and nothing else -- validation
			// ran before it and the post-commit re-read runs after it.
			throw new LegacyApiException(500, "employee_create_failed", messageOf(ex));
		}

		// Deliberately outside any transaction, and deliberately id-only.
		Map<String, Object> employee = store.findByIdWithOrgLabels(newEmployeeId);
		if (employee == null) {
			throw new LegacyApiException(500, "employee_create_failed");
		}
		store.attachLatestSalaryContract(employee);
		store.attachLatestShiftAssignment(employee);
		return employee;
	}

	/** The transactional half: employee, then optional salary, then leave balance, then optional shift. */
	private long insertNewEmployee(
			LegacyRequestContext context, Map<String, Object> body, String phone, String countryCode,
			String employeeCode, Long branchId, Long departmentId, Long jobTitleId, Long shiftId,
			String hireDate, String shiftEffectiveFrom, double expectedHours, boolean hasAnyBranchColumn) {
		// password_hash() only when there is a phone to log in with AND a
		// non-blank password; either alone leaves the column null.
		String rawPassword = body.get("password") == null
				? "" : LegacyValues.toPhpString(body.get("password")).trim();
		String passwordHash = phone != null && !rawPassword.isEmpty()
				? bcrypt.encode(rawPassword) : null;

		Integer contractMonths = null;
		Object rawContractMonths = body.get("contract_duration_months");
		if (rawContractMonths != null && !"".equals(rawContractMonths)) {
			long months = LegacyValues.toPhpLong(rawContractMonths);
			contractMonths = months <= 0 ? null : (int) months;
		}

		Map<String, Object> columns = new LinkedHashMap<>();
		columns.put("company_id", context.companyId());
		columns.put("branch_id", branchId);
		columns.put("department_id", departmentId);
		columns.put("job_title_id", jobTitleId);
		columns.put("employee_code", employeeCode);
		columns.put("expected_daily_hours", expectedHours);
		// first_name/last_name bind raw here: unlike the bulk-import helper,
		// direct create does not trim them.
		columns.put("first_name", rawColumnValue(body.get("first_name")));
		columns.put("last_name", rawColumnValue(body.get("last_name")));
		columns.put("country_code", countryCode);
		columns.put("phone", phone);
		columns.put("password_hash", passwordHash);
		columns.put("role", "employee");
		columns.put("national_id", rawColumnValue(body.get("national_id")));
		columns.put("birth_date", rawColumnValue(body.get("birth_date")));
		columns.put("gender", rawColumnValue(body.get("gender")));
		columns.put("address", rawColumnValue(body.get("address")));
		columns.put("hire_date", hireDate);
		columns.put("contract_duration_months", contractMonths);
		columns.put("is_mobile_attendance_enabled", exactTruthFlag(body, "is_mobile_attendance_enabled", 1));
		columns.put("is_active", 1);
		if (hasAnyBranchColumn) {
			columns.put("can_check_in_any_branch", exactTruthFlag(body, "can_check_in_any_branch", 0));
		}
		long employeeId = store.insertEmployee(columns);

		Object salary = body.get("salary");
		if (!LegacyValues.isPhpEmpty(salary)) {
			store.insertSalaryContract(employeeId, salaryAmounts(salary), hireDate);
		}

		long leaveYear = body.get("leave_opening_year") != null
				? LegacyValues.toPhpLong(body.get("leave_opening_year"))
				: LegacyPhpDateYear.of(hireDate, clock.today());
		Object leaveTotal = body.get("leave_opening_days") != null
				? LegacyValues.toPhpDecimal(body.get("leave_opening_days")).doubleValue()
				: 21.0;
		long fromMonth = monthOrDefault(body.get("period_from_month"), 1);
		long toMonth = monthOrDefault(body.get("period_to_month"), 12);
		Object monthlyCap = body.get("monthly_cap_days") != null && !"".equals(body.get("monthly_cap_days"))
				? LegacyValues.toPhpDecimal(body.get("monthly_cap_days")).doubleValue()
				: null;
		if (store.leaveBalanceExists(employeeId, leaveYear)) {
			store.updateLeaveBalance(employeeId, leaveYear, leaveTotal, fromMonth, toMonth, monthlyCap);
		} else {
			store.insertLeaveBalance(employeeId, leaveYear, leaveTotal, fromMonth, toMonth, monthlyCap);
		}

		if (shiftId != null) {
			store.insertShiftAssignment(employeeId, shiftId, shiftEffectiveFrom);
		}
		return employeeId;
	}

	/**
	 * {@code required()} ({@code functions.php:617-623}): {@code isset()} plus
	 * the exact empty string. {@code 0}, {@code '0'}, {@code false} and
	 * {@code []} all pass -- this is not {@code empty()}.
	 */
	private static void requireFields(Map<String, Object> body, String... fields) {
		for (String field : fields) {
			Object value = body.get(field);
			if (value == null || "".equals(value)) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
			}
		}
	}

	/**
	 * {@code resolve_employee_phone_and_country_code()} ({@code functions.php:80-94}):
	 * a phone with no digits means no phone at all and no country code; any
	 * digits make the country code mandatory and the number validated.
	 */
	private String[] resolvePhoneAndCountryCode(Map<String, Object> body) {
		String rawPhone = LegacyValues.toPhpString(body.get("phone")).trim();
		if (LegacyPhoneNumbers.digitsOnly(rawPhone).isEmpty()) {
			return new String[] {null, null};
		}
		String countryCode = LegacyPhoneNumbers.normalizeDialCode(
				LegacyValues.toPhpString(body.get("country_code")).trim());
		if (countryCode.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "country_code"));
		}
		String phone = phoneNumbers.normalizeLocal(countryCode, rawPhone);
		if (!phoneNumbers.isValidLocal(countryCode, phone)) {
			throw new LegacyApiException(400, "invalid_phone_number");
		}
		return new String[] {phone, countryCode};
	}

	/**
	 * {@code normalize_employee_code()} ({@code functions.php:45-51}): trim, then
	 * collapse internal whitespace runs to a single space.
	 *
	 * <p>The parameter is {@code ?string} under {@code strict_types=1}, so a
	 * JSON number or boolean is a {@code TypeError} in PHP -- and PHP never
	 * catches it. That makes it an uncaught failure, not an application
	 * response, so it goes out through D-084's generic 500 rather than as a
	 * {@code LegacyApiException} carrying the technical text. The message here
	 * is for the server log only.
	 */
	private static String normalizeEmployeeCode(Object raw) {
		if (raw != null && !(raw instanceof CharSequence)) {
			throw new IllegalArgumentException(
					"normalize_employee_code(): Argument #1 ($code) must be of type ?string, "
							+ phpTypeName(raw) + " given");
		}
		String code = raw == null ? "" : raw.toString().trim();
		return code.isEmpty() ? "" : WHITESPACE_RUN.matcher(code).replaceAll(" ");
	}

	/** {@code normalize_optional_branch_id()} ({@code functions.php:946-952}). */
	private static Long normalizeOptionalBranchId(Object raw) {
		if (raw == null || "".equals(raw)) {
			return null;
		}
		long id = LegacyValues.toPhpLong(raw);
		return id > 0 ? id : null;
	}

	/** {@code !empty($body[x]) ? (int) $body[x] : null} -- the department/job-title/shift shape. */
	private static Long optionalPositiveId(Object raw) {
		return LegacyValues.isPhpEmpty(raw) ? null : LegacyValues.toPhpLong(raw);
	}

	/**
	 * {@code employee_department_valid_for_branch()} ({@code functions.php:1027-1035}),
	 * plus D-075's fail-closed company check.
	 *
	 * <p>PHP validates a department through {@code department_branches} alone
	 * when a branch is known -- no company predicate, no active check -- so a
	 * department belonging to another company would pass if it happened to share
	 * a junction row. D-075 approves exactly this narrow divergence: the
	 * foreign-company case fails closed, with the same 404 the missing case
	 * produces, and nothing else about the check changes. A same-tenant inactive
	 * or oddly linked department still behaves exactly as legacy does.
	 */
	private boolean departmentValidForBranch(long departmentId, Long branchId, long companyId) {
		if (store.departmentExistsInOtherCompany(departmentId, companyId)) {
			return false;
		}
		if (branchId != null && branchId > 0) {
			return store.departmentBelongsToBranch(departmentId, branchId);
		}
		return store.departmentBelongsToCompany(departmentId, companyId);
	}

	/**
	 * {@code job_title_belongs_to_department()} plus D-075: the PHP check carries
	 * no company predicate, so a foreign-company job title is rejected here even
	 * though legacy would have stored it.
	 */
	private boolean jobTitleValidForDepartment(long jobTitleId, long departmentId, long companyId) {
		if (store.jobTitleExistsInOtherCompany(jobTitleId, companyId)) {
			return false;
		}
		return store.jobTitleBelongsToDepartment(jobTitleId, departmentId);
	}

	/**
	 * The create-side boolean rule, which is an exact-value test rather than a
	 * cast: only {@code true}, {@code 1}, {@code '1'} and {@code 'true'} mean
	 * on. Every other present value -- including {@code 'yes'}, {@code 'TRUE'}
	 * and {@code 2} -- means off, and an absent key keeps the column default.
	 */
	private static int exactTruthFlag(Map<String, Object> body, String field, int defaultValue) {
		if (!body.containsKey(field)) {
			return defaultValue;
		}
		Object flag = body.get(field);
		boolean on = Boolean.TRUE.equals(flag)
				|| (flag instanceof Number number && number.intValue() == 1 && number.doubleValue() == 1.0d)
				|| "1".equals(flag)
				|| "true".equals(flag);
		return on ? 1 : 0;
	}

	/** {@code (float) ($salary[x] ?? 0)} for each amount create.php binds. */
	private static Map<String, Object> salaryAmounts(Object salary) {
		Map<?, ?> values = salary instanceof Map<?, ?> map ? map : Map.of();
		Map<String, Object> amounts = new LinkedHashMap<>();
		amounts.put("basic_salary", phpFloat(values.get("basic")));
		amounts.put("transport_allowance", phpFloat(values.get("transport")));
		amounts.put("food_allowance", phpFloat(values.get("food_allowance")));
		amounts.put("risk_allowance", phpFloat(values.get("risk_allowance")));
		amounts.put("incentives", phpFloat(values.get("incentives")));
		amounts.put("insurance_deduction", phpFloat(values.get("insurance_deduction")));
		amounts.put("tax_deduction", phpFloat(values.get("tax_deduction")));
		amounts.put("advances_deduction", phpFloat(values.get("advances_deduction")));
		amounts.put("fund_deduction", phpFloat(values.get("fund_deduction")));
		amounts.put("penalty_deduction", phpFloat(values.get("penalty_deduction")));
		return amounts;
	}

	private static double phpFloat(Object raw) {
		return raw == null ? 0.0d : LegacyValues.toPhpDecimal(raw).doubleValue();
	}

	/** {@code isset($x) && $x !== '' ? (int) $x : $default} for the leave period months. */
	private static long monthOrDefault(Object raw, long defaultValue) {
		return raw == null || "".equals(raw) ? defaultValue : LegacyValues.toPhpLong(raw);
	}

	/**
	 * A body value bound straight to a PDO parameter. D-071 measured what that
	 * stores for non-string JSON values -- {@code false} becomes {@code ""},
	 * {@code true} becomes {@code "1"}, an array or object becomes
	 * {@code "Array"} -- which is exactly {@link LegacyValues#toPhpString}.
	 * {@code null} stays SQL NULL.
	 */
	private static Object rawColumnValue(Object raw) {
		return raw == null ? null : LegacyValues.toPhpString(raw);
	}

	private static String phpTypeName(Object value) {
		if (value instanceof Boolean) {
			return "bool";
		}
		if (value instanceof Integer || value instanceof Long) {
			return "int";
		}
		if (value instanceof Number) {
			return "float";
		}
		return value instanceof Map<?, ?> || value instanceof java.util.List<?> ? "array" : "object";
	}

	private static String messageOf(Throwable ex) {
		return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
	}

	/**
	 * {@code employees/update.php}. Read from the PHP source in its own right --
	 * it is not create's mirror image, and the places where it differs are the
	 * places most likely to be normalised away by accident.
	 *
	 * <p>The endpoint mutates {@code $body} as it validates, then writes exactly
	 * the keys that survive. That is why the order below matters: branch removes
	 * its own key when it resolves to nothing, department keeps a null, and the
	 * "is there anything to do" question is asked twice with different answers.
	 */
	public UpdateOutcome update(
			LegacyRequestContext context, long employeeId, Map<String, Object> requestBody) {
		// empty($body): an empty object, and only an empty one, stops here.
		if (LegacyValues.isPhpEmpty(requestBody)) {
			throw new LegacyApiException(400, "nothing_to_update");
		}
		Map<String, Object> body = new LinkedHashMap<>(requestBody);

		// The employee is read before any permission decision, and the read is
		// company-scoped, so another tenant's id is a 404 rather than a 403.
		Map<String, Object> employee = store.findOne(employeeId, context.companyId());
		if (employee == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}

		// is_hr_session also names MANAGER, but requireAuth already rejected
		// managers, so only HR reaches either branch. Changing somebody else
		// needs can_employees; changing yourself does not, and instead reduces
		// the body to six fields before anything else looks at it.
		boolean hrSession = context.role() == Role.HR;
		if (hrSession && employeeId != context.employeeId()
				&& !permissionEnforcer.has(LegacyHrPermissionKey.CAN_EMPLOYEES)) {
			// require_hr_permission() fails with LangKey::FORBIDDEN, so the
			// message is legacy's 'forbidden', not the platform's error.forbidden.
			throw new LegacyApiException(403, "forbidden");
		}
		if (hrSession && employeeId == context.employeeId()) {
			body.keySet().retainAll(SELF_UPDATABLE_FIELDS);
		}

		// Branch: a request value that normalises to nothing removes the key
		// entirely, so an existing branch is never cleared. A supplied branch
		// must belong to this company.
		Long targetBranchId = body.containsKey("branch_id")
				? normalizeOptionalBranchId(body.get("branch_id"))
				: normalizeOptionalBranchId(employee.get("branch_id"));
		if (body.containsKey("branch_id")) {
			if (targetBranchId != null) {
				if (!store.branchExistsInCompany(targetBranchId, context.companyId())) {
					throw new LegacyApiException(404, "branch_not_found");
				}
				body.put("branch_id", targetBranchId);
			} else {
				body.remove("branch_id");
			}
		}

		// Department: the same normaliser, but the null is kept -- so a
		// department can be cleared where a branch cannot.
		Long targetDepartmentId = body.containsKey("department_id")
				? normalizeOptionalBranchId(body.get("department_id"))
				: normalizeOptionalBranchId(employee.get("department_id"));
		if (body.containsKey("department_id")) {
			if (targetDepartmentId != null
					&& !departmentValidForBranch(targetDepartmentId, targetBranchId, context.companyId())) {
				throw new LegacyApiException(404, "department_not_found");
			}
			body.put("department_id", targetDepartmentId);
		}

		// Job title: an (int) cast, never a null -- so clearing it while a
		// department is in play validates 0 against that department and 404s.
		// The value stored is the raw body value, not this cast.
		long targetJobTitleId = body.containsKey("job_title_id")
				? LegacyValues.toPhpLong(body.get("job_title_id"))
				: LegacyValues.toPhpLong(employee.get("job_title_id"));
		if (body.containsKey("job_title_id") && targetDepartmentId != null
				&& !jobTitleValidForDepartment(targetJobTitleId, targetDepartmentId, context.companyId())) {
			throw new LegacyApiException(404, "job_title_not_found");
		}

		// isset() plus a positive cast: shift_id = 0 or null validates nothing.
		Long shiftId = body.get("shift_id") == null ? null : LegacyValues.toPhpLong(body.get("shift_id"));
		if (shiftId != null && shiftId > 0 && !store.shiftBelongsToCompany(shiftId, context.companyId())) {
			throw new LegacyApiException(404, "shift_not_found");
		}

		// Phone: normalize_employee_phone(), which only strips to digits. This
		// is NOT create's country-aware resolver -- no country normalisation and
		// no validity check, so update accepts numbers create would reject.
		if (body.containsKey("phone")) {
			String newPhone = normalizeEmployeePhone(body.get("phone"));
			if (newPhone != null && store.phoneExistsGlobally(newPhone, employeeId)) {
				throw new LegacyApiException(409, "phone_already_exists");
			}
			body.put("phone", newPhone);
			if (newPhone == null) {
				body.put("country_code", null);
			} else {
				String countryCode = LegacyValues.toPhpString(body.get("country_code")).trim();
				if (countryCode.isEmpty()) {
					throw new LegacyApiException(400, "field_required", null, Map.of("field", "country_code"));
				}
				body.put("country_code", countryCode);
			}
		}

		if (body.containsKey("employee_code")) {
			String newCode = normalizeEmployeeCode(body.get("employee_code"));
			if (newCode.isEmpty()) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "employee_code"));
			}
			if (!EMPLOYEE_CODE_FORMAT.matcher(newCode).matches()) {
				throw new LegacyApiException(400, "employee_code_invalid", null, Map.of("field", "employee_code"));
			}
			if (store.employeeCodeExistsInCompany(context.companyId(), newCode, employeeId)) {
				throw new LegacyApiException(409, "employee_code_already_exists");
			}
			body.put("employee_code", newCode);
		}

		boolean hasAnyBranchColumn = store.employeesHasColumn("can_check_in_any_branch");
		if (body.containsKey("is_mobile_attendance_enabled")) {
			body.put("is_mobile_attendance_enabled", exactTruthFlag(body, "is_mobile_attendance_enabled", 0));
		}
		if (hasAnyBranchColumn && body.containsKey("can_check_in_any_branch")) {
			body.put("can_check_in_any_branch", exactTruthFlag(body, "can_check_in_any_branch", 0));
		}
		if (body.containsKey("is_active")) {
			body.put("is_active", exactTruthFlag(body, "is_active", 0));
		}
		if (body.containsKey("expected_daily_hours")) {
			double hours = LegacyValues.toPhpDecimal(body.get("expected_daily_hours")).doubleValue();
			if (hours <= 0) {
				throw new LegacyApiException(400, "invalid_input", null, Map.of("field", "expected_daily_hours"));
			}
			body.put("expected_daily_hours", hours);
		}

		// Only these columns are writable, and unknown keys are simply ignored.
		Map<String, Object> updates = new LinkedHashMap<>();
		for (String column : hasAnyBranchColumn ? UPDATABLE_COLUMNS_WITH_ANY_BRANCH : UPDATABLE_COLUMNS) {
			if (body.containsKey(column)) {
				updates.put(column, updatableColumnValue(column, body.get(column)));
			}
		}
		if (body.containsKey("password")) {
			String plainPassword = LegacyValues.toPhpString(body.get("password")).trim();
			if (!plainPassword.isEmpty()) {
				updates.put("password_hash", bcrypt.encode(plainPassword));
			}
		}

		// The second "nothing to update", and the interesting one: the presence
		// of a shift_id key -- any value, including 0 -- or a non-empty salary
		// of any type gets past it, even though neither may write anything.
		if (updates.isEmpty() && !body.containsKey("shift_id") && LegacyValues.isPhpEmpty(body.get("salary"))) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		long previousJobTitleId = LegacyValues.toPhpLong(employee.get("job_title_id"));
		String hireDateForSalary = body.get("hire_date") != null
				? LegacyValues.toPhpString(body.get("hire_date"))
				: employee.get("hire_date") != null
						? LegacyValues.toPhpString(employee.get("hire_date"))
						: clock.todayAsString();
		String shiftEffectiveFrom = body.get("shift_effective_from") != null
				? LegacyValues.toPhpString(body.get("shift_effective_from"))
				: clock.todayAsString();
		Object salary = body.get("salary");
		try {
			store.inTransaction(() -> {
				store.updateEmployeeColumns(employeeId, context.companyId(), updates);
				// Appended every time, never replacing or de-duplicating.
				if (shiftId != null && shiftId > 0) {
					store.insertShiftAssignment(employeeId, shiftId, shiftEffectiveFrom);
				}
				// is_array() as well as non-empty, and only for an employee with
				// no contract at all -- update never adds a second one.
				if (!LegacyValues.isPhpEmpty(salary) && salary instanceof Map<?, ?>
						&& store.countSalaryContracts(employeeId) == 0) {
					store.insertSalaryContract(employeeId, salaryAmounts(salary), hireDateForSalary);
				}
				return null;
			});
		} catch (Throwable ex) { // NOPMD - catch (Throwable $e), around the transaction only
			// fail(ERROR_WITH_MESSAGE, 500, $e->getMessage()) -- a different key
			// from create's, and the same exception-text-as-data shape.
			throw new LegacyApiException(500, "error_with_message", messageOf(ex));
		}

		Map<String, Object> updated = store.findByIdWithOrgLabels(employeeId);
		if (updated != null) {
			store.attachLatestSalaryContract(updated);
			store.attachLatestShiftAssignment(updated);
		}
		return new UpdateOutcome(
				updated,
				updated != null && body.containsKey("job_title_id")
						&& LegacyValues.toPhpLong(body.get("job_title_id")) != previousJobTitleId,
				updated != null && shiftId != null && shiftId > 0 ? shiftId : null);
	}

	/**
	 * The two notifications {@code update.php} sends after the transaction has
	 * already committed.
	 *
	 * <p>They are deliberately outside it: a failure here returns 500 with the
	 * employee update already durable, which is legacy's behaviour and not
	 * something to "fix" by widening the transaction. The controller calls this
	 * because the message text depends on the request locale.
	 */
	public void notifyAfterUpdate(
			LegacyRequestContext context, long employeeId, UpdateOutcome outcome,
			String jobTitleTitle, String jobTitleBody, String shiftTitle, String shiftBody) {
		if (outcome.jobTitleChanged()) {
			notifications.toEmployee(
					context.companyId(), employeeId, context.employeeId(), "job_title_changed",
					jobTitleTitle, jobTitleBody);
		}
		if (outcome.assignedShiftId() != null) {
			notifications.toEmployee(
					context.companyId(), employeeId, context.employeeId(), "schedule_assigned",
					shiftTitle, shiftBody, "shift", outcome.assignedShiftId());
		}
	}

	/** {@code normalize_employee_phone()} ({@code functions.php:70-73}): digits, or null. */
	private static String normalizeEmployeePhone(Object raw) {
		String digits = LegacyPhoneNumbers.digitsOnly(LegacyValues.toPhpString(raw).trim());
		return digits.isEmpty() ? null : digits;
	}

	/**
	 * The value the dynamic UPDATE binds. The normalised keys were written back
	 * into the body already; everything else binds the way PDO would (D-071).
	 */
	private static Object updatableColumnValue(String column, Object value) {
		if (value == null) {
			return null;
		}
		if (NUMERIC_UPDATE_COLUMNS.contains(column) && value instanceof Number number) {
			return number;
		}
		return LegacyValues.toPhpString(value);
	}

	/** What {@code update.php} produced, so the controller can send the right notifications. */
	public record UpdateOutcome(Map<String, Object> employee, boolean jobTitleChanged, Long assignedShiftId) {
	}

	/**
	 * {@code employees/upload_photo.php}, after the controller has resolved the
	 * target and applied the employee-role self restriction.
	 *
	 * <p>The order is legacy's, and it is the part worth preserving: the file is
	 * stored first, then the scoped update runs, then the row is read back. The
	 * target's existence is never checked, so a missing or foreign id leaves a
	 * stored file, a zero-row update and a null re-read -- and PHP then hands
	 * that null to {@code public_row(array $row)}, which is a {@code TypeError}
	 * it does not catch. Nothing deletes the file, here or there.
	 */
	public Map<String, Object> uploadPhoto(
			LegacyRequestContext context, long targetEmployeeId, MultipartFile file) {
		String photoUrl = fileUploads.store(file, "photos");
		if (photoUrl == null) {
			throw new LegacyApiException(400, "no_file_uploaded");
		}
		store.updatePhotoUrl(targetEmployeeId, context.companyId(), photoUrl);
		Map<String, Object> employee = store.findWithOrgLabels(targetEmployeeId, context.companyId());
		if (employee == null) {
			// public_row(null): an uncaught TypeError in PHP, so D-084's generic
			// 500 -- with the uploaded file left exactly where it was written.
			throw new IllegalStateException(
					"public_row(): Argument #1 ($row) must be of type array, null given");
		}
		return employee;
	}

	/**
	 * {@code employees/delete_preview.php}. The employee's existence is proven
	 * first, company-scoped, so a foreign id 404s before a single related-record
	 * count is taken -- the counts themselves carry no company predicate, which
	 * is exactly why the check has to come first.
	 */
	public List<LegacyEmployeeStore.RelatedRecordCount> deletePreview(
			LegacyRequestContext context, long employeeId) {
		if (!store.employeeExistsInCompany(employeeId, context.companyId())) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		return store.relatedRecordCounts(employeeId);
	}

	/**
	 * {@code employees/delete.php}.
	 *
	 * <p>Three outcomes, and the difference between them is the whole endpoint:
	 * related records without {@code cascade} is a 409 that writes nothing;
	 * related records with {@code cascade} runs the helper's transaction; no
	 * related records at all runs a single unwrapped delete that deliberately
	 * leaves {@code departments.manager_id} alone (D-077).
	 *
	 * <p>PHP computes the summary twice on the 409 path -- once to decide, and
	 * again inside {@code employee_delete_preview_payload()} to build the body.
	 * That is reproduced rather than optimised away: the second read can see a
	 * different database than the first, and collapsing them would change what
	 * a concurrent write makes the client see.
	 */
	public DeleteOutcome delete(LegacyRequestContext context, long employeeId, boolean cascade) {
		Map<String, Object> employee = store.findOne(employeeId, context.companyId());
		if (employee == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}

		List<LegacyEmployeeStore.RelatedRecordCount> related = nonZero(store.relatedRecordCounts(employeeId));
		if (!related.isEmpty() && !cascade) {
			throw new LegacyDeleteBlockedException(store.relatedRecordCounts(employeeId));
		}
		if (!related.isEmpty()) {
			// The helper rethrows whatever the transaction threw, and delete.php
			// does not translate it -- see the note on the wire boundary in
			// LegacyEmployeeController.
			return new DeleteOutcome(nonZero(cascadeDeleteRelated(employeeId, context.companyId())));
		}
		store.deleteEmployeeUnscopedOfAnyTransaction(employeeId, context.companyId());
		return new DeleteOutcome(null);
	}

	/**
	 * {@code employee_cascade_delete_related()}
	 * ({@code employee_delete_helper.php:66-133}), statement for statement.
	 *
	 * <p>The preview is taken <b>before</b> the transaction opens, and it is
	 * that snapshot the response reports -- not how many rows each statement
	 * actually removed. The table list is the helper's own (D-078): nothing is
	 * inferred from foreign keys, and nothing is added.
	 */
	private List<LegacyEmployeeStore.RelatedRecordCount> cascadeDeleteRelated(long employeeId, long companyId) {
		List<LegacyEmployeeStore.RelatedRecordCount> preview = store.relatedRecordCounts(employeeId);
		return store.inTransaction(() -> {
			store.deleteNotificationsFor(employeeId);
			for (String table : store.cascadeTables()) {
				store.deleteByEmployeeId(table, employeeId);
			}
			store.clearDepartmentManager(employeeId, companyId);
			if (store.deleteEmployeeScoped(employeeId, companyId) != 1) {
				// throw new RuntimeException('employee_delete_failed') -- the
				// guard that stops the history from going without the employee.
				throw new IllegalStateException("employee_delete_failed");
			}
			return preview;
		});
	}

	/** {@code $items[] = ...} only when {@code $count > 0}. */
	private static List<LegacyEmployeeStore.RelatedRecordCount> nonZero(
			List<LegacyEmployeeStore.RelatedRecordCount> counts) {
		return counts.stream().filter(count -> count.count() > 0).toList();
	}

	/** Either the cascade's pre-delete preview, or nothing at all for the direct path. */
	public record DeleteOutcome(List<LegacyEmployeeStore.RelatedRecordCount> deletedRelatedRecords) {
	}

	/**
	 * {@code fail(CANNOT_DELETE_EMPLOYEE_HAS_RECORDS, 409, employee_delete_preview_payload($id))}.
	 * Carries the second summary so the controller can render it with translated
	 * labels.
	 */
	public static class LegacyDeleteBlockedException extends RuntimeException {

		private final transient List<LegacyEmployeeStore.RelatedRecordCount> preview;

		public LegacyDeleteBlockedException(List<LegacyEmployeeStore.RelatedRecordCount> preview) {
			super("cannot_delete_employee_has_records");
			this.preview = preview;
		}

		public List<LegacyEmployeeStore.RelatedRecordCount> getPreview() {
			return preview;
		}

	}

	/**
	 * {@code deactivate.php}. The order is legacy's and it is observable: the
	 * scoped {@code UPDATE} runs first, the existence check second, so a missing
	 * or foreign id performs a zero-row write and then 404s.
	 *
	 * <p>The notification insert happens <em>after</em> the response row is
	 * read, outside any transaction -- PHP has none here -- so a failure at that
	 * point returns 500 with the employee already deactivated. That asymmetry is
	 * reproduced rather than smoothed over (D-078's rule for evidenced
	 * transaction boundaries), and it is why this method does not wrap the two
	 * writes together. {@link LegacyNotifications} then attempts the push and
	 * swallows its failure, exactly where PHP does -- see hr-platform#22 for the
	 * delivery itself, which Wave 12.4 does not implement.
	 *
	 * @param title the already-translated notification title -- {@code t()} runs
	 *        in the caller's locale, so what lands in the row depends on the
	 *        request that triggered it, exactly as in PHP
	 */
	public Map<String, Object> deactivate(LegacyRequestContext context, long employeeId, String title, String body) {
		store.setActive(employeeId, context.companyId(), 0);
		Map<String, Object> employee = store.findWithOrgLabels(employeeId, context.companyId());
		if (employee == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		notifications.toEmployee(
				context.companyId(), employeeId, context.employeeId(), "employee_deactivated", title, body);
		return employee;
	}

	/** {@code reactivate.php}: the same shape, with no notification of any kind. */
	public Map<String, Object> reactivate(LegacyRequestContext context, long employeeId) {
		store.setActive(employeeId, context.companyId(), 1);
		Map<String, Object> employee = store.findWithOrgLabels(employeeId, context.companyId());
		if (employee == null) {
			throw new LegacyApiException(404, "employee_not_found");
		}
		return employee;
	}

	/**
	 * {@code sort=employee_code} is matched exactly -- any other value, including
	 * {@code employee_code } with whitespace or a different case, falls to the
	 * default ordering.
	 */
	private static String orderSql(LegacyQueryParameters query) {
		String sort = LegacyValues.toPhpString(query.value("sort"));
		if ("employee_code".equals(sort)) {
			return "CAST(NULLIF(e.employee_code, '') AS UNSIGNED) ASC, e.employee_code ASC, e.id ASC";
		}
		return "e.created_at DESC, e.id DESC";
	}

	/** {@code search_query_param()} ({@code helpers/pagination.php:44-47}). */
	static String searchQueryParam(LegacyQueryParameters query) {
		String value = LegacyValues.toPhpString(query.value("search")).trim();
		return value.isEmpty() ? null : value;
	}

	/** {@code pagination_params()} -- see {@link LegacyPagination#params}. */
	static LegacyPagination.Params paginationParams(LegacyQueryParameters query) {
		return LegacyPagination.params(query);
	}

	/**
	 * {@code employees/template_excel.php}: the lookups, then the template, as
	 * the bytes and headers the response is made of.
	 *
	 * <p>{@code $format = strtolower(trim((string) ($_GET['format'] ?? 'xlsx')))}
	 * and then a single {@code === 'csv'} test, so CSV is the exception and
	 * everything else -- a missing parameter, {@code xlsx}, {@code XLSX}, an
	 * empty value, a typo -- is an XLSX. Reproduced as the same one-sided test
	 * rather than as a format whitelist, because a whitelist would turn the typo
	 * into an error legacy never raises.
	 */
	public Template template(LegacyRequestContext context, LegacyQueryParameters query) {
		// `?? 'xlsx'` is a null coalesce, not an empty check: `?format=` arrives as
		// the empty string, stays empty, and so is simply not "csv".
		Object rawFormat = query.value("format");
		String format = LegacyValues.mbStrToLower(
				LegacyValues.phpTrim(rawFormat == null ? "xlsx" : LegacyValues.toPhpString(rawFormat)));

		LegacyEmployeeSpreadsheetLookups lookups = lookups(context.companyId());
		List<String> headers = LegacyEmployeeSpreadsheetColumns.templateHeaders(
				lookups.firstShiftName(), lookups.firstBranchName(),
				lookups.firstDepartmentName(), lookups.firstJobTitleName(),
				clock.todayAsString());

		boolean csv = "csv".equals(format);
		return new Template(
				csv ? LegacyEmployeeTemplate.csv(headers) : LegacyEmployeeTemplate.xlsx(headers),
				csv ? LegacyEmployeeTemplate.CSV_CONTENT_TYPE : LegacyEmployeeTemplate.XLSX_CONTENT_TYPE,
				LegacyEmployeeTemplate.filename(clock.todayAsString(), csv));
	}

	/**
	 * {@code employee_excel_build_lookups($company_id)}: the four name-to-id maps
	 * the template's examples and the analyzer's matching are both built from.
	 */
	public LegacyEmployeeSpreadsheetLookups lookups(long companyId) {
		return new LegacyEmployeeSpreadsheetLookups(
				store.spreadsheetLookup("branches", companyId, true),
				store.spreadsheetLookup("departments", companyId, true),
				store.spreadsheetLookup("job_titles", companyId, true),
				// Shifts alone are not filtered on is_active -- legacy's asymmetry.
				store.spreadsheetLookup("shifts", companyId, false));
	}

	/** One {@code template_excel.php} response: the file, its type and its name. */
	public record Template(byte[] content, String contentType, String filename) {
	}

	/**
	 * {@code employees/analyze_excel.php}: the uploaded file, analyzed.
	 *
	 * <p>The missing-file check is legacy's
	 * {@code !isset($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK},
	 * which covers both no part at all and a part the upload itself failed on.
	 *
	 * <p>A {@code RuntimeException} out of the helper is <em>not</em> an
	 * uncaught error: {@code analyze_excel.php} catches it and passes its message
	 * through as the API message with a 400. That is why it is translated here
	 * into a {@link LegacyApiException} carrying the literal text, rather than
	 * being left to D-084's deterministic 500.
	 */
	public Map<String, Object> analyzeSpreadsheet(
			LegacyRequestContext context, MultipartFile file, boolean arabic) {
		if (file == null || file.isEmpty()) {
			throw new LegacyApiException(400, "no_file_uploaded");
		}
		byte[] content;
		try {
			content = file.getBytes();
		} catch (java.io.IOException ex) {
			// The part exists but could not be read -- legacy's upload error.
			throw new LegacyApiException(400, "no_file_uploaded");
		}

		try {
			return spreadsheetAnalyzer.analyze(content, context.companyId(), arabic, lookups(context.companyId()));
		} catch (LegacyEmployeeSpreadsheetReader.LegacySpreadsheetException ex) {
			// fail($e->getMessage(), 400) still goes through t(), and t() returns
			// an unknown key unchanged -- so the helper's own sentence is what the
			// client reads, which LegacyMessages reproduces for free.
			throw new LegacyApiException(400, ex.getMessage());
		}
	}

	/**
	 * {@code employees/import_bulk.php}: reviewed rows, imported one at a time.
	 *
	 * <p>{@code $rows = $body[Request::ROWS] ?? $body['rows'] ?? []} reads the
	 * same key twice -- {@code Request::ROWS} <em>is</em> {@code 'rows'} -- so
	 * there is one lookup, and a {@code null} value coalesces to the empty array
	 * exactly as a missing key does.
	 *
	 * <p>After this guard nothing else changes the HTTP status: every row-level
	 * problem is reported inside the result with a 200.
	 */
	public Map<String, Object> importSpreadsheetRows(
			LegacyRequestContext context, Map<String, Object> body) {
		Object raw = body.get("rows");
		if (!LegacyPhpArray.isArray(raw)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "rows"));
		}
		LegacyPhpArray rows = raw == null ? LegacyPhpArray.empty() : LegacyPhpArray.of(raw);
		if (rows.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "rows"));
		}
		// Lookups are built once for the whole batch, not once per row.
		return importer.importRows(context.companyId(), rows, lookups(context.companyId()));
	}

	/**
	 * {@code employees/stats.php}: five aggregates over one shared predicate.
	 *
	 * <p>The predicate is built once and reused for all five queries, so a filter
	 * narrows the gender counts and the tenure average as well as the total --
	 * they are not independent snapshots of the company.
	 *
	 * <p>The filters are legacy's own mix: {@code !empty()} for the three ids and
	 * the two dates, but {@code isset()} for {@code is_active}, so
	 * {@code ?is_active=0} filters where {@code ?branch_id=0} does not.
	 *
	 * <p><b>D-083 is not closed by this.</b> {@code CURDATE()} in the
	 * new-this-month and tenure queries is evaluated by the database on the
	 * connection's timezone, which Phase 1 does not yet set.
	 */
	public Map<String, Object> stats(LegacyRequestContext context, LegacyQueryParameters query) {
		List<String> where = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		where.add("e.company_id=?");
		params.add(context.companyId());
		where.add(store.rosterClause());

		for (String filter : List.of("branch_id", "department_id", "job_title_id")) {
			Object value = query.value(filter);
			if (!LegacyValues.isPhpEmpty(value)) {
				where.add("e." + filter + "=?");
				params.add(LegacyValues.toPhpLong(value));
			}
		}

		// isset(), not !empty(): is_active=0 is a filter here.
		if (query.value("is_active") != null) {
			where.add("e.is_active=?");
			params.add(LegacyValues.toPhpLong(query.value("is_active")));
		}

		String hireDateExpression = "DATE(COALESCE(e.hire_date, e.created_at))";
		if (!LegacyValues.isPhpEmpty(query.value("from"))) {
			where.add(hireDateExpression + " >= ?");
			params.add(LegacyValues.toPhpString(query.value("from")));
		}
		if (!LegacyValues.isPhpEmpty(query.value("to"))) {
			where.add(hireDateExpression + " <= ?");
			params.add(LegacyValues.toPhpString(query.value("to")));
		}

		if (context.role() == Role.MANAGER) {
			where.add(store.managerScopeClause());
			params.add(context.employeeId());
			params.add(context.companyId());
		}

		String whereSql = String.join(" AND ", where);
		long total = store.count(whereSql, params);
		long male = store.count(whereSql + " AND LOWER(TRIM(e.gender)) = 'male'", params);
		long female = store.count(whereSql + " AND LOWER(TRIM(e.gender)) = 'female'", params);
		long newThisMonth = store.count(whereSql + """
				 AND (
					(e.hire_date IS NOT NULL
					 AND YEAR(e.hire_date) = YEAR(CURDATE())
					 AND MONTH(e.hire_date) = MONTH(CURDATE()))
					OR (e.hire_date IS NULL
						AND YEAR(e.created_at) = YEAR(CURDATE())
						AND MONTH(e.created_at) = MONTH(CURDATE()))
				)""", params);

		Double averageTenure = store.averageTenureMonths(whereSql, params);
		// round($raw, 1), and a null average is 0.0 rather than an absent key.
		double tenure = averageTenure == null
				? 0.0d
				: java.math.BigDecimal.valueOf(averageTenure)
						.setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();

		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("total_employees", total);
		stats.put("male_count", male);
		stats.put("female_count", female);
		stats.put("new_this_month", newThisMonth);
		stats.put("avg_tenure_months", tenure);
		return stats;
	}

	/**
	 * {@code employees/my_team.php}: the manager's own branch.
	 *
	 * <p>Manager-only, and scoped by the manager's <em>stored</em> branch rather
	 * than by anything in the request. There is no roster predicate here, so a
	 * pending join request appears on the team even though {@code list.php} hides
	 * it -- legacy's asymmetry, reproduced.
	 */
	public List<Map<String, Object>> myTeam(LegacyRequestContext context) {
		return store.myTeam(context.companyId(), context.employeeId());
	}

	/** {@code pagination_meta()} -- see {@link LegacyPagination#meta}. */
	static Map<String, Object> paginationMeta(long total, LegacyPagination.Params pagination) {
		return LegacyPagination.meta(total, pagination);
	}

	/** One {@code list.php} response: {@code data} rows plus its {@code meta}. */
	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

}
