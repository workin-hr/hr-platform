package com.workin.legacy.payroll;

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

/** Frozen PHP-compatible {@code /apis/api/payroll_batches/*.php} (Wave 12.9). */
@RestController
@RequestMapping("/apis/api/payroll_batches")
public class LegacyPayrollBatchController {

	private final LegacyPayrollBatchService service;
	private final LegacyRequestGuard guard;
	private final LegacyMessages messages;

	public LegacyPayrollBatchController(
			LegacyPayrollBatchService service, LegacyRequestGuard guard, LegacyMessages messages) {
		this.service = service;
		this.guard = guard;
		this.messages = messages;
	}

	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = role();
		LegacyPayrollBatchService.Page page = service.list(
				context.companyId(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "payroll_batches"), page.rows(), page.meta());
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = role();
		return LegacyApiResponse.ok(message(request, "ok"), service.one(context.companyId(), requiredId(request)));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = role();
		Map<String, Object> row = service.create(context.companyId(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "payroll_batch_created"), row));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = role();
		Map<String, Object> row = service.update(
				context.companyId(), requiredId(request), LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "ok"), row);
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = role();
		service.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	@RequestMapping("/fiscal_period.php")
	public LegacyApiResponse fiscalPeriod(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = role();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		int year = (int) intOrZero(query.value("year"));
		int month = (int) intOrZero(query.value("month"));
		return LegacyApiResponse.ok(message(request, "success"), service.fiscalPeriod(context.companyId(), year, month));
	}

	/**
	 * {@code calculate.php}'s {@code ok($message, $data, 200, [Response::COUNT => (string) $count])}:
	 * the count fills the message's {@code {count}} placeholder ({@code $replace},
	 * PHP's 4th positional argument) -- it is not a separate {@code meta} field.
	 */
	@RequestMapping("/calculate.php")
	public LegacyApiResponse calculate(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = role();
		LegacyPayrollBatchService.CalculationResult result = service.calculate(
				context.companyId(), requiredId(request), weeklyRestLabel(request));
		String locale = messages.resolveLocale(request);
		String text = messages.translate(
				locale, "payroll_calculated", Map.of("count", String.valueOf(result.calculatedCount())));
		return LegacyApiResponse.ok(text, result.row());
	}

	@RequestMapping("/finalize.php")
	public LegacyApiResponse finalize(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = role();
		return LegacyApiResponse.ok(
				message(request, "payroll_finalized"), service.finalize(context.companyId(), requiredId(request)));
	}

	@RequestMapping("/reopen.php")
	public LegacyApiResponse reopen(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = role();
		return LegacyApiResponse.ok(
				message(request, "batch_reopened"), service.reopen(context.companyId(), requiredId(request)));
	}

	@RequestMapping("/stats.php")
	public LegacyApiResponse stats(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = role();
		return LegacyApiResponse.ok(
				message(request, "success"), service.stats(context.companyId(), requiredId(request)));
	}

	/** {@code t('schedule_weekly_rest')}, the request's locale, matching every other calendar-consuming controller. */
	private String weeklyRestLabel(HttpServletRequest request) {
		return messages.translate(messages.resolveLocale(request), "schedule_weekly_rest", null);
	}

	private LegacyRequestContext role() {
		LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
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

	private static long intOrZero(Object raw) {
		return raw == null ? 0 : LegacyValues.toPhpLong(raw);
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
