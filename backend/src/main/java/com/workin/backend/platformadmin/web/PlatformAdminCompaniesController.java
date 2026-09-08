package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.PlatformAdminCompanyDirectory;
import com.workin.backend.platformadmin.PlatformAdminCompanyService;
import com.workin.backend.platformadmin.companies.CompanyDirectoryStore;
import com.workin.backend.platformadmin.companies.CompanyListFilters;

/**
 * Platform administration of companies (ADR-0009 Option E).
 *
 * <p>One step, like every other page's actions: a CSRF-protected POST, the
 * surface flag, and an audit row in the same transaction. The three-step
 * ceremony that used to sit here -- offer, second factor, apply -- went with
 * the second factor (ADR-0018).
 */
@Controller
public class PlatformAdminCompaniesController {

	/** One company, for the detail page: the narrow view the actions also use. */
	private final PlatformAdminCompanyDirectory companies;

	private final PlatformAdminCompanyService companyService;

	/** The list: the dashboard's fourteen columns, five filters and pager. */
	private final CompanyDirectoryStore directory;

	public PlatformAdminCompaniesController(PlatformAdminCompanyDirectory companies,
			PlatformAdminCompanyService companyService, CompanyDirectoryStore directory) {
		this.companies = companies;
		this.companyService = companyService;
		this.directory = directory;
	}

	@AuthenticatedUseCase(reason = "Platform-wide oversight: the list of companies this "
			+ "surface administers. Read-only, and the only population it can act on.")
	@GetMapping(PlatformAdminWebSecurityConfig.COMPANIES_PATH)
	public String companies(Model model, HttpServletRequest request) {
		render(model, request);
		return "admin/companies";
	}

	@AuthenticatedUseCase(reason = "One company's detail, with the counts an operator needs before "
			+ "deciding. Read-only, and platform-scoped like the list it comes from.")
	@GetMapping(PlatformAdminWebSecurityConfig.COMPANIES_PATH + "/{companyId}")
	public String detail(
			@org.springframework.web.bind.annotation.PathVariable long companyId,
			Model model, HttpServletRequest request) {
		return this.companies.detail(companyId)
			.map(detail -> {
				PlatformAdminWebCsrf.expose(model, request);
				model.addAttribute("actionsEnabled", this.companyService.actionsEnabled());
				model.addAttribute("detail", detail);
				return "admin/company-detail";
			})
			.orElseGet(() -> "redirect:" + PlatformAdminWebSecurityConfig.COMPANIES_PATH);
	}

	@AuthenticatedUseCase(reason = "Applies one lifecycle action to one company: approve, reject "
			+ "(with a reason), suspend or restore. CSRF-protected, refused outright while the "
			+ "surface is disabled (ADR-0015 prerequisite 7), and audited in the same transaction.")
	@PostMapping(PlatformAdminWebSecurityConfig.COMPANIES_ACTION_PATH)
	public String act(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String action, @RequestParam long companyId,
			@RequestParam(required = false, defaultValue = "") String reason,
			Model model, HttpServletRequest request) {
		PlatformAdminCompanyService.Outcome outcome = this.companyService.apply(
				principal.platformAdminId(), action, companyId, reason);
		if (outcome == PlatformAdminCompanyService.Outcome.DONE) {
			return "redirect:" + PlatformAdminWebSecurityConfig.COMPANIES_PATH;
		}
		render(model, request);
		model.addAttribute("errorKey", switch (outcome) {
			case SURFACE_DISABLED -> "admin_actions_disabled";
			default -> "error_not_found";
		});
		return "admin/companies";
	}

	private void render(Model model, HttpServletRequest request) {
		PlatformAdminWebCsrf.expose(model, request);
		model.addAttribute("actionsEnabled", this.companyService.actionsEnabled());
		CompanyListFilters filters = CompanyListFilters.read(request);
		model.addAttribute("filters", filters);
		model.addAttribute("result", this.directory.list(filters));
		model.addAttribute("activities", this.directory.activities());
		model.addAttribute("titles", this.directory.titles());
		model.addAttribute("sizes", this.directory.sizes());
	}

}
