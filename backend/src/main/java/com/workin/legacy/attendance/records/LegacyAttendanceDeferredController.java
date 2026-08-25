package com.workin.legacy.attendance.records;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyQueryParameters;
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
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyAttendanceDeferredController(LegacyAttendanceStatsService statsService,
			LegacyRequestGuard requestGuard, LegacyMessages messages) {
		this.statsService = statsService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	/** Frozen {@code stats.php}: GET -> bare requireAuth -> active company -> filtered stats. */
	@RequestMapping("/stats.php")
	public LegacyApiResponse stats(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = requestGuard.requireAuth();
		requestGuard.requireCompanyActive(context.companyId());
		String locale = messages.resolveLocale(request);
		return LegacyApiResponse.ok(
				messages.translate(locale, "success", null),
				statsService.stats(context, LegacyQueryParameters.parse(request.getQueryString()),
						messages.translate(locale, "schedule_weekly_rest", null)));
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}
}
