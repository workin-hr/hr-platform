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
import com.workin.backend.platformadmin.hr.PenaltyAdminService;
import com.workin.backend.platformadmin.hr.PenaltyStore;

/** {@code dashboard/pages/penalties/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminPenaltiesController {

	private static final String VIEW = "admin/penalties";

	private static final String PATH = PlatformAdminWebSecurityConfig.PENALTIES_PATH;

	private final PenaltyStore store;

	private final PenaltyAdminService service;

	public AdminPenaltiesController(PenaltyStore store, PenaltyAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's employee penalties, which payroll deducts "
			+ "against. An administrator reaches every company's through the session filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		String applied = request.getParameter("applied") == null
				? "all" : request.getParameter("applied");
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");
		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());

		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("applied", applied);
		model.addAttribute("dateFrom", dateFrom == null ? "" : dateFrom);
		model.addAttribute("dateTo", dateTo == null ? "" : dateTo);
		model.addAttribute("result", this.store.paginate(
				filters, applied, dateFrom, dateTo, showCompany));
		model.addAttribute("employeeOptions", this.store.employeeOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "penalties"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Creates, edits, marks applied or deletes one penalty. "
			+ "A penalty already applied to payroll cannot be edited. Gated by the surface "
			+ "flag and a bound second factor, tenant-checked through the employee join (R-046), "
			+ "and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(name = "penalty_type", required = false, defaultValue = "") String penaltyType,
			@RequestParam(name = "penalty_days", required = false, defaultValue = "") String penaltyDays,
			@RequestParam(required = false, defaultValue = "") String reason,
			@RequestParam(name = "penalty_date", required = false, defaultValue = "") String penaltyDate) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "add_penalty" -> this.service.add(session, adminId, bound, employeeId,
						penaltyType, penaltyDays, reason, penaltyDate);
				case "edit_penalty" -> this.service.saveEdit(session, adminId, bound, id,
						employeeId, penaltyType, penaltyDays, reason, penaltyDate);
				case "mark_applied" -> this.service.markApplied(session, adminId, bound, id);
				case "delete_penalty" -> this.service.delete(session, adminId, bound, id);
				default -> throw new PenaltyAdminService.RefusedException(
						PenaltyAdminService.Refusal.INVALID);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (PenaltyAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(PenaltyAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
			case BAD_DAYS -> "penalty_days_invalid";
		};
	}

}
