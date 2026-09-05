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
import com.workin.backend.platformadmin.hr.AdministrativeDecision;
import com.workin.backend.platformadmin.hr.AdministrativeDecisionAdminService;
import com.workin.backend.platformadmin.hr.AdministrativeDecisionStore;

/** {@code dashboard/pages/administrative_decisions/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminDecisionsController {

	private static final String VIEW = "admin/administrative-decisions";

	private static final String PATH =
			PlatformAdminWebSecurityConfig.ADMINISTRATIVE_DECISIONS_PATH;

	private final AdministrativeDecisionStore store;

	private final AdministrativeDecisionAdminService service;

	public AdminDecisionsController(
			AdministrativeDecisionStore store, AdministrativeDecisionAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's administrative decisions, which its employees "
			+ "read in the app. An administrator reaches every company's through the session "
			+ "filter.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) Long id,
			@RequestParam(required = false) String error) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		model.addAttribute("showCompanyColumn",
				DashboardOrgScope.showsCompanyColumn(current, filters.companyId()));
		model.addAttribute("filters", filters);
		model.addAttribute("result", this.store.paginate(filters));
		model.addAttribute("canManage",
				DashboardAccess.canViewPage(current, "administrative_decisions"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", "add".equals(action));
		model.addAttribute("editRow", "edit".equals(action) ? visible(current, filters, id) : null);
		return VIEW;
	}

	private AdministrativeDecision visible(
			DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		AdministrativeDecision row = this.store.find(id);
		return row != null && DashboardOrgScope.canOpenRow(session, filters, row.companyId())
				? row : null;
	}

	@AuthenticatedUseCase(reason = "Creates, edits or deletes one administrative decision. The "
			+ "edit cannot change which company owns it (D-176). Gated by the surface flag and "
			+ "a bound second factor, tenant-checked, and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(required = false, defaultValue = "") String title,
			@RequestParam(required = false, defaultValue = "") String body,
			@RequestParam(name = "is_active", required = false) String isActive) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();
		// `!empty($_POST['is_active'])` -- an unchecked box is absent, and
		// absent is inactive. The opposite default from the org pages, where an
		// absent flag means active.
		boolean active = isActive != null && !isActive.isBlank() && !"0".equals(isActive.trim());

		try {
			long wrote = switch (action) {
				case "add_decision" -> this.service.add(
						session, adminId, bound, companyId, title, body, active);
				case "edit_decision" -> this.service.saveEdit(
						session, adminId, bound, id, title, body, active);
				case "delete_decision" -> this.service.delete(session, adminId, bound, id);
				default -> throw new AdministrativeDecisionAdminService.RefusedException(
						AdministrativeDecisionAdminService.Refusal.INVALID);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (AdministrativeDecisionAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(
			AdministrativeDecisionAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case INVALID -> "error_required";
			case FOREIGN_ROW -> "error_db";
		};
	}

}
