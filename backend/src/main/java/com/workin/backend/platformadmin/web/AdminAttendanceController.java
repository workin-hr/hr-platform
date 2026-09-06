package com.workin.backend.platformadmin.web;

import java.time.LocalDate;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.AttendanceAdminService;
import com.workin.backend.platformadmin.hr.AttendanceStore;
import com.workin.backend.platformadmin.hr.EmployeeStore;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.wire.LegacyMessages;

/** {@code dashboard/pages/attendance/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminAttendanceController {

	private static final String VIEW = "admin/attendance";

	private static final String PATH = PlatformAdminWebSecurityConfig.ATTENDANCE_PATH;

	private final AttendanceStore store;

	private final AttendanceAdminService service;

	private final EmployeeStore employeeStore;

	private final LegacyClock clock;

	private final LegacyMessages messages;

	public AdminAttendanceController(
			AttendanceStore store, AttendanceAdminService service, EmployeeStore employeeStore,
			LegacyClock clock, LegacyMessages messages) {
		this.store = store;
		this.service = service;
		this.employeeStore = employeeStore;
		this.clock = clock;
		this.messages = messages;
	}

	@AuthenticatedUseCase(reason = "One company's attendance punches, which payroll is computed "
			+ "from. An administrator reaches every company's through the session filter; a "
			+ "company-scoped session is held to its own by DashboardOrgScope.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		// `$_GET['from'] ?? date('Y-m-01')` and `$_GET['to'] ?? date('Y-m-d')`.
		LocalDate today = this.clock.today();
		String from = blankOr(request.getParameter("from"), today.withDayOfMonth(1).toString());
		String to = blankOr(request.getParameter("to"), today.toString());
		int aggPage = positiveOr(request.getParameter("agg_page"), 1);

		long optionsCompanyId = filters.companyId() > 0
				? filters.companyId() : (current.isScopedToOneCompany() ? current.companyId() : 0);
		String weeklyRestLabel = this.messages.translate(
				this.messages.resolveLocale(request), "schedule_weekly_rest", null);

		model.addAttribute("showCompanyColumn",
				DashboardOrgScope.showsCompanyColumn(current, filters.companyId()));
		model.addAttribute("filters", filters);
		model.addAttribute("from", from);
		model.addAttribute("to", to);
		model.addAttribute("aggPage", aggPage);
		model.addAttribute("result", this.store.paginateDetail(filters, from, to, weeklyRestLabel));
		model.addAttribute("aggregateResult", this.store.aggregate(
				filters, from, to, aggPage,
				current.isScopedToOneCompany() ? current.companyId() : 0,
				today.toString()));
		model.addAttribute("employeeOptions", this.store.employeeOptions(optionsCompanyId));
		model.addAttribute("exceptionTypes", this.store.exceptionTypeOptions(optionsCompanyId));
		model.addAttribute("branchOptions", this.employeeStore.branchOptions(optionsCompanyId));
		model.addAttribute("departmentOptions", this.employeeStore.departmentOptions(optionsCompanyId));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "attendance"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Adds, edits, deletes one attendance punch, or clears a whole "
			+ "date range for a company. Gated by the surface flag and a bound second factor, "
			+ "tenant-checked through the employee join (R-046/R-059), and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(name = "check_in", required = false, defaultValue = "") String checkIn,
			@RequestParam(name = "check_out", required = false, defaultValue = "") String checkOut,
			@RequestParam(name = "exception_type_id", required = false, defaultValue = "0")
					long exceptionTypeId,
			@RequestParam(required = false, defaultValue = "") String from,
			@RequestParam(required = false, defaultValue = "") String to) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();
		Long exceptionType = AttendanceAdminService.exceptionTypeOrNull(exceptionTypeId);

		try {
			long wrote = switch (action) {
				case "add_attendance" -> this.service.add(
						session, adminId, bound, employeeId, checkIn, checkOut, exceptionType);
				case "edit_attendance" -> this.service.saveEdit(
						session, adminId, bound, id, checkIn, checkOut, exceptionType);
				case "delete" -> this.service.delete(session, adminId, bound, id);
				case "delete_range" -> this.service.deleteRange(
						session, adminId, bound, companyId, from, to,
						this.clock.today().toString()).companyId();
				default -> throw new AttendanceAdminService.RefusedException(
						AttendanceAdminService.Refusal.INVALID);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (AttendanceAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(AttendanceAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
		};
	}

	private static String blankOr(String raw, String fallback) {
		return raw == null || raw.isEmpty() ? fallback : raw;
	}

	private static int positiveOr(String raw, int fallback) {
		if (raw == null || raw.isEmpty()) {
			return fallback;
		}
		try {
			return Math.max(fallback, Integer.parseInt(raw.trim()));
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

}
