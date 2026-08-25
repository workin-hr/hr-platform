package com.workin.legacy.attendance.records;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/** Deferred Wave-12.6 attendance reporting routes that depend on requests/payroll helpers. */
@RestController
@RequestMapping("/apis/api/attendance")
public class LegacyAttendanceDeferredController {

	private final LegacyAttendanceStatsService statsService;
	private final LegacyAttendanceListService listService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyAttendanceDeferredController(LegacyAttendanceStatsService statsService,
			LegacyAttendanceListService listService, LegacyRequestGuard requestGuard, LegacyMessages messages) {
		this.statsService = statsService;
		this.listService = listService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** Frozen {@code stats.php}: GET -> bare requireAuth -> active company -> filtered stats. */
	@RequestMapping("/stats.php")
	public LegacyApiResponse stats(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = authenticatedActiveCompany();
		String locale = messages.resolveLocale(request);
		return LegacyApiResponse.ok(
				messages.translate(locale, "success", null),
				statsService.stats(context, LegacyQueryParameters.parse(request.getQueryString()),
						messages.translate(locale, "schedule_weekly_rest", null)));
	}

	/** Frozen {@code list.php}: GET -> bare requireAuth -> active company -> regular/fill-days list. */
	@RequestMapping("/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = authenticatedActiveCompany();
		String locale = messages.resolveLocale(request);
		LegacyAttendanceListService.Page page = listService.list(
				context, LegacyQueryParameters.parse(request.getQueryString()),
				messages.translate(locale, "schedule_weekly_rest", null));
		fixSyntheticIds(page.rows());
		return LegacyApiResponse.ok(messages.translate(locale, "attendance_records", null), page.rows(), page.meta());
	}

	private LegacyRequestContext authenticatedActiveCompany() {
		LegacyRequestContext context = requestGuard.requireAuth();
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	/** Keeps the helper's stable synthetic-id formula tied to the employee after row metadata is merged. */
	private static void fixSyntheticIds(Iterable<Map<String, Object>> rows) {
		for (Map<String, Object> row : rows) {
			if (row.get("attendance_id") != null || row.get("date") == null) {
				continue;
			}
			long employeeId = LegacyValues.toPhpLong(row.get("employee_id"));
			long compact = Long.parseLong(LegacyValues.toPhpString(row.get("date")).replace("-", ""));
			row.put("id", -1L * (employeeId * 100_000_000L + compact % 100_000_000L));
		}
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}
}
