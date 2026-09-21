package com.workin.backend.platformadmin.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.org.ActiveCompanies;
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
public class AdminDepartmentsController {

	private static final String VIEW = "admin/departments";

	private static final String PATH = PlatformAdminWebSecurityConfig.DEPARTMENTS_PATH;

	private final DepartmentStore store;

	private final DepartmentAdminService service;

	private final ActiveCompanies companies;

	private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

	public AdminDepartmentsController(DepartmentStore store, DepartmentAdminService service, ActiveCompanies companies) {
		this.store = store;
		this.service = service;
		this.companies = companies;
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
		List<Department.BranchOption> branchOptions = this.store.branchOptions(filters.companyId());
		model.addAttribute("branchOptions", branchOptions);
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "departments"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		boolean addOpen = "add".equals(action);
		Department editRow = "edit".equals(action) ? visible(current, filters, id) : null;
		// _department_form.php: with no company to add under, the form asks for one and
		// department-form.js renders that company's active branches as cards.
		boolean pickCompany = addOpen && !current.isScopedToOneCompany() && filters.companyId() <= 0;
		model.addAttribute("addOpen", addOpen);
		model.addAttribute("editRow", editRow);
		model.addAttribute("pickCompany", pickCompany);
		model.addAttribute("companyOptions", pickCompany ? this.companies.all() : List.of());
		model.addAttribute("branchesByCompany", pickCompany ? branchesByCompany() : "{}");
		model.addAttribute("selectedBranches", JSON.writeValueAsString(
				editRow == null ? List.of() : editRow.branchIds()));
		// The cards are one company's: the row's on an edit, including a retired branch
		// the department is still linked to, or on a filtered add the toolbar's list,
		// which is already that company's. With no form open, none.
		model.addAttribute("formBranches", editRow != null
				? this.store.editBranchOptions(editRow.companyId(), editRow.id())
				: addOpen && !pickCompany ? branchOptions : List.of());
		return VIEW;
	}

	/** {@code org_branches_grouped_by_company()}, as the JSON department-form.js reads. */
	private String branchesByCompany() {
		java.util.Map<String, List<java.util.Map<String, Object>>> grouped = new java.util.LinkedHashMap<>();
		this.store.activeBranchesByCompany().forEach((company, branches) -> grouped.put(
				String.valueOf(company),
				branches.stream().map(branch -> java.util.Map.<String, Object>of(
						"id", branch.id(), "name", branch.name())).toList()));
		return JSON.writeValueAsString(grouped);
	}

	/** {@code dbFind()} then {@link DashboardOrgScope#canOpenRow}. */
	private Department visible(
			DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		Department row = this.store.find(id);
		return row != null && DashboardOrgScope.canOpenRow(session, filters, row.companyId())
				? row : null;
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
			// Also binds branch_ids[], the name department-form.js's cards post: Spring's
			// resolver reads name + "[]" when the plain name is absent.
			@RequestParam(name = "branch_ids", required = false) String[] branchIds,
			@RequestParam(name = "is_active", required = false) String isActive,
			Model model, RedirectAttributes redirect) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		List<Long> branches = Department.parseBranchIds(branchIds);

		try {
			long wrote = switch (action) {
				case "add" -> this.service.add(session, adminId, companyId, name, branches);
				case "save_edit" -> this.service.saveEdit(session, adminId, id, companyId,
						name, branches, isActive == null || !"0".equals(isActive.trim()));
				case "delete" -> this.service.delete(session, adminId, id, companyId);
				default -> throw new DepartmentAdminService.RefusedException(
						DepartmentAdminService.Refusal.NO_COMPANY);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			switch (action) {
				case "delete" -> AdminFlash.deleted(redirect, model);
				default -> AdminFlash.saved(redirect, model);
			}
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
			case NO_COMPANY -> "select_company_first";
			case FOREIGN_ROW -> "error_db";
			case NO_ROW -> "no_data";
			case NAME_REQUIRED -> "error_required";
			case BAD_BRANCHES -> "select_at_least_one_branch";
		};
	}

}
