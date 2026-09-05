package com.workin.backend.platformadmin.web;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.workin.backend.authorization.AuthenticatedUseCase;
import com.workin.backend.platformadmin.PlatformAdminLoginThrottle;
import com.workin.backend.platformadmin.mfa.PlatformAdminMfaService;
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
	private final PlatformAdminMfaService mfaService;
	private final PlatformAdminLoginThrottle throttle;

	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	public PlatformAdminWebController(PlatformAdminWebLoginService loginService,
			PlatformAdminMfaService mfaService, PlatformAdminLoginThrottle throttle) {
		this.loginService = loginService;
		this.mfaService = mfaService;
		this.throttle = throttle;
	}

	/** Session attribute holding a password step awaiting its second factor. */
	static final String PENDING = PlatformAdminWebController.class.getName() + ".pending";

	/** Session attribute marking an enrolment between its two steps. */
	static final String ENROLLING = PlatformAdminWebController.class.getName() + ".enrolling";

	@AuthenticatedUseCase(reason = "Renders the signed-in administrator's own overview. "
			+ "No catalog permission: the platform-admin domain is separate from the tenant "
			+ "permission model, and this page performs no administrative action.")
	@GetMapping(PlatformAdminWebSecurityConfig.PATH_PREFIX)
	public String home(@AuthenticationPrincipal PlatformAdminWebPrincipal principal, Model model,
			HttpServletRequest request) {
		csrf(model, request);
		model.addAttribute("factorBound", principal.factorBound());
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
		HttpSession session = rotateSession(request);

		if (this.mfaService.isBound(outcome.platformAdminId())) {
			// The password is not enough. No security context is created here --
			// only a pending marker, so the single thing this session can reach
			// is the challenge page.
			session.setAttribute(PENDING, new PlatformAdminPendingAuthentication(
					outcome.platformAdminId(), outcome.phone(), Instant.now()));
			return "redirect:" + PlatformAdminWebSecurityConfig.MFA_PATH;
		}

		// No bound factor: the session exists so the administrator can enrol,
		// and carries factorBound=false so a privileged operation can refuse it
		// (D-152 -- existing rows migrate unbound and must not act until bound).
		authenticate(request, response, session,
				new PlatformAdminWebPrincipal(outcome.platformAdminId(), outcome.phone(), false));
		return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
	}

	@PublicUseCase(reason = "The second-factor challenge. The password has passed but no security "
			+ "context exists yet, so this is gated on the session's pending marker rather than "
			+ "on an authenticated principal -- granting a context first would defeat the factor.")
	@GetMapping(PlatformAdminWebSecurityConfig.MFA_PATH)
	public String mfaForm(Model model, HttpServletRequest request) {
		if (pendingIn(request) == null) {
			return "redirect:" + PlatformAdminWebSecurityConfig.LOGIN_PATH;
		}
		csrf(model, request);
		return "admin/mfa";
	}

	@PublicUseCase(reason = "Second-factor submission, completing an authentication that is "
			+ "already half-proven. CSRF-protected and throttled on the same shared budget as "
			+ "the password step.")
	@PostMapping(PlatformAdminWebSecurityConfig.MFA_PATH)
	public String mfa(@RequestParam String code, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		PlatformAdminPendingAuthentication pending = pendingIn(request);
		if (pending == null) {
			return "redirect:" + PlatformAdminWebSecurityConfig.LOGIN_PATH;
		}

		// ADR-0015 prerequisite 3 covers the TOTP step too, not only the
		// password one. Namespaced so a spent code budget cannot lock out the
		// password step or vice versa, while both are still bounded.
		String budgetKey = "totp:" + pending.platformAdminId();
		if (this.throttle.isExhausted(budgetKey) || !this.mfaService.verify(pending.platformAdminId(), code)) {
			this.throttle.recordFailure(budgetKey);
			csrf(model, request);
			model.addAttribute("error", "That code was not accepted.");
			return "admin/mfa";
		}
		this.throttle.clear(budgetKey);

		// Rotate again: the id that carried the half-authenticated state is not
		// the id that carries the authenticated one.
		HttpSession session = rotateSession(request);
		authenticate(request, response, session,
				new PlatformAdminWebPrincipal(pending.platformAdminId(), pending.phone(), true));
		return "redirect:" + PlatformAdminWebSecurityConfig.PATH_PREFIX;
	}

	@PublicUseCase(reason = "D-152's enrolment ceremony. Pre-authentication by definition: "
			+ "the account has no second factor yet, and the gate is the password plus an "
			+ "operator-issued bootstrap token, not a session.")
	@GetMapping(PlatformAdminWebSecurityConfig.ENROL_PATH)
	public String enrolForm(Model model, HttpServletRequest request) {
		csrf(model, request);
		return "admin/enrol";
	}

	@PublicUseCase(reason = "Begins enrolment against a password and a bootstrap token, and "
			+ "displays the seed once. Creates no session and grants no context: the factor is "
			+ "not bound until a code verifies.")
	@PostMapping(PlatformAdminWebSecurityConfig.ENROL_PATH)
	public String enrol(@RequestParam String phone, @RequestParam String password,
			@RequestParam(name = "bootstrapToken") String bootstrapToken, Model model,
			HttpServletRequest request) {
		PlatformAdminWebLoginService.Outcome outcome = this.loginService.authenticate(phone, password);
		// One message for both failure modes. Distinguishing "wrong password"
		// from "bad token" would let either be brute-forced against a known-good
		// other half.
		if (!outcome.succeeded()) {
			csrf(model, request);
			model.addAttribute("error", "Those details were not accepted.");
			return "admin/enrol";
		}
		Optional<String> seed = this.mfaService.beginEnrolment(outcome.platformAdminId(), bootstrapToken);
		if (seed.isEmpty()) {
			csrf(model, request);
			model.addAttribute("error", "Those details were not accepted.");
			return "admin/enrol";
		}

		// The seed is rendered exactly here and never stored anywhere it could
		// be read back -- not in the session, which is persisted to the same
		// database, and not in a redirect.
		// No session rotation here, deliberately. Rotation defends against
		// fixation, which matters where a session *gains privilege* -- and this
		// one does not: it carries a marker saying an enrolment is in progress
		// and grants no security context, and the confirm step ends in a
		// redirect to the login page rather than a session. Rotating anyway
		// would discard the CSRF token the confirm form is about to carry.
		request.getSession(true).setAttribute(ENROLLING, outcome.platformAdminId());
		csrf(model, request);
		model.addAttribute("seed", seed.get());
		model.addAttribute("phone", outcome.phone());
		return "admin/enrol-confirm";
	}

	@PublicUseCase(reason = "Confirms enrolment with a code. Authorised by the in-progress "
			+ "enrolment marker, which only the token-gated begin step can have created.")
	@PostMapping(PlatformAdminWebSecurityConfig.ENROL_CONFIRM_PATH)
	public String confirmEnrolment(@RequestParam String code, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		Object enrolling = session == null ? null : session.getAttribute(ENROLLING);
		if (!(enrolling instanceof Long platformAdminId)) {
			return "redirect:" + PlatformAdminWebSecurityConfig.ENROL_PATH;
		}
		if (!this.mfaService.confirmEnrolment(platformAdminId, code)) {
			csrf(model, request);
			model.addAttribute("error", "That code was not accepted. Start again to get a new seed.");
			return "admin/enrol";
		}
		session.removeAttribute(ENROLLING);
		return "redirect:" + PlatformAdminWebSecurityConfig.LOGIN_PATH;
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

	/**
	 * A fresh session, carrying the establishment stamp the absolute cap reads.
	 * Always a new id: both the password step and the second-factor step rotate,
	 * so neither a pre-login cookie nor a half-authenticated one survives into
	 * the next state.
	 */
	private static HttpSession rotateSession(HttpServletRequest request) {
		HttpSession existing = request.getSession(false);
		if (existing != null) {
			existing.invalidate();
		}
		HttpSession session = request.getSession(true);
		session.setAttribute(PlatformAdminSessionRevalidationFilter.ESTABLISHED_AT,
				Instant.now().toEpochMilli());
		return session;
	}

	private void authenticate(HttpServletRequest request, HttpServletResponse response,
			HttpSession session, PlatformAdminWebPrincipal principal) {
		Authentication authentication =
				new UsernamePasswordAuthenticationToken(principal, null, List.of());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		this.securityContextRepository.saveContext(context, request, response);
	}

	/** The live pending challenge for this session, or null. */
	private static PlatformAdminPendingAuthentication pendingIn(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return null;
		}
		if (!(session.getAttribute(PENDING) instanceof PlatformAdminPendingAuthentication pending)) {
			return null;
		}
		if (!pending.isLiveAt(Instant.now())) {
			session.removeAttribute(PENDING);
			return null;
		}
		return pending;
	}

	private static boolean isAuthenticated() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null
				&& authentication.getPrincipal() instanceof PlatformAdminWebPrincipal;
	}

	private static void csrf(Model model, HttpServletRequest request) {
		PlatformAdminWebCsrf.expose(model, request);
	}

}
