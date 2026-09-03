package com.workin.backend.platformadmin.web;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.authorization.PublicUseCase;

/**
 * The platform-admin UI (ADR-0015): server-rendered JTE, cookie session, no
 * token in the browser.
 *
 * <p>Every mapping here must land on
 * {@link PlatformAdminWebSecurityConfig}'s chain.
 * {@code PlatformAdminWebChainCoverageTest} enumerates the handler registry and
 * asserts exactly that, so a page added here without a matching route pattern
 * fails the build rather than being served quietly by the tenant catch-all.
 *
 * <p>Authentication calls {@link PlatformAdminWebLoginService} in-process. It
 * does not consume {@code POST /api/platform-admin/login}: ADR-0015 is explicit
 * that this surface shares the services, not the HTTP contract.
 */
@Controller
public class PlatformAdminWebController {

	private final PlatformAdminWebLoginService loginService;

	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	public PlatformAdminWebController(PlatformAdminWebLoginService loginService) {
		this.loginService = loginService;
	}

	@AuthenticatedUseCase(reason = "Renders the signed-in administrator's own overview. "
			+ "No catalog permission: the platform-admin domain is separate from the tenant "
			+ "permission model, and this page performs no administrative action.")
	@GetMapping(PlatformAdminWebSecurityConfig.PATH_PREFIX)
	public String home(@AuthenticationPrincipal PlatformAdminWebPrincipal principal, Model model,
			HttpServletRequest request) {
		csrf(model, request);
		model.addAttribute("currentAdminPhone", principal.phone());
		return "admin/home";
	}

	@PublicUseCase(reason = "The login form itself. It must be reachable unauthenticated "
			+ "or there is no way to authenticate; it renders no data beyond a CSRF token.")
	@GetMapping(PlatformAdminWebSecurityConfig.LOGIN_PATH)
	public String loginForm(Model model, HttpServletRequest request) {
		if (isAuthenticated()) {
			return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
		}
		csrf(model, request);
		return "admin/login";
	}

	@PublicUseCase(reason = "Credential submission is by definition pre-authentication. "
			+ "It is CSRF-protected and delegates the credential check to the shared "
			+ "PlatformAdminLoginService.")
	@PostMapping(PlatformAdminWebSecurityConfig.LOGIN_PATH)
	public String login(@RequestParam String phone, @RequestParam String password, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		PlatformAdminWebLoginService.Outcome outcome = this.loginService.authenticate(phone, password);
		if (!outcome.succeeded()) {
			csrf(model, request);
			// One message for every failure mode. Distinguishing "no such
			// administrator" from "wrong password" hands an unauthenticated
			// caller an account-enumeration oracle for free.
			model.addAttribute("error", "Those credentials were not accepted.");
			return "admin/login";
		}

		// Session fixation: a brand-new session, so a cookie planted before
		// authentication is not the one that ends up authenticated. Spring
		// Security's changeSessionId() covers its own authentication filters;
		// this controller authenticates directly, so it does the rotation itself.
		HttpSession existing = request.getSession(false);
		if (existing != null) {
			existing.invalidate();
		}
		HttpSession session = request.getSession(true);
		session.setAttribute(PlatformAdminSessionRevalidationFilter.ESTABLISHED_AT,
				Instant.now().toEpochMilli());

		PlatformAdminWebPrincipal principal =
				new PlatformAdminWebPrincipal(outcome.platformAdminId(), outcome.phone());
		Authentication authentication =
				new UsernamePasswordAuthenticationToken(principal, null, List.of());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		this.securityContextRepository.saveContext(context, request, response);

		return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
	}

	@AuthenticatedUseCase(reason = "Ends the caller's own session. Nothing to authorize "
			+ "beyond being the session holder; no permission applies to discarding your own session.")
	@PostMapping(PlatformAdminWebSecurityConfig.LOGOUT_PATH)
	public String logout(@AuthenticationPrincipal PlatformAdminWebPrincipal principal,
			HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			// Invalidate, not just clear: with Spring Session JDBC this deletes
			// the row, so the session is gone for every worker rather than for
			// the one that happened to serve the logout.
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		if (principal != null) {
			this.loginService.recordLogout(principal.platformAdminId());
		}
		return "redirect:" + PlatformAdminWebSecurityConfig.LOGIN_PATH;
	}

	private static boolean isAuthenticated() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null
				&& authentication.getPrincipal() instanceof PlatformAdminWebPrincipal;
	}

	/**
	 * JTE has no form taglib, so the token is an explicit template parameter
	 * rather than something a tag emits. Making it explicit is the point: a
	 * form that forgets it fails visibly on the first POST.
	 */
	private static void csrf(Model model, HttpServletRequest request) {
		CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		model.addAttribute("csrfParameterName", token != null ? token.getParameterName() : "_csrf");
		model.addAttribute("csrfToken", token != null ? token.getToken() : "");
	}

}
