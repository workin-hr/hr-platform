package com.workin.backend.platformadmin.web;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.content.BroadcastAdminService;
import com.workin.backend.platformadmin.content.BroadcastAudience;

/**
 * {@code dashboard/pages/notifications/page.php} -- the platform broadcast.
 *
 * <p>The reach is shown on the page before anything is sent, because the
 * number is what makes this a decision rather than a click.
 */
@Controller
@Profile("phase1-mysql")
public class AdminNotificationsController {

	private static final String VIEW = "admin/notifications";

	private static final String REDIRECT = "redirect:" + PlatformAdminWebSecurityConfig.NOTIFICATIONS_PATH;

	private final BroadcastAdminService service;

	public AdminNotificationsController(BroadcastAdminService service) {
		this.service = service;
	}

	@AuthenticatedUseCase(reason = "The platform broadcast, which writes a notification to every "
			+ "employee it addresses. Only a platform administrator may see or send one.")
	@GetMapping(PlatformAdminWebSecurityConfig.NOTIFICATIONS_PATH)
	public String list(@AuthenticationPrincipal PlatformAdminWebPrincipal principal, Model model,
			@RequestParam(required = false) String error,
			@RequestParam(required = false) Integer sent) {
		model.addAttribute("audiences", BroadcastAudience.values());
		model.addAttribute("reach", this.service.reachOfAllEmployees());
		model.addAttribute("recent", this.service.recent());
		model.addAttribute("actionsEnabled", this.service.actionsEnabled());
		model.addAttribute("factorBound", principal.factorBound());
		model.addAttribute("errorKey", error);
		model.addAttribute("sentCount", sent);
		return VIEW;
	}

	@AuthenticatedUseCase(reason = "Sends one broadcast. Gated in the service by the surface "
			+ "flag, a bound second factor and an explicit confirmation, and audited with its reach.")
	@PostMapping(PlatformAdminWebSecurityConfig.NOTIFICATIONS_PATH)
	public String send(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String audience,
			@RequestParam(required = false) String title,
			@RequestParam(required = false) String body,
			@RequestParam(required = false) Long companyId,
			@RequestParam(required = false) String confirmBroadcast) {

		BroadcastAdminService.Result result = this.service.send(
				principal.platformAdminId(), principal.factorBound(), audience, title, body,
				companyId, confirmBroadcast != null && !confirmBroadcast.isBlank());

		return result.ok()
				? REDIRECT + "?sent=" + result.recipients()
				: REDIRECT + "?error=" + result.errorKey();
	}

}
