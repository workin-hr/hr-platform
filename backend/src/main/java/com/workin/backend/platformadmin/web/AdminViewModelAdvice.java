package com.workin.backend.platformadmin.web;

import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.workin.legacy.LegacyClock;

/**
 * Supplies every admin page with the four things its layout needs -- the
 * label lookup, the language, who is looking, and which sidebar entry is
 * current -- so no controller has to remember to.
 *
 * <p>Scoped to this package, not to a list of controller classes. A
 * {@code @ControllerAdvice} with no selector at all would apply to the API
 * controllers too, and adding model attributes to a JSON response body is
 * how a parity envelope grows a field PHP does not send. But a hand-listed
 * {@code assignableTypes} is worse than it looks: a page added without
 * being added to the list renders with every attribute null, and the first
 * symptom is an unboxing {@code NullPointerException} inside a template,
 * which says nothing about the cause. This package holds only the admin
 * web controllers -- the bearer-token API ones live in the parent -- so
 * the boundary is the same and it maintains itself.
 */
@ControllerAdvice(basePackages = "com.workin.backend.platformadmin.web")
public class AdminViewModelAdvice {

	/**
	 * The dashboard persists the choice in {@code $_SESSION['lang']} and
	 * defaults to Arabic ({@code DEFAULT_LANG}). The shared
	 * {@code LocaleResolutionFilter} resolves {@code ?lang} per request and
	 * defaults to English, which is right for the API -- so the admin UI
	 * keeps its own session-scoped copy rather than changing a rule the
	 * parity tests pin.
	 */
	static final String LANG_SESSION_KEY = AdminViewModelAdvice.class.getName() + ".LANG";

	private static final Set<String> SUPPORTED = Set.of("ar", "en");

	private static final String DEFAULT_LANG = "ar";

	private final MessageSource messageSource;

	private final AdminPageAvailability availability;

	/**
	 * Legacy's clock, absent under the PostgreSQL profile.
	 *
	 * <p>An {@link ObjectProvider} rather than a constructor parameter for the
	 * reason the rest of this surface uses one: this advice serves both
	 * profiles and {@link LegacyClock} reads the legacy database.
	 */
	private final ObjectProvider<LegacyClock> clock;

	public AdminViewModelAdvice(MessageSource messageSource, AdminPageAvailability availability,
			ObjectProvider<LegacyClock> clock) {
		this.messageSource = messageSource;
		this.availability = availability;
		this.clock = clock;
	}

	/**
	 * Today, as legacy reckons it.
	 *
	 * <p>{@code date('Y-m-d')} in this product is neither UTC nor the JVM
	 * default: PHP sets the timezone from {@code configs.is_daylight_saving}
	 * before any request runs, and every {@code CURDATE()} the pages compare
	 * against is evaluated on a connection set to the same offset (D-083,
	 * D-099). A page reading the JVM clock disagrees with its own SQL for two
	 * hours a day -- an employee hired "today" would not appear in today's
	 * cohort, and a tenure would tick over a day early.
	 *
	 * <p>Cross-cutting and easy to forget, so it comes from here rather than
	 * from each page -- R-058's rule, and {@code AdminClockUsageTest} keeps a
	 * new page from reaching for {@code LocalDate.now()} instead.
	 */
	@ModelAttribute("today")
	public java.time.LocalDate today() {
		LegacyClock legacyClock = this.clock.getIfAvailable();
		return legacyClock == null ? java.time.LocalDate.now() : legacyClock.today();
	}

	/**
	 * Whether a sidebar entry has a controller behind it in this
	 * deployment. Read from the handler mapping, not declared -- see
	 * {@link AdminPageAvailability}.
	 */
	@ModelAttribute("available")
	public Function<String, Boolean> available() {
		return this.availability::has;
	}

	@ModelAttribute("lang")
	public String lang(HttpServletRequest request) {
		String requested = request.getParameter("lang");
		HttpSession session = request.getSession(false);
		if (requested != null && SUPPORTED.contains(requested)) {
			request.getSession().setAttribute(LANG_SESSION_KEY, requested);
			return requested;
		}
		Object stored = session == null ? null : session.getAttribute(LANG_SESSION_KEY);
		return stored instanceof String value && SUPPORTED.contains(value) ? value : DEFAULT_LANG;
	}

