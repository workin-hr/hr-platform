package com.workin.legacy.workforce;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/** Core {@code leave_balances} CRUD/list/stats/generate behavior. */
@Service
public class LegacyLeaveBalanceService {

	private static final String FORBIDDEN = "forbidden_insufficient_role";
	private static final List<String> UPDATE_FIELDS = List.of(
			"total_days", "used_days", "period_from_month", "period_to_month", "monthly_cap_days");

	private final LegacyLeaveBalanceStore store;
	private final LegacyClock clock;

	public LegacyLeaveBalanceService(LegacyLeaveBalanceStore store, LegacyClock clock) {
		this.store = store;
		this.clock = clock;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) { }

	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		LegacyPagination.Params page = LegacyPagination.params(query);
		LegacyLeaveBalanceStore.Filter filter = filter(context, query, true);
		long total = store.count(filter);
		return new Page(store.list(filter, page), LegacyPagination.meta(total, page));
	}

	public Map<String, Object> one(LegacyRequestContext context, long id) {
		Map<String, Object> row = store.byId(id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		long companyId = number(row.get("company_id"));
		long employeeId = number(row.get("employee_id"));
		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			if (employeeId != context.employeeId()) {
				throw new LegacyApiException(403, FORBIDDEN);
			}
		} else {
			if (companyId != context.companyId()) {
				throw new LegacyApiException(403, FORBIDDEN);
			}
			if (context.role() == LegacyEmployee.Role.MANAGER
					&& !store.managerCanAccess(context.companyId(), context.employeeId(), employeeId)) {
				throw new LegacyApiException(403, FORBIDDEN);
			}
		}
		return row;
	}

	public Map<String, Object> create(LegacyRequestContext context, Map<String, Object> body) {
		required(body, "employee_id");
		required(body, "year");
		required(body, "total_days");
		long employeeId = LegacyValues.toPhpLong(body.get("employee_id"));
		int year = (int) LegacyValues.toPhpLong(body.get("year"));
		if (store.employeeCompanyId(employeeId) != context.companyId()) {
			throw new LegacyApiException(403, FORBIDDEN);
		}
		if (store.byEmployeeAndYear(employeeId, year) != null) {
			throw new LegacyApiException(400, "already_exists");
		}
		Object total = LegacyValues.toPhpDecimal(body.get("total_days"));
		Object used = LegacyValues.toPhpDecimal(body.getOrDefault("used_days", 0));
		int from = presentNonEmpty(body, "period_from_month")
				? (int) LegacyValues.toPhpLong(body.get("period_from_month")) : 1;
		int to = presentNonEmpty(body, "period_to_month")
				? (int) LegacyValues.toPhpLong(body.get("period_to_month")) : 12;
		Object cap = presentNonEmpty(body, "monthly_cap_days")
				? LegacyValues.toPhpDecimal(body.get("monthly_cap_days")) : null;
		long id = store.insert(employeeId, year, total, used, from, to, cap);
		Map<String, Object> row = store.byId(id);
		if (row == null) {
			throw new IllegalStateException("public_row(): expected leave balance row");
		}
		return writeResponseRow(row);
	}

	public Map<String, Object> update(LegacyRequestContext context, long id, Map<String, Object> body) {
		LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
		for (String key : UPDATE_FIELDS) {
			if (body.containsKey(key)) {
				fields.put(key, body.get(key));
			}
		}
		if (fields.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}
		Map<String, Object> row = store.byId(id);
		if (row == null) {
			throw new LegacyApiException(400, "not_found");
		}
		if (number(row.get("company_id")) != context.companyId()) {
			throw new LegacyApiException(403, FORBIDDEN);
		}
		store.update(id, fields);
		Map<String, Object> updated = store.byId(id);
		if (updated == null) {
			throw new IllegalStateException("public_row(): expected leave balance row");
		}
		return writeResponseRow(updated);
	}

	public void delete(LegacyRequestContext context, long id) {
		Map<String, Object> row = store.byId(id);
		if (row == null) {
			throw new LegacyApiException(400, "not_found");
		}
		if (number(row.get("company_id")) != context.companyId()) {
			throw new LegacyApiException(403, FORBIDDEN);
		}
		store.delete(id);
	}

	public void generate(LegacyRequestContext context, Map<String, Object> body) {
		int year = body.containsKey("year")
				? (int) LegacyValues.toPhpLong(body.get("year")) : clock.today().getYear();
		if (year <= 0) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "year"));
		}
		store.generate(context.companyId(), year, store.defaultAnnualLeaveDays(context.companyId()));
	}

	public Map<String, Object> stats(LegacyRequestContext context, LegacyQueryParameters query) {
		Map<String, Object> raw = store.stats(filter(context, query, false));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("employees_count", (int) number(raw.get("employees_count")));
		result.put("total_days", decimal(raw.get("total_days")));
		result.put("used_days", decimal(raw.get("used_days")));
		result.put("remaining_days", decimal(raw.get("remaining_days")));
		return result;
	}

	private LegacyLeaveBalanceStore.Filter filter(
			LegacyRequestContext context, LegacyQueryParameters query, boolean listFilters) {
		Long own = context.role() == LegacyEmployee.Role.EMPLOYEE ? context.employeeId() : null;
		Long manager = context.role() == LegacyEmployee.Role.MANAGER ? context.employeeId() : null;
		Long employeeFilter = null;
		if (listFilters && own == null && !LegacyValues.isPhpEmpty(query.value("employee_id"))) {
			employeeFilter = LegacyValues.toPhpLong(query.value("employee_id"));
		}
		int yearFrom = (int) LegacyValues.toPhpLong(query.value("year_from"));
		int yearTo = (int) LegacyValues.toPhpLong(query.value("year_to"));
		Integer from = yearFrom > 0 && yearTo > 0 ? yearFrom : null;
		Integer to = from == null ? null : yearTo;
		Integer year = null;
		if (from == null) {
			Object rawYear = query.value("year");
			year = rawYear == null ? clock.today().getYear() : (int) LegacyValues.toPhpLong(rawYear);
			if (year <= 0) {
				year = null;
			}
		}
		return new LegacyLeaveBalanceStore.Filter(
				context.companyId(), own, manager, employeeFilter, from, to, year,
				listFilters ? LegacyPagination.searchQueryParam(query) : null);
	}

	/** Create/update PHP re-reads omit employee_code but do include the joined company_id. */
	private static Map<String, Object> writeResponseRow(Map<String, Object> raw) {
		Map<String, Object> result = new LinkedHashMap<>(raw);
		result.remove("employee_code");
		return result;
	}

	private static void required(Map<String, Object> body, String field) {
		Object value = body.get(field);
		if (!body.containsKey(field) || value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", field));
		}
	}

	private static boolean presentNonEmpty(Map<String, Object> body, String field) {
		return body.containsKey(field) && body.get(field) != null && !"".equals(body.get(field));
	}

	private static long number(Object value) {
		if (value == null) {
			return 0;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private static double decimal(Object value) {
		if (value == null) {
			return 0;
		}
		try {
			return Double.parseDouble(String.valueOf(value));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}
}
