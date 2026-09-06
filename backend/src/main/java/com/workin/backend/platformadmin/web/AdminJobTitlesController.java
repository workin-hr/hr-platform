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
import com.workin.backend.platformadmin.org.JobTitle;
import com.workin.backend.platformadmin.org.JobTitleAdminService;
import com.workin.backend.platformadmin.org.JobTitleStore;

/** {@code dashboard/pages/job_titles/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminJobTitlesController {

	private static final String VIEW = "admin/job-titles";

	private static final String PATH = PlatformAdminWebSecurityConfig.JOB_TITLES_PATH;

	private final JobTitleStore store;

	private final JobTitleAdminService service;

	public AdminJobTitlesController(JobTitleStore store, JobTitleAdminService service) {
		this.store = store;
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "One company's job titles and the departments they sit in. "
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
		model.addAttribute("departmentOptions", this.store.departmentOptions(filters.companyId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "job_titles"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", "add".equals(action));
		model.addAttribute("editRow", "edit".equals(action) ? visible(current, filters, id) : null);
		return VIEW;
	}

	private JobTitle visible(
			DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		JobTitle row = this.store.find(id);
		return row != null && DashboardOrgScope.canOpenRow(session, filters, row.companyId())
				? row : null;
	}

	@AuthenticatedUseCase(reason = "Creates, edits or deactivates a job title. Gated by the "
			+ "surface flag and a bound second factor, tenant-checked on the title and on the "
			+ "department it names, and audited.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(name = "company_id", required = false, defaultValue = "0") long companyId,
			@RequestParam(name = "department_id", required = false, defaultValue = "0") long departmentId,
			@RequestParam(required = false, defaultValue = "") String name,
			@RequestParam(name = "work_hours", required = false, defaultValue = "") String workHours,
			@RequestParam(name = "is_active", required = false) String isActive) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();
		Long department = departmentId > 0 ? departmentId : null;

		try {
			long wrote = switch (action) {
				case "add" -> this.service.add(
						session, adminId, bound, companyId, department, name, workHours);
				case "save_edit" -> this.service.saveEdit(session, adminId, bound, id, companyId,
						department, name, workHours,
						isActive == null || !"0".equals(isActive.trim()));
				case "delete" -> this.service.delete(session, adminId, bound, id, companyId);
				default -> throw new JobTitleAdminService.RefusedException(
						JobTitleAdminService.Refusal.NO_COMPANY);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (JobTitleAdminService.RefusedException refused) {
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

	private static String messageKey(JobTitleAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
			case NO_COMPANY -> "select_company_first";
			case FOREIGN_ROW -> "error_db";
			case FOREIGN_DEPARTMENT -> "select_company_first_department";
			case INVALID_FIELDS -> "error_required";
		};
	}

}
