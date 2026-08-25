package com.workin.legacy.payroll;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.wire.LegacyApiException;

/** {@code payroll_batches/{list,one,create,update,delete,fiscal_period}.php} (Wave 12.9 slice 1). */
@Service
public class LegacyPayrollBatchService {

	/** {@code PayrollStatusEnum} ({@code config/enums.php}), the schema's own two values. */
	private static final String DRAFT = "draft";
	private static final String FINALIZED = "finalized";

	private final LegacyPayrollBatchStore store;
	private final LegacyPayrollFiscalSettings fiscalSettings;
	private final LegacyWeeklyOffDays weeklyOffDays;

	public LegacyPayrollBatchService(
			LegacyPayrollBatchStore store, LegacyPayrollFiscalSettings fiscalSettings,
			LegacyWeeklyOffDays weeklyOffDays) {
		this.store = store;
		this.fiscalSettings = fiscalSettings;
		this.weeklyOffDays = weeklyOffDays;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Page list(long companyId, LegacyQueryParameters query) {
		LegacyPagination.Params page = LegacyPagination.params(query);
		String status = statusOrNull(query.value("status"));
		Integer year = yearOrNull(query.value("year"));
		String search = LegacyPagination.searchQueryParam(query);

		long total = store.countForList(companyId, status, year, search);
		List<Map<String, Object>> rows = store.list(companyId, status, year, search, page);
		return new Page(rows, LegacyPagination.meta(total, page));
	}

	public Map<String, Object> one(long companyId, long batchId) {
		Map<String, Object> row = store.withStats(batchId, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return row;
	}

	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		int month = (int) requiredLong(body, "month");
		int year = (int) requiredLong(body, "year");
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);

		if (store.existsForPeriod(companyId, month, year)) {
			throw new LegacyApiException(400, "already_exists");
		}

		long id = store.insert(companyId, month, year, bounds[0], bounds[1], DRAFT);
		Map<String, Object> row = store.withStats(id, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return row;
	}

	public Map<String, Object> update(long companyId, long batchId, Map<String, Object> body) {
		Map<String, Object> batch = store.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}

		int month = body.get("month") != null
				? (int) LegacyValues.toPhpLong(body.get("month")) : ((Number) batch.get("month")).intValue();
		int year = body.get("year") != null
				? (int) LegacyValues.toPhpLong(body.get("year")) : ((Number) batch.get("year")).intValue();
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);

		store.updatePeriod(batchId, month, year, bounds[0], bounds[1]);
		Map<String, Object> row = store.withStats(batchId, companyId);
		if (row == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		return row;
	}

	public void delete(long companyId, long batchId) {
		Map<String, Object> batch = store.scoped(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}
		store.deleteWithPayslips(batchId);
	}

	/** {@code fiscal_period.php}: bounds plus the company-wide (no employee override) working-day count. */
	public Map<String, Object> fiscalPeriod(long companyId, int year, int month) {
		if (year < 2000 || month < 1 || month > 12) {
			throw new LegacyApiException(400, "invalid_input");
		}
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);
		int workingDays = periodWorkingDays(companyId, bounds[0], bounds[1]);

		return new java.util.LinkedHashMap<>(Map.of(
				"period_from", bounds[0],
				"period_to", bounds[1],
				"year", year,
				"month", month,
				"working_days", workingDays));
	}

	/**
	 * {@code payroll_period_working_days()} ({@code payroll_calculation.php:151-186}),
	 * the no-{@code employee_id} branch only -- the shift-override path is a
	 * per-employee concern the calculation engine slice owns.
	 */
	private int periodWorkingDays(long companyId, String from, String to) {
		java.time.LocalDate start = java.time.LocalDate.parse(from);
		java.time.LocalDate end = java.time.LocalDate.parse(to);
		if (end.isBefore(start)) {
			return 0;
		}
		List<String> rest = weeklyOffDays.forCompany(companyId);
		int count = 0;
		for (java.time.LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			int dayOfWeek = day.getDayOfWeek().getValue() % 7; // PHP's date('w'): 0 = Sunday
			if (!LegacyWeeklyOffDays.isWeeklyRestDay(dayOfWeek, rest)) {
				count++;
			}
		}
		return Math.max(0, count);
	}

	private static String statusOrNull(Object raw) {
		return raw == null ? null : LegacyValues.toPhpString(raw);
	}

	private static Integer yearOrNull(Object raw) {
		long year = raw == null ? 0 : LegacyValues.toPhpLong(raw);
		return year > 0 ? (int) year : null;
	}

	/** {@code required()} ({@code functions.php:617-623}): {@code isset()} plus a bare empty-string check. */
	private static long requiredLong(Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value == null || "".equals(value)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", key));
		}
		return LegacyValues.toPhpLong(value);
	}
}
