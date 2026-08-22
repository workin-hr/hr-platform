package com.workin.legacy.employees;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyPhpArray;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee.Role;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code hr_employees/{list,create,update_permissions}.php} -- the desktop
 * system users and their seventeen permission flags.
 *
 * <h2>D-076: the one deliberate divergence</h2>
 * <p>In legacy, <em>any</em> HR session may create HR or manager users and
 * rewrite anyone's permissions, including its own, with no flag gate and no
 * anti-escalation check. That is privilege escalation reachable by every HR
 * user, and D-076 declines to reproduce it. Mutation authority on this surface
 * is COMPANY_ADMIN only; {@code list.php} keeps PHP's gate unchanged.
 *
 * <p>The authority check sits immediately after {@code requireAuth()} and
 * {@code requireCompanyActive()}, and <em>before</em> any body, query-id or
 * target inspection. That placement is the decision, not an implementation
 * detail: it means an HR session receives the same {@code forbidden} 403
 * whether the target is itself, a peer, a manager, missing, foreign, or the
 * body is malformed -- so the response can never confirm that a given id
 * exists. Legacy's own {@code forbidden} key is reused; no new key is invented.
 *
 * <p>Everything else is literal. For a COMPANY_ADMIN session the two mutations
 * follow the PHP exactly, including the three behaviours confirmed during the
 * matrix review: a target must already be {@code hr} or {@code manager};
 * permissions are a full replacement; and {@code list.php} shows only
 * {@code hr}.
 */
@Service
public class LegacyHrEmployeeService {

	/** {@code $allowed_roles = [HR, MANAGER]} -- strict membership, in create's own order. */
	private static final List<String> CREATABLE_ROLES = List.of("hr", "manager");

	/** {@code preg_split('/\\s+/', trim($name), 2)}. */
	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

	private final LegacyEmployeeStore store;
	private final LegacyPhoneNumbers phoneNumbers;
	private final LegacyClock clock;
	private final PasswordEncoder bcrypt;

	public LegacyHrEmployeeService(
			LegacyEmployeeStore store, LegacyPhoneNumbers phoneNumbers,
			LegacyClock clock, PasswordEncoder bcrypt) {
		this.store = store;
		this.phoneNumbers = phoneNumbers;
		this.clock = clock;
		this.bcrypt = bcrypt;
	}

	/**
	 * D-076's authority check. Called by the two mutations only, and called
	 * before anything about the request beyond the session is looked at.
	 */
	public void requireMutationAuthority(LegacyRequestContext context) {
		if (context.role() != Role.COMPANY_ADMIN) {
			throw new LegacyApiException(403, "forbidden");
		}
	}

	/**
	 * {@code hr_employees/list.php}: the company's HR users, newest first.
	 *
	 * <p>{@code e.role = 'hr'} is the whole role filter, so a manager carrying
	 * permissions never appears here. Not broadened -- the asymmetry is
	 * legacy's, and widening it would change what an existing client renders.
	 */
	public LegacyEmployeeService.Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		LegacyPagination.Params pagination = LegacyEmployeeService.paginationParams(query);

