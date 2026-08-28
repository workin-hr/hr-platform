package com.workin.legacy.payroll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.spreadsheet.LegacyXlsxWriter;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/** Frozen PHP-compatible {@code /apis/api/payslips/*.php} (Wave 12.9 slice 3). */
@RestController
@RequestMapping("/apis/api/payslips")
public class LegacyPayslipController {

	private final LegacyPayslipService service;
	private final LegacyPayslipWriteCoordinator writes;
	private final LegacyRequestGuard guard;
	private final LegacyMessages messages;
	private final LegacyClock clock;

	public LegacyPayslipController(
			LegacyPayslipService service, LegacyPayslipWriteCoordinator writes,
			LegacyRequestGuard guard, LegacyMessages messages, LegacyClock clock) {
		this.service = service;
		this.writes = writes;
		this.guard = guard;
		this.messages = messages;
		this.clock = clock;
	}

	/** {@code create.php}: Company Admin, HR only. */
	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = writerRole();
		Map<String, Object> row = writes.create(context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "payslip_created"), row));
	}

	/** {@code delete.php}: Company Admin, HR only. */
	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = writerRole();
		writes.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "payslip_deleted"), null);
	}

	/** {@code one.php}: Company Admin, HR, Manager, Employee (own only). */
	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = readerRole();
		Map<String, Object> row = service.one(
				context.companyId(), context.role(), context.employeeId(), requiredId(request),
				presentLabel(request), weeklyRestLabel(request), officialHolidayFallbackLabel(request));
		return LegacyApiResponse.ok(message(request, "payslip"), row);
	}

	/** {@code list.php}: Company Admin, HR, Manager, Employee (own only). */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = readerRole();
		LegacyPayslipService.Page page = service.list(
				context.companyId(), context.role(), context.employeeId(),
				LegacyQueryParameters.parse(request.getQueryString()),
				presentLabel(request), weeklyRestLabel(request), officialHolidayFallbackLabel(request));
		return LegacyApiResponse.ok(message(request, "payslips"), page.rows(), page.meta());
	}

	/**
	 * {@code export.php}: the payslip rows as an XLSX workbook, same role list as
	 * {@code list.php} -- an EMPLOYEE is served their own payslips only.
	 *
	 * <p>Its filename encodes the filter that produced it: the batch when one was
	 * given, the period when both dates were, and today's date otherwise. Per
	 * D-085 the port owes the same reader-observable workbook, not the same
	 * archive bytes.
	 */
	@RequestMapping("/export.php")
	public ResponseEntity<byte[]> export(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = readerRole();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		String locale = messages.resolveLocale(request);

		String from = LegacyValues.toPhpString(query.value("from")).trim();
		String to = LegacyValues.toPhpString(query.value("to")).trim();
		Long batchId = positiveOrNull(LegacyValues.toPhpLong(query.value("batch_id")));

		LegacyPayslipStore.ExportFilter filter = new LegacyPayslipStore.ExportFilter(
				context.companyId(),
				context.role() == LegacyEmployee.Role.EMPLOYEE ? context.employeeId() : null,
				positiveOrNull(LegacyValues.toPhpLong(query.value("employee_id"))),
				positiveOrNull(LegacyValues.toPhpLong(query.value("branch_id"))),
				positiveOrNull(LegacyValues.toPhpLong(query.value("department_id"))),
				batchId,
				positiveIntOrNull(LegacyValues.toPhpLong(query.value("month"))),
				positiveIntOrNull(LegacyValues.toPhpLong(query.value("year"))),
				from.isEmpty() ? null : from,
				to.isEmpty() ? null : to,
				blankToNull(LegacyValues.toPhpString(query.value("search"))));

		List<Map<String, Object>> rows = service.exportRows(filter,
				presentLabel(request), weeklyRestLabel(request), officialHolidayFallbackLabel(request));

		List<String> headers = LegacyPayslipExportSheet.HEADER_KEYS.stream()
				.map(key -> messages.translate(locale, key, null)).toList();
		List<List<String>> body = new java.util.ArrayList<>();
		int serial = 0;
		for (Map<String, Object> row : rows) {
			body.add(LegacyPayslipExportSheet.row(row, ++serial));
		}

		byte[] bytes;
		try {
			bytes = LegacyXlsxWriter.build(headers, body, "Payslips", List.of(), List.of(), 1, Map.of());
		} catch (Throwable ex) { // PHP catches Throwable around xlsx_build_bytes().
			throw new LegacyApiException(500, "file_save_failed", ex.getMessage());
		}

		String filename = LegacyPayslipExportSheet.filename(batchId, from, to, clock.todayAsString());
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
				.contentLength(bytes.length)
				.body(bytes);
	}

	private static Long positiveOrNull(long value) {
		return value > 0 ? value : null;
	}

	private static Integer positiveIntOrNull(long value) {
		return value > 0 ? (int) value : null;
	}

	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** {@code update.php}: Company Admin, HR only. */
	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = writerRole();
		Map<String, Object> body = phpUpdateBody(LegacyJsonBody.read(request));
		Map<String, Object> row = writes.update(
				context.companyId(), requiredId(request), body,
				presentLabel(request), weeklyRestLabel(request), officialHolidayFallbackLabel(request));
		return LegacyApiResponse.ok(message(request, "payslip_updated"), row);
	}

	/**
	 * PHP's {@code $body['penalty_days'] ?? payroll_unapplied_penalty_days(...)} treats an explicit
	 * JSON {@code null} exactly like an omitted key. {@link LegacyPayslipService#update} uses
	 * key-presence to distinguish an explicit override, so normalize only this null-coalesced
	 * compatibility field at the literal PHP adapter boundary before the service sees it.
	 */
	static Map<String, Object> phpUpdateBody(Map<String, Object> body) {
		if (!body.containsKey("penalty_days") || body.get("penalty_days") != null) {
			return body;
		}
		Map<String, Object> normalized = new LinkedHashMap<>(body);
		normalized.remove("penalty_days");
		return normalized;
	}

	private String presentLabel(HttpServletRequest request) {
		return messages.translate(messages.resolveLocale(request), "csv_attendance_present_day", null);
	}

	private String weeklyRestLabel(HttpServletRequest request) {
		return messages.translate(messages.resolveLocale(request), "schedule_weekly_rest", null);
	}

	private String officialHolidayFallbackLabel(HttpServletRequest request) {
		return messages.translate(messages.resolveLocale(request), "csv_official_holiday_days", null);
	}

	private LegacyRequestContext writerRole() {
		LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private LegacyRequestContext readerRole() {
		LegacyRequestContext context = guard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR,
				LegacyEmployee.Role.MANAGER, LegacyEmployee.Role.EMPLOYEE);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private static long requiredId(HttpServletRequest request) {
		Object id = LegacyQueryParameters.parse(request.getQueryString()).value("id");
		if (id == null || "".equals(id)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		return LegacyValues.toPhpLong(id);
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
