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
import com.workin.backend.platformadmin.hr.WorkforcePlan;
import com.workin.backend.platformadmin.hr.WorkforcePlanAdminService;
import com.workin.backend.platformadmin.hr.WorkforcePlanStore;

/** {@code dashboard/pages/workforce_planning/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminWorkforcePlanningController {

	private static final String VIEW = "admin/workforce-planning";

	private static final String PATH = PlatformAdminWebSecurityConfig.WORKFORCE_PLANNING_PATH;

	private final WorkforcePlanStore store;

	private final WorkforcePlanAdminService service;

	public AdminWorkforcePlanningController(
			WorkforcePlanStore store, WorkforcePlanAdminService service) {
		this.store = store;
		this.service = service;
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

		WorkforcePlan editRow = "edit".equals(action) ? visible(current, filters, id) : null;
		// R-051: the options are a server-side query per company, not every
		// company's shipped to the browser to filter. When editing, they follow
		// the row's own company rather than the filter -- an administrator with
		// no filter would otherwise be offered every company's branches for a
		// row that D-176 will only let stay in its own.
		long optionsCompanyId = editRow != null ? editRow.companyId() : filters.companyId();
		model.addAttribute("branchOptions", this.store.branchOptions(optionsCompanyId));
		model.addAttribute("departmentOptions", this.store.departmentOptions(optionsCompanyId));
		model.addAttribute("jobTitleOptions", this.store.jobTitleOptions(optionsCompanyId));
		model.addAttribute("canManage",
				DashboardAccess.canViewPage(current, "workforce_planning"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", "add".equals(action));
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
			@RequestParam(name = "planned_count", required = false, defaultValue = "0")
					int plannedCount) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "add_wp" -> this.service.add(
						session, adminId, bound, companyId, branchId, departmentId, jobTitleId,
						plannedCount);
				case "edit_wp" -> this.service.saveEdit(
						session, adminId, bound, id, branchId, departmentId, jobTitleId,
						plannedCount);
				case "delete_wp" -> this.service.delete(session, adminId, bound, id);
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
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
			case DUPLICATE_TARGET -> "already_exists";
		};
	}

}