		List<String> where = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		where.add("e.company_id=?");
		params.add(context.companyId());
		where.add("e.role = ?");
		params.add("hr");

		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			// One LIKE across the display name, the code and the phone -- no
			// digits-only branch, unlike employees/list.php.
			where.add("(" + store.displayNameExpression()
					+ " LIKE ? OR e.employee_code LIKE ? OR e.phone LIKE ?)");
			String pattern = "%" + search + "%";
			params.add(pattern);
			params.add(pattern);
			params.add(pattern);
		}

		String whereSql = String.join(" AND ", where);
		long total = store.count(whereSql, params);
		List<Map<String, Object>> rows = store.hrEmployeeList(
				whereSql, params, pagination.limit(), pagination.offset());

		List<Map<String, Object>> attached = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			attached.add(attachPermissions(row));
		}
		return new LegacyEmployeeService.Page(attached, LegacyEmployeeService.paginationMeta(total, pagination));
	}

	/**
	 * {@code hr_employees/create.php}: a narrow employee row plus its
	 * permissions, in one transaction.
	 *
	 * <p>Narrow is the point. This INSERT writes fifteen columns and no more --
	 * no {@code expected_daily_hours}, no {@code job_title_id}, no
	 * {@code contract_duration_months}, no {@code is_mobile_attendance_enabled},
	 * no shift assignment and <em>no</em> {@code leave_balance} row. Neither
	 * {@code employees/create.php} nor {@code employee_create_from_payload()} is
	 * reused: both write more, and both would change what this endpoint
	 * produces.
	 */
	public Map<String, Object> create(LegacyRequestContext context, Map<String, Object> body) {
		long companyId = context.companyId();

		// required($body, [ROLE, BRANCH_ID])
		requireField(body, "role");
		requireField(body, "branch_id");

		String[] phone = resolvePhoneAndCountry(body);
		if (phone[0] != null && store.phoneExistsGlobally(phone[0], null)) {
			throw new LegacyApiException(409, "phone_already_exists");
		}

		String firstName = trimmed(body.get("first_name"));
		String lastName = trimmed(body.get("last_name"));
		if (firstName.isEmpty() && !LegacyValues.isPhpEmpty(body.get("name"))) {
			// preg_split(..., 2): the first token is the first name and
			// everything after it, spaces included, is the last name.
			String[] parts = WHITESPACE_RUN.split(trimmed(body.get("name")), 2);
			firstName = parts.length > 0 ? parts[0] : "";
			lastName = parts.length > 1 ? parts[1] : "";
		}
		if (firstName.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "first_name"));
		}

		// in_array($body[ROLE], ['hr','manager'], true): strict, so a numeric or
		// boolean role is invalid rather than coerced -- and company_admin can
		// never be created through this endpoint.
		Object role = body.get("role");
		if (!(role instanceof String roleText) || !CREATABLE_ROLES.contains(roleText)) {
			throw new LegacyApiException(400, "invalid_role");
		}

		if (!store.branchExistsInCompany(LegacyValues.toPhpLong(body.get("branch_id")), companyId)) {
			throw new LegacyApiException(404, "branch_not_found");
		}
		long branchId = LegacyValues.toPhpLong(body.get("branch_id"));

		// isset(), so a present-but-null employee_code is treated as absent --
		// and anything else is handed straight to normalize_employee_code(),
		// whose parameter is typed `?string`.
		String employeeCode = null;
		if (body.get("employee_code") != null) {
			employeeCode = normalizeEmployeeCode(body.get("employee_code"));
			if (employeeCode.isEmpty()) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "employee_code"));
			}
			if (!EMPLOYEE_CODE_FORMAT.matcher(employeeCode).matches()) {
				throw new LegacyApiException(400, "employee_code_invalid", null,
						Map.of("field", "employee_code"));
			}
			if (store.employeeCodeExistsInCompany(companyId, employeeCode, null)) {
				throw new LegacyApiException(409, "employee_code_already_exists");
			}
		}

		Long departmentId = null;
		if (!LegacyValues.isPhpEmpty(body.get("department_id"))) {
			departmentId = LegacyValues.toPhpLong(body.get("department_id"));
			if (!departmentValidForBranch(departmentId, branchId, companyId)) {
				throw new LegacyApiException(404, "department_not_found");
			}
		}

		Object permissions = body.get("permissions");
		String finalFirstName = firstName;
		String finalLastName = lastName;
		String finalCode = employeeCode;
		Long finalDepartmentId = departmentId;

		long employeeId;
		try {
			employeeId = store.inTransaction(() -> {
				String plainPassword = body.get("password") == null ? "" : trimmed(body.get("password"));
				String passwordHash = phone[0] != null && !plainPassword.isEmpty()
						? bcrypt.encode(plainPassword) : null;

				Map<String, Object> columns = new LinkedHashMap<>();
				columns.put("company_id", companyId);
				columns.put("branch_id", branchId);
				columns.put("department_id", finalDepartmentId);
				columns.put("employee_code", finalCode);
				columns.put("first_name", finalFirstName);
				columns.put("last_name", finalLastName);
				columns.put("country_code", phone[1]);
				columns.put("phone", phone[0]);
				columns.put("password_hash", passwordHash);
				columns.put("role", roleText);
				columns.put("national_id", body.get("national_id"));
				columns.put("birth_date", body.get("birth_date"));
				columns.put("gender", body.get("gender"));
				columns.put("address", body.get("address"));
				columns.put("hire_date", body.get("hire_date") == null
						? clock.todayAsString() : body.get("hire_date"));
				columns.put("is_active", 1);

				long newId = store.insertEmployee(columns);
				// Inside the transaction and after the insert, exactly where PHP
				// has it -- so a scalar `permissions` rolls a written employee
				// back rather than being rejected up front.
				store.upsertHrPermissions(newId, permissionValues(permissions));
				return newId;
			});
		} catch (Throwable ex) { // NOPMD - catch (Throwable $e), around the transaction only
			// catch (Throwable $e) { $pdo->rollBack(); ... } -- the rollback is
			// the transaction template's. A duplicate is reported as a phone
			// conflict whatever the key was, which is create.php's own coarser
			// mapping; everything else carries the exception text as data, as
			// PHP does. Deliberately local: the global advice stays on
			// Exception rather than being broadened to Throwable.
			if (isDuplicateEntry(ex)) {
				throw new LegacyApiException(409, "phone_already_exists");
			}
			throw new LegacyApiException(500, "employee_create_failed", messageOf(ex));
		}

		Map<String, Object> created = store.hrEmployeeWithPermissions(employeeId);
		if (created == null) {
			// fail(USER_NOT_FOUND, 500) -- inside the try, so PHP's catch then
			// rolls back a transaction that has already committed and answers
			// 500 anyway. The status and message are what reach the client.
			throw new LegacyApiException(500, "user_not_found");
		}
		return attachPermissions(created);
	}

	/**
	 * {@code hr_employees/update_permissions.php}: replace a target's seventeen
	 * flags.
	 *
	 * <p>There is no transaction here, and no read-back of the target before the
	 * write beyond its id and role -- the upsert is a single statement.
	 */
	public Map<String, Object> updatePermissions(
			LegacyRequestContext context, LegacyQueryParameters query, Map<String, Object> body) {
		Object rawId = query.value("id");
		if (rawId == null || "".equals(rawId)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		long employeeId = LegacyValues.toPhpLong(rawId);

		Map<String, Object> target = store.hrEmployeeRoleInCompany(employeeId, context.companyId());
		if (target == null) {
			// Missing and foreign are the same answer: the lookup is scoped by
			// company, so another tenant's id is simply not there.
			throw new LegacyApiException(404, "user_not_found");
		}
		String role = LegacyValues.toPhpString(target.get("role"));
		if (!CREATABLE_ROLES.contains(role)) {
			// A company admin and an ordinary employee are both refused here.
			throw new LegacyApiException(403, "forbidden");
		}

		store.upsertHrPermissions(employeeId, permissionValues(body.get("permissions")));

		Map<String, Object> updated = store.hrEmployeeWithPermissions(employeeId);
		return attachPermissions(updated);
	}

	/**
	 * {@code hr_permissions_values_from_input()}:
	 * {@code (int) ($permissions[$key] ?? 0)} for all seventeen keys, every call.
	 *
	 * <p>This is what makes an update a replacement. A body naming one flag
	 * revokes the other sixteen; a body with no {@code permissions} key at all
	 * clears all seventeen; and {@code (int) "yes"} is 0, so a non-numeric
	 * truthy value revokes rather than grants.
	 */
	private static List<Integer> permissionValues(Object permissions) {
		// `$body['permissions'] ?? []` -- absent and null are both the empty
		// array, and never reach the typed parameter.
		Object supplied = permissions == null ? Map.of() : permissions;
		if (!(supplied instanceof Map<?, ?>) && !(supplied instanceof List<?>)) {
			// hr_permissions_upsert_sql(int $employee_id, array $permissions):
			// a scalar is a TypeError, not an empty permission set. Treating it
			// as empty would silently clear all seventeen flags on input PHP
			// refuses outright.
			// D-086: the deterministic half of PHP's message, and no more. The
			// real one ends ", called in <file> on line <n>", naming a source
			// location inside hr-legacy that this process does not have --
			// synthesising one would put fabricated evidence in a payload the
			// client reads. create.php catches this and returns it as data;
			// update_permissions.php does not, and that stays D-084's 500.
			throw new LegacyPhpArray.LegacyPhpTypeError(
					"hr_permissions_upsert_sql(): Argument #2 ($permissions) must be of type array, "
							+ phpTypeName(supplied) + " given");
		}
		// A JSON array decodes to a numeric-keyed PHP array, which is still an
		// array: no TypeError, and every named lookup simply misses, so all
		// seventeen come out as zeros.
		Map<?, ?> named = supplied instanceof Map<?, ?> map ? map : Map.of();
		List<Integer> values = new ArrayList<>(LegacyEmployeeStore.HR_PERMISSION_KEYS.size());
		for (String key : LegacyEmployeeStore.HR_PERMISSION_KEYS) {
			Object value = named.get(key);
			values.add(value == null ? 0 : (int) LegacyValues.toPhpLong(value));
		}
		return values;
	}

	/**
	 * {@code employee_row_attach_hr_permissions()}: the seventeen columns are
	 * lifted into a {@code permissions} object and removed from the row.
	 *
	 * <p>Only for an {@code hr} or {@code manager} row -- any other role keeps
	 * its columns exactly as selected, which for this module means the joined
	 * nulls stay visible. And a row with no {@code hr_permissions} row joins to
	 * nulls, which become zeros in the map rather than nulls.
	 */
	private static Map<String, Object> attachPermissions(Map<String, Object> row) {
		Map<String, Object> attached = new LinkedHashMap<>(row);
		String role = LegacyValues.toPhpString(attached.get("role"));
		if (!CREATABLE_ROLES.contains(role)) {
			return attached;
		}
		Map<String, Object> permissions = new LinkedHashMap<>();
		for (String key : LegacyEmployeeStore.HR_PERMISSION_KEYS) {
			Object value = attached.get(key);
			permissions.put(key, value == null ? 0L : LegacyValues.toPhpLong(value));
		}
		for (String key : LegacyEmployeeStore.HR_PERMISSION_KEYS) {
			attached.remove(key);
		}
		attached.put("permissions", permissions);
		return attached;
	}

	/**
	 * {@code db_is_duplicate_entry()}: MySQL 1062 only, never every SQLSTATE
	 * 23000 -- which would also catch NOT NULL and foreign-key violations.
	 * Spring wraps the driver's exception, so the whole cause chain is searched.
	 */
	private static boolean isDuplicateEntry(Throwable ex) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = ex; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				text.append(current.getMessage()).append('\n');
			}
			if (current.getCause() == current) {
				break;
			}
		}
		String message = text.toString();
		return message.contains("1062")
				|| message.toLowerCase(java.util.Locale.ROOT).contains("duplicate entry");
	}

	private static String messageOf(Throwable ex) {
		return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
	}

	/** {@code /^[0-9]{1,64}$/} -- {@code is_valid_employee_code_format()}. */
	private static final Pattern EMPLOYEE_CODE_FORMAT = Pattern.compile("^[0-9]{1,64}$");

	/**
	 * {@code normalize_employee_code(?string $code)}: trim, then collapse
	 * whitespace runs.
	 *
	 * <p>The parameter is typed, and {@code strict_types=1} means PHP does not
	 * coerce into it. A JSON number, boolean or array in {@code employee_code}
	 * is a {@code TypeError} before any coercion happens -- so the raw value is
	 * type-checked here rather than being pushed through a string cast, which
	 * would silently accept input legacy rejects.
	 *
	 * <p>The check sits before {@code beginTransaction()} in the PHP, and the
	 * {@code TypeError} is uncaught, so it reaches the client as D-084's
	 * generic 500 with nothing written.
	 */
	private static String normalizeEmployeeCode(Object raw) {
		if (raw != null && !(raw instanceof String)) {
			// Uncaught in PHP -- this call is before beginTransaction() -- so
			// the text never reaches a client and D-084's generic 500 is the
			// whole response. Built to D-086's rule regardless.
			throw new LegacyPhpArray.LegacyPhpTypeError(
					"normalize_employee_code(): Argument #1 ($code) must be of type ?string, "
							+ phpTypeName(raw) + " given");
		}
		return LegacyValues.phpTrim((String) raw).replaceAll("\\s+", " ");
	}

	/**
	 * The type name PHP puts in a {@code TypeError}: {@code int}, {@code float},
	 * {@code string}, {@code array}, and {@code true}/{@code false} rather than
	 * {@code bool}. Measured, not assumed.
	 */
	private static String phpTypeName(Object value) {
		if (value instanceof Boolean flag) {
			return flag ? "true" : "false";
		}
		if (value instanceof Integer || value instanceof Long) {
			return "int";
		}
		if (value instanceof Double || value instanceof Float
				|| value instanceof java.math.BigDecimal) {
			return "float";
		}
		if (value instanceof String) {
			return "string";
		}
		if (value instanceof java.util.List<?> || value instanceof Map<?, ?>) {
			return "array";
		}
		return value == null ? "null" : value.getClass().getSimpleName();
	}

	/**
	 * {@code resolve_employee_phone_and_country_code()}, the strict one: an
	 * absent country code and an invalid number both end the request, unlike
	 * the spreadsheet's forgiving resolver.
	 */
	private String[] resolvePhoneAndCountry(Map<String, Object> body) {
		String rawPhone = trimmed(body.get("phone"));
		if (LegacyPhoneNumbers.digitsOnly(rawPhone).isEmpty()) {
			return new String[] {null, null};
		}
		String countryCode = LegacyPhoneNumbers.normalizeDialCode(trimmed(body.get("country_code")));
		if (countryCode.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "country_code"));
		}
		String normalized = phoneNumbers.normalizeLocal(countryCode, rawPhone);
		if (!phoneNumbers.isValidLocal(countryCode, normalized)) {
			throw new LegacyApiException(400, "invalid_phone_number");
		}
		return new String[] {normalized, countryCode};
	}

	/**
	 * {@code employee_department_valid_for_branch()} plus D-075.
	 *
	 * <p>The PHP checks only the {@code department_branches} junction when a
	 * branch is known -- no company predicate -- so a department belonging to
	 * another company would be accepted here if a dirty junction row happened to
	 * link it to one of this company's branches. D-075 approves exactly that
	 * narrow divergence: the foreign-company case fails closed with the same 404
	 * the missing case produces, and nothing else about the check moves. A
	 * same-tenant department that is inactive, unlinked or oddly linked still
	 * behaves exactly as legacy does.
	 */
	private boolean departmentValidForBranch(Long departmentId, Long branchId, long companyId) {
		if (departmentId == null || departmentId <= 0) {
			return true;
		}
		if (store.departmentExistsInOtherCompany(departmentId, companyId)) {
			return false;
		}
		if (branchId != null && branchId > 0) {
			return store.departmentBelongsToBranch(departmentId, branchId);
		}
		return store.departmentBelongsToCompany(departmentId, companyId);
	}

	/** {@code required($body, [$field])}: missing, null and the empty string all fail. */
	private static void requireField(Map<String, Object> body, String field) {
		Object value = body.get(field);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
	}

	private static String trimmed(Object value) {
		return LegacyValues.phpTrim(value == null ? "" : LegacyValues.toPhpString(value));
	}

}
