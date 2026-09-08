package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.CompanyForm;
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

	private final com.workin.legacy.phone.LegacyPhoneNumbers phoneNumbers;

	public PlatformAdminCompaniesController(PlatformAdminCompanyDirectory companies,
			PlatformAdminCompanyService companyService, CompanyDirectoryStore directory,
			com.workin.legacy.phone.LegacyPhoneNumbers phoneNumbers) {
		this.companies = companies;
		this.companyService = companyService;
		this.directory = directory;
		this.phoneNumbers = phoneNumbers;
	}

	@AuthenticatedUseCase(reason = "Platform-wide oversight: the list of companies this "
			+ "surface administers. Read-only, and the only population it can act on.")
	@GetMapping(PlatformAdminWebSecurityConfig.COMPANIES_PATH)
	public String companies(Model model, HttpServletRequest request,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) Long edit) {
		render(model, request);
		// ?action=add opens the form empty; ?edit=<id> opens it on that row --
		// the same two entry points companies.php offers.
		model.addAttribute("formOpen", "add".equals(action) || edit != null);
		model.addAttribute("editCompanyId", edit == null ? 0L : edit);
		// Prefilled from the row, as _company_form.php is: an edit form that
		// opens empty asks the operator to retype a company to change its
		// address, and a blank field that saves is a field that clears.
		model.addAttribute("editCompany",
				edit == null ? null : this.companies.editable(edit).orElse(null));
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

	@AuthenticatedUseCase(reason = "Creates a company, which provisions a login for its owner, "
			+ "or edits one. Multipart because the logo is required and the commercial "
			+ "registration optional, exactly as company_admin_create/update take them. "
			+ "CSRF-protected, refused while the surface is disabled, and audited.")
	@PostMapping(PlatformAdminWebSecurityConfig.COMPANIES_PATH + "/save")
	public String save(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String action,
			@RequestParam(required = false, defaultValue = "0") long id,
			@RequestParam(required = false) String company_name,
			@RequestParam(required = false) String first_name,
			@RequestParam(required = false) String last_name,
			@RequestParam(required = false) String country_code,
			@RequestParam(required = false) String phone,
			@RequestParam(required = false) String password,
			@RequestParam(required = false) String main_branch_address,
			@RequestParam(required = false) String company_activity_id,
			@RequestParam(required = false) String company_title_id,
			@RequestParam(required = false) String company_size_id,
			@RequestParam(required = false) String company_code,
			@RequestParam(required = false) org.springframework.web.multipart.MultipartFile logo,
			@RequestParam(required = false)
					org.springframework.web.multipart.MultipartFile commercial_reg,
			Model model, HttpServletRequest request) {

		boolean editing = "save_edit".equals(action);
		CompanyForm.Result form = CompanyForm.validate(this.phoneNumbers,
				this.companies.dialCodes(), editing,
				company_name, first_name, last_name, country_code, phone, password,
				main_branch_address, company_activity_id, company_title_id, company_size_id,
				company_code);

		PlatformAdminCompanyService.Saved saved = form.ok()
				? (editing
						? this.companyService.update(principal.platformAdminId(), id, form.write(),
								logo, commercial_reg)
						: this.companyService.create(principal.platformAdminId(), form.write(),
								logo, commercial_reg))
				: new PlatformAdminCompanyService.Saved(false, form.errorKey());

		if (saved.ok()) {
			return "redirect:" + PlatformAdminWebSecurityConfig.COMPANIES_PATH;
		}
		// Back to the form with the reason, which is what legacy's redirect to
		// ?action=add / ?action=edit does after flashing it.
		render(model, request);
		model.addAttribute("errorKey", saved.errorKey());
		model.addAttribute("formOpen", true);
		model.addAttribute("editCompanyId", editing ? id : 0L);
		model.addAttribute("editCompany",
				editing ? this.companies.editable(id).orElse(null) : null);
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
		model.addAttribute("formOpen", false);
		model.addAttribute("editCompanyId", 0L);
		model.addAttribute("editCompany", null);
	}

}
