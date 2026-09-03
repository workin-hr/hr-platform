package com.workin.backend.platformadmin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;

/**
 * Lists and revokes the administrator's own sessions (ADR-0015 prerequisite 13,
 * delivering ADR-0005's requirement rather than deferring it).
 *
 * <p>Scoped to the caller throughout. Revoking another administrator's session
 * would be a privileged operation, and this surface performs none.
 */
@Controller
public class PlatformAdminSessionsController {

	private final PlatformAdminSessionInventory inventory;

	public PlatformAdminSessionsController(PlatformAdminSessionInventory inventory) {
		this.inventory = inventory;
	}

	@AuthenticatedUseCase(reason = "Lists the caller's own sessions. No catalog permission: "
			+ "the platform-admin domain is separate from the tenant permission model, and "
			+ "this reads nothing belonging to anyone else.")
	@GetMapping(PlatformAdminWebSecurityConfig.SESSIONS_PATH)
	public String sessions(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			Model model, HttpServletRequest request) {
		PlatformAdminWebCsrf.expose(model, request);
		model.addAttribute("currentAdminPhone", principal.phone());
		model.addAttribute("sessions", this.inventory.browserSessions(
				principal.platformAdminId(), currentSessionId(request)));
		return "admin/sessions";
	}

	@AuthenticatedUseCase(reason = "Ends one of the caller's own sessions. Ownership is "
			+ "re-checked in the service against the caller's id, because a session id is an "
			+ "opaque string the form could contain anything in.")
	@PostMapping(PlatformAdminWebSecurityConfig.SESSIONS_REVOKE_PATH)
	public String revoke(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			@RequestParam String sessionId, HttpServletRequest request) {
		this.inventory.revokeBrowserSession(principal.platformAdminId(), sessionId);
		if (sessionId.equals(currentSessionId(request))) {
			// Revoking the session you are using is a logout; the context has to
			// go with it or the next request is served from a dead session.
			SecurityContextHolder.clearContext();
			return "redirect:" + PlatformAdminWebSecurityConfig.LOGIN_PATH;
		}
		return "redirect:" + PlatformAdminWebSecurityConfig.SESSIONS_PATH;
	}

	private static String currentSessionId(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		return session == null ? null : session.getId();
	}

}
