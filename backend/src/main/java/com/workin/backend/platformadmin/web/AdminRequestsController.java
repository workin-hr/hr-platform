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
import com.workin.backend.platformadmin.hr.EmployeeRequestAdminService;
import com.workin.backend.platformadmin.hr.EmployeeRequestStore;

/**
 * {@code dashboard/pages/requests/page.php}.
 *
 * <p>The list defaults to {@code pending} rather than to everything, because
 * the page exists to work through a queue.
 */
@Controller
@Profile("phase1-mysql")
public class AdminRequestsController {

	private static final String VIEW = "admin/requests";

	private static final String PATH = PlatformAdminWebSecurityConfig.REQUESTS_PATH;

	private final EmployeeRequestStore store;

	private final EmployeeRequestAdminService service;

	public AdminRequestsController(
			EmployeeRequestStore store, EmployeeRequestAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's employee requests. An administrator reaches "
			+ "every company's through the session filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		// `$_GET['status'] ?? REQ_PENDING` -- a queue, not an archive.
		String status = request.getParameter("status") == null
				? "pending" : request.getParameter("status");
		long typeId = positive(request.getParameter("type_id"));
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");
		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());

		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("status", status);
		model.addAttribute("typeId", typeId);
		model.addAttribute("dateFrom", dateFrom == null ? "" : dateFrom);
		model.addAttribute("dateTo", dateTo == null ? "" : dateTo);
		model.addAttribute("typeOptions", this.store.typeOptions(filters.companyId()));
		model.addAttribute("result", this.store.paginate(
				filters, status, typeId, dateFrom, dateTo, showCompany));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "requests"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	private static long positive(String raw) {
		if (raw == null || raw.isBlank()) {
			return 0L;
		}
		try {
			long value = Long.parseLong(raw.trim());
			return value > 0 ? value : 0L;
		} catch (NumberFormatException ex) {
			return 0L;
		}
	}

	@AuthenticatedUseCase(reason = "Approves, rejects or deletes one employee request. "
			+ "Approving deducts leave and writes attendance exceptions. Gated by the surface "
			+ "flag and a bound second factor, tenant-checked through the employee join (R-046), "
			+ "and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(required = false, defaultValue = "") String comment) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "approve" -> this.service.approve(session, adminId, bound, id, comment);
				case "reject" -> this.service.reject(session, adminId, bound, id, comment);
				case "delete" -> this.service.delete(session, adminId, bound, id);
				default -> throw new EmployeeRequestAdminService.RefusedException(
						EmployeeRequestAdminService.Refusal.FOREIGN_ROW);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (EmployeeRequestAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(EmployeeRequestAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case FOREIGN_ROW -> "error_db";
			case INSUFFICIENT_BALANCE -> "insufficient_leave_balance";
			case NOT_PENDING -> "error_required";
		};
	}

}
