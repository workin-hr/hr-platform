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
import com.workin.backend.platformadmin.stepup.PlatformAdminStepUpService;

/**
 * Platform administration of companies (ADR-0009 Option E).
 *
 * <p>Three steps on purpose. The list offers an action; the confirmation page
 * collects a TOTP code and mints an approval bound to <em>that</em> company with
 * <em>that</em> reason; applying spends it. Collapsing the last two would mean
 * minting and spending an approval in one request, which is the same as not
 * having one.
 *
 * <p>The approval id travels in a hidden field, and that is safe because it
 * proves nothing on its own: the service recomputes the action, target and
 * request digest server-side from the parameters it is about to act on, so a
 * tampered field fails the comparison rather than widening it.
 */
@Controller
public class PlatformAdminCompaniesController {

	private final PlatformAdminCompanyDirectory companies;
	private final PlatformAdminCompanyService companyService;
	private final PlatformAdminStepUpService stepUpService;

	public PlatformAdminCompaniesController(PlatformAdminCompanyDirectory companies,
			PlatformAdminCompanyService companyService, PlatformAdminStepUpService stepUpService) {
		this.companies = companies;
		this.companyService = companyService;
		this.stepUpService = stepUpService;
	}

	@AuthenticatedUseCase(reason = "Platform-wide oversight: the list of companies this "
			+ "surface administers. Read-only, and the only population it can act on.")
	@GetMapping(PlatformAdminWebSecurityConfig.COMPANIES_PATH)
	public String companies(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			Model model, HttpServletRequest request) {
		render(model, request, principal);
		return "admin/companies";
	}

	@AuthenticatedUseCase(reason = "Collects the second factor for one specific action against "
			+ "one specific company. Mints nothing until the code verifies.")
	@PostMapping(PlatformAdminWebSecurityConfig.COMPANIES_CONFIRM_PATH)
	public String confirm(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String action, @RequestParam long companyId,
			@RequestParam(required = false, defaultValue = "") String reason,
			@RequestParam(required = false) String code,
			Model model, HttpServletRequest request) {
		PlatformAdminWebCsrf.expose(model, request);
		model.addAttribute("currentAdminPhone", principal.phone());
		model.addAttribute("action", action);
		model.addAttribute("companyId", companyId);
		model.addAttribute("reason", reason);

		if (code == null || code.isBlank()) {
			model.addAttribute("approvalId", null);
			return "admin/company-confirm";
		}
		return this.stepUpService
			.approve(principal.platformAdminId(),
					this.companyService.request(action, companyId, reason), code)
			.map(approvalId -> {
				model.addAttribute("approvalId", approvalId);
				return "admin/company-confirm";
			})
			.orElseGet(() -> {
				model.addAttribute("approvalId", null);
				model.addAttribute("error", "That code was not accepted.");
				return "admin/company-confirm";
			});
	}

	@AuthenticatedUseCase(reason = "Applies one administrative action, spending a step-up "
			+ "approval the service re-derives and re-checks server-side. Refused outright "
			+ "while the surface is disabled (ADR-0015 prerequisite 7).")
	@PostMapping(PlatformAdminWebSecurityConfig.COMPANIES_APPLY_PATH)
	public String apply(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String action, @RequestParam long companyId,
			@RequestParam(required = false, defaultValue = "") String reason,
			@RequestParam String approvalId, Model model, HttpServletRequest request) {
		PlatformAdminCompanyService.Outcome outcome = this.companyService.apply(
				principal.platformAdminId(), principal.factorBound(),
				action, companyId, reason, approvalId);
		if (outcome == PlatformAdminCompanyService.Outcome.DONE) {
			return "redirect:" + PlatformAdminWebSecurityConfig.COMPANIES_PATH;
		}
		render(model, request, principal);
		model.addAttribute("error", switch (outcome) {
			case SURFACE_DISABLED -> "Administrative actions are disabled on this deployment.";
			case SECOND_FACTOR_NOT_BOUND -> "Bind a second factor before performing this action.";
			default -> "That approval was not accepted for this request.";
		});
		return "admin/companies";
	}

	private void render(Model model, HttpServletRequest request, PlatformAdminWebPrincipal principal) {
		PlatformAdminWebCsrf.expose(model, request);
		model.addAttribute("currentAdminPhone", principal.phone());
		model.addAttribute("actionsEnabled", this.companyService.actionsEnabled());
		model.addAttribute("companies", this.companies.list(200));
	}

}
