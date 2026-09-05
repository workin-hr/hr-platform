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
import com.workin.backend.platformadmin.hr.ComplaintAdminService;
import com.workin.backend.platformadmin.hr.ComplaintStore;

/** {@code dashboard/pages/complaints/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminComplaintsController {

	private static final String VIEW = "admin/complaints";

	private static final String PATH = PlatformAdminWebSecurityConfig.COMPLAINTS_PATH;

	private final ComplaintStore store;

	private final ComplaintAdminService service;

	public AdminComplaintsController(ComplaintStore store, ComplaintAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "Complaints from employees and from companies. A company "
			+ "sees only its employees'; an administrator sees both kinds across every company "
			+ "through the session filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		String source = request.getParameter("source");
		String status = request.getParameter("status");
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");

		model.addAttribute("showCompanyColumn",
				DashboardOrgScope.showsCompanyColumn(current, filters.companyId()));
		model.addAttribute("filters", filters);
		model.addAttribute("source", source == null ? "" : source);
		model.addAttribute("status", status == null ? "" : status);
		model.addAttribute("dateFrom", dateFrom == null ? "" : dateFrom);
		model.addAttribute("dateTo", dateTo == null ? "" : dateTo);
		model.addAttribute("result", this.store.paginate(
				current, filters, source, status, dateFrom, dateTo));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "complaints"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Answers, restatuses or deletes one complaint. A "
			+ "company-scoped session may restatus only its employees' complaints. Gated by the "
			+ "surface flag and a bound second factor, tenant-checked, and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(required = false, defaultValue = "") String reply,
			@RequestParam(required = false, defaultValue = "") String status) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "reply" -> this.service.reply(session, adminId, bound, id, reply, status);
				case "set_status" -> this.service.setStatus(session, adminId, bound, id, status);
				case "delete" -> this.service.delete(session, adminId, bound, id);
				default -> throw new ComplaintAdminService.RefusedException(
						ComplaintAdminService.Refusal.FOREIGN_ROW);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (ComplaintAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(ComplaintAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case FOREIGN_ROW -> "error_db";
			case INVALID_STATUS -> "error_required";
		};
	}

}
