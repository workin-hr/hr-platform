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
import com.workin.backend.platformadmin.hr.AdvanceAdminService;
import com.workin.backend.platformadmin.hr.AdvanceStore;

/** {@code dashboard/pages/advances/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminAdvancesController {

	private static final String VIEW = "admin/advances";

	private static final String PATH = PlatformAdminWebSecurityConfig.ADVANCES_PATH;

	private final AdvanceStore store;

	private final AdvanceAdminService service;

	public AdminAdvancesController(AdvanceStore store, AdvanceAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's salary advances and what remains outstanding "
			+ "on them. An administrator reaches every company's through the session filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		String status = request.getParameter("status") == null
				? "all" : request.getParameter("status");
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");
		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());

		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("status", status);
		model.addAttribute("dateFrom", dateFrom == null ? "" : dateFrom);
		model.addAttribute("dateTo", dateTo == null ? "" : dateTo);
		model.addAttribute("result", this.store.paginate(
				filters, status, dateFrom, dateTo, showCompany));
		model.addAttribute("employeeOptions", this.store.employeeOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "advances"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Creates, edits, approves, rejects, marks repaid or deletes "
			+ "one salary advance. An edit adjusts the outstanding balance rather than "
			+ "overwriting it. Gated by the surface flag and a bound second factor, "
			+ "tenant-checked through the employee join (R-046, D-176), and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(required = false, defaultValue = "") String amount,
			@RequestParam(required = false, defaultValue = "") String reason,
			@RequestParam(name = "request_date", required = false, defaultValue = "") String requestDate,
			@RequestParam(name = "rejection_reason", required = false, defaultValue = "") String rejectionReason) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "add_advance" -> this.service.add(
						session, adminId, bound, employeeId, amount, reason, requestDate);
				case "edit_advance" -> this.service.saveEdit(
						session, adminId, bound, id, employeeId, amount, reason, requestDate);
				case "approve" -> this.service.approve(session, adminId, bound, id);
				case "reject" -> this.service.reject(session, adminId, bound, id, rejectionReason);
				case "mark_paid" -> this.service.markPaid(session, adminId, bound, id);
				case "delete_advance" -> this.service.delete(session, adminId, bound, id);
				default -> throw new AdvanceAdminService.RefusedException(
						AdvanceAdminService.Refusal.INVALID);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (AdvanceAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(AdvanceAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
			case REASON_REQUIRED -> "rejection_reason_required";
		};
	}

}
