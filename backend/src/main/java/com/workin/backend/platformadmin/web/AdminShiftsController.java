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
import com.workin.backend.platformadmin.org.Shift;
import com.workin.backend.platformadmin.org.ShiftAdminService;
import com.workin.backend.platformadmin.org.ShiftStore;

/** {@code dashboard/pages/shifts/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminShiftsController {

	private static final String VIEW = "admin/shifts";

	private static final String PATH = PlatformAdminWebSecurityConfig.SHIFTS_PATH;

	private final ShiftStore store;

	private final ShiftAdminService service;

	public AdminShiftsController(ShiftStore store, ShiftAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's shifts. An administrator reaches every "
			+ "company's through the session filter.")
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

		boolean showCompany = DashboardOrgScope.showsCompanyColumn(current, filters.companyId());
		model.addAttribute("showCompanyColumn", showCompany);
		model.addAttribute("filters", filters);
		model.addAttribute("result", this.store.paginate(filters, showCompany));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "shifts"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", "add".equals(action));
		model.addAttribute("editRow", "edit".equals(action) ? visible(current, filters, id) : null);
		return VIEW;
	}

	private Shift visible(
			DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		Shift row = this.store.find(id);
		return row != null && DashboardOrgScope.canOpenRow(session, filters, row.companyId())
				? row : null;
	}

	@AuthenticatedUseCase(reason = "Creates, edits or deactivates a shift. Gated by the surface "
			+ "flag and a bound second factor, tenant-checked, and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(required = false, defaultValue = "") String name,
			@RequestParam(name = "start_time", required = false) String startTime,
			@RequestParam(name = "end_time", required = false) String endTime,
			@RequestParam(name = "is_active", required = false) String isActive) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "add" -> this.service.add(
						session, adminId, bound, companyId, name, startTime, endTime);
				case "save_edit" -> this.service.saveEdit(session, adminId, bound, id, companyId,
						name, startTime, endTime, isActive == null || !"0".equals(isActive.trim()));
				case "delete" -> this.service.delete(session, adminId, bound, id, companyId);
				default -> throw new ShiftAdminService.RefusedException(
						ShiftAdminService.Refusal.NO_COMPANY);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (ShiftAdminService.RefusedException refused) {
			return "redirect:" + PATH + failureTail(action, id) + "&error=" + messageKey(refused);
		}
	}

	private static String failureTail(String action, long id) {
		return switch (action) {
			case "add" -> "?action=add";
			case "save_edit" -> "?action=edit&id=" + id;
			default -> "?";
		};
	}

	private static String messageKey(ShiftAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case NO_COMPANY -> "select_company_first";
			case FOREIGN_ROW -> "error_db";
			case NAME_REQUIRED -> "error_required";
		};
	}

}
