package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.WorkforcePlan;
import com.workin.backend.platformadmin.hr.WorkforcePlanAdminService;
import com.workin.backend.platformadmin.hr.WorkforcePlanStore;
import com.workin.backend.platformadmin.org.ActiveCompanies;
import com.workin.backend.platformadmin.org.OrgCascade;
import com.workin.backend.platformadmin.org.OrgCascadeStore;

/** {@code dashboard/pages/workforce_planning/page.php}. */
@Controller
public class AdminWorkforcePlanningController {

	private static final String VIEW = "admin/workforce-planning";

	private static final String PATH = PlatformAdminWebSecurityConfig.WORKFORCE_PLANNING_PATH;

	private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

	private final WorkforcePlanStore store;

	private final WorkforcePlanAdminService service;

	private final ActiveCompanies companies;

	private final OrgCascadeStore cascades;

	public AdminWorkforcePlanningController(WorkforcePlanStore store, WorkforcePlanAdminService service,
			ActiveCompanies companies, OrgCascadeStore cascades) {
		this.store = store;
		this.service = service;
		this.companies = companies;
		this.cascades = cascades;
	}

	@AuthenticatedUseCase(reason = "How many people a company plans for each branch, department "
			+ "and job title, beside how many it has. An administrator reaches every company's "
			+ "through the session filter; the form's options are scoped to the same company.")
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

		model.addAttribute("filters", filters);
		model.addAttribute("result", this.store.paginate(filters));

		boolean addOpen = "add".equals(action);
		WorkforcePlan editRow = "edit".equals(action) ? visible(current, filters, id) : null;
		// page.php: with no company to add under, the form asks for one through
		// org_render_company_field_in_form(), and workforce-form.js fills the branch,
		// department and job title selects for the company chosen.
		boolean pickCompany = addOpen && !current.isScopedToOneCompany() && filters.companyId() <= 0;
		// R-051: the maps carry only the companies this form may use -- the edited
		// row's own (D-176), the filtered company's on an add, and every company
		// only for an administrator with no filter, whose reach that is. With no
		// form open there is nothing to fill.
		OrgCascade cascade = editRow != null ? this.cascades.cascade(editRow.companyId())
				: addOpen ? this.cascades.cascade(filters.companyId()) : OrgCascade.NONE;
		model.addAttribute("companyOptions", pickCompany ? this.companies.all() : java.util.List.of());
		model.addAttribute("branchesByCompany", JSON.writeValueAsString(cascade.branchesByCompany()));
		model.addAttribute("departmentsByBranch", JSON.writeValueAsString(cascade.departmentsByBranch()));
		model.addAttribute("jobTitlesByDepartment", JSON.writeValueAsString(cascade.jobTitlesByDepartment()));
		model.addAttribute("jobTitlesByCompany", JSON.writeValueAsString(cascade.jobTitlesByCompany()));
		model.addAttribute("canManage",
				DashboardAccess.canViewPage(current, "workforce_planning"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", addOpen);
		model.addAttribute("editRow", editRow);
		return VIEW;
	}

	private WorkforcePlan visible(
			DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		WorkforcePlan row = this.store.find(id);
		return row != null && DashboardOrgScope.canOpenRow(session, filters, row.companyId())
				? row : null;
	}

	@AuthenticatedUseCase(reason = "Plans, re-points or removes one workforce target. Gated by "
			+ "the surface flag and a bound second factor. The row's own company is authoritative: "
			+ "an edit may move the target within it but never out of it, and never rewrites it.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(name = "branch_id", required = false, defaultValue = "0") long branchId,
			@RequestParam(name = "department_id", required = false, defaultValue = "0")
					long departmentId,
			@RequestParam(name = "job_title_id", required = false, defaultValue = "0")
					long jobTitleId,
			// Text, cast as legacy's (int) $_POST['planned_count'] is: the form is novalidate,
			// as legacy's is, so "1.5" or "1e2" can arrive, and an int parameter would
			// answer with a 400 page where legacy stores 1 or 100.
			@RequestParam(name = "planned_count", required = false, defaultValue = "0")
					String plannedCountText) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		// The store reads and writes planned_count as an int, so a count past 2147483647 is
		// stored at that bound; legacy's int(10) unsigned column keeps up to 4294967295 (D-249).
		int plannedCount = (int) Math.max(Integer.MIN_VALUE,
				Math.min(Integer.MAX_VALUE, com.workin.legacy.PhpCast.intval(plannedCountText)));

		try {
			long wrote = switch (action) {
				case "add_wp" -> this.service.add(
						session, adminId, companyId, branchId, departmentId, jobTitleId,
						plannedCount);
				case "edit_wp" -> this.service.saveEdit(
						session, adminId, id, branchId, departmentId, jobTitleId,
						plannedCount);
				case "delete_wp" -> this.service.delete(session, adminId, id);
				default -> throw new WorkforcePlanAdminService.RefusedException(
						WorkforcePlanAdminService.Refusal.FOREIGN_ROW);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (WorkforcePlanAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
		}
	}

	private static String messageKey(WorkforcePlanAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
			case DUPLICATE_TARGET -> "already_exists";
		};
	}

}
