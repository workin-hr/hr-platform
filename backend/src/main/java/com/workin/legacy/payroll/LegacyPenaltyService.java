package com.workin.legacy.payroll;

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
import com.workin.legacy.wire.LegacyMessages;

/** Frozen behavior port of {@code /apis/api/penalties/*.php}. */
@Service
public class LegacyPenaltyService {

	private final LegacyPenaltyStore store;
	private final LegacyPenaltyAmounts penaltyAmounts;
	private final LegacyMessages messages;

	public LegacyPenaltyService(
			LegacyPenaltyStore store, LegacyPenaltyAmounts penaltyAmounts, LegacyMessages messages) {
		this.store = store;
		this.penaltyAmounts = penaltyAmounts;
		this.messages = messages;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Map<String, Object> create(LegacyRequestContext context, Map<String, Object> body, String locale) {
		required(body, "employee_id");
		required(body, "penalty_type");
		required(body, "penalty_date");

		long employeeId = LegacyValues.toPhpLong(body.get("employee_id"));
		String type = LegacyValues.toPhpString(body.get("penalty_type"));
		Double days = LegacyPenaltyDays.normalize(LegacyValues.toPhpDecimal(body.get("penalty_days")).doubleValue());
		if (days == null) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "penalty_days"));
		}
		String date = LegacyValues.toPhpString(body.get("penalty_date"));
		String reason = body.get("reason") == null ? "" : LegacyValues.toPhpString(body.get("reason"));
		if (store.employeeCompanyId(employeeId) != context.companyId()) {
			throw new LegacyApiException(403, "forbidden_insufficient_role");
		}
		long id = store.insert(employeeId, type, days, date, reason);
		Map<String, Object> row = requireRow(store.publicMutationRow(id));
		Long fromEmployeeId = context.employeeId() > 0 ? context.employeeId() : null;
		store.insertEmployeeNotification(
				context.companyId(), employeeId, fromEmployeeId,
				messages.translate(locale, "notif_penalty_issued_title", null),
				messages.translate(locale, "notif_penalty_issued_body", Map.of("date", date)), id);
		return row;
	}

	public Page list(LegacyRequestContext context, LegacyQueryParameters query) {
		LegacyPagination.Params page = LegacyPagination.params(query);
		List<String> where = new ArrayList<>();
		List<Object> bind = new ArrayList<>();
		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			where.add("p.employee_id=?");
			bind.add(context.employeeId());
		} else {
			where.add("e.company_id=?");
			bind.add(context.companyId());
			if (context.role() == LegacyEmployee.Role.MANAGER) {
				managerScope(context, where, bind);
			}
			if (!LegacyValues.isPhpEmpty(query.value("employee_id"))) {
				where.add("p.employee_id=?");
				bind.add(LegacyValues.toPhpLong(query.value("employee_id")));
			}
		}
		dateFilters(query, where, bind);
		String search = LegacyPagination.searchQueryParam(query);
		if (search != null) {
			where.add("(TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,''))) LIKE ? OR e.employee_code LIKE ? OR p.penalty_type LIKE ?)");
			String like = "%" + search + "%";
			bind.add(like);
			bind.add(like);
			bind.add(like);
		}
		long total = store.count(where, bind);
		return new Page(store.list(where, bind, page), LegacyPagination.meta(total, page));
	}

	public Map<String, Object> one(LegacyRequestContext context, long id) {
		Map<String, Object> row = store.oneRow(id);
		if (row == null) {
			throw new LegacyApiException(404, "not_found");
		}
		long targetEmployee = LegacyValues.toPhpLong(row.get("employee_id"));
		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			if (targetEmployee != context.employeeId()) {
				throw new LegacyApiException(403, "forbidden_insufficient_role");
			}
		} else {
			if (LegacyValues.toPhpLong(row.get("company_id")) != context.companyId()) {
				throw new LegacyApiException(403, "forbidden_insufficient_role");
			}
			if (context.role() == LegacyEmployee.Role.MANAGER
					&& !store.managerCanAccess(context.employeeId(), targetEmployee, context.companyId())) {
				throw new LegacyApiException(403, "forbidden_insufficient_role");
			}
		}
		return row;
	}

	public Map<String, Object> update(long companyId, long id, Map<String, Object> body) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (String key : List.of("penalty_type", "penalty_days", "reason", "penalty_date")) {
			if (body.containsKey(key)) {
				values.put(key, body.get(key));
			}
		}
		if (values.containsKey("penalty_days")) {
			Double normalized = LegacyPenaltyDays.normalize(LegacyValues.toPhpDecimal(values.get("penalty_days")).doubleValue());
			if (normalized == null) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "penalty_days"));
			}
			values.put("penalty_days", normalized);
		}
		if (values.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}
		Map<String, Object> row = store.mutableState(id);
		validateMutable(companyId, row);
		if (store.updateFields(id, values) == 0) {
			// Batch finalization marked the penalty applied after the preflight read. The
			// conditional SQL write changed nothing; expose the same immutability error.
			throw new LegacyApiException(403, "forbidden");
		}
		return requireRow(store.publicMutationRow(id));
	}

	public void delete(long companyId, long id) {
		Map<String, Object> row = store.mutableState(id);
		validateMutable(companyId, row);
		if (store.deleteById(id) == 0) {
			// Same finalization race as update(): once applied, the row must survive because
			// the finalized payslip already accounts for its deduction.
			throw new LegacyApiException(403, "forbidden");
		}
	}

	public Map<String, Object> stats(LegacyRequestContext context, LegacyQueryParameters query) {
		List<String> where = new ArrayList<>(List.of("e.company_id=?"));
		List<Object> bind = new ArrayList<>(List.of(context.companyId()));
		if (context.role() == LegacyEmployee.Role.MANAGER) {
			managerScope(context, where, bind);
		}
		dateFilters(query, where, bind);
		long total = store.count(where, bind);
		double days = store.totalPenaltyDays(where, bind);
		long applied = store.appliedCount(where, bind);
		double amount = penaltyAmounts.totalAmount(where, bind);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("total_penalties", total);
		result.put("total_penalty_days", days);
		result.put("total_penalties_amount", amount);
		result.put("applied", applied);
		result.put("not_applied", Math.max(0, total - applied));
		return result;
	}

	public List<Map<String, Object>> report(LegacyRequestContext context, LegacyQueryParameters query) {
		List<String> where = new ArrayList<>(List.of("e.company_id=?"));
		List<Object> bind = new ArrayList<>(List.of(context.companyId()));
		if (!LegacyValues.isPhpEmpty(query.value("employee_id"))) {
			where.add("p.employee_id=?");
			bind.add(LegacyValues.toPhpLong(query.value("employee_id")));
		}
		dateFilters(query, where, bind);
		if (context.role() == LegacyEmployee.Role.MANAGER) {
			managerScope(context, where, bind);
		}
		return store.report(where, bind);
	}

	private static void validateMutable(long companyId, Map<String, Object> row) {
		if (row == null) {
			throw new LegacyApiException(400, "not_found");
		}
		if (LegacyValues.toPhpLong(row.get("company_id")) != companyId) {
			throw new LegacyApiException(403, "forbidden_insufficient_role");
		}
		if (LegacyValues.toPhpLong(row.get("applied_to_payroll")) == 1L) {
			throw new LegacyApiException(403, "forbidden");
		}
	}

	private static void managerScope(LegacyRequestContext context, List<String> where, List<Object> bind) {
		where.add("e.branch_id=(SELECT eb.branch_id FROM employees eb WHERE eb.id=? AND eb.company_id=? LIMIT 1)");
		bind.add(context.employeeId());
		bind.add(context.companyId());
	}

	private static void dateFilters(LegacyQueryParameters query, List<String> where, List<Object> bind) {
		if (!LegacyValues.isPhpEmpty(query.value("from"))) {
			where.add("p.penalty_date>=?");
			bind.add(LegacyValues.toPhpString(query.value("from")));
		}
		if (!LegacyValues.isPhpEmpty(query.value("to"))) {
			where.add("p.penalty_date<=?");
			bind.add(LegacyValues.toPhpString(query.value("to")));
		}
	}

	private static void required(Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
		}
	}

	private static Map<String, Object> requireRow(Map<String, Object> row) {
		if (row == null) {
			throw new IllegalStateException("penalty public_row received null");
		}
		return row;
	}
}
