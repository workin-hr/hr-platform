package com.workin.legacy.payroll;

import java.util.ArrayList;
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

/** Frozen PHP-compatible {@code /apis/api/penalties/*.php}. */
@RestController
@RequestMapping("/apis/api/penalties")
public class LegacyPenaltyController {

	private static final List<String> REPORT_HEADERS = List.of(
			"Code", "Name", "Branch", "Type", "Penalty Days", "Reason", "Date", "Applied to Payroll", "Added By");

	private final LegacyPenaltyService service;
	private final LegacyRequestGuard guard;
	private final LegacyMessages messages;
	private final LegacyClock clock;

	public LegacyPenaltyController(LegacyPenaltyService service, LegacyRequestGuard guard,
			LegacyMessages messages, LegacyClock clock) {
		this.service = service;
		this.guard = guard;
		this.messages = messages;
		this.clock = clock;
	}

	@RequestMapping("/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = administrative();
		Map<String, Object> row = service.create(context, LegacyJsonBody.read(request), messages.resolveLocale(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), row));
	}

	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = authenticated();
		LegacyPenaltyService.Page page = service.list(context, query(request));
		return LegacyApiResponse.ok(message(request, "ok"), page.rows(), page.meta());
	}

	@RequestMapping("/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = authenticated();
		return LegacyApiResponse.ok(message(request, "ok"), service.one(context, requiredId(request)));
	}

	@RequestMapping("/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = administrative();
		long id = requiredId(request);
		return LegacyApiResponse.ok(message(request, "ok"), service.update(context.companyId(), id, LegacyJsonBody.read(request)));
	}

	@RequestMapping("/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = administrative();
		service.delete(context.companyId(), requiredId(request));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	@RequestMapping("/stats.php")
	public LegacyApiResponse stats(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = reportRole();
		return LegacyApiResponse.ok(message(request, "success"), service.stats(context, query(request)));
	}

	@RequestMapping("/report.php")
	public ResponseEntity<?> report(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = reportRole();
		LegacyQueryParameters query = query(request);
		List<Map<String, Object>> rows = service.report(context, query);
		if ("csv".equals(query.value("format"))) {
			List<List<String>> values = new ArrayList<>();
			for (Map<String, Object> row : rows) {
				List<String> one = new ArrayList<>();
				for (Object value : row.values()) one.add(value == null ? "" : LegacyValues.toPhpString(value));
				values.add(one);
			}
			byte[] bytes;
			try {
				bytes = LegacyXlsxWriter.build(REPORT_HEADERS, values, "Report", List.of(), List.of(), 1, Map.of());
			} catch (RuntimeException ex) {
				throw new LegacyApiException(500, "file_save_failed", ex.getMessage());
			}
			String filename = "penalties_report_" + clock.todayAsString() + ".xlsx";
			return ResponseEntity.ok()
					.contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
					.header(HttpHeaders.CACHE_CONTROL, "no-cache")
					.contentLength(bytes.length)
					.body(bytes);
		}
		return ResponseEntity.ok(LegacyApiResponse.ok(message(request, "penalties_report"), rows));
	}

	private LegacyRequestContext authenticated() {
		LegacyRequestContext context = guard.requireAuth();
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private LegacyRequestContext administrative() {
		LegacyRequestContext context = guard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private LegacyRequestContext reportRole() {
		LegacyRequestContext context = guard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		guard.requireCompanyActive(context.companyId());
		return context;
	}

	private static LegacyQueryParameters query(HttpServletRequest request) {
		return LegacyQueryParameters.parse(request.getQueryString());
	}

	/** required($_GET,[id]) followed by (int): non-numeric present values cast to zero and continue. */
	private static long requiredId(HttpServletRequest request) {
		LegacyQueryParameters query = query(request);
		Object raw = query.value("id");
		if (raw == null || "".equals(raw)) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "id"));
		}
		return LegacyValues.toPhpLong(raw);
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) throw new LegacyApiException(405, "invalid_method");
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(messages.resolveLocale(request), key, null);
	}
}