	/**
	 * The template's {@code t} -- {@code __()} by another name. Resolves
	 * against {@code i18n/admin-messages}, and answers the key itself when
	 * there is no translation, which is what
	 * {@code spring.messages.use-code-as-default-message} already does for
	 * the API and what legacy's {@code t()} passthrough does.
	 */
	@ModelAttribute("t")
	public Function<String, String> translator(HttpServletRequest request) {
		Locale locale = Locale.forLanguageTag(lang(request));
		return key -> this.messageSource.getMessage(key, null, key, locale);
	}

	/**
	 * The sidebar's active entry, taken from the path rather than set by
	 * each controller -- one fewer thing a new page can forget.
	 * {@code /admin} is the dashboard's {@code index}.
	 */
	@ModelAttribute("currentPage")
	public String currentPage(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (path == null || path.equals("/admin") || path.equals("/admin/")) {
			return "index";
		}
		String tail = path.substring(path.lastIndexOf('/') + 1);
		return tail.isEmpty() ? "index" : tail;
	}

	/**
	 * The CSRF token's field name and value, for every page in this package.
	 *
	 * <p>Here rather than in each controller for the reason this advice exists
	 * at all: a page that forgets renders its forms with an empty token, and
	 * the symptom is a 403 on submit that looks like a permissions problem.
	 * Four pages had already forgotten -- FAQs, banners, notifications and
	 * dial codes -- because {@code PlatformAdminWebCsrf.expose()} is a call a
	 * new controller has to remember to make.
	 *
	 * <p>The older controllers still call it themselves; a model attribute set
	 * by a controller wins over one from an advice, so those keep working
	 * unchanged.
	 */
	@ModelAttribute("csrfParameterName")
	public String csrfParameterName(HttpServletRequest request) {
		CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		return token == null ? "_csrf" : token.getParameterName();
	}

	@ModelAttribute("csrfToken")
	public String csrfToken(HttpServletRequest request) {
		CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		return token == null ? "" : token.getToken();
	}

	/**
	 * Who is looking, as the sidebar and every page need it.
	 *
	 * <p>Every session this surface currently issues is a platform
	 * administrator's, so this is always {@link DashboardSession#admin}. It is
	 * a session object rather than an {@code isAdmin} boolean because
	 * {@link AdminNav} and {@link DashboardAccess} answer per audience, and a
	 * boolean cannot carry the company filter or an HR employee's permission
	 * set. When the owner and HR logins arrive (ADR-0016) this is the one
	 * method that changes; nothing downstream of it has to.
	 *
	 * <p>The filter is read through {@link DashboardOrgScope}, so
	 * {@code ?company_id=} already works for an administrator on any page that
	 * consults {@code session.companyId()}.
	 */
	@ModelAttribute("session")
	public DashboardSession session(HttpServletRequest request) {
		HttpSession httpSession = request.getSession(false);
		return DashboardSession.admin(DashboardOrgScope.current(httpSession));
	}

	/**
	 * The signed-in administrator's phone, which the layout shows in the topbar
	 * -- and, more consequentially, uses to decide whether to render the shell
	 * at all. A null here is the login, MFA and enrolment case, where there is
	 * no principal yet and the bare {@code auth-shell} is right.
	 *
	 * <p><b>Here rather than on each controller (R-058).</b> It was on six of
	 * them and missing from fourteen, and the fourteen rendered with no sidebar
	 * and no page title. Nothing failed: the parameter is declared on the
	 * templates without a default, so a missing model entry is null and the
	 * layout quietly takes the other branch. A cross-cutting value that every
	 * page needs and any page can forget belongs in the advice that already
	 * supplies the rest of them.
	 */
	@ModelAttribute("currentAdminPhone")
	public String currentAdminPhone(@AuthenticationPrincipal PlatformAdminWebPrincipal principal) {
		return principal == null ? null : principal.phone();
	}

	/**
	 * Whether this session has cleared its second factor.
	 *
	 * <p>Here for R-058's reason, and because it is the one piece of session
	 * state that changes what a page may do: an unbound administrator can read
	 * every list and write nothing. The topbar says so on every page, and links
	 * to enrolment when it is missing -- D-152 migrates existing rows unbound,
	 * so reaching enrolment cannot depend on landing on one particular page.
	 */
	@ModelAttribute("factorBound")
	public boolean factorBound(@AuthenticationPrincipal PlatformAdminWebPrincipal principal) {
		return principal != null && principal.factorBound();
	}

}
