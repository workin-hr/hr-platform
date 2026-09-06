package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.LeaveBalanceAdminService;
import com.workin.backend.platformadmin.hr.LeaveBalanceStore;
import com.workin.legacy.LegacyClock;

/**
 * {@code dashboard/pages/leave_balances/page.php}, the first of the HR pages.
 *
 * <p>Two things differ from the org pages it reuses the machinery of. The list
 * is always narrowed to one {@code year}, defaulting to the current one rather
 * than to "all"; and the rows reach a company through their employee, so the
 * company column and every tenant check go through a join.
 */
@Controller
@Profile("phase1-mysql")
public class AdminLeaveBalancesController {

	private static final String VIEW = "admin/leave-balances";

	private static final String PATH = PlatformAdminWebSecurityConfig.LEAVE_BALANCES_PATH;

	private final LeaveBalanceStore store;

	private final LeaveBalanceAdminService service;

	private final LegacyClock clock;

	public AdminLeaveBalancesController(
			LeaveBalanceStore store, LeaveBalanceAdminService service, LegacyClock clock) {
		this.store = store;
		this.service = service;
		this.clock = clock;
	}

	@AuthenticatedUseCase(reason = "One company's annual leave balances. An administrator "
			+ "reaches every company's through the session filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		int thisYear = this.clock.now().getYear();
		int year = year(request.getParameter("year"), thisYear);
		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());

		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("year", year);
		// The dashboard offers next year through five years back.
		model.addAttribute("yearOptions", java.util.stream.IntStream
				.rangeClosed(thisYear - 5, thisYear + 1).boxed()
				.sorted(java.util.Comparator.reverseOrder()).toList());
		model.addAttribute("result", this.store.paginate(filters, year, showCompany));
		model.addAttribute("employeeOptions", this.store.employeeOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "leave_balances"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	/** {@code (int) ($_GET['year'] ?? date('Y'))}: an unreadable year is this one. */
	private static int year(String raw, int fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			int parsed = Integer.parseInt(raw.trim());
			return parsed > 0 ? parsed : fallback;
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	@AuthenticatedUseCase(reason = "Creates, edits or deletes one employee's annual leave "
			+ "balance. Gated by the surface flag and a bound second factor, tenant-checked "
			+ "through the employee join (R-046), and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(required = false, defaultValue = "") String year,
			@RequestParam(name = "total_days", required = false, defaultValue = "") String totalDays,
			@RequestParam(name = "used_days", required = false, defaultValue = "") String usedDays) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "add_leave" -> this.service.add(session, adminId, bound, employeeId,
						year(year, this.clock.now().getYear()), totalDays);
				case "edit_leave" -> this.service.saveEdit(
						session, adminId, bound, id, totalDays, usedDays);
				case "delete_leave" -> this.service.delete(session, adminId, bound, id);
				default -> throw new LeaveBalanceAdminService.RefusedException(
						LeaveBalanceAdminService.Refusal.NO_COMPANY);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (LeaveBalanceAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(LeaveBalanceAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case NO_COMPANY -> "select_company_first";
			case NO_EMPLOYEE -> "error_required";
			case FOREIGN_ROW -> "error_db";
		};
	}

}
