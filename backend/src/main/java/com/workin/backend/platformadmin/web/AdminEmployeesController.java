package com.workin.backend.platformadmin.web;

import java.math.BigDecimal;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.Employee;
import com.workin.backend.platformadmin.hr.EmployeeAdminService;
import com.workin.backend.platformadmin.hr.EmployeeStore;
import com.workin.backend.platformadmin.org.ActiveCompanies;
import com.workin.backend.platformadmin.org.OrgCascade;
import com.workin.backend.platformadmin.org.OrgCascadeStore;
import com.workin.legacy.PhpCast;

/** {@code dashboard/pages/employees/page.php}. */
@Controller
public class AdminEmployeesController {

	private static final String VIEW = "admin/employees";

	private static final String PATH = PlatformAdminWebSecurityConfig.EMPLOYEES_PATH;

	private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

	private final EmployeeStore store;

	private final EmployeeAdminService service;

	private final ActiveCompanies companies;

	private final OrgCascadeStore cascades;

	public AdminEmployeesController(EmployeeStore store, EmployeeAdminService service,
			ActiveCompanies companies, OrgCascadeStore cascades) {
		this.store = store;
		this.service = service;
		this.companies = companies;
		this.cascades = cascades;
	}

	@AuthenticatedUseCase(reason = "One company's employees, or every company's for an "
			+ "administrator through the session filter. Pending join requests are excluded; the "
			+ "form's branch, department, job-title and shift options are scoped to the same "
			+ "company as the row being edited.")
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

		String status = parameter(request, "filter", "all");
		long branchId = number(request, "filter_branch");
		long departmentId = number(request, "filter_department");
		long jobTitleId = number(request, "filter_job_title");
		String hireFrom = parameter(request, "date_from", "");
		String hireTo = parameter(request, "date_to", "");

		Employee.Form editRow = "edit".equals(action) ? visible(current, filters, id) : null;
		// R-051: server-side, and following the edited row's own company so an
		// unfiltered administrator is never offered options D-176 will refuse.
		long optionsCompanyId = editRow != null ? editRow.companyId() : filters.companyId();

