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
import com.workin.backend.platformadmin.content.PhoneCountryAdminService;
import com.workin.backend.platformadmin.content.PhoneCountryForm;

/**
 * {@code dashboard/pages/phone_countries/page.php} -- the dial codes and
 * phone-length rules every client reads at startup.
 *
 * <p>{@code @Profile("phase1-mysql")}: the table belongs to the legacy
 * schema. {@link AdminPageAvailability} reads that back off the handler
 * mapping, so under another profile the sidebar shows the entry disabled
 * rather than offering a link that fails.
 *
 * <p>One page, three POST actions, distinguished by an {@code action}
 * field exactly as the dashboard does. Redirect-after-post throughout, so
 * a refresh cannot repeat a write.
 */
@Controller
@Profile("phase1-mysql")
public class AdminPhoneCountriesController {

	private static final String VIEW = "admin/phone-countries";

	private static final String REDIRECT = "redirect:" + PlatformAdminWebSecurityConfig.PHONE_COUNTRIES_PATH;

	private final PhoneCountryAdminService service;

	public AdminPhoneCountriesController(PhoneCountryAdminService service) {
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "The platform's dial codes and phone rules. Read by every "
			+ "mobile and desktop client; only a platform administrator may see or change them.")
	@GetMapping(PlatformAdminWebSecurityConfig.PHONE_COUNTRIES_PATH)
	public String list(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			Model model, @RequestParam(required = false) String error) {
		render(model, principal, error);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Adds one dial code. Gated again in the service by the "
			+ "surface flag and a bound second factor, and audited.")
	@PostMapping(PlatformAdminWebSecurityConfig.PHONE_COUNTRIES_PATH)
	public String submit(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String action,
			@RequestParam(required = false) Long id,
			@RequestParam(required = false) String countryCode,
			@RequestParam(required = false) String nameAr,
			@RequestParam(required = false) String nameEn,
			@RequestParam(required = false) String flagEmoji,
			@RequestParam(required = false) String phoneLength,
			@RequestParam(required = false) String prefixes,
			@RequestParam(required = false) String isActive,
			@RequestParam(required = false) String sortOrder,
			HttpServletRequest request) {

		boolean active = isActive != null && !isActive.isBlank();
		PhoneCountryAdminService.Result result = switch (action) {
			case "add" -> this.service.create(principal.platformAdminId(), principal.factorBound(),
					PhoneCountryForm.validate(countryCode, nameAr, nameEn, flagEmoji,
							phoneLength, prefixes, active, sortOrder));
			case "edit" -> id == null
					? new PhoneCountryAdminService.Result(PhoneCountryAdminService.Outcome.NOT_FOUND, "error_not_found")
					: this.service.update(principal.platformAdminId(), principal.factorBound(), id,
							PhoneCountryForm.validate(countryCode, nameAr, nameEn, flagEmoji,
									phoneLength, prefixes, active, sortOrder));
			case "delete" -> id == null
					? new PhoneCountryAdminService.Result(PhoneCountryAdminService.Outcome.NOT_FOUND, "error_not_found")
					: this.service.delete(principal.platformAdminId(), principal.factorBound(), id);
			// An unknown action is a client that does not match this form.
			// Answering "not found" rather than throwing keeps it a page the
			// operator can carry on using.
			default -> new PhoneCountryAdminService.Result(
					PhoneCountryAdminService.Outcome.NOT_FOUND, "error_not_found");
		};

		return result.ok() ? REDIRECT : REDIRECT + "?error=" + result.errorKey();
	}

	private void render(Model model, PlatformAdminWebPrincipal principal, String errorKey) {
		model.addAttribute("countries", this.service.list());
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", errorKey);
	}

}
