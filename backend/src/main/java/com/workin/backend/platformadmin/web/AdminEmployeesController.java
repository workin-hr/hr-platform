package com.workin.backend.platformadmin.web;

import java.math.BigDecimal;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.Employee;
import com.workin.backend.platformadmin.hr.EmployeeAdminService;
import com.workin.backend.platformadmin.hr.EmployeeStore;

/** {@code dashboard/pages/employees/page.php}. */
@Controller
@Profile("phase1-mysql")
public class AdminEmployeesController {

	private static final String VIEW = "admin/employees";

	private static final String PATH = PlatformAdminWebSecurityConfig.EMPLOYEES_PATH;

	private final EmployeeStore store;

	private final EmployeeAdminService service;

	public AdminEmployeesController(EmployeeStore store, EmployeeAdminService service) {
		this.store = store;
		this.service = service;
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

		Employee editRow = "edit".equals(action) ? visible(current, filters, id) : null;
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
		model.addAttribute("branchOptions", this.store.branchOptions(optionsCompanyId));
		model.addAttribute("departmentOptions", this.store.departmentOptions(optionsCompanyId));
		model.addAttribute("jobTitleOptions", this.store.jobTitleOptions(optionsCompanyId));
		model.addAttribute("shiftOptions", this.store.shiftOptions(optionsCompanyId));
		model.addAttribute("canManage", DashboardAccess.canViewPage(current, "employees"));
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		model.addAttribute("addOpen", "add".equals(action));
		model.addAttribute("editRow", editRow);
		return VIEW;
	}

	private Employee visible(DashboardSession session, DashboardListFilters filters, Long id) {
		if (id == null || id <= 0) {
			return null;
		}
		Employee row = this.store.find(id);
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
			@RequestParam(required = false, defaultValue = "0") long id) {

		DashboardSession session = DashboardSession.admin(
				DashboardOrgScope.current(request.getSession(false)));
		long adminId = principal.platformAdminId();
		boolean bound = principal.factorBound();

		try {
			long wrote = switch (action) {
				case "add_employee" -> this.service.add(session, adminId, bound, addCommand(request));
				case "save_edit" -> this.service.saveEdit(
						session, adminId, bound, id, editCommand(request));
				case "deactivate" -> this.service.setActive(session, adminId, bound, id, false);
				case "reactivate" -> this.service.setActive(session, adminId, bound, id, true);
				case "delete" -> this.service.delete(session, adminId, bound, id);
				default -> throw new EmployeeAdminService.RefusedException(
						EmployeeAdminService.Refusal.FOREIGN_ROW);
			};
			DashboardOrgScope.rememberAfterWrite(session, request, wrote);
			return "redirect:" + PATH;
		} catch (EmployeeAdminService.RefusedException refused) {
			return "redirect:" + PATH + "?error=" + messageKey(refused);
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

	private static long number(HttpServletRequest request, String name) {
		try {
			return Long.parseLong(parameter(request, name, "0"));
		}
		catch (NumberFormatException ex) {
			// PHP's (int) cast, which reads anything unparseable as zero.
			return 0L;
		}
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
			case FACTOR_NOT_BOUND -> "mfa_required_for_actions";
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
