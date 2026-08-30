package com.workin.legacy.dashboard;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code dashboard/stats.php} -- the single summary-widget endpoint,
 * COMPANY_ADMIN or HR only, and the only one of Wave 13.5's five that reads
 * company data at all.
 *
 * <p>Unlike the four reference endpoints beside it, this one calls
 * {@code requireCompanyActive()}: a suspended company's admin can still read
 * banners and FAQs but not their own statistics.
 */
@RestController
@RequestMapping("/apis/api/dashboard")
public class LegacyDashboardController {

	private final LegacyDashboardService dashboardService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyDashboardController(
			LegacyDashboardService dashboardService, LegacyRequestGuard requestGuard,
			LegacyMessages messages) {
		this.dashboardService = dashboardService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	@RequestMapping("/stats.php")
	public LegacyApiResponse stats(HttpServletRequest request) {
		if (!"GET".equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		requestGuard.requireCompanyActive(context.companyId());
		return LegacyApiResponse.ok(
				messages.translate(messages.resolveLocale(request), "success", null),
				dashboardService.stats(context.companyId()));
	}
}
