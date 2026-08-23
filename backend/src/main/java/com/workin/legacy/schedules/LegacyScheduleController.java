package com.workin.legacy.schedules;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code /apis/api/schedules/assign_employee_schedule.php} (Wave 12.6 slice 2).
 *
 * <h2>The role list is on the guard, not in the endpoint</h2>
 * <p>Unlike the five mutating {@code attendance} endpoints, which call a bare
 * {@code requireAuth()} and then test the role themselves, this one passes the
 * list to the guard:
 * {@code requireAuth([COMPANY_ADMIN, HR, MANAGER])}. Two consequences worth
 * keeping straight -- a MANAGER is <b>allowed</b> here where attendance refuses
 * them, and an EMPLOYEE is refused by the guard before
 * {@code requireCompanyActive()} runs rather than after it.
 *
 * <h2>The success envelope carries no data</h2>
 * <p>{@code ok(LangKey::SCHEDULE_ASSIGNED)} passes no {@code $data}, so the
 * response is {@code {success, message}} with the key absent -- not
 * {@code "data": null}. It reports no count either: a caller cannot tell from
 * the response how many days were written, or whether any were.
 */
@RestController
@RequestMapping("/apis/api/schedules")
public class LegacyScheduleController {

	private final LegacyScheduleService scheduleService;
	private final LegacyRequestGuard requestGuard;
	private final LegacyMessages messages;

	public LegacyScheduleController(
			LegacyScheduleService scheduleService, LegacyRequestGuard requestGuard,
			LegacyMessages messages) {
		this.scheduleService = scheduleService;
		this.requestGuard = requestGuard;
		this.messages = messages;
	}

	@RequestMapping("/assign_employee_schedule.php")
	public LegacyApiResponse assignEmployeeSchedule(HttpServletRequest request) {
		if (!"POST".equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER);
		requestGuard.requireCompanyActive(context.companyId());

		String locale = messages.resolveLocale(request);
		scheduleService.assign(
				context,
				LegacyJsonBody.read(request),
				new LegacyScheduleService.NotificationText(
						messages.translate(locale, "notif_schedule_assigned_title", null),
						messages.translate(locale, "notif_schedule_assigned_body", null)));

		return LegacyApiResponse.ok(messages.translate(locale, "schedule_assigned", null), null);
	}

}
