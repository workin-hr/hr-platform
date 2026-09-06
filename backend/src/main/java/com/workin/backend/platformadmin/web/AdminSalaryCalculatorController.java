package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.payroll.SalaryCalculatorForm;

/**
 * {@code dashboard/pages/salary_calculator/page.php}.
 *
 * <p>The one ported page with no tenant dimension at all: it reads no table,
 * writes none, and takes no row id, so there is nothing for <b>D-176</b> to
 * anchor and nothing for a company filter to scope. The only gate it needs is
 * the section permission, which legacy applies through
 * {@code payroll_require_section()} and which is present here for the same
 * reason it is on the employee detail page -- dormant while every session on
 * this surface is a platform administrator, and waiting for the audiences
 * <b>R-044</b> still has open.
 *
 * <p>GET and POST render the same page. The form posts, but every field is
 * also readable from the query string, and legacy resolves the two in that
 * order.
 */
@Controller
@Profile("phase1-mysql")
public class AdminSalaryCalculatorController {

	private static final String VIEW = "admin/salary-calculator";

	private static final String PATH = PlatformAdminWebSecurityConfig.SALARY_CALCULATOR_PATH;

	@AuthenticatedUseCase(reason = "An Egyptian net-pay estimate from figures typed into the "
			+ "form. Touches no table and no company: nothing is read, nothing is written, and "
			+ "the result is not persisted. Gated by the salary-calculator permission.")
	@GetMapping(PATH)
	public String page(HttpServletRequest request, Model model) {
		return render(request, model);
	}

	@AuthenticatedUseCase(reason = "The same estimate, from the form's own POST. It computes and "
			+ "renders; there is nothing to store, so there is nothing to redirect after.")
	@PostMapping(PATH)
	public String submit(HttpServletRequest request, Model model) {
		return render(request, model);
	}

	private String render(HttpServletRequest request, Model model) {
		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession current = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", current);

		if (!DashboardAccess.canViewPayrollSection(current, "salary_calculator")) {
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}

		// isset($_GET['reset']): the key's presence in the query string, whatever
		// its value -- the page's reset link is a bare ?reset=1, and ?reset= or
		// ?reset alone resets just the same.
		boolean reset = hasQueryKey(request, "reset");

		SalaryCalculatorForm form = reset ? SalaryCalculatorForm.empty()
				: SalaryCalculatorForm.of(field(request, "gross"), field(request, "si_base"),
						field(request, "other_non_taxable"));

		model.addAttribute("grossRaw", form.grossRaw());
		model.addAttribute("siRaw", form.siRaw());
		model.addAttribute("otherRaw", form.otherRaw());
		// A gross of zero or less renders the empty panel rather than a result,
		// so "-5000" prints nothing while "0.5" prints a loss -- the clamped
		// insurance floor costs more than the salary is worth.
		model.addAttribute("result", form.estimate());
		return VIEW;
	}

	/**
	 * {@code $_GET[$name] ?? $_POST[$name] ?? ''}.
	 *
	 * <p>The servlet specification presents query-string parameters before
	 * body parameters, so {@code getParameter} already resolves the two in
	 * legacy's order -- and, like {@code ??}, an empty query value wins over a
	 * populated body one rather than falling through to it.
	 */
	private static String field(HttpServletRequest request, String name) {
		String value = request.getParameter(name);
		return value == null ? "" : value;
	}

	private static boolean hasQueryKey(HttpServletRequest request, String name) {
		String query = request.getQueryString();
		if (query == null) {
			return false;
		}
		for (String pair : query.split("&")) {
			int equals = pair.indexOf('=');
			if (name.equals(equals < 0 ? pair : pair.substring(0, equals))) {
				return true;
			}
		}
		return false;
	}

}
