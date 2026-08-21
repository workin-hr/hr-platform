package com.workin.legacy.employees;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpDateYear;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee.Role;
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

	/** {@code AppConfig::DEFAULT_LIMIT} / {@code pagination_params($default, 100)}. */
	private static final long DEFAULT_LIMIT = 20;
	private static final long MAX_LIMIT = 100;

	/** {@code preg_match('/^\d+$/', $search_needle)}. */
	private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");

	/** {@code /^[0-9]{1,64}$/} -- {@code is_valid_employee_code_format()}. */
	private static final Pattern EMPLOYEE_CODE_FORMAT = Pattern.compile("^[0-9]{1,64}$");

	/** {@code preg_replace('/\s+/u', ' ', $code)} in {@code normalize_employee_code()}. */
	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

	private final LegacyEmployeeStore store;
	private final LegacyNotifications notifications;
	private final LegacyPhoneNumbers phoneNumbers;
	private final LegacyClock clock;
	/**
	 * {@code password_hash($p, PASSWORD_BCRYPT)}. The shared bean is the same
	 * encoder legacy login already verifies with; it writes the {@code $2a$}
	 * bcrypt tag where PHP writes {@code $2y$}. The two are the same algorithm
	 * and verify each other's hashes in both directions, and the column is never
	 * serialized, so the tag is the one byte-level difference here.
	 */
	private final PasswordEncoder bcrypt;

	public LegacyEmployeeService(
			LegacyEmployeeStore store, LegacyNotifications notifications, LegacyPhoneNumbers phoneNumbers,
			LegacyClock clock, PasswordEncoder bcrypt) {
		this.store = store;
		this.notifications = notifications;
		this.phoneNumbers = phoneNumbers;
		this.clock = clock;
		this.bcrypt = bcrypt;
	}

	/**
	 * {@code list.php}. The WHERE clauses are assembled in PHP's own order --
	 * company, roster, branch, department, search, manager, job title, active,
	 * date range -- so the generated SQL and its bound parameters line up with
	 * the original statement.
	 */
	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		Pagination pagination = paginationParams(query);

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
	 * JSON number or boolean is a {@code TypeError} in PHP -- an uncaught 500,
	 * not a validation error. That 500's exact body (PHP adds {@code file},
	 * {@code line} and {@code trace}) is the shape still deferred, so this
	 * raises a 500 carrying the type-error text and nothing else.
	 */
	private static String normalizeEmployeeCode(Object raw) {
		if (raw != null && !(raw instanceof CharSequence)) {
			throw new LegacyApiException(
					500, "normalize_employee_code(): Argument #1 ($code) must be of type ?string, "
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
	private static String searchQueryParam(LegacyQueryParameters query) {
		String value = LegacyValues.toPhpString(query.value("search")).trim();
		return value.isEmpty() ? null : value;
	}

	/**
	 * {@code pagination_params(AppConfig::DEFAULT_LIMIT, 100)}
	 * ({@code helpers/pagination.php:12-23}). {@code $raw ?: $defaultLimit} is
	 * the subtle one: a limit that casts to {@code 0} becomes the default, not 1.
	 */
	private static Pagination paginationParams(LegacyQueryParameters query) {
		long page = Math.max(1, LegacyValues.toPhpLong(query.value("page") == null ? 1 : query.value("page")));
		Object rawLimit = query.value("limit");
		if (rawLimit == null) {
			rawLimit = query.value("per_page");
		}
		long raw = LegacyValues.toPhpLong(rawLimit == null ? DEFAULT_LIMIT : rawLimit);
		long limit = Math.min(Math.max(1, raw == 0 ? DEFAULT_LIMIT : raw), MAX_LIMIT);
		return new Pagination(page, limit, (page - 1) * limit);
	}

	/** {@code pagination_meta()} ({@code helpers/pagination.php:28-39}), key order included. */
	private static Map<String, Object> paginationMeta(long total, Pagination pagination) {
		long pages = pagination.limit() > 0 ? (long) Math.ceil((double) total / pagination.limit()) : 0;
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("page", pagination.page());
		meta.put("limit", pagination.limit());
		meta.put("total", total);
		meta.put("total_pages", pages);
		meta.put("has_next", pagination.page() < pages);
		meta.put("has_previous", pagination.page() > 1);
		return meta;
	}

	/** {@code array{page:int, limit:int, offset:int}}. */
	private record Pagination(long page, long limit, long offset) {
	}

	/** One {@code list.php} response: {@code data} rows plus its {@code meta}. */
	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

}
