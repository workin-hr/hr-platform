package com.workin.legacy.payroll;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
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

	public LegacyPayslipController(
			LegacyPayslipService service, LegacyPayslipWriteCoordinator writes,
			LegacyRequestGuard guard, LegacyMessages messages) {
		this.service = service;
		this.writes = writes;
		this.guard = guard;
		this.messages = messages;
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
