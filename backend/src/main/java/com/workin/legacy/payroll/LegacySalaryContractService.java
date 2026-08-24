package com.workin.legacy.payroll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.wire.LegacyApiException;

@Service
public class LegacySalaryContractService {

    private final LegacySalaryContractStore store;

    public LegacySalaryContractService(LegacySalaryContractStore store) {
        this.store = store;
    }

    public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {}

    public Page list(long companyId, LegacyQueryParameters query) {
        Object employeeRaw = query.value("employee_id");
        if (employeeRaw == null || "".equals(employeeRaw)) {
            throw required("employee_id");
        }
        long employeeId = LegacyValues.toPhpLong(employeeRaw);
        requireEmployee(companyId, employeeId);
        LegacyPagination.Params page = LegacyPagination.params(query);
        String search = LegacyPagination.searchQueryParam(query);
        long total = store.count(employeeId, search);
        return new Page(store.list(employeeId, search, page), LegacyPagination.meta(total, page));
    }

    public Map<String, Object> one(long companyId, long id) {
        Map<String, Object> row = store.scoped(companyId, id);
        if (row == null) throw new LegacyApiException(404, "not_found");
        return row;
    }

    public Map<String, Object> create(long companyId, Map<String, Object> body) {
        required(body, "employee_id", "effective_from");
        long employeeId = LegacyValues.toPhpLong(body.get("employee_id"));
        requireEmployee(companyId, employeeId);
        Map<String, Object> values = values(body, null);
        values.put("employee_id", employeeId);
        long id = store.insert(values);
        return requireRow(store.byId(id));
    }

    public Map<String, Object> update(long companyId, long id, Map<String, Object> body) {
        Map<String, Object> existing = store.scoped(companyId, id);
        if (existing == null) throw new LegacyApiException(404, "not_found");
        Map<String, Object> values = values(body, existing);
        store.update(id, values);
        return requireRow(store.byId(id));
    }

    public void delete(long companyId, long id) {
        if (store.scoped(companyId, id) == null) throw new LegacyApiException(404, "not_found");
        store.delete(id);
    }

    private void requireEmployee(long companyId, long employeeId) {
        if (!store.employeeOwned(companyId, employeeId)) {
            throw new LegacyApiException(404, "employee_not_found");
        }
    }

    private static Map<String, Object> values(Map<String, Object> body, Map<String, Object> old) {
        Map<String, Object> v = new LinkedHashMap<>();
        String mode = text(value(body, old, "salary_mode", "monthly"));
        if (!"daily".equals(mode) && !"monthly".equals(mode)) mode = "monthly";
        double basic = decimal(value(body, old, "basic_salary", 0));
        Object daily = body != null && body.containsKey("daily_wage")
                ? nullableDecimal(body.get("daily_wage"))
                : old == null ? null : old.get("daily_wage");
        double transport = decimal(value(body, old, "transport_allowance", 0));
        double food = decimal(value(body, old, "food_allowance", 0));
        double risk = decimal(value(body, old, "risk_allowance", 0));
        double incentives = decimal(value(body, old, "incentives", 0));
        if ("daily".equals(mode)) {
            basic = 0; transport = 0; food = 0; risk = 0; incentives = 0;
        }
        v.put("salary_mode", mode);
        v.put("basic_salary", basic);
        v.put("daily_wage", daily);
        v.put("transport_allowance", transport);
        v.put("food_allowance", food);
        v.put("risk_allowance", risk);
        v.put("incentives", incentives);
        v.put("insurance_deduction", decimal(value(body, old, "insurance_deduction", 0)));
        v.put("tax_deduction", decimal(value(body, old, "tax_deduction", 0)));
        v.put("advances_deduction", decimal(value(body, old, "advances_deduction", 0)));
        v.put("fund_deduction", decimal(value(body, old, "fund_deduction", 0)));
        v.put("penalty_deduction", decimal(value(body, old, "penalty_deduction", 0)));
        v.put("effective_from", value(body, old, "effective_from", null));
        return v;
    }

    private static Object value(Map<String, Object> body, Map<String, Object> old, String key, Object fallback) {
        if (body != null && body.containsKey(key) && body.get(key) != null) return body.get(key);
        if (old != null && old.get(key) != null) return old.get(key);
        return fallback;
    }

    private static String text(Object value) { return value == null ? "" : LegacyValues.toPhpString(value); }
    private static double decimal(Object value) { return LegacyValues.toPhpDecimal(value).doubleValue(); }
    private static Double nullableDecimal(Object value) {
        return value == null || "".equals(value) ? null : LegacyValues.toPhpDecimal(value).doubleValue();
    }
    private static Map<String, Object> requireRow(Map<String, Object> row) {
        if (row == null) throw new IllegalStateException("salary contract public_row received null");
        return row;
    }
    private static void required(Map<String, Object> body, String... keys) {
        for (String key : keys) if (body == null || body.get(key) == null || "".equals(body.get(key))) throw required(key);
    }
    private static LegacyApiException required(String key) {
        return new LegacyApiException(400, "field_required", null, Map.of("field", key));
    }
}
