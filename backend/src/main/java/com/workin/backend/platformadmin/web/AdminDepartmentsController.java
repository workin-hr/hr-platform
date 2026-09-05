package com.workin.backend.platformadmin.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.org.Department;
import com.workin.backend.platformadmin.org.DepartmentAdminService;
import com.workin.backend.platformadmin.org.DepartmentStore;

/**
 * {@code dashboard/pages/departments/page.php}.
 *
 * <p>The same three states as branches -- list, add, edit -- with one extra
 * moving part: the branch picker, whose options depend on the company
 * currently filtered to.
 */
@Controller
@Profile("phase1-mysql")
public class AdminDepartmentsController {

	private static final String VIEW = "admin/departments";

	private static final String PATH = PlatformAdminWebSecurityConfig.DEPARTMENTS_PATH;

	private final DepartmentStore store;

	private final DepartmentAdminService service;

	public AdminDepartmentsController(DepartmentStore store, DepartmentAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's departments and the branches they span. "
			+ "An administrator reaches every company's through the session filter.")
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
		model.addAttribute("branchOptions", this.store.branchOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "departments"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", "add".equals(action));
		model.addAttribute("editRow", "edit".equals(action) ? visible(current, filters, id) : null);
		return VIEW;
	}

	/** The same visibility rule as branches: see {@code AdminBranchesController}. */
	private Department visible(DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		Department row = this.store.find(id);
		if (row == null) {
			return null;
		}
		if (session.isScopedToOneCompany()) {
			return row.companyId() == session.companyId() ? row : null;
		}
		return filters.companyId() > 0 && row.companyId() != filters.companyId() ? null : row;
	}

	@AuthenticatedUseCase(reason = "Creates, edits or deactivates a department and the branches "
			+ "it spans. Gated by the surface flag and a bound second factor, tenant-checked "
			+ "on both the department and every branch it names, and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(required = false, defaultValue = "") String name,
			@RequestParam(name = "branch_ids", required = false) String[] branchIds,
			@RequestParam(name = "is_active", required = false) String isActive) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();
		List<Long> branches = Department.parseBranchIds(branchIds);

		try {
			long wrote = switch (action) {
				case "add" -> this.service.add(session, adminId, bound, companyId, name, branches);
				case "save_edit" -> this.service.saveEdit(session, adminId, bound, id, companyId,
						name, branches, isActive == null || !"0".equals(isActive.trim()));
				case "delete" -> this.service.delete(session, adminId, bound, id, companyId);
				default -> throw new DepartmentAdminService.RefusedException(
						DepartmentAdminService.Refusal.NO_COMPANY);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (DepartmentAdminService.RefusedException refused) {
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

	private static String messageKey(DepartmentAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case NO_COMPANY -> "select_company_first";
			case FOREIGN_ROW -> "error_db";
			case NAME_REQUIRED -> "error_required";
			case BAD_BRANCHES -> "select_at_least_one_branch";
		};
	}

}
