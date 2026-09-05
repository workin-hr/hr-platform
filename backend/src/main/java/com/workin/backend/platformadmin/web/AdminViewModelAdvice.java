package com.workin.backend.platformadmin.web;

import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

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

	public AdminViewModelAdvice(MessageSource messageSource, AdminPageAvailability availability) {
		this.messageSource = messageSource;
		this.availability = availability;
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

}
