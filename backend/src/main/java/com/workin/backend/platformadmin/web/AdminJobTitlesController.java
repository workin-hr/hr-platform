package com.workin.backend.platformadmin.web;

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
import com.workin.backend.platformadmin.org.JobTitle;
import com.workin.backend.platformadmin.org.JobTitleAdminService;
import com.workin.backend.platformadmin.org.JobTitleStore;

/** {@code dashboard/pages/job_titles/page.php}. */
@Controller
public class AdminJobTitlesController {

	private static final String VIEW = "admin/job-titles";

	private static final String PATH = PlatformAdminWebSecurityConfig.JOB_TITLES_PATH;

	private final JobTitleStore store;

	private final JobTitleAdminService service;

	private final ActiveCompanies companies;

	private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

	public AdminJobTitlesController(JobTitleStore store, JobTitleAdminService service, ActiveCompanies companies) {
		this.store = store;
		this.service = service;
		this.companies = companies;
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
		java.util.List<JobTitle.DepartmentOption> departmentOptions = this.store.departmentOptions(filters.companyId());
		model.addAttribute("departmentOptions", departmentOptions);
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "job_titles"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		boolean addOpen = "add".equals(action);
		JobTitle editRow = "edit".equals(action) ? visible(current, filters, id) : null;
		// _job_title_form.php: with no company to add under, the form asks for one and
		// job-title-form.js fills the departments from that company's active ones.
		boolean pickCompany = addOpen && !current.isScopedToOneCompany() && filters.companyId() <= 0;
		model.addAttribute("addOpen", addOpen);
		model.addAttribute("editRow", editRow);
		model.addAttribute("pickCompany", pickCompany);
		model.addAttribute("companyOptions", pickCompany ? this.companies.all() : java.util.List.of());
		model.addAttribute("departmentsByCompany", pickCompany ? departmentsByCompany() : "{}");
		// The form's own list is one company's: the row's on an edit, including the
		// department it already has once that is retired, or on a filtered add the
		// toolbar's list, which is already that company's. With no form open, none.
		model.addAttribute("formDepartments", editRow != null
				? this.store.departmentOptions(editRow.companyId(), editRow.departmentId())
				: addOpen && !pickCompany ? departmentOptions : java.util.List.of());
		return VIEW;
	}

	/** {@code org_departments_grouped_by_company()}, as the JSON job-title-form.js reads. */
	private String departmentsByCompany() {
		java.util.Map<String, java.util.List<java.util.Map<String, Object>>> grouped = new java.util.LinkedHashMap<>();
		this.store.activeDepartmentsByCompany().forEach((company, departments) -> grouped.put(
				String.valueOf(company),
				departments.stream().map(department -> java.util.Map.<String, Object>of(
						"id", department.id(), "name", department.name())).toList()));
		return JSON.writeValueAsString(grouped);
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
			@RequestParam(name = "is_active", required = false) String isActive,
			Model model, RedirectAttributes redirect) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		Long department = departmentId > 0 ? departmentId : null;

		try {
			long wrote = switch (action) {
				case "add" -> this.service.add(
						session, adminId, companyId, department, name, workHours);
				case "save_edit" -> this.service.saveEdit(session, adminId, id, companyId,
						department, name, workHours,
						isActive == null || !"0".equals(isActive.trim()));
				case "delete" -> this.service.delete(session, adminId, id, companyId);
				default -> throw new JobTitleAdminService.RefusedException(
						JobTitleAdminService.Refusal.NO_COMPANY);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			switch (action) {
				case "delete" -> AdminFlash.deleted(redirect, model);
				default -> AdminFlash.saved(redirect, model);
			}
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
			case NO_COMPANY -> "select_company_first";
			case FOREIGN_ROW -> "error_db";
			case NO_ROW -> "no_data";
			case FOREIGN_DEPARTMENT -> "select_company_first_department";
			case INVALID_FIELDS -> "error_required";
		};
	}

}
