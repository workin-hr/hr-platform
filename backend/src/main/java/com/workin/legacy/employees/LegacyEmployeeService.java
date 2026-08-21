package com.workin.legacy.employees;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee.Role;
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

	private final LegacyEmployeeStore store;

	public LegacyEmployeeService(LegacyEmployeeStore store) {
		this.store = store;
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
