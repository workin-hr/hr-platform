package com.workin.backend.i18n;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the request language once (owner decision 2026-08-08,
 * legacy app_locale() precedence): explicit ?lang wins, then
 * Accept-Language, default English. Runs before the security chain so
 * even rejected requests render localized bodies.
 *
 * <p>The resolved value is also stashed as a request attribute
 * ({@link #RESOLVED_LOCALE_ATTRIBUTE}) for {@link LocaleResolverConfig}
 * to read: DispatcherServlet unconditionally rebuilds
 * LocaleContextHolder from its own LocaleResolver bean at the top of
 * every request, so that bean cannot itself read back through
 * LocaleContextHolder (self-referential -- StackOverflowError) and
 * needs a plain, non-ThreadLocal source of truth instead.
 *
 * <p><b>One past {@code HIGHEST_PRECEDENCE}, not at it, and the single
 * step matters.</b> This filter reads {@code lang} with
 * {@code request.getParameter()}, and the first {@code getParameter()}
 * call makes the container parse the request -- query string and form
 * body together -- fixing the body's charset for the rest of it. Spring
 * Boot's {@code OrderedCharacterEncodingFilter}, which is what sets that
 * charset to UTF-8, sits at {@code HIGHEST_PRECEDENCE} as well, so at
 * equal order the two ran in an undefined sequence; when this one won,
 * every non-ASCII form field arrived decoded as ISO-8859-1 and an Arabic
 * title posted from an admin form was stored as mojibake. See R-067.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class LocaleResolutionFilter extends OncePerRequestFilter {

	public static final String RESOLVED_LOCALE_ATTRIBUTE = LocaleResolutionFilter.class.getName() + ".RESOLVED_LOCALE";

	/**
	 * The precedence rule itself, callable without having gone through
	 * the filter.
	 *
	 * <p>Spring Security rejects an unauthenticated request from inside
	 * its own chain, and cannot rely on this filter having run first --
	 * filter ordering between the two is not something a caller should
	 * have to reason about. {@code ApiSecurityErrorHandler} therefore
	 * resolves the locale straight from the request through here, so
	 * both paths share one definition of the rule rather than growing a
	 * second, subtly different copy.
	 */
	public static Locale resolve(HttpServletRequest request) {
		Object alreadyResolved = request.getAttribute(RESOLVED_LOCALE_ATTRIBUTE);
		if (alreadyResolved instanceof Locale locale) {
			return locale;
		}
		String langParam = request.getParameter("lang");
		return langParam != null && !langParam.isBlank()
				? SupportedLocales.fromLangParam(langParam)
				: SupportedLocales.fromAcceptLanguage(request.getHeader("Accept-Language"));
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Locale locale = resolve(request);
		request.setAttribute(RESOLVED_LOCALE_ATTRIBUTE, locale);
		LocaleContextHolder.setLocale(locale);
		try {
			filterChain.doFilter(request, response);
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}
	}

}
