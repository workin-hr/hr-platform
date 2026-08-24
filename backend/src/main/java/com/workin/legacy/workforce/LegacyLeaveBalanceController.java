package com.workin.legacy.workforce;

import java.io.IOException;
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
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

/** All ten {@code /apis/api/leave_balances/*.php} endpoints (Wave 12.7). */
@RestController
@RequestMapping("/apis/api/leave_balances")
public class LegacyLeaveBalanceController {

	private static final MediaType XLSX = MediaType.parseMediaType(
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	private final LegacyLeaveBalanceService service;
	private final LegacyLeaveBalanceSpreadsheetService spreadsheets;
	private final LegacyRequestGuard guard;
	private final LegacyMessages messages;
	private final LegacyClock clock;

	public LegacyLeaveBalanceController(
			LegacyLeaveBalanceService service,
			LegacyLeaveBalanceSpreadsheetService spreadsheets,
			LegacyRequestGuard guard,
			LegacyMessages messages,
			LegacyClock clock) {
		this.service = service;
		this.spreadsheets = spreadsheets;
		this.guard = guard;
		this.messages = messages;
		this.clock = clock;
	}

	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = anyRole();
		LegacyLeaveBalanceService.Page page = service.list(
				context, LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		return LegacyApiResponse.ok(message(request, "ok"), service.one(anyRole(), requiredId(request)));
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		Map<String, Object> row = service.create(companyRole(), LegacyJsonBody.read(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), row));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = companyRole();
		Map<String, Object> row = service.update(context, requiredId(request), LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "ok"), row);
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		service.delete(companyRole(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	@RequestMapping("/generate.php")
	public LegacyApiResponse generate(HttpServletRequest request) {
		requireMethod(request, "POST");
		service.generate(companyRole(), LegacyJsonBody.read(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	@RequestMapping("/stats.php")
	public LegacyApiResponse stats(HttpServletRequest request) {
		requireMethod(request, "GET");
		Map<String, Object> data = service.stats(anyRole(), LegacyQueryParameters.parse(request.getQueryString()));
		return LegacyApiResponse.ok(message(request, "success"), data);
	}

	/** Legacy has no method guard here. */
	@RequestMapping("/template_excel.php")
	public ResponseEntity<byte[]> template(HttpServletRequest request) {
		LegacyRequestContext context = companyRole();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		int year = query.value("year") == null
				? clock.today().getYear() : (int) LegacyValues.toPhpLong(query.value("year"));
		if (year < 2000 || year > 2100) throw new LegacyApiException(400, "invalid_input");
		try {
			byte[] body = spreadsheets.template(context.companyId(), year, messages.resolveLocale(request));
			return ResponseEntity.ok()
					.contentType(XLSX)
					.header(HttpHeaders.CONTENT_DISPOSITION,
							"attachment; filename=\"leave_balances_" + year + "_template.xlsx\"")
					.body(body);
		} catch (RuntimeException ex) {
			throw new LegacyApiException(500, "file_save_failed", ex.getMessage());
		}
	}

	@RequestMapping("/analyze_excel.php")
	public LegacyApiResponse analyze(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = companyRole();
		Part file = part(request, "file");
		if (file == null || file.getSize() == 0) throw new LegacyApiException(400, "no_file_uploaded");
		int year = formOrQueryYear(request);
		try {
			Map<String, Object> data = spreadsheets.analyze(
					file.getInputStream().readAllBytes(), context.companyId(), year, messages.resolveLocale(request));
			return LegacyApiResponse.ok(message(request, "leave_balances_excel_analyzed"), data);
		} catch (IllegalArgumentException ex) {
			// PHP passes RuntimeException::getMessage() directly as the message key.
			throw new LegacyApiException(400, ex.getMessage());
		} catch (IOException ex) {
			throw new LegacyApiException(400, "Empty or unreadable file");
		}
	}

	@RequestMapping("/import_bulk.php")
	public LegacyApiResponse importBulk(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = companyRole();
		Map<String, Object> body = LegacyJsonBody.read(request);
		Object rows = body.get("rows");
		if (!(rows instanceof java.util.Collection<?> || rows instanceof Map<?, ?>)
				|| LegacyValues.isPhpEmpty(rows)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "rows"));
		}
		Map<String, Object> result = spreadsheets.importRows(
				context.companyId(), rows, messages.resolveLocale(request));
		int inserted = ((Number) result.get("inserted")).intValue();
		int updated = ((Number) result.get("updated")).intValue();
		boolean failed = !((java.util.List<?>) result.get("failed")).isEmpty();
		String key = inserted == 0 && updated == 0 && failed
				? "leave_balances_import_failed" : "leave_balances_imported";
		return LegacyApiResponse.ok(message(request, key), result);
	}

	private LegacyRequestContext anyRole() {
		LegacyRequestContext context = guard.requireAuth();
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private LegacyRequestContext companyRole() {
		LegacyRequestContext context = guard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
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

	private int formOrQueryYear(HttpServletRequest request) {
		String form = request.getParameter("year");
		Object query = LegacyQueryParameters.parse(request.getQueryString()).value("year");
		int year = form != null && !form.isEmpty()
				? (int) LegacyValues.toPhpLong(form)
				: query != null ? (int) LegacyValues.toPhpLong(query) : clock.today().getYear();
		return year < 2000 || year > 2100 ? clock.today().getYear() : year;
	}

	private static Part part(HttpServletRequest request, String name) {
		try {
			return request.getPart(name);
		} catch (IOException | ServletException ex) {
			return null;
		}
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) throw new LegacyApiException(405, "invalid_method");
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
