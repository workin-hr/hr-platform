package com.workin.legacy.payroll;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Exact behavior port of frozen {@code /apis/api/advances/*.php}. */
@Service
public class LegacyAdvanceService {

	private static final String PENDING = "pending";
	private static final String SINGLE_PAYROLL_MONTH = "single_payroll_month";
	private static final String INSTALLMENTS = "installments";
	private static final String SINGLE_MONTH = "single_month";
	private static final String MULTIPLE_MONTHS = "multiple_months";
	private static final List<String> STATUSES = List.of(PENDING, "approved", "rejected");
	private static final ObjectMapper JSON = new ObjectMapper();

	private final LegacyAdvanceStore store;

	public LegacyAdvanceService(LegacyAdvanceStore store) {
		this.store = store;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Map<String, Object> create(LegacyRequestContext context, Map<String, Object> body) {
		required(body, "amount");
		long employeeId;
		String status = PENDING;
		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			employeeId = context.employeeId();
		} else {
			required(body, "employee_id");
			employeeId = LegacyValues.toPhpLong(body.get("employee_id"));
			if (body.get("status") != null) {
				status = LegacyValues.toPhpString(body.get("status"));
			}
		}
		if (employeeId == 0L) {
			throw new LegacyApiException(400, "invalid_employee");
		}

		Map<String, Object> values = new LinkedHashMap<>();
		values.put("employee_id", employeeId);
		values.put("amount", body.get("amount")); // PDO/MariaDB owns decimal coercion.
		values.put("reason", body.get("reason"));
		values.put("deduction_mode", INSTALLMENTS.equals(body.get("deduction_mode")) ? INSTALLMENTS : SINGLE_PAYROLL_MONTH);
		values.put("deduction_type", MULTIPLE_MONTHS.equals(body.get("deduction_type")) ? MULTIPLE_MONTHS : SINGLE_MONTH);
		values.put("deduction_month_count", phpIsset(body, "deduction_month_count")
				? LegacyValues.toPhpLong(body.get("deduction_month_count")) : 1L);
		values.put("deduction_amount_per_month", phpIsset(body, "deduction_amount_per_month")
				? LegacyValues.toPhpDecimal(body.get("deduction_amount_per_month")) : null);
		values.put("deduction_payroll_year", optionalInt(body, "deduction_payroll_year"));
		values.put("deduction_payroll_month", optionalInt(body, "deduction_payroll_month"));
		values.put("deduction_installments_json", createJson(body.get("deduction_installments_json")));
		values.put("status", status);

		long id = store.insert(values);
		return requirePublicRow(store.withEmployee(id));
	}

	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		LegacyPagination.Params page = LegacyPagination.params(query);
		List<String> predicates = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		predicates.add("a.employee_id IN (SELECT id FROM employees WHERE company_id=?)");
		args.add(context.companyId());

		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			predicates.add("a.employee_id=?");
			args.add(context.employeeId());
		} else if (!LegacyValues.isPhpEmpty(query.value("employee_id"))) {
			predicates.add("a.employee_id=?");
			args.add(LegacyValues.toPhpLong(query.value("employee_id")));
		}

		if (!LegacyValues.isPhpEmpty(query.value("status"))) {
			String status = LegacyValues.toPhpString(query.value("status"));
			if (!STATUSES.contains(status)) {
				throw new LegacyApiException(400, "invalid_filter_value");
			}
			predicates.add("a.status=?");
			args.add(status);
		}

		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			predicates.add("(TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) LIKE ? OR e.employee_code LIKE ?)");
			args.add("%" + search + "%");
			args.add("%" + search + "%");
		}
		if (!LegacyValues.isPhpEmpty(query.value("from"))) {
			predicates.add("a.request_date >= ?");
			args.add(LegacyValues.toPhpString(query.value("from")));
		}
		if (!LegacyValues.isPhpEmpty(query.value("to"))) {
			predicates.add("a.request_date <= ?");
			args.add(LegacyValues.toPhpString(query.value("to")));
		}

		long total = store.count(predicates, args);
		return new Page(store.list(predicates, args, page), LegacyPagination.meta(total, page));
	}

	public Map<String, Object> one(LegacyRequestContext context, long id) {
		Map<String, Object> row = store.scopedWithEmployee(context.companyId(), id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		if (context.role() == LegacyEmployee.Role.EMPLOYEE
				&& LegacyValues.toPhpLong(row.get("employee_id")) != context.employeeId()) {
			throw new LegacyApiException(403, "forbidden");
		}
		return row;
	}

	public Map<String, Object> update(LegacyRequestContext context, long id, Map<String, Object> body) {
		Map<String, Object> existing = store.scoped(context.companyId(), id);
		if (existing == null) {
			throw new LegacyApiException(404, "not_found");
		}
		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			if (LegacyValues.toPhpLong(existing.get("employee_id")) != context.employeeId()) {
				throw new LegacyApiException(403, "forbidden");
			}
			if (!PENDING.equals(existing.get("status"))) {
				throw new LegacyApiException(400, "cannot_edit_non_pending_advance");
			}
			Object amount = body.containsKey("amount") ? body.get("amount") : existing.get("amount");
			Object reason = body.containsKey("reason") ? body.get("reason") : existing.get("reason");
			store.updateEmployee(id, amount, reason);
		} else {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("amount", body.containsKey("amount") ? body.get("amount") : existing.get("amount"));
			values.put("remaining", body.containsKey("remaining") ? body.get("remaining") : existing.get("remaining"));
			values.put("reason", body.containsKey("reason") ? body.get("reason") : existing.get("reason"));
			values.put("status", body.containsKey("status") ? body.get("status") : existing.get("status"));
			values.put("rejection_reason", body.containsKey("rejection_reason") ? body.get("rejection_reason") : existing.get("rejection_reason"));

			Object modeRaw = body.containsKey("deduction_mode") ? body.get("deduction_mode") : existing.get("deduction_mode");
			String mode = modeRaw == null ? SINGLE_PAYROLL_MONTH : LegacyValues.toPhpString(modeRaw);
			values.put("deduction_mode", INSTALLMENTS.equals(mode) || SINGLE_PAYROLL_MONTH.equals(mode) ? mode : SINGLE_PAYROLL_MONTH);
			Object typeRaw = body.containsKey("deduction_type") ? body.get("deduction_type") : existing.get("deduction_type");
			values.put("deduction_type", MULTIPLE_MONTHS.equals(typeRaw) ? MULTIPLE_MONTHS : SINGLE_MONTH);
			values.put("deduction_month_count", phpIsset(body, "deduction_month_count")
					? LegacyValues.toPhpLong(body.get("deduction_month_count")) : defaultValue(existing.get("deduction_month_count"), 1L));
			values.put("deduction_amount_per_month", phpIsset(body, "deduction_amount_per_month")
					? LegacyValues.toPhpDecimal(body.get("deduction_amount_per_month")) : existing.get("deduction_amount_per_month"));
			values.put("deduction_payroll_year", updateOptionalInt(body, existing, "deduction_payroll_year"));
			values.put("deduction_payroll_month", updateOptionalInt(body, existing, "deduction_payroll_month"));
			values.put("deduction_installments_json", updateJson(body, existing));
			store.updateAdministrative(id, values);
		}
		return requirePublicRow(store.withEmployee(id));
	}

	public Map<String, Object> approve(long id) {
		store.approve(id);
		return requirePublicRow(store.withEmployee(id));
	}

	public Map<String, Object> reject(long id, Map<String, Object> body) {
		required(body, "rejection_reason");
		store.reject(id, body.get("rejection_reason"));
		return requirePublicRow(store.withEmployee(id));
	}

	public Map<String, Object> pay(long id, Map<String, Object> body) {
		required(body, "amount");
		Map<String, Object> state = store.paymentState(id);
		if (state == null) {
			throw new LegacyApiException(404, "not_found");
		}
		BigDecimal payment = LegacyValues.toPhpDecimal(body.get("amount"));
		BigDecimal remaining = LegacyValues.toPhpDecimal(state.get("remaining")).subtract(payment);
		if (remaining.signum() < 0) {
			throw new LegacyApiException(400, "payment_exceeds_remaining");
		}
		store.pay(id, remaining);
		return requirePublicRow(store.withEmployee(id));
	}

	public void delete(LegacyRequestContext context, long id) {
		Map<String, Object> row = store.deleteState(id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		if (!PENDING.equals(row.get("status"))) {
			throw new LegacyApiException(400, "cannot_delete_approved_advance");
		}
		if (context.role() == LegacyEmployee.Role.EMPLOYEE
				&& LegacyValues.toPhpLong(row.get("employee_id")) != context.employeeId()) {
			throw new LegacyApiException(403, "forbidden");
		}
		store.delete(id);
	}

	private static Object optionalInt(Map<String, Object> body, String key) {
		Object raw = body.get(key);
		return raw != null && !"".equals(raw) ? LegacyValues.toPhpLong(raw) : null;
	}

	private static Object updateOptionalInt(Map<String, Object> body, Map<String, Object> existing, String key) {
		if (!body.containsKey(key)) {
			return existing.get(key);
		}
		Object raw = body.get(key);
		return raw == null || "".equals(raw) ? null : LegacyValues.toPhpLong(raw);
	}

	private static String createJson(Object raw) {
		if (LegacyValues.isPhpEmpty(raw)) {
			return null;
		}
		return raw instanceof String text ? text : json(raw);
	}

	private static String updateJson(Map<String, Object> body, Map<String, Object> existing) {
		if (!body.containsKey("deduction_installments_json")) {
			Object current = existing.get("deduction_installments_json");
			return current == null ? null : LegacyValues.toPhpString(current);
		}
		Object raw = body.get("deduction_installments_json");
		return raw == null || "".equals(raw) ? null : raw instanceof String text ? text : json(raw);
	}

	private static String json(Object raw) {
		try {
			return JSON.writeValueAsString(raw);
		} catch (JacksonException ex) {
			return null; // json_encode() failure is assigned as false/null-ish and PDO stores NULL-like text state.
		}
	}

	private static boolean phpIsset(Map<String, Object> body, String key) {
		return body.containsKey(key) && body.get(key) != null;
	}

	private static Object defaultValue(Object value, Object fallback) {
		return value == null ? fallback : value;
	}

	private static void required(Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
		}
	}

	private static Map<String, Object> requirePublicRow(Map<String, Object> row) {
		if (row == null) {
			throw new IllegalStateException("advance public_row received null");
		}
		return row;
	}
}
