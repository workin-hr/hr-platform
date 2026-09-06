package com.workin.backend.platformadmin.web;

import java.time.LocalDate;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.hr.Employee;
import com.workin.backend.platformadmin.hr.EmployeeDetailStore;
import com.workin.backend.platformadmin.hr.EmployeeStore;

/**
 * {@code dashboard/pages/employees/detail.php}, reached in legacy as
 * {@code employee_detail.php}.
 *
 * <p>Read-only, and the whole of <b>R-057</b> is that legacy guarded it with
 * {@code requireLogin()} and nothing else: no section permission, and a tenant
 * predicate on {@code isCompany()}, which is the company-owner session flag
 * alone. An HR session took neither branch and could read any employee in any
 * company -- pay, attendance, penalties, advances, payslip and documents.
 */
@Controller
@Profile("phase1-mysql")
public class AdminEmployeeDetailController {

	private static final String VIEW = "admin/employee-detail";

	private static final String PATH = PlatformAdminWebSecurityConfig.EMPLOYEE_DETAIL_PATH;

	private final EmployeeStore store;

	private final EmployeeDetailStore detailStore;

	public AdminEmployeeDetailController(EmployeeStore store, EmployeeDetailStore detailStore) {
		this.store = store;
		this.detailStore = detailStore;
	}

	@AuthenticatedUseCase(reason = "One employee's record for a chosen month: salary, leave, "
			+ "attendance, requests, penalties, advances, payslip and documents. Gated by the "
			+ "employees permission the sibling list page uses, and confined to the employee's "
			+ "own company for any session that is scoped to one.")
	@GetMapping(PATH)
	public String page(
			@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request, Model model,
			@RequestParam(required = false, defaultValue = "") String id,
			@RequestParam(required = false, defaultValue = "") String month,
			@RequestParam(required = false, defaultValue = "") String year) {

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		// The guard legacy's sibling page has and this one did not, redirecting
		// to the dashboard root as hr_require_section() redirects to index.php.
		// On this surface every session is a platform administrator, so it does
		// not bite today -- it is here for the company-owner and HR surfaces
		// that R-044 still has open, which are exactly the audience R-057
		// exposed.
		if (!DashboardAccess.canViewPage(current, "employees")) {
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}

		long employeeId = asInt(id, 0);
		Employee employee = employeeId > 0 ? this.store.find(employeeId) : null;
		if (employee == null
				|| !DashboardOrgScope.canOpenRow(current, filters, employee.companyId())) {
			// Legacy flashes no_data and returns to the list rather than
			// answering whether the id exists.
			return "redirect:" + PlatformAdminWebSecurityConfig.EMPLOYEES_PATH + "?error=no_data";
		}

		LocalDate today = LocalDate.now();
		int selectedMonth = clampMonth(asInt(month, today.getMonthValue()));
		int selectedYear = asInt(year, today.getYear());

		model.addAttribute("detail",
				this.detailStore.of(employee, selectedMonth, selectedYear));
		model.addAttribute("canManage", true);
		model.addAttribute("factorBound", principal.factorBound());
		return VIEW;
	}

	/**
	 * PHP's {@code (int)} cast, which these three parameters all go through.
	 *
	 * <p>Binding them as {@code long}/{@code Integer} instead would answer 400
	 * to {@code ?id=abc}, where legacy reads zero and redirects to the list --
	 * a difference a crawler or a stale bookmark finds immediately.
	 */
	private static int asInt(String raw, int fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(raw.trim());
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

	/**
	 * Legacy's month comes from {@code (int) $_GET['month']} with no bound, so
	 * {@code month=0} or {@code month=99} simply matches no attendance row. The
	 * port keeps the request from reaching SQL with a nonsense month but does
	 * not change what a valid one returns.
	 */
	private static int clampMonth(int month) {
		if (month < 1 || month > 12) {
			return LocalDate.now().getMonthValue();
		}
		return month;
	}

}