		model.addAttribute("showCompanyColumn",
				DashboardOrgScope.showsCompanyColumn(current, filters.companyId()));
		model.addAttribute("filters", filters);
		model.addAttribute("status", status);
		model.addAttribute("filterBranch", branchId);
		model.addAttribute("filterDepartment", departmentId);
		model.addAttribute("filterJobTitle", jobTitleId);
		model.addAttribute("dateFrom", hireFrom);
		model.addAttribute("dateTo", hireTo);
		model.addAttribute("result", this.store.paginate(
				filters, status, branchId, departmentId, jobTitleId, hireFrom, hireTo));
		// An org row the employee already has is still offered once deactivated,
		// so an unchanged save keeps it instead of clearing it.
		model.addAttribute("branchOptions", this.store.branchOptions(
				optionsCompanyId, editRow == null ? 0 : editRow.branchId()));
		model.addAttribute("departmentOptions", this.store.departmentOptions(
				optionsCompanyId, editRow == null ? 0 : editRow.departmentId()));
		model.addAttribute("jobTitleOptions", this.store.jobTitleOptions(
				optionsCompanyId, editRow == null ? 0 : editRow.jobTitleId()));
		model.addAttribute("shiftOptions", this.store.shiftOptions(optionsCompanyId));
		boolean addOpen = "add".equals(action);
		// _employee_form.php: an add with no company to add under asks for one in the
		// form, employee-form.js fills the branch, department and job title selects for
		// the company chosen, and employee-shift.js the shift select (D-250).
		boolean pickCompany = addOpen && !current.isScopedToOneCompany() && filters.companyId() <= 0;
		// R-051: the maps carry only the companies this form may use -- the edited
		// employee's own, with the org rows they already have (D-176, D-250), the
		// filtered company's on an add, and every company only for an administrator with
		// no filter, whose reach that is. With no form open there is nothing to fill.
		OrgCascade cascade = editRow != null
				? this.cascades.cascade(editRow.companyId(), new OrgCascade.Kept(
						editRow.branchId(), editRow.departmentId(), editRow.jobTitleId()))
				: addOpen ? this.cascades.cascade(filters.companyId()) : OrgCascade.NONE;
		model.addAttribute("companyOptions", pickCompany ? this.companies.all() : java.util.List.of());
		model.addAttribute("branchesByCompany", JSON.writeValueAsString(cascade.branchesByCompany()));
		model.addAttribute("departmentsByCompany", JSON.writeValueAsString(cascade.departmentsByCompany()));
		model.addAttribute("departmentsByBranch", JSON.writeValueAsString(cascade.departmentsByBranch()));
		model.addAttribute("jobTitlesByDepartment", JSON.writeValueAsString(cascade.jobTitlesByDepartment()));
		model.addAttribute("jobTitlesByCompany", JSON.writeValueAsString(cascade.jobTitlesByCompany()));
		model.addAttribute("shiftsByCompany", JSON.writeValueAsString(
				pickCompany ? this.cascades.shiftsByCompany(filters.companyId()) : java.util.Map.of()));
		model.addAttribute("selectedJobLabel",
				editRow == null ? "" : cascade.jobTitleName(editRow.companyId(), editRow.jobTitleId()));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "employees"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", addOpen);
		model.addAttribute("editRow", editRow);
		return VIEW;
	}

	private Employee.Form visible(DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		Employee.Form row = this.store.editForm(id);
		return row != null && DashboardOrgScope.canOpenRow(session, filters, row.companyId())
				? row : null;
	}

	@AuthenticatedUseCase(reason = "Creates, edits, deactivates, reactivates or deletes one "
			+ "employee. Gated by the surface flag and a bound second factor. The row's own "
			+ "company is authoritative -- legacy checked none of this, which is R-053: its "
			+ "edit could set another tenant's employee's password and its delete cascades.")
	@PostMapping(PATH)
	public String submit(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			Model model, RedirectAttributes redirect) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();

		try {
			long wrote = switch (action) {
				case "add_employee" -> this.service.add(session, adminId, addCommand(request));
				case "save_edit" -> this.service.saveEdit(
						session, adminId, id, editCommand(request));
				case "deactivate" -> this.service.setActive(session, adminId, id, false);
				case "reactivate" -> this.service.setActive(session, adminId, id, true);
				case "delete" -> this.service.delete(session, adminId, id);
				default -> throw new EmployeeAdminService.RefusedException(
						EmployeeAdminService.Refusal.FOREIGN_ROW);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			switch (action) {
				case "deactivate" -> AdminFlash.warning(redirect, AdminFlash.t(model).apply("deactivate"));
				case "reactivate" -> AdminFlash.success(redirect, AdminFlash.t(model).apply("reactivate") + " ✓");
				case "delete" -> AdminFlash.deleted(redirect, model);
				default -> AdminFlash.saved(redirect, model);
			}
			return "redirect:" + PATH + AdminReturnTo.query(request, PATH, wrote);
		} catch (EmployeeAdminService.RefusedException refused) {
			return "redirect:" + PATH + AdminReturnTo.queryWithError(request, PATH, messageKey(refused));
		}
	}

	private static EmployeeAdminService.AddCommand addCommand(HttpServletRequest request) {
		return new EmployeeAdminService.AddCommand(
				number(request, "company_id"),
				nullableId(request, "branch_id"),
				nullableId(request, "department_id"),
				nullableId(request, "job_title_id"),
				number(request, "shift_id"),
				parameter(request, "first_name", ""),
				parameter(request, "last_name", ""),
				parameter(request, "employee_code", ""),
				parameter(request, "phone", ""),
				parameter(request, "country_code", ""),
				parameter(request, "password", ""),
				parameter(request, "national_id", ""),
				parameter(request, "birth_date", ""),
				parameter(request, "gender", ""),
				parameter(request, "address", ""),
				parameter(request, "hire_date", ""),
				parameter(request, "shift_effective_from", ""),
				parameter(request, "contract_duration", ""),
				parameter(request, "contract_duration_unit", "months"),
				checkbox(request, "is_mobile_attendance_enabled"),
				salary(request));
	}

	private static EmployeeAdminService.EditCommand editCommand(HttpServletRequest request) {
		return new EmployeeAdminService.EditCommand(
				nullableId(request, "branch_id"),
				nullableId(request, "department_id"),
				nullableId(request, "job_title_id"),
				number(request, "shift_id"),
				parameter(request, "first_name", ""),
				parameter(request, "last_name", ""),
				parameter(request, "employee_code", ""),
				parameter(request, "phone", ""),
				parameter(request, "country_code", ""),
				parameter(request, "password", ""),
				parameter(request, "national_id", ""),
				parameter(request, "birth_date", ""),
				parameter(request, "gender", ""),
				parameter(request, "address", ""),
				parameter(request, "hire_date", ""),
				parameter(request, "shift_effective_from", ""),
				parameter(request, "contract_duration", ""),
				parameter(request, "contract_duration_unit", "months"),
				checkbox(request, "is_mobile_attendance_enabled"));
	}

	/** Create path only: legacy's edit does not touch the salary contract. */
	private static EmployeeStore.EmployeeSalary salary(HttpServletRequest request) {
		return new EmployeeStore.EmployeeSalary(
				money(request, "basic_salary"), money(request, "transport"),
				money(request, "food"), money(request, "risk"), money(request, "incentives"),
				money(request, "insurance"), money(request, "tax"), money(request, "advances"),
				money(request, "fund"), money(request, "penalty"));
	}

	private static String parameter(HttpServletRequest request, String name, String fallback) {
		String value = request.getParameter(name);
		return value == null ? fallback : value.trim();
	}

	/** PHP's {@code (int) ($_POST[$name] ?? 0)} ({@link PhpCast#intval}). */
	private static long number(HttpServletRequest request, String name) {
		return PhpCast.intval(parameter(request, name, "0"));
	}

	/** Zero means "none" on these three columns, which are nullable. */
	private static Long nullableId(HttpServletRequest request, String name) {
		long value = number(request, name);
		return value > 0 ? value : null;
	}

	private static BigDecimal money(HttpServletRequest request, String name) {
		String raw = parameter(request, name, "");
		if (raw.isEmpty()) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(raw);
		}
		catch (NumberFormatException ex) {
			return BigDecimal.ZERO;
		}
	}

	private static boolean checkbox(HttpServletRequest request, String name) {
		String value = request.getParameter(name);
		// PHP's !empty(): absent, "", "0" and "false"-ish all read as unchecked.
		return value != null && !value.isEmpty() && !"0".equals(value);
	}

	private static String messageKey(EmployeeAdminService.RefusedException refused) {
		return switch (refused.refusal()) {
			case ACTIONS_DISABLED -> "admin_actions_disabled";
			case FOREIGN_ROW -> "error_db";
			case INVALID -> "error_required";
			case CODE_INVALID -> "employee_code_invalid";
			// R-054: legacy flashes `employee_code_already_exists`, which its own
			// dashboard catalogue does not define -- so the user is shown the
			// raw key. `already_exists` is defined and means this.
			case CODE_TAKEN -> "already_exists";
			case PHONE_INVALID -> "error_invalid_phone";
		};
	}

}
