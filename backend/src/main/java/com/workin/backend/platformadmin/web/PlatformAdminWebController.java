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
import com.workin.backend.platformadmin.PlatformAdminLoginService;
import com.workin.backend.platformadmin.home.HomeChart;
import com.workin.backend.platformadmin.home.HomeService;
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
 * <p>Authentication calls {@link PlatformAdminWebLoginService} in-process: one
 * password, no phone, no second factor (ADR-0018).
 */
@Controller
public class PlatformAdminWebController {

	private final PlatformAdminWebLoginService loginService;

	private final HomeService homeService;

	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	public PlatformAdminWebController(PlatformAdminWebLoginService loginService,
			HomeService homeService) {
		this.loginService = loginService;
		this.homeService = homeService;
	}

	@AuthenticatedUseCase(reason = "Renders the signed-in administrator's own overview: counts, "
			+ "charts and two panels, every one of them scoped to the session's company filter. "
			+ "No catalog permission of its own -- each block asks for the permission of the "
			+ "page it summarises -- and it performs no administrative action.")
	@GetMapping(PlatformAdminWebSecurityConfig.PATH_PREFIX)
	public String home(@AuthenticationPrincipal PlatformAdminWebPrincipal principal, Model model,
			HttpServletRequest request) {
		csrf(model, request);

		DashboardSession session = (DashboardSession) model.getAttribute("session");
		DashboardListFilters filters = DashboardListFilters.read(session, request);
		DashboardSession scoped = DashboardSession.admin(filters.companyId());
		model.addAttribute("session", scoped);

		model.addAttribute("summary", this.homeService.summary(scoped));
		model.addAttribute("charts", translateChartLabels(this.homeService.charts(scoped), model));
		model.addAttribute("activities", this.homeService.recentActivities(scoped, 5));
		model.addAttribute("complaints", this.homeService.openComplaints(scoped, filters, 4));
		model.addAttribute("turnover", this.homeService.turnover(scoped));
		model.addAttribute("banners", this.homeService.banners());
		java.util.List<HomeChart> planning = this.homeService.workforcePlanning(scoped);
		model.addAttribute("planned", planning.get(0));
		model.addAttribute("actual", planning.get(1));
		return "admin/home";
	}

	/**
	 * The gender and age series come out of SQL as keys, because grouping on a
	 * translated string would group differently per language. They become
	 * labels here, using the same {@code t} the templates render with.
	 */
	@SuppressWarnings("unchecked")
	private static java.util.SequencedMap<String, HomeChart> translateChartLabels(
			java.util.SequencedMap<String, HomeChart> charts, Model model) {
		Object translator = model.getAttribute("t");
		if (!(translator instanceof java.util.function.Function)) {
			return charts;
		}
		java.util.function.Function<String, String> t =
				(java.util.function.Function<String, String>) translator;
		java.util.SequencedMap<String, HomeChart> translated = new java.util.LinkedHashMap<>();
		charts.forEach((key, chart) -> translated.put(key,
				"chart_gender".equals(key) || "chart_age".equals(key)
						? chart.translateLabels(label -> t.apply(labelKey(key, label)))
						: chart));
		return translated;
	}

	/** {@code home_gender_label()} / {@code home_age_label()}, as message keys. */
	private static String labelKey(String chart, String raw) {
		if ("chart_gender".equals(chart)) {
			return switch (raw) {
				case "male" -> "gender_male";
				case "female" -> "gender_female";
				case "unknown" -> "chart_unknown";
				default -> "gender_other";
			};
		}
		// Spelled out, not concatenated: AdminLayoutWiringTest reads the
		// message keys a controller can emit straight out of the source, and a
		// built string is one it cannot check against the catalogue.
		return switch (raw) {
			case "under_20" -> "age_under_20";
			case "twenties" -> "age_twenties";
			case "thirties" -> "age_thirties";
			case "forties" -> "age_forties";
			case "fifty_plus" -> "age_fifty_plus";
			default -> "chart_unknown";
		};
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
			+ "It is CSRF-protected and delegates the credential check to PlatformAdminLoginService, "
			+ "whose miss budget is per client address.")
	@PostMapping(PlatformAdminWebSecurityConfig.LOGIN_PATH)
	public String login(@RequestParam String password, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		// The client address is what the miss budget is charged to. Behind the
		// proxy this is the real one: server.forward-headers-strategy=native in
		// production, and the proxy is the only route (R-049).
		Optional<Long> admin = this.loginService.authenticate(password, request.getRemoteAddr());
		if (admin.isEmpty()) {
			csrf(model, request);
			model.addAttribute("errorKey", "error_auth");
			return "admin/login";
		}
		HttpSession session = rotateSession(request);
		authenticate(request, response, session,
				new PlatformAdminWebPrincipal(admin.get(), PlatformAdminLoginService.ADMIN_IDENTIFIER));
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
	private static boolean isAuthenticated() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null
				&& authentication.getPrincipal() instanceof PlatformAdminWebPrincipal;
	}

	private static void csrf(Model model, HttpServletRequest request) {
		PlatformAdminWebCsrf.expose(model, request);
	}

}
