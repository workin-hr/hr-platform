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
import com.workin.backend.platformadmin.hr.CompanyAssetAdminService;
import com.workin.backend.platformadmin.hr.CompanyAssetStore;

/** {@code dashboard/pages/assets/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminAssetsController {

	private static final String VIEW = "admin/assets";

	private static final String PATH = PlatformAdminWebSecurityConfig.ASSETS_PATH;

	private final CompanyAssetStore store;

	private final CompanyAssetAdminService service;

	public AdminAssetsController(CompanyAssetStore store, CompanyAssetAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's assets on loan to employees. An administrator "
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

		String returned = request.getParameter("returned") == null
				? "all" : request.getParameter("returned");
		String dateFrom = request.getParameter("date_from");
		String dateTo = request.getParameter("date_to");
		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());

		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("returned", returned);
		model.addAttribute("dateFrom", dateFrom == null ? "" : dateFrom);
		model.addAttribute("dateTo", dateTo == null ? "" : dateTo);
		model.addAttribute("result", this.store.paginate(
				filters, returned, dateFrom, dateTo, showCompany));
		model.addAttribute("employeeOptions", this.store.employeeOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "assets"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Assigns, edits, marks returned or deletes one asset. "
			+ "An asset already returned cannot be edited. Gated by the surface flag and a "
			+ "bound second factor, tenant-checked through the employee join (R-046), and "
			+ "audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "employee_id", required = false, defaultValue = "0") long employeeId,
			@RequestParam(name = "asset_text", required = false, defaultValue = "") String assetText,
			@RequestParam(name = "asset_date", required = false, defaultValue = "") String assetDate,
			@RequestParam(name = "asset_end_date", required = false, defaultValue = "") String assetEndDate) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "add_asset" -> this.service.add(
						session, adminId, bound, employeeId, assetText, assetDate, assetEndDate);
				case "edit_asset" -> this.service.saveEdit(session, adminId, bound, id,
						employeeId, assetText, assetDate, assetEndDate);
				case "mark_returned" -> this.service.markReturned(session, adminId, bound, id);
				case "delete_asset" -> this.service.delete(session, adminId, bound, id);
				default -> throw new CompanyAssetAdminService.RefusedException(
						CompanyAssetAdminService.Refusal.INVALID);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (CompanyAssetAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(CompanyAssetAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
		};
	}

}
